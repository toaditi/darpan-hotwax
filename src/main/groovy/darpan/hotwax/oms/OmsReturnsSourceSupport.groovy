package darpan.hotwax.oms

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import darpan.reconciliation.source.SourceFilterSupport

import static darpan.common.ValueSupport.normalize
import static darpan.common.ValueSupport.normalizeBool
import static darpan.common.ValueSupport.normalizeInt

/**
 * Extraction for OMS `/rest/s1/oms/reconciliationReturns` (DAR-BE-018, design §7).
 *
 * A separate class from OmsRestSourceSupport on purpose: that file is already ~1,900 lines and
 * carries the sales-order contract (SALES_ORDER/EXCHANGE filtering, exchange manifest sidecar,
 * pagination-strategy fallbacks) that returns do not share. Config parsing, auth header building,
 * URL building, window parsing, and the actual HTTP call are reused from it as statics rather than
 * re-implemented.
 *
 * The endpoint terminates cleanly on hasMore, honours pageSize, and pre-excludes returns with no
 * Shopify reference server-side (excludedNoShopifyRefCount) — so none of the legacy orders-path
 * heuristics (shrinking-page probes, repeated-page detection) apply here.
 */
class OmsReturnsSourceSupport {

    static final String DEFAULT_RETURNS_PATH = "/rest/s1/oms/reconciliationReturns"
    static final String RETURNS_WINDOW_FROM_PARAM = "returnDateFrom"
    static final String RETURNS_WINDOW_THRU_PARAM = "returnDateThru"
    static final String DEFAULT_FILE_NAME_PREFIX = "oms-returns"
    static final String RETURN_CHANNEL_FIELD = "returnChannelEnumId"
    static final int DEFAULT_RETURNS_PAGE_SIZE = 500
    /** Same 1..1000 envelope resolveOrdersPageSize clamps to, for the same reason. */
    static final int MIN_RETURNS_PAGE_SIZE = 1
    static final int MAX_RETURNS_PAGE_SIZE = 1000
    static final int MAX_RETURNS_PAGE_COUNT = 20000
    static final String RETURNS_EXTERNAL_ID_PARAM = "externalId"
    /** OMS leaves externalId BLANK until a refund exists; this field carries the id from creation. */
    static final String RETURNS_SHOPIFY_RETURN_ID_PARAM = "shopifyReturnId"
    /** 20 ids per call is the width live-proven 2026-08-20; the endpoint accepts CSV and repeated params. */
    static final int RETURNS_LOOKUP_CHUNK_SIZE = 20
    /** Attempts per page, inclusive of the first. 3 covers a gateway blip without hammering a sick host. */
    static final int DEFAULT_MAX_PAGE_ATTEMPTS = 3
    /** Linear backoff step: attempt N waits N x this before re-requesting. */
    static final int DEFAULT_RETRY_BACKOFF_MILLIS = 2000
    /**
     * Statuses a retry can actually fix. All four are "the server is momentarily unable", not "the
     * request is wrong": 502/503/504 come from the gateway rather than the OMS app, and 429 is an
     * explicit ask to come back later.
     *
     * 500 is deliberately absent. It signals a deterministic server-side fault, so three attempts
     * only triple the load and delay a certain failure by the whole backoff.
     */
    static final Set<Integer> RETRYABLE_STATUS_CODES = [429, 502, 503, 504] as Set

    private static final JsonSlurper JSON_SLURPER = new JsonSlurper()

    /**
     * No client field of its own. callOmsEndpoint (reused below, not reimplemented — see class doc)
     * dispatches through OmsRestSourceSupport's own private `httpClient`, so a second seam here
     * would be dead code the real request path never reads. Delegating keeps the single transport
     * seam and leaves the brief's test API (`OmsReturnsSourceSupport.setHttpClient { ... }`) working
     * unchanged for callers.
     */
    static void setHttpClient(Closure client) {
        OmsRestSourceSupport.setHttpClient(client)
    }

    static void resetHttpClient() {
        OmsRestSourceSupport.resetHttpClient()
    }

    /**
     * Delegates to OmsRestSourceSupport.safeFileName for the same reason setHttpClient does above:
     * one sanitization seam, not a reimplementation. The fallback is returns-specific
     * ("oms-returns.json") so a caller-supplied blank fileName still lands on a returns-shaped
     * default rather than the orders extractor's "oms-orders.json".
     */
    static String safeReturnsFileName(Object rawName) {
        return OmsRestSourceSupport.safeFileName(rawName, "${DEFAULT_FILE_NAME_PREFIX}.json")
    }

    static Map<String, Object> extractReturns(Object rawConfig, Object windowStart, Object windowEnd,
                                              List keepRecordFields, Closure pageProgressListener,
                                              List sourceFilters, Map options = [:]) {
        return extractReturnsInternal(rawConfig, windowStart, windowEnd, null, keepRecordFields,
                pageProgressListener, sourceFilters, options ?: [:], true)
    }

    static Map<String, Object> extractReturnsToFile(Object rawConfig, Object windowStart, Object windowEnd,
                                                    File targetFile, List keepRecordFields,
                                                    Closure pageProgressListener, List sourceFilters,
                                                    Map options = [:]) {
        return extractReturnsInternal(rawConfig, windowStart, windowEnd, targetFile, keepRecordFields,
                pageProgressListener, sourceFilters, options ?: [:], false)
    }

    private static Map<String, Object> extractReturnsInternal(Object rawConfig, Object windowStart,
                                                              Object windowEnd, File targetFile,
                                                              List keepRecordFields, Closure pageProgressListener,
                                                              List sourceFilters, Map options,
                                                              boolean retainRecords) {
        List<String> errors = []
        List<String> warnings = []

        // toPlainMap, NOT safeConfigMap: safeConfigMap redacts auth secrets into hasUsername/
        // hasPassword/hasApiToken booleans for UI-safe listing responses (see
        // listOmsRestSourceConfigs.groovy / saveOmsRestSourceConfig.groovy, its only other callers).
        // buildHeaders below needs the real username/password/apiToken — the same reason
        // OmsRestSourceSupport.lookupOrdersByExternalId documents at its own toPlainMap call ("NOT
        // safeConfigMapFromPlain, which redacts secrets ... and would strip the auth token
        // buildHeaders needs"). toPlainMap(null) is an empty (falsy) Map, so the null-config guard
        // below still works.
        Map<String, Object> config = OmsRestSourceSupport.toPlainMap(rawConfig)
        if (!config) {
            return failure(["OMS REST source config could not be read."], [], retainRecords)
        }

        Long fromMillis = OmsRestSourceSupport.parseWindowMillis(windowStart, "windowStart", errors)
        Long thruMillis = OmsRestSourceSupport.parseWindowMillis(windowEnd, "windowEnd", errors)
        if (errors) return failure(errors, [], retainRecords)

        String endpointUrl = OmsRestSourceSupport.buildOrdersEndpointUrl(
                normalize(config.baseUrl), DEFAULT_RETURNS_PATH)

        Map<String, String> headers
        try {
            headers = OmsRestSourceSupport.buildHeaders(config)
        } catch (IllegalArgumentException e) {
            return failure([normalize(e.message) ?: "OMS auth configuration is invalid."], [], retainRecords)
        }

        // Parsed once, before any HTTP call, so a malformed rule fails pre-flight rather than
        // mid-window on some later page (matches the orders contract). parseRules returns the rule
        // list directly (List<Map<String,Object>>), not a wrapping Map.
        List<Map<String, Object>> parsedFilters
        try {
            parsedFilters = SourceFilterSupport.parseRules(sourceFilters)
        } catch (Exception e) {
            return failure([normalize(e.message) ?: "Configured exclusion rules are invalid."], [], retainRecords)
        }

        int pageSize = resolveReturnsPageSize(config, options)
        int maxPageAttempts = Math.max(1, normalizeInt(options?.maxPageAttempts, DEFAULT_MAX_PAGE_ATTEMPTS))
        int retryBackoffMillis = Math.max(0, normalizeInt(options?.retryBackoffMillis, DEFAULT_RETRY_BACKOFF_MILLIS))
        List<Map<String, Object>> collected = []
        OutputSink sink = new OutputSink(targetFile)
        Map<String, Object> serverCounts = [:]
        Map<String, Object> exclusionCounts = [:]
        long cumulativeRaw = 0L

        try {
            int pageIndex = 0
            boolean hasMore = true
            while (hasMore) {
                if (pageIndex >= MAX_RETURNS_PAGE_COUNT) {
                    warnings.add("Returns extraction stopped at the ${MAX_RETURNS_PAGE_COUNT}-page ceiling; the window may be incomplete.".toString())
                    break
                }
                String url = buildReturnsUrl(endpointUrl, fromMillis, thruMillis, pageIndex, pageSize)
                Map<String, Object> attempt = requestReturnsPage(url, headers, config, pageIndex,
                        maxPageAttempts, retryBackoffMillis, warnings)
                if (!attempt.get("ok")) {
                    errors.add((String) attempt.get("error"))
                    break
                }
                Map response = (Map) attempt.get("response")

                Map body
                try {
                    body = (Map) JSON_SLURPER.parseText(normalize(response?.body) ?: "{}")
                } catch (Exception e) {
                    errors.add("OMS returns response was not valid JSON: ${e.message}".toString())
                    break
                }

                List pageReturns = (body.get("returns") instanceof List) ? (List) body.get("returns") : []
                cumulativeRaw += pageReturns.size()

                pageReturns.each { Object raw ->
                    if (!(raw instanceof Map)) return
                    Map<String, Object> record = (Map<String, Object>) raw
                    // Configured exclusions run CLIENT-side: the endpoint has no knowledge of tenant
                    // rules. They cannot double-count against excludedNoShopifyRefCount because that
                    // filter is server-side — those returns never arrive here at all (design §9.5).
                    Map match = SourceFilterSupport.firstMatchingRule(record, parsedFilters)
                    if (match != null) {
                        String key = String.valueOf(match.get("sequenceNum"))
                        exclusionCounts.put(key, normalizeInt(exclusionCounts.get(key), 0) + 1)
                        return
                    }
                    applyJoinKeyFallback(record)
                    Map<String, Object> projected = projectRecord(record, keepRecordFields)
                    sink.write(projected)
                    if (retainRecords) collected.add(projected)
                }

                // M1: window-wide totals repeat on every page; the first page's copy is the
                // authoritative one and is the only one guaranteed to exist (a window with no
                // returns yields no other page) — mirrors OmsRestSourceSupport.extractOrdersInternal
                // (:1169-1171), whose own comment states this explicitly. The endpoint's actual
                // per-page repetition semantics are unobserved (no live response captured yet, per
                // this class's own doc); first-page-wins is the same conservative assumption the
                // orders sibling makes, not a verified contract.
                if (pageIndex == 0) {
                    serverCounts.put("returnsCount", normalizeInt(body.get("returnsCount"), 0))
                    serverCounts.put("excludedNoShopifyRefCount", normalizeInt(body.get("excludedNoShopifyRefCount"), 0))
                }

                if (pageProgressListener != null) pageProgressListener.call(cumulativeRaw)

                // C4: hasMore is the ONLY termination signal here (this endpoint has none of the
                // legacy orders-path shrinking-page/repeated-page heuristics — see class doc).
                // Absence therefore cannot be read as "false": that would end the window after this
                // page and report SUCCESS on a partial extract, indistinguishable from a window that
                // genuinely held one page. Fail instead, mirroring OmsRestSourceSupport's own
                // recon-endpoint handling (:1361-1372) exactly, including using normalizeBool (not a
                // strict `== true`) for a present-but-stringly-typed value such as "true".
                Object rawHasMore = body.get("hasMore")
                if (rawHasMore == null) {
                    errors.add("OMS returns response carried no hasMore flag, so the end of the window cannot be determined; refusing to report a possibly-partial extract as complete.".toString())
                    break
                }
                hasMore = normalizeBool(rawHasMore)
                pageIndex++
            }
            // finish() must never run on an error-triggered break out of the loop above — it would
            // write a well-formed closing `],"metadata":{...}}` onto a file that only holds the
            // pages fetched before the failure, producing a valid-looking but silently partial
            // document. Mirrors OmsRestSourceSupport.extractOrdersInternal, which calls
            // sink.abort() (not finish()) on every one of its own error returns.
            if (errors) {
                sink.abort()
            } else {
                sink.finish(buildMetadata(serverCounts, exclusionCounts, parsedFilters))
            }
        } catch (Exception e) {
            sink.abort()
            errors.add("OMS returns extraction failed: ${e.message}".toString())
        } finally {
            sink.close()
        }

        if (errors) {
            // Mirrors OmsRestSourceSupport.extractOrdersToFile's `sink.abort(); targetFile.delete()`
            // on any error: abort() above already closed the writer without the closing bracket, so
            // whatever partial bytes it wrote must not be left on disk looking like a real file.
            // No-op for the in-memory extractReturns path (targetFile is null there).
            if (targetFile != null) targetFile.delete()
            return failure(errors, warnings, retainRecords)
        }

        int recordCount = sink.writtenCount
        Map<String, Object> result = [
                recordCount    : recordCount,
                dataAvailable  : recordCount > 0,
                requestMetadata: buildMetadata(serverCounts, exclusionCounts, parsedFilters),
                warnings       : warnings,
                errors         : [],
                fileName       : OmsRestSourceSupport.buildDefaultFileName(fromMillis, thruMillis, DEFAULT_FILE_NAME_PREFIX),
        ]
        // extractReturnsToFile's contract is "same Map minus records" — omit the key entirely
        // (matching OmsRestSourceSupport.extractOrdersToFile's `result.remove("records")`), not just
        // set it null.
        if (retainRecords) result.put("records", collected)
        return result
    }

    /**
     * One page, retried a bounded number of times for the statuses a retry can fix.
     *
     * Retry lives HERE rather than only at the automation layer on purpose. A failure that escapes
     * this method aborts the sink and deletes the part-written file, so the automation's own retry
     * (AutomationExecutionSupport.buildFailureFields, transient -> PENDING on a 5-minute backoff)
     * re-fetches the window from page 0 and re-pays for every page that had already succeeded. A
     * page-level attempt costs one request and a few seconds instead.
     *
     * On failure it returns a message naming the page, the attempts spent, and the server's own
     * error detail. That page number is the whole diagnostic: page 0 says the window is too wide
     * for the endpoint, while a deep page says offset decay, and the two want opposite remedies.
     */
    protected static Map<String, Object> requestReturnsPage(String url, Map<String, String> headers, Map config,
                                                            int pageIndex, int maxAttempts, int backoffMillis,
                                                            List<String> warnings) {
        int lastStatus = 0
        String lastDetail = null
        int attemptsUsed = 0
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            attemptsUsed = attempt
            // Reused, not hand-rolled: callOmsEndpoint already handles gzip, status extraction,
            // and dispatches through the injected test client (see setHttpClient above).
            Map response = OmsRestSourceSupport.callOmsEndpoint(url, headers, config)
            int statusCode = normalizeInt(response?.statusCode, 0)
            if (statusCode >= 200 && statusCode < 300) return [ok: true, response: response]

            lastStatus = statusCode
            // Read on EVERY attempt, not just the last: a gateway's 504 body is usually empty while
            // the app's own 503 carries a real code, and whichever attempt saw detail is worth
            // keeping over a later blank one.
            lastDetail = OmsRestSourceSupport.describeEndpointError(response?.body) ?: lastDetail
            if (!RETRYABLE_STATUS_CODES.contains(statusCode)) break
            if (attempt >= maxAttempts) break
            warnings.add(("OMS returns page ${pageIndex} failed with status ${statusCode}; " +
                    "retrying (attempt ${attempt + 1} of ${maxAttempts}).").toString())
            sleepQuietly((long) backoffMillis * attempt)
        }

        String attemptsNote = attemptsUsed > 1 ? " after ${attemptsUsed} attempts" : ""
        String detailNote = lastDetail ? ": ${lastDetail}" : ""
        return [ok: false, error: ("OMS returns request for page ${pageIndex} failed with " +
                "status ${lastStatus}${attemptsNote}${detailNote}.").toString()]
    }

    /**
     * Restores the interrupt flag rather than swallowing it: this runs inside a Moqui service
     * thread, and a cancel that arrives mid-backoff must stay visible to the code above.
     */
    private static void sleepQuietly(long millis) {
        if (millis <= 0L) return
        try {
            Thread.sleep(millis)
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * Page size, in precedence order: an explicit caller option, then the operator's per-source
     * setting, then the shipped default. Clamped to MIN..MAX for the reason the orders sibling
     * clamps — a mistyped 99999 must not become a request that is certain to time the gateway out.
     *
     * This is the lever the 504 on prod run 100616 had no answer for: when OMS cannot build a
     * 500-row page inside its gateway deadline, retrying cannot help and only a smaller page can.
     */
    protected static int resolveReturnsPageSize(Map config, Map options) {
        Integer requested = normalizeInt(options?.pageSize)
        if (requested == null) requested = normalizeInt(config?.returnsPageSize)
        if (requested == null) return DEFAULT_RETURNS_PAGE_SIZE
        return Math.min(MAX_RETURNS_PAGE_SIZE, Math.max(MIN_RETURNS_PAGE_SIZE, requested))
    }

    protected static String buildReturnsUrl(String endpointUrl, Long fromMillis, Long thruMillis,
                                            int pageIndex, int pageSize) {
        StringBuilder url = new StringBuilder(endpointUrl)
        url.append(endpointUrl.contains("?") ? "&" : "?")
        url.append(RETURNS_WINDOW_FROM_PARAM).append("=").append(fromMillis)
        url.append("&").append(RETURNS_WINDOW_THRU_PARAM).append("=").append(thruMillis)
        url.append("&pageIndex=").append(pageIndex)
        url.append("&pageSize=").append(pageSize)
        return url.toString()
    }

    /**
     * JOIN-KEY FALLBACK (2026-08-20). `externalId` is the returns-pair join key, and OMS leaves it
     * BLANK until a refund exists — 67 of 772 rows in the 2026-08-20 live run. Those rows reached the
     * compare with no key at all and could only ever land as false "missing in Shopify" differences.
     *
     * The fallback is not a guess: it tracks Shopify's own grain exactly. Shopify emits a RETURN row
     * while a return is unrefunded and switches to the REFUND row once a refund exists (the
     * refunded-return narrowing in ShopifyReturnRefsSupport). So an OMS return with no refund yet is
     * named on the Shopify side by its RETURN id — which is precisely `shopifyReturnId`.
     *
     * Measured against the full 772-row run: 283 rows matched a Shopify event via externalId
     * (268 REFUND / 15 RETURN) and 65 via this fallback (65 RETURN / **0 REFUND**) — the fallback
     * never once engaged for a refunded event. Zero key collisions, zero rows left keyless.
     *
     * Do NOT invert this into "prefer shopifyReturnId": the two fields hold DIFFERENT Shopify objects
     * for a refunded return (32 of 45 sampled differed — externalId the refund id, shopifyReturnId the
     * return id), and Shopify suppresses the RETURN row once refunded, so preferring shopifyReturnId
     * would break every refunded match.
     */
    protected static void applyJoinKeyFallback(Map<String, Object> record) {
        if (record == null) return
        if (normalize(record.get("externalId"))) return
        String fallback = normalize(record.get("shopifyReturnId"))
        if (fallback) record.put("externalId", fallback)
    }

    /**
     * Point-existence check for OMS return ids, the missing-in-OMS mirror of Shopify's
     * lookup#ShopifyRefundOrReturnIds. Returns [ok, foundIds, missingIds, errors] — the contract
     * MissingDiffVerificationSupport consumes.
     *
     * Escapes the run window BY CONSTRUCTION. The endpoint requires window params, but a by-id lookup
     * must not be narrowed by them: OMS windows on entryDate (when the return was CREATED) while the
     * Shopify side windows on the refund/return event createdAt (when it was REFUNDED), and a return
     * opened weeks before its refund is in-window for one and far outside for the other. In the
     * 2026-08-20 run that clock mismatch accounted for 45 of 59 remaining differences, with a median
     * age of 11 days and a tail to 22 — so a 10-year window, same as lookupOrdersByExternalId.
     *
     * Fails CLOSED: any transport error, non-2xx, unparseable body or ignored filter returns ok=false
     * with both lists empty, so a degraded OMS over-reports rather than silently suppressing rows.
     */
    static Map<String, Object> lookupReturnsByExternalId(Object rawConfig, Collection externalIds,
                                                         Map options = [:]) {
        List<String> ids = (externalIds ?: []).collect { normalize(it) }.findAll { it }.unique()
        if (!ids) return [ok: false, foundIds: [], missingIds: [], errors: ["No externalIds provided for OMS returns lookup."]]

        Map<String, Object> config = OmsRestSourceSupport.toPlainMap(rawConfig)
        if (!config) return [ok: false, foundIds: [], missingIds: [], errors: ["OMS REST source config could not be read."]]

        String endpointUrl = OmsRestSourceSupport.buildOrdersEndpointUrl(
                normalize(config.baseUrl), DEFAULT_RETURNS_PATH)
        Map<String, String> headers
        try {
            headers = OmsRestSourceSupport.buildHeaders(config)
        } catch (IllegalArgumentException e) {
            return [ok: false, foundIds: [], missingIds: [], errors: [normalize(e.message) ?: "OMS auth configuration is invalid."]]
        }

        long thruMillis = System.currentTimeMillis() + 86400000L
        long fromMillis = thruMillis - 3650L * 86400000L
        Set<String> found = new LinkedHashSet<String>()

        for (List<String> chunk : ids.collate(normalizeInt(options?.chunkSize, RETURNS_LOOKUP_CHUNK_SIZE))) {
            // TWO-FIELD LOOKUP (2026-08-21). externalId alone silently misses every return that has no
            // refund yet: OMS leaves that column NULL until one exists and carries the id only in
            // shopifyReturnId. Probed on six live RETURN_REQUESTED rows — 0/6 resolved by externalId,
            // 6/6 by shopifyReturnId — so a by-externalId-only lookup reported all six as "confirmed
            // missing" when OMS held every one of them. Same asymmetry the extractor's
            // applyJoinKeyFallback exists for; this is its lookup-side twin.
            //
            // The second call runs ONLY for ids the first did not resolve, so a window of settled
            // returns still costs one call per chunk.
            Map first = queryReturnsByField(endpointUrl, headers, config, RETURNS_EXTERNAL_ID_PARAM,
                    chunk, fromMillis, thruMillis)
            if (first.error) return [ok: false, foundIds: [], missingIds: [], errors: [first.error]]
            found.addAll((Collection<String>) first.found)

            List<String> unresolved = chunk.findAll { !found.contains(it) }
            if (unresolved) {
                Map second = queryReturnsByField(endpointUrl, headers, config, RETURNS_SHOPIFY_RETURN_ID_PARAM,
                        unresolved, fromMillis, thruMillis)
                if (second.error) return [ok: false, foundIds: [], missingIds: [], errors: [second.error]]
                found.addAll((Collection<String>) second.found)
            }
        }
        return [ok: true, foundIds: new ArrayList<String>(found),
                missingIds: ids.findAll { !found.contains(it) }, errors: []]
    }


    /**
     * One filtered page against the returns endpoint, echo-checked on the SAME field it filtered by.
     * Returns [found: Set, error: String|null] — a transport or contract failure yields an error and
     * never a partial classification, because "not found" and "could not ask" must not look alike.
     */
    private static Map queryReturnsByField(String endpointUrl, Map<String, String> headers, Map config,
                                           String paramName, List<String> ids, long fromMillis, long thruMillis) {
        Set<String> found = new LinkedHashSet<String>()
        String url = new StringBuilder(endpointUrl)
                .append(endpointUrl.contains("?") ? "&" : "?")
                .append(RETURNS_WINDOW_FROM_PARAM).append("=").append(fromMillis)
                .append("&").append(RETURNS_WINDOW_THRU_PARAM).append("=").append(thruMillis)
                .append("&").append(paramName).append("=").append(ids.join(","))
                .append("&pageIndex=0&pageSize=").append(Math.max(ids.size() * 2, 50))
                .toString()
        Map response
        try {
            response = OmsRestSourceSupport.callOmsEndpoint(url, headers, config)
        } catch (Exception e) {
            return [found: found, error: "OMS returns lookup failed: ${e.message}".toString()]
        }
        int statusCode = normalizeInt(response?.statusCode, 0)
        if (statusCode < 200 || statusCode >= 300) {
            return [found: found, error: "OMS returns lookup failed with HTTP ${statusCode}.".toString()]
        }
        Map body
        try {
            body = (Map) JSON_SLURPER.parseText(normalize(response?.body) ?: "{}")
        } catch (Exception e) {
            return [found: found, error: "OMS returns lookup returned unparseable JSON: ${e.message}".toString()]
        }
        List returned = (body.get("returns") instanceof List) ? (List) body.get("returns") : []
        // ECHO-CHECK against the field actually filtered on: this endpoint answers a filter it does not
        // implement with HTTP 200 and an unfiltered corpus (orderExternalId, live-proven 2026-08-20).
        Set<String> requested = new HashSet<String>(ids)
        boolean echoed = false
        for (Object raw : returned) {
            if (!(raw instanceof Map)) continue
            String value = normalize(((Map) raw).get(paramName))
            if (value && requested.contains(value)) { found.add(value); echoed = true }
        }
        if (!returned.isEmpty() && !echoed) {
            return [found: new LinkedHashSet<String>(),
                    error: "OMS returns lookup did not honor the ${paramName} filter (no returned record echoed a requested id).".toString()]
        }
        return [found: found, error: null]
    }

    /**
     * Keeps items[] nested (RQ-21). A projection naming only top-level fields must never strip
     * items — phase 2's composite key addresses it by JSON path.
     */
    protected static Map<String, Object> projectRecord(Map<String, Object> record, List keepRecordFields) {
        if (!keepRecordFields) return record
        Set<String> keep = keepRecordFields.collect { normalize(it) }.findAll { it } as Set
        keep.add("items")
        Map<String, Object> projected = [:]
        record.each { Object key, Object value ->
            if (keep.contains(String.valueOf(key))) projected.put(String.valueOf(key), value)
        }
        return projected
    }

    protected static Map<String, Object> buildMetadata(Map serverCounts, Map exclusionCounts,
                                                        List<Map<String, Object>> parsedFilters) {
        Map<String, Object> filters = [:]
        filters.put("excludedNoShopifyRefCount", normalizeInt(serverCounts.get("excludedNoShopifyRefCount"), 0))
        filters.put("serverReportedReturnsCount", normalizeInt(serverCounts.get("returnsCount"), 0))

        // parseRules returns the rule list directly — no "rules" wrapper key to unwrap.
        List<Map<String, Object>> rules = parsedFilters ?: []
        if (rules) {
            // Every configured rule appears, including one that matched nothing (excludedCount 0) —
            // a missing entry would read as "not applied". Absent entirely when no rules configured.
            filters.put("configuredExclusions", rules.collect { Map<String, Object> rule ->
                String key = String.valueOf(rule.get("sequenceNum"))
                return [
                        sequenceNum    : rule.get("sequenceNum"),
                        fieldExpression: rule.get("fieldExpression"),
                        operator       : rule.get("operator"),
                        values         : rule.get("values"),
                        excludedCount  : normalizeInt(exclusionCounts.get(key), 0),
                ]
            })
        }
        return [filters: filters]
    }

    // retainRecords, NOT unconditional: extractReturnsToFile's contract is "same Map minus records"
    // on EVERY path, not just success. A failure() that always carried `records: []` would leave
    // the key present (if empty) on every extractReturnsToFile error, contradicting that contract
    // and the sibling's extractOrdersToFile, which strips `records` unconditionally.
    private static Map<String, Object> failure(List<String> errors, List<String> warnings = [],
                                                boolean retainRecords = true) {
        Map<String, Object> result = [recordCount: 0, dataAvailable: false, requestMetadata: [:],
                warnings: warnings ?: [], errors: errors ?: [], fileName: null]
        if (retainRecords) result.put("records", [])
        return result
    }

    /**
     * Streams {records:[...],metadata:{...}} so a month-scale window never holds more than a page.
     *
     * Mirrors OmsRestSourceSupport.OrdersDocumentSink's cleanup contract exactly: the writer opens
     * LAZILY on the first actual write (never in advance), so a request that fails before a single
     * record is known good never creates a file at all; and abort() closes without writing the
     * closing bracket/metadata, so a mid-window failure leaves either no file (nothing was ever
     * written) or a deliberately-unterminated fragment that the caller then deletes — never a
     * well-formed-looking but silently partial document.
     */
    protected static class OutputSink {
        private final File targetFile
        private Writer writer
        private boolean first = true
        int writtenCount = 0

        OutputSink(File targetFile) { this.targetFile = targetFile }

        private void ensureOpen() {
            if (writer != null || targetFile == null) return
            targetFile.getParentFile()?.mkdirs()
            writer = new OutputStreamWriter(new FileOutputStream(targetFile), "UTF-8")
            writer.write('{"records":[')
        }

        void write(Map<String, Object> record) {
            writtenCount++
            if (targetFile == null) return
            ensureOpen()
            if (!first) writer.write(",")
            writer.write(JsonOutput.toJson(record))
            first = false
        }

        void finish(Map<String, Object> metadata) {
            if (targetFile == null) return
            ensureOpen()
            writer.write('],"metadata":')
            writer.write(JsonOutput.toJson(metadata ?: [:]))
            writer.write('}')
            writer.flush()
        }

        /** Closes without writing the closing bracket/metadata — never call finish() after this. */
        void abort() {
            if (writer != null) {
                try { writer.close() } catch (Exception ignored) { }
                writer = null
            }
        }

        void close() {
            try { writer?.close() } catch (Exception ignored) { }
        }
    }
}
