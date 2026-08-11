package darpan.hotwax.oms

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import darpan.reconciliation.source.SourceFilterSupport

import static darpan.common.ValueSupport.boundedInt
import static darpan.common.ValueSupport.normalize
import static darpan.common.ValueSupport.normalizeBool
import static darpan.common.ValueSupport.normalizeInt

import java.nio.charset.StandardCharsets
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.net.URLEncoder
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.zip.GZIPInputStream

class OmsRestSourceSupport {
    static final String DEFAULT_ORDERS_PATH = "/rest/s1/oms/orders"
    static final String DEFAULT_FILE_NAME_PREFIX = "oms-orders"
    static final String DEFAULT_API_KEY_HEADER_NAME = "api_key"
    static final String DEFAULT_TIME_ZONE = "UTC"
    static final String SALES_ORDER_TYPE_ID = "SALES_ORDER"
    static final String EXCHANGE_ORDER_ASSOC_TYPE_ID = "EXCHANGE"
    static final int EXCHANGE_MANIFEST_MAX_ENTRIES = 500
    static final String ORDER_TYPE_ID_FIELD = "orderTypeId"
    static final String ORDER_STATUS_ID_FIELD = "statusId"
    static final String ORDER_ITEM_ASSOC_TYPE_ID_FIELD = "orderItemAssocTypeId"
    static final int DEFAULT_ORDERS_PAGE_SIZE = 500
    static final int MAX_ORDERS_PAGE_COUNT = 20000
    // A status-defined population has no natural bound the way a date window does. This ceiling exists
    // so a misconfigured status set cannot page indefinitely; breaching it FAILS the extract rather
    // than truncating, because a short state extract is indistinguishable from records genuinely
    // missing on this side.
    static final int DEFAULT_STATE_EXTRACT_MAX_RECORDS = 50000
    // Default prefetch window after the pagination strategy commits. Two keeps one page in
    // flight while the previous is consumed — chosen for memory-constrained production hosts;
    // raise per config (ordersFetchConcurrency, capped at 4) where the box has headroom.
    static final int DEFAULT_ORDERS_FETCH_CONCURRENCY = 2
    // Some OMS list endpoints silently cap a page at 50 records regardless of the requested pageSize;
    // a 50-record first page is therefore treated as "maybe truncated" and triggers a second-page probe.
    static final int OMS_DEFAULT_SERVER_PAGE_SIZE = 50
    static final String DEFAULT_WINDOW_FIELD_NAME = "orderDate"

    private static final JsonSlurper JSON_SLURPER = new JsonSlurper()
    private static final List<String> CONFIG_FIELD_NAMES = [
            "omsRestSourceConfigId",
            "description",
            "companyUserGroupId",
            "createdByUserId",
            "baseUrl",
            "ordersPath",
            "timeZone",
            "authType",
            "username",
            "password",
            "apiToken",
            "headersJson",
            "connectTimeoutSeconds",
            "readTimeoutSeconds",
            "isActive",
            "canReadOrders",
            "createdDate",
            "lastUpdatedDate",
    ]
    private static final List<Closure<Long>> WINDOW_MILLIS_PARSERS = [
            { String text -> Instant.parse(text).toEpochMilli() },
            { String text -> OffsetDateTime.parse(text).toInstant().toEpochMilli() },
            { String text -> ZonedDateTime.parse(text).toInstant().toEpochMilli() },
            { String text -> Timestamp.valueOf(text).time },
            { String text -> LocalDateTime.parse(text).toInstant(ZoneOffset.UTC).toEpochMilli() },
            { String text -> LocalDate.parse(text).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() },
    ]
    private static final List<Map<String, Object>> PAGINATION_STRATEGIES = [
            [name: "pageIndexPageSize", indexParam: "pageIndex", sizeParam: "pageSize"],
            [name: "viewIndexViewSize", indexParam: "viewIndex", sizeParam: "viewSize"],
    ]
    private static final Set<Integer> RECOVERABLE_PAGINATION_STATUS_CODES = [400, 404, 405, 422] as Set
    private static final List<String> ORDER_LIST_KEYS = ["orders", "order", "data", "items", "records", "results"]
    private static final Closure DEFAULT_HTTP_CLIENT = { Map request -> executeHttpRequest(request) }
    private static Closure httpClient = DEFAULT_HTTP_CLIENT

    static void setHttpClient(Closure client) {
        httpClient = client ?: DEFAULT_HTTP_CLIENT
    }

    static void resetHttpClient() {
        httpClient = DEFAULT_HTTP_CLIENT
    }

    static Map<String, Object> extractOrders(Object rawConfig, Object windowStart, Object windowEnd,
                                             List keepRecordFields = null, Closure pageProgressListener = null,
                                             List excludeFilters = null, Map extractOptions = null) {
        // In-memory variant kept for tests and small interactive windows. The automation path must
        // use extractOrdersToFile so month-scale windows never materialize whole in heap.
        StringWriter buffer = new StringWriter()
        Map<String, Object> result = extractOrdersInternal(rawConfig, windowStart, windowEnd,
                new OrdersDocumentSink({ -> buffer }), keepRecordFields, pageProgressListener, excludeFilters,
                extractOptions)
        if (!(result.errors as List)) {
            String outputText = buffer.toString()
            result.outputText = outputText
            // Convenience for in-memory callers only; the streaming pipeline never retains records.
            result.records = ((JSON_SLURPER.parseText(outputText) as Map).records as List) ?: []
        }
        return result
    }

    static Map<String, Object> extractOrdersToFile(Object rawConfig, Object windowStart, Object windowEnd,
                                                   File targetFile, List keepRecordFields = null,
                                                   Closure pageProgressListener = null,
                                                   List excludeFilters = null, Map extractOptions = null) {
        OrdersDocumentSink sink = new OrdersDocumentSink({ ->
            targetFile.getParentFile()?.mkdirs()
            return new BufferedWriter(new OutputStreamWriter(new FileOutputStream(targetFile), StandardCharsets.UTF_8))
        })
        Map<String, Object> result
        try {
            result = extractOrdersInternal(rawConfig, windowStart, windowEnd, sink, keepRecordFields,
                    pageProgressListener, excludeFilters, extractOptions)
        } catch (Exception e) {
            sink.abort()
            targetFile.delete()
            throw e
        }
        if (result.errors) {
            sink.abort()
            targetFile.delete()
        }
        result.remove("records")
        return result
    }

    private static Map<String, Object> extractOrdersInternal(Object rawConfig, Object windowStart, Object windowEnd,
                                                             OrdersDocumentSink sink, List keepRecordFields = null,
                                                             Closure pageProgressListener = null,
                                                             List excludeFilters = null, Map extractOptions = null) {
        Set<String> keepFieldSet = normalizeKeepFields(keepRecordFields)
        Map<String, Object> options = normalizeExtractOptions(extractOptions)
        Map config = toPlainMap(rawConfig)
        // Synchronized: page-preparation runs on fetch-pool worker threads under concurrency.
        List<String> warnings = Collections.synchronizedList(new ArrayList<String>())
        List<String> errors = []
        List<Map<String, Object>> excludeRules
        try {
            // Parsed once, before the fetch pool starts: page preparation runs on worker threads, so
            // a malformed rule must fail here, once, rather than N times mid-window.
            excludeRules = SourceFilterSupport.parseRules(excludeFilters)
        } catch (IllegalArgumentException e) {
            excludeRules = Collections.emptyList()
            errors.add(e.message)
        }

        // A state-based extract defines its population by status alone, so both bounds may be absent.
        // A HALF-supplied window is still an error: it reads as a window and silently would not be one.
        //
        // Branch on SUPPLIED-ness, never on the parse result. parseWindowMillis returns null for two
        // different outcomes — "nothing was supplied" and "what was supplied would not parse" — so
        // testing fromMillis == null would report a malformed timestamp as a missing one, and two
        // malformed bounds as "no window supplied", telling the operator to add a status filter when
        // the real problem is a bad date.
        boolean fromSupplied = windowStart != null
        boolean thruSupplied = windowEnd != null
        Long fromMillis = fromSupplied ? parseWindowMillis(windowStart, "windowStart", errors) : null
        Long thruMillis = thruSupplied ? parseWindowMillis(windowEnd, "windowEnd", errors) : null
        if (fromSupplied != thruSupplied) {
            errors.add("windowStart and windowEnd must be supplied together, or both omitted.")
        }
        if (fromMillis != null && thruMillis != null && fromMillis > thruMillis) {
            errors.add("windowStart must be before or equal to windowEnd.")
        }
        if (!fromSupplied && !thruSupplied && !(options.orderStatusIds as List)) {
            errors.add("An extract with no date window must supply at least one order status.")
        }

        String baseUrl = normalize(config?.baseUrl)
        String ordersPath = normalize(config?.ordersPath) ?: DEFAULT_ORDERS_PATH
        String timeZone = normalize(config?.timeZone) ?: DEFAULT_TIME_ZONE
        if (!baseUrl) errors.add("Base URL is required.")

        Map<String, String> headers = [:]
        if (!errors) {
            try {
                headers = buildHeaders(config)
            } catch (IllegalArgumentException e) {
                errors.add(e.message)
            }
        }

        String endpointUrl = null
        // Same derivation buildOrdersUrl uses, so the reported query cannot drift from the one
        // actually issued (extraQueryParams — pagination — are request-shape only, not reported here).
        Map<String, Object> metadataQueryParams = buildOrdersQueryParams(options, fromMillis, thruMillis)
        Map<String, Object> requestMetadata = [
                method     : "GET",
                baseUrl    : sanitizeBaseUrl(baseUrl),
                ordersPath : normalizeOrdersPath(ordersPath),
                queryParams: metadataQueryParams,
                authType   : normalize(config?.authType)?.toUpperCase() ?: "NONE",
                timeZone   : timeZone,
                headerNames: safeHeaderNames(headers),
                pagination : [
                        pageSize    : resolveOrdersPageSize(config),
                        pageCount   : 0,
                        strategy    : null,
                        totalFetched: 0,
                ],
        ]

        if (!errors) {
            endpointUrl = buildOrdersEndpointUrl(baseUrl, ordersPath)
        }

        Map<String, Object> baseResult = [
                dataAvailable  : false,
                recordCount    : 0,
                records        : [],
                requestMetadata: requestMetadata,
                warnings       : warnings,
                errors         : errors,
                fromMillis     : fromMillis,
                thruMillis     : thruMillis,
                fileName       : buildDefaultFileName(fromMillis, thruMillis,
                        SALES_ORDER_TYPE_ID.equalsIgnoreCase((String) options.orderTypeId)
                                ? null : "oms-transfer-orders"),
        ]
        if (errors) return baseResult

        int excludedNonSalesOrderCount = 0
        int excludedExchangeOrderCount = 0
        int extractedRecordCount = 0
        int consumedRawCount = 0
        List exchangeManifest = []
        boolean exchangeManifestTruncated = false
        Map<Integer, Integer> excludedByRuleCounts = [:]
        Closure pageConsumer = { Map<String, Object> pageBundle ->
            // Pages arrive as pre-filtered, pre-serialized bundles (built on the fetch thread),
            // so consuming a page is an append plus counter bumps — no parsed graphs retained.
            excludedNonSalesOrderCount += (int) pageBundle.excludedNonSalesOrderCount
            excludedExchangeOrderCount += (int) pageBundle.excludedExchangeOrderCount
            ((Map<Integer, Integer>) pageBundle.excludedByRuleCounts ?: [:]).each { Integer sequenceNum, Integer count ->
                excludedByRuleCounts.put(sequenceNum, (excludedByRuleCounts.get(sequenceNum) ?: 0) + count)
            }
            for (Object entry : (List) (pageBundle.excludedExchangeOrders ?: [])) {
                if (exchangeManifest.size() >= EXCHANGE_MANIFEST_MAX_ENTRIES) { exchangeManifestTruncated = true; break }
                exchangeManifest.add(entry)
            }
            int filteredCount = (int) pageBundle.filteredCount
            if (filteredCount > 0) sink.writeSerializedPage((String) pageBundle.serializedRecords, filteredCount)
            extractedRecordCount += filteredCount
            consumedRawCount += (int) pageBundle.rawCount
            // Windowed extracts are bounded by their window; only a window-less (state) extract needs
            // this. Fail hard — never truncate. Branches on supplied-ness (not fromMillis/thruMillis
            // nullness) for the same reason the window-required check above does: both signal "no
            // window" identically once parsed, but only supplied-ness is unambiguous.
            if (!fromSupplied && !thruSupplied && consumedRawCount > (options.maxRecords as int)) {
                throw new IllegalStateException(
                        "Status-only extract exceeded its maximum of ${options.maxRecords} records. " +
                        "Narrow the status list or raise the limit; the extract was not truncated.")
            }
            if (pageProgressListener != null) {
                // Progress is advisory: a listener failure must never fail the extraction.
                try {
                    pageProgressListener.call(consumedRawCount)
                } catch (Exception ignored) {
                }
            }
        }

        if (keepFieldSet) {
            requestMetadata.projection = [keepRecordFields: keepFieldSet.sort()]
        }

        Map extraction
        try {
            // extractOptions, not options: buildOrdersUrl and filterComparableOrderRecords each
            // normalize their own extractOptions argument internally (see their own docs), and
            // normalizeExtractOptions is not idempotent on filterOrderTypeServerSide — it is
            // recomputed from whether orderTypeId is present, and a normalized map always carries
            // one (defaulted to SALES_ORDER). Feeding the already-normalized options map back in
            // would flip filterOrderTypeServerSide to true for every plain sales-order extract and
            // put a spurious orderTypeId=SALES_ORDER on the wire — breaking the byte-identical
            // sales-order URL contract. The raw, possibly-null extractOptions carries the same
            // information and normalizes correctly exactly once at each leaf.
            extraction = extractAllOrderPages(endpointUrl, fromMillis, thruMillis, headers, config, warnings,
                    pageConsumer, keepFieldSet, excludeRules, extractOptions)
        } catch (IOException e) {
            sink.abort()
            errors.add("Failed writing OMS extract output: ${e.message}".toString())
            return baseResult
        } catch (IllegalStateException e) {
            // Thrown by pageConsumer when a window-less (state) extract breaches its record ceiling.
            // Caught here, once, so the service returns a clean error rather than an escaped
            // exception — consistent with how the IOException case above is handled.
            sink.abort()
            errors.add(e.message)
            return baseResult
        }
        requestMetadata.statusCode = extraction.statusCode
        requestMetadata.attemptCount = extraction.attemptCount ?: 0
        if (extraction.retriedWithTrailingSlash) requestMetadata.retriedWithTrailingSlash = true
        requestMetadata.pagination = extraction.pagination ?: requestMetadata.pagination
        if (requestMetadata.pagination instanceof Map) {
            ((Map) requestMetadata.pagination).fetchConcurrency = resolveOrdersFetchConcurrency(config)
        }
        if (extraction.errors) {
            sink.abort()
            errors.addAll((List) extraction.errors)
            return baseResult
        }

        Map<String, Object> stateExtractMetadata = (!fromSupplied && !thruSupplied) ? [
                statusIds : new ArrayList<String>((List<String>) options.orderStatusIds),
                maxRecords: options.maxRecords,
        ] : null
        requestMetadata.filters = buildFilterMetadata(excludedNonSalesOrderCount, excludedExchangeOrderCount,
                exchangeManifestTruncated, excludeRules, excludedByRuleCounts, stateExtractMetadata)
        Map documentMetadata = requestMetadata + [
                sourceType            : "HOTWAX_OMS_REST_ORDERS",
                omsRestSourceConfigId : normalize(config?.omsRestSourceConfigId),
                windowStartEpochMillis: fromMillis,
                windowEndEpochMillis  : thruMillis,
                extractedRecordCount  : extractedRecordCount,
        ]
        try {
            sink.finish(documentMetadata)
        } catch (IOException e) {
            sink.abort()
            errors.add("Failed writing OMS extract output: ${e.message}".toString())
            return baseResult
        }
        return baseResult + [
                dataAvailable: extractedRecordCount > 0,
                recordCount  : extractedRecordCount,
                warnings     : warnings,
                errors       : errors,
                exchangeManifest         : exchangeManifest,
                exchangeManifestTruncated: exchangeManifestTruncated,
        ]
    }

    /**
     * Streams the extract document as {"records":[...],"metadata":{...}} — records first so pages
     * can be appended as they arrive and the metadata (counts, pagination) written once at the
     * end. Key order is irrelevant to consumers: the file is read by JSON parsers (Spark multiLine
     * JSON + JSONPath), never positionally. The writer is opened lazily so rejected requests never
     * create a file, and flushed per page so committed pages live on disk, not in heap.
     */
    protected static class OrdersDocumentSink {
        private final Closure<Writer> writerFactory
        private Writer writer
        private int writtenRecordCount = 0

        OrdersDocumentSink(Closure<Writer> writerFactory) {
            this.writerFactory = writerFactory
        }

        private void ensureOpen() {
            if (writer == null) {
                writer = writerFactory.call()
                writer.write('{"records":[')
            }
        }

        void writeSerializedPage(String serializedRecords, int recordCount) {
            if (recordCount <= 0) return
            ensureOpen()
            if (writtenRecordCount > 0) writer.write(',')
            writer.write(serializedRecords)
            writtenRecordCount += recordCount
            writer.flush()
        }

        void finish(Map metadata) {
            ensureOpen()
            writer.write('],"metadata":')
            writer.write(JsonOutput.toJson(metadata))
            writer.write('}')
            writer.flush()
            writer.close()
            writer = null
        }

        void abort() {
            if (writer != null) {
                try {
                    writer.close()
                } catch (IOException ignored) {
                }
                writer = null
            }
        }
    }

    static Map<String, Object> safeConfigMap(def cfg) {
        return safeConfigMapFromPlain(toPlainMap(cfg))
    }

    private static Map<String, Object> safeConfigMapFromPlain(Map config) {
        return [
                omsRestSourceConfigId : config.omsRestSourceConfigId,
                description           : config.description,
                companyUserGroupId    : config.companyUserGroupId,
                baseUrl               : sanitizeBaseUrl(config.baseUrl),
                ordersPath            : normalize(config.ordersPath) ?: DEFAULT_ORDERS_PATH,
                timeZone              : normalize(config.timeZone) ?: DEFAULT_TIME_ZONE,
                authType              : normalize(config.authType)?.toUpperCase() ?: "NONE",
                hasUsername           : !!normalize(config.username),
                hasPassword           : !!normalize(config.password),
                hasApiToken           : !!normalize(config.apiToken),
                customHeaderNames     : safeHeaderNames(parseHeadersJson(config.headersJson)),
                connectTimeoutSeconds : normalizeInt(config.connectTimeoutSeconds, 30),
                readTimeoutSeconds    : normalizeInt(config.readTimeoutSeconds, 60),
                isActive              : normalizeBool(config.isActive, true) ? "Y" : "N",
                canReadOrders         : normalizeBool(config.canReadOrders, true),
                createdDate           : config.createdDate,
                lastUpdatedDate       : config.lastUpdatedDate,
        ]
    }

    /**
     * DAR-BE-005: {@code allowSharedPeer} defaults to today's strict owner-only behavior. The save
     * path passes the caller's actual peer standing (computed from the ConfigTenantAccess grant
     * table before this call) so a member tenant may edit every field of a shared config; the
     * delete path never passes it, and additionally never forwards a foreign existingConfig here at
     * all (see deleteOmsRestSourceConfig.groovy) — the peer-vs-stranger distinction for delete's
     * denial text is decided by the caller, not this method, so this method's own mismatch message
     * is never reached from delete and cannot leak a cross-tenant existence oracle there.
     */
    static void requireWritableTenantConfig(Map existingConfig, String activeTenantUserGroupId, boolean canWrite,
                                            boolean allowSharedPeer = false) {
        String tenantId = normalize(activeTenantUserGroupId)
        if (!tenantId) throw new IllegalArgumentException("An active tenant is required for tenant-scoped writes.")
        if (!canWrite) throw new IllegalArgumentException("Your active tenant is read-only for this action.")
        if (existingConfig && normalize(existingConfig.companyUserGroupId) != tenantId && !allowSharedPeer) {
            throw new IllegalArgumentException("Requested OMS source config is not available in your active tenant.")
        }
    }

    static final int PAIR_LOOKUP_MAX_IDS = 100

    /**
     * Point lookup of full order groups by Shopify externalId — escapes run windows by construction
     * (callers pass a wide orderDate window). Conservative: any per-id failure fails the whole call.
     */
    static Map<String, Object> lookupOrdersByExternalId(Map rawConfig, Collection externalIds,
                                                        Long fromMillis, Long thruMillis) {
        // Plain mapping like the extraction path (extractOrdersInternal) — NOT safeConfigMapFromPlain,
        // which redacts secrets for response metadata and would strip the auth token buildHeaders needs.
        Map config = toPlainMap(rawConfig)
        List<String> errors = []
        Map<String, Object> ordersByExternalId = [:]
        List<String> ids = (externalIds ?: []).collect { normalize(it) }.findAll { it }.unique()
        if (!ids) return [ok: false, ordersByExternalId: [:], errors: ["No externalIds provided for OMS pair lookup."]]
        if (ids.size() > PAIR_LOOKUP_MAX_IDS) {
            return [ok: false, ordersByExternalId: [:],
                    errors: ["OMS pair lookup requested ${ids.size()} ids, above the ${PAIR_LOOKUP_MAX_IDS}-id cap.".toString()]]
        }
        Map<String, String> headers
        String endpointUrl
        try {
            headers = buildHeaders(config)
            endpointUrl = buildOrdersEndpointUrl(config.baseUrl as String, config.ordersPath as String)
        } catch (Exception e) {
            return [ok: false, ordersByExternalId: [:], errors: ["OMS pair lookup setup failed: ${e.message}".toString()]]
        }
        for (String externalId : ids) {
            String url = buildOrdersUrl(endpointUrl, fromMillis, thruMillis,
                    [externalId: externalId, pageSize: 50, pageIndex: 0])
            Map response
            try {
                response = callOmsEndpoint(url, headers, config)
            } catch (Exception e) {
                return [ok: false, ordersByExternalId: [:], errors: ["OMS pair lookup for ${externalId} failed: ${e.message}".toString()]]
            }
            int statusCode = (response?.statusCode ?: 0) as int
            if (statusCode < 200 || statusCode >= 300) {
                return [ok: false, ordersByExternalId: [:], errors: ["OMS pair lookup for ${externalId} failed with HTTP ${statusCode}.".toString()]]
            }
            List records = []
            try {
                def parsed = JSON_SLURPER.parseText((response.body ?: "null") as String)
                if (parsed instanceof List) records = (List) parsed
                else if (parsed instanceof Map) {
                    Object ordersValue = ((Map) parsed).get('orders')
                    records = ordersValue instanceof List ? (List) ordersValue
                            : (((Map) parsed).values().find { it instanceof List } ?: []) as List
                }
            } catch (Exception e) {
                return [ok: false, ordersByExternalId: [:], errors: ["OMS pair lookup for ${externalId} returned unparseable JSON: ${e.message}".toString()]]
            }
            // Echo validation: a tenant-configured OMS endpoint that ignores the (possibly unknown)
            // externalId= query param would otherwise return arbitrary window orders, producing false
            // "original present" evidence and garbage pair sums. Records whose own externalId field
            // does not echo the id we asked for are dropped. If the endpoint returned records at all
            // but NONE echoed the requested id, the filter is evidently unsupported — that is proof
            // the response is not trustworthy for this id, so the whole lookup fails rather than
            // risking a confidently-wrong diff row. A genuinely empty response (zero records) is left
            // alone: that is valid evidence of "no such order," not a sign the filter was ignored.
            List<Map> rawMapRecords = records.findAll { it instanceof Map } as List<Map>
            List<Map> echoedRecords = rawMapRecords.findAll { Map record -> normalize(record.get('externalId')) == externalId }
            if (rawMapRecords && !echoedRecords) {
                return [ok: false, ordersByExternalId: [:],
                        errors: ["OMS pair lookup for ${externalId} failed: endpoint did not honor externalId filter (no returned record echoed externalId ${externalId}).".toString()]]
            }
            ordersByExternalId.put(externalId, echoedRecords.collect { Map record -> summarizePairOrder(record) })
        }
        return [ok: true, ordersByExternalId: ordersByExternalId, errors: errors]
    }

    protected static Map<String, Object> summarizePairOrder(Map record) {
        return [
                omsOrderId      : normalize(record.get('orderId')),
                externalId      : normalize(record.get('externalId')),
                orderName       : normalize(record.get('orderName')),
                orderTypeId     : normalize(record.get('orderTypeId')),
                statusId        : normalize(record.get('statusId')),
                grandTotal      : record.get('grandTotal'),
                orderDate       : record.get('orderDate'),
                hasExchangeAssoc: containsExchangeOrderAssociation(record),
        ]
    }

    static String safeFileName(Object rawName, String fallback = null) {
        String normalized = normalize(rawName)
        String fileName = normalized ? normalized.tokenize("/\\").last() : normalize(fallback)
        if (!fileName) fileName = buildDefaultFileName(null, null)
        fileName = fileName.replaceAll(/[^A-Za-z0-9._-]/, "_").replaceAll(/^\.+/, "")
        if (!fileName) fileName = buildDefaultFileName(null, null)
        return fileName.toLowerCase().endsWith(".json") ? fileName : "${fileName}.json"
    }

    static String buildDefaultFileName(Long fromMillis, Long thruMillis, String filePrefix = null) {
        String fromToken = fromMillis != null ? fromMillis.toString() : "start"
        String thruToken = thruMillis != null ? thruMillis.toString() : "end"
        String prefix = normalize(filePrefix) ?: DEFAULT_FILE_NAME_PREFIX
        return "${prefix}-${fromToken}-${thruToken}.json"
    }

    // Both call sites (extractOrdersInternal) are supplied-guarded — they only call this when the
    // caller passed a non-null windowStart/windowEnd — so rawValue is never null here.
    protected static Long parseWindowMillis(Object rawValue, String label, List<String> errors) {
        if (rawValue instanceof Number) return ((Number) rawValue).longValue()
        if (rawValue instanceof Timestamp) return ((Timestamp) rawValue).time
        if (rawValue instanceof Date) return ((Date) rawValue).time
        if (rawValue instanceof Instant) return ((Instant) rawValue).toEpochMilli()
        if (rawValue instanceof OffsetDateTime) return ((OffsetDateTime) rawValue).toInstant().toEpochMilli()
        if (rawValue instanceof ZonedDateTime) return ((ZonedDateTime) rawValue).toInstant().toEpochMilli()
        if (rawValue instanceof LocalDateTime) return ((LocalDateTime) rawValue).toInstant(ZoneOffset.UTC).toEpochMilli()
        if (rawValue instanceof LocalDate) return ((LocalDate) rawValue).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()

        String value = normalize(rawValue)
        if (!value) {
            errors.add("${label} is required.")
            return null
        }
        if (value ==~ /-?\d+/) {
            try {
                return Long.parseLong(value)
            } catch (NumberFormatException ignored) {
                errors.add("${label} must be a Timestamp, Date, ISO-8601 value, or epoch milliseconds.")
                return null
            }
        }

        for (Closure<Long> parser : WINDOW_MILLIS_PARSERS) {
            try {
                return parser.call(value)
            } catch (Exception ignored) {
            }
        }

        errors.add("${label} must be a Timestamp, Date, ISO-8601 value, or epoch milliseconds.")
        return null
    }

    protected static String buildOrdersUrl(String endpointUrl, Long fromMillis, Long thruMillis,
                                           Map<String, Object> extraQueryParams,
                                           Map<String, Object> extractOptions = null) {
        Map<String, Object> options = normalizeExtractOptions(extractOptions)
        String separator = endpointUrl.contains("?") ? "&" : "?"
        Map<String, Object> queryParams = buildOrdersQueryParams(options, fromMillis, thruMillis)

        queryParams.putAll(extraQueryParams ?: [:])
        return "${endpointUrl}${separator}${queryParams.collect { key, value -> "${encodeQueryComponent(key)}=${encodeQueryComponent(value)}" }.join("&")}"
    }

    /** The reconciliation-defined query parameters for an orders request, in wire order. Shared by
     *  buildOrdersUrl and by the request metadata so the reported query cannot drift from the issued
     *  one. Caller-supplied extraQueryParams (pagination) are appended by buildOrdersUrl, not here. */
    protected static Map<String, Object> buildOrdersQueryParams(Map<String, Object> options,
                                                                Long fromMillis, Long thruMillis) {
        Map<String, Object> queryParams = new LinkedHashMap<>()

        // The window is optional: a state-based extract defines its population by status alone.
        String windowFieldName = (String) options.windowFieldName
        if (fromMillis != null) queryParams.put("${windowFieldName}_from".toString(), fromMillis)
        if (thruMillis != null) queryParams.put("${windowFieldName}_thru".toString(), thruMillis)

        // Only sent when the caller explicitly asked for a type, so the shipped sales-order path
        // keeps producing exactly the URLs it produced before.
        if (options.filterOrderTypeServerSide as boolean) {
            queryParams.put(ORDER_TYPE_ID_FIELD, options.orderTypeId)
        }

        // Moqui's searchFormMap turns a comma-joined value plus <field>_op=in into a SQL IN
        // (EntityFindBase.searchFormMap). Server-side status filtering costs the OMS nothing extra.
        List<String> orderStatusIds = (List<String>) options.orderStatusIds
        if (orderStatusIds) {
            queryParams.put(ORDER_STATUS_ID_FIELD, orderStatusIds.join(","))
            queryParams.put("${ORDER_STATUS_ID_FIELD}_op".toString(), "in")
        }

        return queryParams
    }

    protected static String buildOrdersEndpointUrl(String baseUrl, String ordersPath) {
        String normalizedBase = normalize(baseUrl)?.replaceAll(/\/+$/, "")
        String normalizedPath = normalizeOrdersPath(ordersPath)
        if (!normalizedBase) return normalizedPath

        try {
            URI uri = new URI(normalizedBase)
            if (uri.scheme && uri.host) {
                String joinedPath = joinPathWithOverlap(uri.path, normalizedPath)
                // Drop any userInfo (basic-auth embedded in the URL) from the live request URL so
                // credentials cannot leak into request paths, logs, or connection-failure error text.
                // URL-embedded auth is not a supported mode here — use username/password/apiToken.
                return new URI(uri.scheme, null, uri.host, uri.port, joinedPath, uri.query, uri.fragment).toString()
            }
        } catch (Exception ignored) {
        }

        return "${normalizedBase}${suffixAfterPathOverlap(normalizedBase, normalizedPath)}"
    }

    protected static String normalizeOrdersPath(Object rawPath) {
        String path = normalize(rawPath) ?: DEFAULT_ORDERS_PATH
        path = path.replaceAll(/\\+/, "/")
        return path.startsWith("/") ? path : "/${path}"
    }

    protected static String normalizePath(Object rawPath) {
        String path = normalize(rawPath)?.replaceAll(/\\+/, "/")
        if (!path) return ""
        path = path.replaceAll(/\/+$/, "")
        if (!path) return "/"
        return path.startsWith("/") ? path : "/${path}"
    }

    protected static String joinPathWithOverlap(Object rawBasePath, String rawChildPath) {
        String basePath = normalizePath(rawBasePath)
        String childPath = normalizePath(rawChildPath)
        if (!basePath || basePath == "/") return childPath
        if (!childPath || childPath == "/") return basePath

        List<String> baseSegments = basePath.tokenize("/")
        List<String> childSegments = childPath.tokenize("/")
        int overlap = overlappingSegmentCount(baseSegments, childSegments)
        List<String> joinedSegments = []
        joinedSegments.addAll(baseSegments)
        joinedSegments.addAll(childSegments.drop(overlap))
        return "/" + joinedSegments.join("/")
    }

    protected static String suffixAfterPathOverlap(String normalizedBase, String normalizedPath) {
        try {
            URI uri = new URI(normalizedBase)
            return "/" + joinPathWithOverlap(uri.path, normalizedPath).tokenize("/").join("/")
        } catch (Exception ignored) {
        }

        List<String> baseSegments = normalizePath(normalizedBase).tokenize("/")
        List<String> pathSegments = normalizePath(normalizedPath).tokenize("/")
        int overlap = overlappingSegmentCount(baseSegments, pathSegments)
        List<String> suffixSegments = pathSegments.drop(overlap)
        return suffixSegments ? "/" + suffixSegments.join("/") : ""
    }

    protected static int overlappingSegmentCount(List<String> baseSegments, List<String> childSegments) {
        int maxOverlap = Math.min(baseSegments?.size() ?: 0, childSegments?.size() ?: 0)
        for (int overlap = maxOverlap; overlap > 0; overlap--) {
            List<String> baseSuffix = baseSegments.subList(baseSegments.size() - overlap, baseSegments.size())
                    .collect { String segment -> segment.toLowerCase(Locale.ROOT) }
            List<String> childPrefix = childSegments.subList(0, overlap)
                    .collect { String segment -> segment.toLowerCase(Locale.ROOT) }
            if (baseSuffix == childPrefix) return overlap
        }
        return 0
    }

    protected static String trailingSlashBeforeQuery(String rawUrl) {
        String url = normalize(rawUrl)
        if (!url) return url
        int queryIndex = url.indexOf("?")
        String pathPart = queryIndex >= 0 ? url.substring(0, queryIndex) : url
        if (pathPart.endsWith("/")) return url
        return pathPart + "/" + (queryIndex >= 0 ? url.substring(queryIndex) : "")
    }

    // ---------------------------------------------------------------------------------------------
    // Connection diagnostics
    // ---------------------------------------------------------------------------------------------

    /** An operator is watching a popup: fail fast, single attempt, never retry. */
    static final int PROBE_CONNECT_TIMEOUT_SECONDS = 5
    static final int PROBE_READ_TIMEOUT_SECONDS = 10
    private static final long PROBE_WINDOW_MILLIS = 86400000L

    /**
     * Operator-initiated connection probe for a saved OMS REST config.
     *
     * Composes the SAME helpers the extractor uses — buildOrdersEndpointUrl, buildHeaders,
     * callOmsEndpoint — so the probe exercises the real auth construction and inherits the header
     * stripping and base-URL validation, while skipping the file sink, projection and pagination
     * machinery whose failures would drown the credential signal.
     *
     * Never writes to ec.message and never throws: every outcome is a check row. Pass
     * config.credentialError when the caller could not decrypt a stored secret.
     *
     * @param config plain config map (see safeConfigMap), optionally carrying credentialError
     * @return [checks: List] in the shared diagnostics check contract
     */
    static final String PROBE_STAGE_CREDENTIAL = "credential"
    static final String PROBE_STAGE_CONNECT = "connect"
    static final List<String> PROBE_STAGES = [PROBE_STAGE_CREDENTIAL, PROBE_STAGE_CONNECT].asImmutable()
    /** Mirrors SourceConnectionDiagnosticsSupport.STAGE_FIRST — "whichever stage you run first". */
    static final String PROBE_STAGE_FIRST = "@first"

    /**
     * Run every stage in one call. Kept alongside the staged path so a single invocation still gives
     * a complete verdict — the UI walks the stages to show progress, anything else can just ask once.
     */
    static Map<String, Object> probeConnection(Map config) {
        List<Map<String, Object>> checks = []
        String stage = PROBE_STAGES[0]
        while (stage) {
            Map<String, Object> result = probeConnectionStage(config, stage)
            checks.addAll((List<Map<String, Object>>) result.checks)
            stage = normalize(result.nextStage)
        }
        return [checks: checks, nextStage: null]
    }

    /**
     * One stage, returning its rows plus the next stage to run (null when finished).
     *
     * Only two stages: the credential check is local, and everything after it is decided by a single
     * orders GET. Splitting that GET further would mean issuing the same request twice to report the
     * same three facts, so reachability, auth and the orders read land together.
     */
    static Map<String, Object> probeConnectionStage(Map config, String stage) {
        switch (normalize(stage)) {
            case PROBE_STAGE_FIRST:
            case PROBE_STAGE_CREDENTIAL: return probeCredentialStage(config)
            case PROBE_STAGE_CONNECT: return probeConnectStage(config)
            default: return [checks: [], nextStage: null]
        }
    }

    /**
     * Entirely local. password and apiToken are encrypt="true", so an unreadable secret fails HERE,
     * before a socket is opened — reporting it as a network fault is the misdiagnosis this ordering
     * exists to prevent.
     */
    protected static Map<String, Object> probeCredentialStage(Map config) {
        String failure = normalize(config?.credentialError)
        if (!failure) {
            try {
                buildHeaders(config)
            } catch (IllegalArgumentException e) {
                // Our own fixed validation text ("API token is required for BEARER auth."), safe to show.
                failure = e.message
            } catch (Throwable ignored) {
                failure = "The stored credentials could not be read."
            }
        }

        if (failure) {
            return [checks   : [diagnosticsCheck("credential", "Credential readable", "FAIL", failure),
                                probeSkipped("reachable", "Base URL reachable"),
                                probeSkipped("auth", "Credentials accepted"),
                                probeSkipped("ordersRead", "Orders readable")],
                    nextStage: null]
        }

        String authType = normalize(config?.authType)?.toUpperCase() ?: "NONE"
        return [checks   : [diagnosticsCheck("credential", "Credential readable", "PASS",
                                    authType == "NONE" ? "No credentials configured (auth type NONE)." : null)],
                nextStage: PROBE_STAGE_CONNECT]
    }

    /**
     * Composes the SAME helpers the extractor uses — buildOrdersEndpointUrl, buildHeaders,
     * callOmsEndpoint — so the probe exercises the real auth construction and inherits the header
     * stripping and base-URL validation, while skipping the file sink, projection and pagination
     * machinery whose failures would drown the credential signal.
     */
    protected static Map<String, Object> probeConnectStage(Map config) {
        List<Map<String, Object>> checks = []
        Map<String, String> headers
        try {
            headers = buildHeaders(config)
        } catch (Throwable ignored) {
            return [checks   : [diagnosticsCheck("reachable", "Base URL reachable", "FAIL",
                                        "The stored credentials could not be read."),
                                probeSkipped("auth", "Credentials accepted"),
                                probeSkipped("ordersRead", "Orders readable")],
                    nextStage: null]
        }

        // buildOrdersEndpointUrl falls back to the bare orders path when there is no base URL, which
        // is fine for display but must never be requested — require an absolute http(s) endpoint.
        String endpointUrl = buildOrdersEndpointUrl(normalize(config?.baseUrl), config?.ordersPath as String)
        if (!endpointUrl || !(endpointUrl.toLowerCase(Locale.ROOT) ==~ /^https?:\/\/.+/)) {
            return [checks   : [diagnosticsCheck("reachable", "Base URL reachable", "FAIL",
                                        "No usable base URL is configured."),
                                probeSkipped("auth", "Credentials accepted"),
                                probeSkipped("ordersRead", "Orders readable")],
                    nextStage: null]
        }

        // One record over a one-day window: the orders API requires the date parameters, and the
        // window only needs to be well-formed, not meaningful.
        long thruMillis = System.currentTimeMillis() + PROBE_WINDOW_MILLIS
        String requestUrl = buildOrdersUrl(endpointUrl, thruMillis - PROBE_WINDOW_MILLIS, thruMillis,
                pageQueryParams(PAGINATION_STRATEGIES[0], 0, 1))
        Map<String, Object> probeConfig = new LinkedHashMap<>(config ?: [:])
        probeConfig.put("connectTimeoutSeconds", PROBE_CONNECT_TIMEOUT_SECONDS)
        probeConfig.put("readTimeoutSeconds", PROBE_READ_TIMEOUT_SECONDS)

        long startedAt = System.currentTimeMillis()
        Map<String, Object> response
        try {
            response = callOmsEndpoint(requestUrl, headers, probeConfig)
        } catch (Throwable ignored) {
            return [checks   : [diagnosticsCheck("reachable", "Base URL reachable", "FAIL",
                                        "The base URL could not be reached.", System.currentTimeMillis() - startedAt),
                                probeSkipped("auth", "Credentials accepted"),
                                probeSkipped("ordersRead", "Orders readable")],
                    nextStage: null]
        }
        long elapsedMillis = System.currentTimeMillis() - startedAt
        int statusCode = normalizeInt(response?.statusCode, 0)

        if (statusCode <= 0) {
            return [checks   : [diagnosticsCheck("reachable", "Base URL reachable", "FAIL",
                                        "The base URL could not be reached.", elapsedMillis),
                                probeSkipped("auth", "Credentials accepted"),
                                probeSkipped("ordersRead", "Orders readable")],
                    nextStage: null]
        }

        // Any HTTP status at all proves the host answered.
        checks.add(diagnosticsCheck("reachable", "Base URL reachable", "PASS", null, elapsedMillis))

        if (statusCode == 401 || statusCode == 403) {
            checks.add(diagnosticsCheck("auth", "Credentials accepted", "FAIL",
                    "The stored credentials were rejected (HTTP ${statusCode}).".toString()))
            checks.add(probeSkipped("ordersRead", "Orders readable"))
            return [checks: checks, nextStage: null]
        }
        checks.add(diagnosticsCheck("auth", "Credentials accepted", "PASS"))

        if (statusCode == 404) {
            checks.add(diagnosticsCheck("ordersRead", "Orders readable", "FAIL",
                    "The orders path was not found (HTTP 404). Check the base URL and orders path."))
            return [checks: checks, nextStage: null]
        }
        if (statusCode < 200 || statusCode >= 300) {
            checks.add(diagnosticsCheck("ordersRead", "Orders readable", "FAIL",
                    "The orders request failed with HTTP ${statusCode}.".toString()))
            return [checks: checks, nextStage: null]
        }

        String body = response?.body?.toString()
        if (!body) {
            checks.add(diagnosticsCheck("ordersRead", "Orders readable", "FAIL", "The orders response was empty."))
            return [checks: checks, nextStage: null]
        }
        Object parsed
        try {
            parsed = new JsonSlurper().parseText(body)
        } catch (Exception ignored) {
            // The classic misconfiguration: a login page or proxy error page answering with HTTP 200.
            checks.add(diagnosticsCheck("ordersRead", "Orders readable", "FAIL",
                    "The orders response was not valid JSON."))
            return [checks: checks, nextStage: null]
        }

        int recordCount = ((List) extractOrderRecords(parsed, new ArrayList<String>())).size()
        checks.add(diagnosticsCheck("ordersRead", "Orders readable", "PASS",
                recordCount > 0 ? "1 order returned" : "no orders in range"))
        return [checks: checks, nextStage: null]
    }

    protected static Map<String, Object> probeSkipped(String key, String label) {
        return diagnosticsCheck(key, label, "SKIP", "Not attempted.")
    }

    /** Local builder for the shared check contract — keeps darpan-hotwax free of a core-class import. */
    protected static Map<String, Object> diagnosticsCheck(String key, String label, String status,
                                                          String detail = null, Long durationMillis = null) {
        return [
                key           : key,
                label         : label,
                status        : status,
                detail        : normalize(detail),
                durationMillis: durationMillis,
        ] as Map<String, Object>
    }

    protected static Map<String, Object> callOmsEndpoint(String requestUrl, Map<String, String> headers, Map config,
                                                         boolean acceptGzip = true) {
        return (httpClient.call([
                method               : "GET",
                url                  : requestUrl,
                headers              : headers,
                acceptGzip           : acceptGzip,
                connectTimeoutSeconds: normalizeInt(config?.connectTimeoutSeconds, 30),
                readTimeoutSeconds   : normalizeInt(config?.readTimeoutSeconds, 60),
        ]) ?: [:]) as Map<String, Object>
    }

    protected static Map<String, Object> extractAllOrderPages(String endpointUrl, Long fromMillis, Long thruMillis,
                                                              Map<String, String> headers, Map config,
                                                              List<String> warnings, Closure pageConsumer,
                                                              Set<String> keepFieldSet = null,
                                                              List<Map<String, Object>> excludeRules = null,
                                                              Map<String, Object> extractOptions = null) {
        int pageSize = resolveOrdersPageSize(config)
        int maxPageCount = Math.max(1, normalizeInt(config?.maxOrdersPageCount, MAX_ORDERS_PAGE_COUNT))
        int fetchConcurrency = resolveOrdersFetchConcurrency(config)

        for (Map<String, Object> strategy : PAGINATION_STRATEGIES) {
            Map<String, Object> firstPage = prepareOrdersPage(endpointUrl, fromMillis, thruMillis,
                    pageQueryParams(strategy, 0, pageSize), headers, config, warnings, keepFieldSet, excludeRules,
                    extractOptions)
            if (!firstPage.success) {
                if (isRecoverablePaginationFailure(firstPage.statusCode)) continue
                return failedPageResult(firstPage)
            }

            List<Map<String, Object>> pageMetas = [pageMeta(firstPage)]
            if ((int) firstPage.rawCount == 0) return successfulPageResult(strategy, pageSize, pageMetas, 0)
            if (!shouldProbeSecondPage((int) firstPage.rawCount, pageSize)) {
                pageConsumer.call(firstPage)
                return successfulPageResult(strategy, pageSize, pageMetas, (int) firstPage.rawCount)
            }

            Map<String, Object> secondPage = prepareOrdersPage(endpointUrl, fromMillis, thruMillis,
                    pageQueryParams(strategy, 1, pageSize), headers, config, warnings, keepFieldSet, excludeRules,
                    extractOptions)
            if (!secondPage.success) {
                if (isRecoverablePaginationFailure(secondPage.statusCode)) continue
                return failedPageResult(secondPage, pageMetas)
            }

            pageMetas.add(pageMeta(secondPage))
            if ((int) secondPage.rawCount == 0) {
                pageConsumer.call(firstPage)
                return successfulPageResult(strategy, pageSize, pageMetas, (int) firstPage.rawCount)
            }
            if (sameOrderPageBundles(firstPage, secondPage)) {
                warnings.add("OMS REST pagination strategy ${strategy.name} did not advance beyond the first page.")
                continue
            }

            // Strategy committed: consume the probe pages, then stream the remainder through a
            // small prefetch window. Worker threads carry fetch+parse+filter+serialize and hand
            // back only compact serialized text, so with window W the steady-state overhead is
            // ~W serialized pages — sized for resource-constrained production hosts.
            pageConsumer.call(firstPage)
            pageConsumer.call(secondPage)
            int rawFetchedCount = ((int) firstPage.rawCount) + ((int) secondPage.rawCount)
            boolean secondPageShrank = ((int) secondPage.rawCount) < ((int) firstPage.rawCount)
            Map<String, Object> previousPage = secondPage
            firstPage = null
            secondPage = null
            if (secondPageShrank) return successfulPageResult(strategy, pageSize, pageMetas, rawFetchedCount)

            ExecutorService fetchPool = fetchConcurrency > 1 ? Executors.newFixedThreadPool(fetchConcurrency) : null
            try {
                Map<Integer, Future<Map<String, Object>>> inFlightPages = new LinkedHashMap<>()
                int nextPageToRequest = 2
                int pageIndex = 2
                while (pageIndex < maxPageCount) {
                    while (fetchPool != null && nextPageToRequest < maxPageCount && inFlightPages.size() < fetchConcurrency) {
                        int requestIndex = nextPageToRequest++
                        inFlightPages.put(requestIndex, fetchPool.submit({ ->
                            prepareOrdersPage(endpointUrl, fromMillis, thruMillis,
                                    pageQueryParams(strategy, requestIndex, pageSize), headers, config, warnings,
                                    keepFieldSet, excludeRules, extractOptions)
                        } as Callable<Map<String, Object>>))
                    }

                    Map<String, Object> page = fetchPool != null ?
                            inFlightPages.remove(pageIndex).get() :
                            prepareOrdersPage(endpointUrl, fromMillis, thruMillis,
                                    pageQueryParams(strategy, pageIndex, pageSize), headers, config, warnings,
                                    keepFieldSet, excludeRules, extractOptions)
                    pageMetas.add(pageMeta(page))
                    if (!page.success) return failedPageResult(page, pageMetas)

                    if ((int) page.rawCount == 0) break
                    if (sameOrderPageBundles(previousPage, page)) {
                        warnings.add("OMS REST pagination strategy ${strategy.name} stopped because page ${pageIndex} repeated the previous page.")
                        break
                    }

                    pageConsumer.call(page)
                    rawFetchedCount += (int) page.rawCount
                    boolean pageShrank = ((int) page.rawCount) < ((int) previousPage.rawCount)
                    previousPage = page
                    if (pageShrank) break
                    pageIndex++
                }

                if (pageIndex >= maxPageCount) {
                    return [
                            errors    : ["OMS REST pagination exceeded ${maxPageCount} pages for the selected time period."],
                            statusCode: latestStatusCode(pageMetas),
                            attemptCount: totalAttemptCount(pageMetas),
                            retriedWithTrailingSlash: anyTrailingSlashRetry(pageMetas),
                            pagination: paginationMetadata(strategy, pageSize, pageMetas, rawFetchedCount, true),
                    ]
                }

                return successfulPageResult(strategy, pageSize, pageMetas, rawFetchedCount)
            } finally {
                // Ends speculative fetches on every exit path (success, failure, truncation).
                fetchPool?.shutdownNow()
            }
        }

        Map<String, Object> unpaginatedPage = prepareOrdersPage(endpointUrl, fromMillis, thruMillis, [:], headers, config, warnings, keepFieldSet, excludeRules, extractOptions)
        if (!unpaginatedPage.success) return failedPageResult(unpaginatedPage)
        warnings.add("OMS REST pagination parameters did not advance; extracted the first unpaginated response only.")
        List<Map<String, Object>> unpaginatedMetas = [pageMeta(unpaginatedPage)]
        int unpaginatedRawCount = (int) unpaginatedPage.rawCount
        if (unpaginatedRawCount > 0) pageConsumer.call(unpaginatedPage)
        return successfulPageResult([name: "unpaginated"], pageSize, unpaginatedMetas, unpaginatedRawCount)
    }

    /**
     * Fetches one page and converts it to a compact "page bundle" on the calling thread:
     * pre-filtered records serialized to comma-joined JSON text, plus counts and a raw-content
     * hash for the repeated-page guard. The parsed record graph never leaves this method, which
     * is what keeps concurrent prefetching cheap on memory: an in-flight page costs roughly its
     * serialized text, not a parsed object graph.
     */
    protected static Map<String, Object> prepareOrdersPage(String endpointUrl, Long fromMillis, Long thruMillis,
                                                           Map<String, Object> pageParams,
                                                           Map<String, String> headers, Map config,
                                                           List<String> warnings, Set<String> keepFieldSet = null,
                                                           List<Map<String, Object>> excludeRules = null,
                                                           Map<String, Object> extractOptions = null) {
        Map<String, Object> page = fetchOrdersPage(endpointUrl, fromMillis, thruMillis, pageParams, headers, config,
                warnings, extractOptions)
        if (!page.success) return page
        List rawRecords = page.records ?: []
        Map<String, Object> pageFilter = filterComparableOrderRecords(rawRecords, excludeRules, extractOptions)
        List filtered = (List) pageFilter.records
        // Projection happens after filtering (the EXCHANGE scan needs the full record) and before
        // serialization, so a trimmed extract writes ~90x less than the full order documents.
        List outputRecords = keepFieldSet ? filtered.collect { Object record -> trimRecord(record, keepFieldSet) } : filtered
        StringBuilder serialized = new StringBuilder(Math.max(16, outputRecords.size() * 512))
        boolean firstRecord = true
        for (Object record : outputRecords) {
            if (!firstRecord) serialized.append(',')
            serialized.append(JsonOutput.toJson(record))
            firstRecord = false
        }
        return [
                success                   : true,
                statusCode                : page.statusCode,
                attemptCount              : page.attemptCount,
                retriedWithTrailingSlash  : page.retriedWithTrailingSlash,
                rawCount                  : rawRecords.size(),
                rawHash                   : rawRecords.hashCode(),
                filteredCount             : filtered.size(),
                excludedNonSalesOrderCount: pageFilter.excludedNonSalesOrderCount,
                excludedExchangeOrderCount: pageFilter.excludedExchangeOrderCount,
                excludedExchangeOrders    : pageFilter.excludedExchangeOrders,
                excludedByRuleCounts      : pageFilter.excludedByRuleCounts,
                serializedRecords         : serialized.toString(),
        ]
    }

    protected static Set<String> normalizeKeepFields(List keepRecordFields) {
        if (!keepRecordFields) return null
        Set<String> keepFieldSet = new LinkedHashSet<String>()
        keepRecordFields.each { Object field ->
            String name = normalize(field)
            if (name) keepFieldSet.add(name)
        }
        return keepFieldSet ?: null
    }

    protected static Map trimRecord(Object record, Set<String> keepFieldSet) {
        if (!(record instanceof Map)) return [:]
        Map trimmed = new LinkedHashMap()
        ((Map) record).each { Object key, Object value ->
            if (keepFieldSet.contains(key?.toString()?.trim())) trimmed.put(key, value)
        }
        return trimmed
    }

    // Repeated-page detection without retaining parsed pages: raw count + deep hash of the raw
    // records + filtered serialization must all match. A true server repeat matches all three;
    // a false positive needs a raw-content hash collision on adjacent pages with identical
    // filtered text, which is negligible for this warning-and-stop heuristic.
    private static boolean sameOrderPageBundles(Map<String, Object> left, Map<String, Object> right) {
        if (left == null || right == null) return false
        return left.rawCount == right.rawCount &&
                left.rawHash == right.rawHash &&
                Objects.equals(left.serializedRecords, right.serializedRecords)
    }

    /** Per-page bookkeeping kept after a page's records are consumed — never the records themselves. */
    private static Map<String, Object> pageMeta(Map<String, Object> page) {
        return [
                statusCode              : page?.statusCode,
                attemptCount            : page?.attemptCount,
                retriedWithTrailingSlash: page?.retriedWithTrailingSlash,
        ]
    }

    protected static Map<String, Object> fetchOrdersPage(String endpointUrl, Long fromMillis, Long thruMillis,
                                                         Map<String, Object> pageParams,
                                                         Map<String, String> headers, Map config,
                                                         List<String> warnings,
                                                         Map<String, Object> extractOptions = null) {
        String requestUrl = buildOrdersUrl(endpointUrl, fromMillis, thruMillis, pageParams, extractOptions)
        Map response
        boolean retriedWithTrailingSlash = false
        boolean retriedWithoutCompression = false
        int attemptCount = 1
        try {
            try {
                response = callOmsEndpoint(requestUrl, headers, config)
            } catch (SocketTimeoutException timeoutException) {
                // A gzip-buffering proxy may hold the entire response until page generation
                // completes, so first-byte can exceed the read timeout even though the server is
                // healthy. Retry the page once with compression disabled: a plain streamed
                // response trickles bytes continuously and keeps the read timer alive.
                warnings.add("OMS REST page request timed out (${timeoutException.message}); retrying once without compression.".toString())
                attemptCount = 2
                retriedWithoutCompression = true
                response = callOmsEndpoint(requestUrl, headers, config, false)
            }
            Integer firstStatusCode = normalizeInt(response.statusCode, 0)
            if (firstStatusCode == 404) {
                String retryUrl = trailingSlashBeforeQuery(requestUrl)
                if (retryUrl && retryUrl != requestUrl) {
                    response = callOmsEndpoint(retryUrl, headers, config, !retriedWithoutCompression)
                    attemptCount++
                    retriedWithTrailingSlash = true
                }
            }
        } catch (Exception e) {
            return pageFailure(0, attemptCount, retriedWithTrailingSlash, "OMS REST request failed: ${e.message}")
        }

        Integer statusCode = normalizeInt(response.statusCode, 0)
        if (statusCode < 200 || statusCode >= 300) {
            return pageFailure(statusCode, attemptCount, retriedWithTrailingSlash, "OMS REST request failed with status ${statusCode}.")
        }

        Object parsed
        String body = response.body?.toString()
        if (!body) {
            parsed = [orders: []]
            warnings.add("OMS REST response body was empty.")
        } else {
            try {
                // Fresh parser per call: pages parse on fetch-pool worker threads under concurrency.
                parsed = new JsonSlurper().parseText(body)
            } catch (Exception e) {
                return pageFailure(statusCode, attemptCount, retriedWithTrailingSlash, "OMS REST response was not valid JSON: ${e.message}")
            }
        }

        return [
                success   : true,
                statusCode: statusCode,
                attemptCount: attemptCount,
                retriedWithTrailingSlash: retriedWithTrailingSlash,
                records   : extractOrderRecords(parsed, warnings),
        ]
    }

    private static Map<String, Object> pageFailure(int statusCode, int attemptCount, boolean retriedWithTrailingSlash, String error) {
        return [
                success                 : false,
                statusCode              : statusCode,
                attemptCount            : attemptCount,
                retriedWithTrailingSlash: retriedWithTrailingSlash,
                errors                  : [error],
        ]
    }

    protected static Map<String, Object> pageQueryParams(Map<String, Object> strategy, int pageIndex, int pageSize) {
        Map<String, Object> params = new LinkedHashMap<>()
        params[(String) strategy.sizeParam] = pageSize
        params[(String) strategy.indexParam] = pageIndex
        return params
    }

    protected static boolean shouldProbeSecondPage(int rawCount, int pageSize) {
        return rawCount >= pageSize || rawCount == OMS_DEFAULT_SERVER_PAGE_SIZE
    }

    protected static boolean isRecoverablePaginationFailure(Object statusCode) {
        int status = normalizeInt(statusCode, 0)
        return RECOVERABLE_PAGINATION_STATUS_CODES.contains(status)
    }

    protected static Map<String, Object> successfulPageResult(Map<String, Object> strategy, int pageSize,
                                                             List<Map<String, Object>> pageMetas, int rawFetchedCount) {
        return [
                errors    : [],
                statusCode: latestStatusCode(pageMetas),
                attemptCount: totalAttemptCount(pageMetas),
                retriedWithTrailingSlash: anyTrailingSlashRetry(pageMetas),
                pagination: paginationMetadata(strategy, pageSize, pageMetas, rawFetchedCount, false),
        ]
    }

    protected static Map<String, Object> failedPageResult(Map<String, Object> failedPage,
                                                         List<Map<String, Object>> pages = []) {
        List<Map<String, Object>> allPages = []
        allPages.addAll(pages ?: [])
        if (failedPage) allPages.add(failedPage)
        return [
                errors    : (failedPage?.errors ?: ["OMS REST request failed."]) as List,
                statusCode: failedPage?.statusCode ?: latestStatusCode(allPages),
                attemptCount: totalAttemptCount(allPages),
                retriedWithTrailingSlash: anyTrailingSlashRetry(allPages),
                pagination: [
                        pageSize    : null,
                        pageCount   : allPages.size(),
                        strategy    : null,
                        totalFetched: 0,
                ],
        ]
    }

    protected static Map<String, Object> paginationMetadata(Map<String, Object> strategy, int pageSize,
                                                           List<Map<String, Object>> pages, int recordCount,
                                                           boolean truncated) {
        return [
                pageSize    : pageSize,
                pageCount   : pages?.size() ?: 0,
                strategy    : strategy?.name,
                totalFetched: recordCount,
                truncated   : truncated,
        ]
    }

    protected static int totalAttemptCount(List<Map<String, Object>> pages) {
        return (pages ?: []).sum { Map page -> normalizeInt(page?.attemptCount, 0) } as int
    }

    protected static Integer latestStatusCode(List<Map<String, Object>> pages) {
        return ((pages ?: []).reverse().find { it?.statusCode != null }?.statusCode ?: 0) as Integer
    }

    protected static boolean anyTrailingSlashRetry(List<Map<String, Object>> pages) {
        return (pages ?: []).any { Map page -> page?.retriedWithTrailingSlash == true }
    }

    protected static int resolveOrdersPageSize(Map config) {
        return boundedInt(config?.ordersPageSize, DEFAULT_ORDERS_PAGE_SIZE, 1, 1000)
    }

    // Bounded 1..4: production hosts are resource-constrained, and each additional in-flight
    // page costs one serialized page of heap plus one transient parse on a worker thread.
    protected static int resolveOrdersFetchConcurrency(Map config) {
        return boundedInt(config?.ordersFetchConcurrency, DEFAULT_ORDERS_FETCH_CONCURRENCY, 1, 4)
    }

    protected static Map<String, String> buildHeaders(Map config) {
        Map<String, String> headers = new LinkedHashMap<>()
        headers.put("Accept", "application/json")
        headers.putAll(parseHeadersJson(config?.headersJson))

        String authType = normalize(config?.authType)?.toUpperCase() ?: "NONE"
        if (authType == "NONE") return headers
        if (authType == "BASIC") {
            String username = normalize(config?.username)
            String password = normalize(config?.password)
            if (!username || !password) throw new IllegalArgumentException("Username and password are required for BASIC auth.")
            String token = "${username}:${password}".getBytes(StandardCharsets.UTF_8).encodeBase64().toString()
            headers.put("Authorization", "Basic " + token)
            return headers
        }
        if (authType == "BEARER") {
            String token = normalize(config?.apiToken) ?: normalize(config?.password)
            if (!token) throw new IllegalArgumentException("API token is required for BEARER auth.")
            headers.put("Authorization", "Bearer " + token)
            return headers
        }
        if (authType == "API_KEY") {
            String token = normalize(config?.apiToken) ?: normalize(config?.password)
            if (!token) throw new IllegalArgumentException("API key is required for API_KEY auth.")
            headers.put(DEFAULT_API_KEY_HEADER_NAME, token)
            return headers
        }

        throw new IllegalArgumentException("Auth Type must be NONE, BASIC, BEARER, or API_KEY.")
    }

    // Audit M5.3 — block tenant-controlled headers that would override the auth handshake or smuggle
    // routing. Authorization/Cookie/Host/Proxy-* are the classic SSRF-companion + credential-replay
    // surface; X-Forwarded-* let a tenant claim an internal source IP. Header *values* with CR/LF are
    // rejected to defeat HTTP-header splitting on permissive proxies.
    private static final Set<String> BLOCKED_HEADER_NAMES = ([
            "authorization", "cookie", "host", "proxy-authorization", "proxy-authenticate",
            "x-forwarded-for", "x-forwarded-host", "x-forwarded-proto", "forwarded",
    ] as Set<String>).asImmutable()

    private static final int MAX_HEADER_VALUE_LENGTH = 4096
    private static final java.util.regex.Pattern HEADER_CONTROL_CHARS = java.util.regex.Pattern.compile("[\\x00-\\x1F\\x7F]")

    static void validateHeadersJson(Object rawHeadersJson) {
        parseHeadersJson(rawHeadersJson)
    }

    protected static Map<String, String> parseHeadersJson(Object rawHeadersJson) {
        String headersJson = normalize(rawHeadersJson)
        if (!headersJson) return [:]
        Object parsed
        try {
            parsed = JSON_SLURPER.parseText(headersJson)
        } catch (Exception e) {
            throw new IllegalArgumentException("Headers JSON is invalid: ${e.message}")
        }
        if (!(parsed instanceof Map)) throw new IllegalArgumentException("Headers JSON must be a JSON object.")
        Map<String, String> headers = new LinkedHashMap<>()
        ((Map) parsed).each { key, value ->
            String headerName = normalize(key)
            String headerValue = normalize(value)
            if (!headerName || !headerValue) return
            String lower = headerName.toLowerCase(Locale.ROOT)
            // Blocked header names (Authorization / Cookie / Host / Proxy-* / X-Forwarded-*) are
            // silently dropped — the auth handshake sets Authorization on its own and any value the
            // tenant supplies here would override that. We don't fail the save because legacy configs
            // may already carry these names; we just refuse to send them upstream.
            if (BLOCKED_HEADER_NAMES.contains(lower)) return
            if (headerValue.length() > MAX_HEADER_VALUE_LENGTH) {
                throw new IllegalArgumentException("Header '${headerName}' value exceeds ${MAX_HEADER_VALUE_LENGTH} chars.")
            }
            if (HEADER_CONTROL_CHARS.matcher(headerValue).find()) {
                throw new IllegalArgumentException("Header '${headerName}' value contains control characters (potential header smuggling).")
            }
            headers.put(headerName, headerValue)
        }
        return headers
    }

    protected static List<String> safeHeaderNames(Map<String, String> headers) {
        return (headers ?: [:]).keySet()
                .findAll { String headerName -> !BLOCKED_HEADER_NAMES.contains(headerName.toLowerCase(Locale.ROOT)) }
                .sort()
    }

    protected static List extractOrderRecords(Object parsed, List<String> warnings) {
        if (parsed == null) return []
        if (parsed instanceof List) return (List) parsed
        if (parsed instanceof Map) {
            String key = ORDER_LIST_KEYS.find { String candidate -> ((Map) parsed).get(candidate) instanceof List }
            if (key) return ((Map) parsed).get(key) as List
            warnings.add("OMS REST response JSON object did not contain an order list.")
            return []
        }

        warnings.add("OMS REST response JSON root was not an object or array.")
        return []
    }

    protected static Map<String, Object> filterComparableOrderRecords(Collection records,
                                                                      List<Map<String, Object>> excludeRules = null,
                                                                      Map<String, Object> extractOptions = null) {
        Map<String, Object> options = normalizeExtractOptions(extractOptions)
        String orderTypeId = (String) options.orderTypeId
        boolean applyExchangeExclusion = options.applyExchangeExclusion as boolean

        List filteredRecords = []
        List excludedExchangeOrders = []
        int excludedNonSalesOrderCount = 0
        int excludedExchangeOrderCount = 0
        Map<Integer, Integer> excludedByRuleCounts = [:]
        (records ?: []).each { Object record ->
            // Order is load-bearing: the two built-in exclusions keep priority so their counts never
            // shift when a tenant adds a configured rule, and a record excluded by more than one
            // reason is attributed to exactly one bucket.
            if (!isRequestedOrderType(record, orderTypeId)) {
                excludedNonSalesOrderCount++
            } else if (applyExchangeExclusion && containsExchangeOrderAssociation(record)) {
                excludedExchangeOrderCount++
                excludedExchangeOrders.add(exchangeManifestEntry((Map) record))
            } else {
                Map<String, Object> matchedRule = SourceFilterSupport.firstMatchingRule(record, excludeRules)
                if (matchedRule != null) {
                    Integer sequenceNum = (Integer) matchedRule.get("sequenceNum")
                    excludedByRuleCounts.put(sequenceNum, (excludedByRuleCounts.get(sequenceNum) ?: 0) + 1)
                } else {
                    filteredRecords.add(record)
                }
            }
        }
        return [
                records                    : filteredRecords,
                excludedNonSalesOrderCount : excludedNonSalesOrderCount,
                excludedExchangeOrderCount : excludedExchangeOrderCount,
                excludedExchangeOrders     : excludedExchangeOrders,
                excludedByRuleCounts       : excludedByRuleCounts,
        ]
    }

    /** Request-metadata filter block. Existing keys keep their names and meanings exactly. */
    protected static Map<String, Object> buildFilterMetadata(int excludedNonSalesOrderCount,
                                                             int excludedExchangeOrderCount,
                                                             boolean exchangeManifestTruncated,
                                                             List<Map<String, Object>> excludeRules,
                                                             Map<Integer, Integer> excludedByRuleCounts,
                                                             Map<String, Object> stateExtract = null) {
        Map<String, Object> filters = [
                requiredOrderTypeId          : SALES_ORDER_TYPE_ID,
                excludedNonSalesOrderCount   : excludedNonSalesOrderCount,
                excludedOrderItemAssocTypeIds: [EXCHANGE_ORDER_ASSOC_TYPE_ID],
                excludedExchangeOrderCount   : excludedExchangeOrderCount,
                exchangeManifestTruncated    : exchangeManifestTruncated,
        ]
        if (excludeRules) {
            // Every configured rule is reported, including ones that matched nothing: a rule that
            // vanished from metadata would read as "not applied".
            filters.configuredExclusions = excludeRules.collect { Map<String, Object> rule ->
                [
                        sequenceNum    : rule.get("sequenceNum"),
                        fieldExpression: rule.get("fieldExpression"),
                        operator       : rule.get("operator"),
                        values         : new ArrayList<String>((List) rule.get("values")),
                        excludedCount  : (excludedByRuleCounts?.get(rule.get("sequenceNum")) ?: 0),
                ]
            }
        }
        // Absent rather than empty for a windowed extract, matching configuredExclusions above: a
        // block that appeared for every extract would read as "this always applies".
        if (stateExtract != null) filters.stateExtract = stateExtract
        return filters
    }

    /** Identity summary of an excluded exchange order — ids and amounts only, no customer fields. */
    protected static Map<String, Object> exchangeManifestEntry(Map record) {
        return [
                omsOrderId: normalize(record.get('orderId')),
                externalId: normalize(record.get('externalId')),
                orderName : normalize(record.get('orderName')),
                toOrderId : firstExchangeAssocToOrderId(record),
                grandTotal: record.get('grandTotal'),
                orderDate : record.get('orderDate'),
                statusId  : normalize(record.get('statusId')),
        ]
    }

    /** toOrderId of the first EXCHANGE assoc anywhere in the document (same scan shape as the exclusion filter). */
    protected static String firstExchangeAssocToOrderId(Object value) {
        if (value instanceof Map) {
            Map record = (Map) value
            Object assocTypeId = record.find { key, ignored -> normalize(key) == ORDER_ITEM_ASSOC_TYPE_ID_FIELD }?.value
            if (normalize(assocTypeId)?.equalsIgnoreCase(EXCHANGE_ORDER_ASSOC_TYPE_ID)) return normalize(record.get('toOrderId'))
            for (Object child : record.values()) {
                String hit = firstExchangeAssocToOrderId(child)
                if (hit) return hit
            }
            return null
        }
        if (value instanceof Collection) {
            for (Object child : (Collection) value) {
                String hit = firstExchangeAssocToOrderId(child)
                if (hit) return hit
            }
        }
        return null
    }

    static String exchangeManifestFileName(Object orderFileName) {
        String name = normalize(orderFileName) ?: "oms-orders.json"
        return name.replaceAll(/(?i)\.json$/, "") + ".exchange-manifest.json"
    }

    protected static String encodeQueryComponent(Object value) {
        return URLEncoder.encode(value == null ? "" : value.toString(), StandardCharsets.UTF_8.name())
    }

    protected static Map toPlainMap(def record) {
        if (record == null) return [:]
        if (record instanceof Map) return new LinkedHashMap(record as Map)
        Map copy = [:]
        CONFIG_FIELD_NAMES.each { String fieldName ->
            try {
                copy[fieldName] = record.get(fieldName)
            } catch (Exception ignored) {
                try {
                    copy[fieldName] = record."${fieldName}"
                } catch (Exception ignoredAgain) {
                }
            }
        }
        return copy
    }

    /**
     * One place that turns caller-supplied extract options into a complete, defaulted Map. Absent
     * options must reproduce sales-order behaviour exactly, so every default here is the value the
     * pre-parameterization code hardcoded.
     *
     * filterOrderTypeServerSide is deliberately keyed on whether the caller ASKED for an order type,
     * not on the effective value: the shipped sales-order path never sent orderTypeId as a query
     * parameter and must keep not sending it, so its request URLs stay byte-identical.
     */
    static Map<String, Object> normalizeExtractOptions(Map rawOptions) {
        Map raw = rawOptions ?: [:]
        String requestedOrderTypeId = normalize(raw.get("orderTypeId"))
        String orderTypeId = requestedOrderTypeId ?: SALES_ORDER_TYPE_ID
        String windowFieldName = normalize(raw.get("windowFieldName")) ?: DEFAULT_WINDOW_FIELD_NAME

        Object rawStatusIds = raw.get("orderStatusIds")
        Collection statusSource = rawStatusIds instanceof Collection ? (Collection) rawStatusIds
                : (normalize(rawStatusIds) ? normalize(rawStatusIds).tokenize(",") : [])
        List<String> orderStatusIds = statusSource
                .collect { Object value -> normalize(value) }
                .findAll { String value -> value } as List<String>

        // The EXCHANGE order-item-association scan exists to keep exchange sales orders out of the
        // sales-order comparison. It is meaningless on any other order type, where it would only cost
        // a full-document walk per record.
        boolean applyExchangeExclusion = raw.containsKey("applyExchangeExclusion")
                ? normalizeBool(raw.get("applyExchangeExclusion"))
                : SALES_ORDER_TYPE_ID.equalsIgnoreCase(orderTypeId)

        return [
                orderTypeId              : orderTypeId,
                windowFieldName          : windowFieldName,
                orderStatusIds           : orderStatusIds,
                applyExchangeExclusion   : applyExchangeExclusion,
                // Explicit null check, not Elvis: normalizeInt(0) returns 0, which Groovy's ?: treats as
                // falsy, so `maxRecords: 0` would silently become the 50000 default — the same
                // looks-like-success-but-is-wrong failure this ceiling exists to prevent.
                maxRecords               : normalizeInt(raw.get("maxRecords")) != null
                        ? normalizeInt(raw.get("maxRecords"))
                        : DEFAULT_STATE_EXTRACT_MAX_RECORDS,
                // Truthiness, not a null check: normalize() returns "" (not null) for a blank or
                // whitespace-only value, and the Elvis on orderTypeId above already treats "" as
                // unset. A strict != null here would flag a blank orderTypeId for server-side
                // filtering while the effective type silently defaulted to SALES_ORDER.
                // `!!` and not `as Boolean`: the reference-type cast passes null through, so when no
                // orderTypeId key is supplied at all this would store a literal null instead of the
                // false the contract promises.
                filterOrderTypeServerSide: !!requestedOrderTypeId,
        ]
    }

    protected static boolean isRequestedOrderType(Object record, String orderTypeId) {
        if (!(record instanceof Map)) return false
        Object recordOrderTypeId = ((Map) record).find { key, ignored ->
            normalize(key) == ORDER_TYPE_ID_FIELD
        }?.value
        return normalize(recordOrderTypeId)?.equalsIgnoreCase(orderTypeId)
    }

    protected static boolean isSalesOrder(Object record) {
        return isRequestedOrderType(record, SALES_ORDER_TYPE_ID)
    }

    protected static boolean containsExchangeOrderAssociation(Object value) {
        if (value instanceof Map) {
            Map record = (Map) value
            Object assocTypeId = record.find { key, ignored ->
                normalize(key) == ORDER_ITEM_ASSOC_TYPE_ID_FIELD
            }?.value
            if (normalize(assocTypeId)?.equalsIgnoreCase(EXCHANGE_ORDER_ASSOC_TYPE_ID)) return true
            return record.values().any { Object child -> containsExchangeOrderAssociation(child) }
        }
        if (value instanceof Collection) {
            return ((Collection) value).any { Object child -> containsExchangeOrderAssociation(child) }
        }
        return false
    }

    protected static Map<String, Object> executeHttpRequest(Map request) {
        // Audit 2026-06-11 #15: re-validate the resolved endpoint at request time, not only at
        // config-save time. A baseUrl mutated out-of-band (direct DB write, data import, or a row
        // created before the save guard shipped) would otherwise reach the HTTP client unchecked and
        // could target loopback / link-local / RFC1918 / cloud-metadata addresses (SSRF). No host
        // allow-list — customers self-host OMS on arbitrary domains, matching the save-path decision.
        // Runs only on the real network path; tests inject their own client via setHttpClient.
        def __urlCheck = darpan.facade.common.OutboundHttpPolicy.validate(request.url as String)
        if (!__urlCheck.ok) throw new IllegalStateException("OMS endpoint URL blocked by outbound policy: ${__urlCheck.error}")
        HttpURLConnection connection = (HttpURLConnection) new URL(request.url as String).openConnection()
        connection.requestMethod = request.method as String
        connection.connectTimeout = normalizeInt(request.connectTimeoutSeconds, 30) * 1000
        connection.readTimeout = normalizeInt(request.readTimeoutSeconds, 60) * 1000
        ((Map<String, String>) (request.headers ?: [:])).each { String name, String value ->
            connection.setRequestProperty(name, value)
        }
        // Set after tenant headers so it cannot be overridden: we only know how to decode gzip.
        // JSON page bodies compress ~8-10x, which multiplies effective throughput on the
        // window-limited long-haul connections OMS extracts run over. Callers may disable it
        // (acceptGzip=false) when retrying a page whose compressed response starved the read
        // timeout behind a buffering proxy.
        if (request.acceptGzip != false) connection.setRequestProperty("Accept-Encoding", "gzip")

        int statusCode = connection.responseCode
        InputStream stream = statusCode >= 400 ? connection.errorStream : connection.inputStream
        String body = readResponseBody(stream, connection.getContentEncoding())
        return [statusCode: statusCode, body: body, headers: connection.headerFields]
    }

    protected static String readResponseBody(InputStream stream, String contentEncoding) {
        if (stream == null) return ""
        InputStream decoded = "gzip".equalsIgnoreCase(normalize(contentEncoding) ?: "") ? new GZIPInputStream(stream) : stream
        return decoded.getText(StandardCharsets.UTF_8.name())
    }

    protected static String sanitizeBaseUrl(Object rawBaseUrl) {
        String value = normalize(rawBaseUrl)
        if (!value) return value
        try {
            URI uri = new URI(value)
            if (uri.userInfo) {
                URI clean = new URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null)
                return clean.toString().replaceAll(/\/+$/, "")
            }
        } catch (Exception ignored) {
        }
        return value.replaceAll(/\/+$/, "")
    }
}
