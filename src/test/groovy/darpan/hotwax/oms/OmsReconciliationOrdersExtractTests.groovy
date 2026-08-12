package darpan.hotwax.oms

import groovy.json.JsonOutput
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * Extractor behaviour for the OMS `/rest/s1/oms/reconciliationOrders` endpoint (DAR-BE-018).
 *
 * The recon endpoint differs from the legacy `/rest/s1/oms/orders` path on five points, and each
 * one is pinned here: its own URL path, half-open `orderDateFrom`/`orderDateThru` window params,
 * `hasMore`-driven termination instead of the shrinking-page heuristic, server-side sales-order
 * filtering (so the client-side `orderTypeId` filter must not run — the projection does not carry
 * that field), and exchange orders delivered inline behind an `isExchange` flag instead of being
 * excluded into a sidecar by the client.
 *
 * CONTRACT CAVEAT: as of 2026-08-11 no swagger, fixture, or captured response for this endpoint
 * exists in the repo. The shapes below are transcribed from the DAR-BE-018 handoff and are NOT
 * verified against the live gorjana endpoint. Treat a fixture mismatch found during live probing
 * as a defect in these tests, not necessarily in the extractor.
 */
class OmsReconciliationOrdersExtractTests {

    @AfterEach
    void resetClient() {
        OmsRestSourceSupport.resetHttpClient()
    }

    @Test
    void reconModeCallsTheReconciliationPathEvenWhenTheConfigCarriesTheLegacyOrdersPath() {
        String capturedUrl = null
        OmsRestSourceSupport.setHttpClient { Map request ->
            capturedUrl = request.url as String
            return [statusCode: 200, body: reconBody([], false)]
        }

        OmsRestSourceSupport.extractOrders(baseConfig(), "2026-05-01T00:00:00Z", "2026-05-01T01:00:00Z",
                null, null, null, [reconEndpoint: true])

        assertNotNull(capturedUrl)
        assertTrue(capturedUrl.contains("/rest/s1/oms/reconciliationOrders"),
                "recon mode must not use the config's legacy ordersPath: ${capturedUrl}")
        assertFalse(capturedUrl.contains("/rest/s1/oms/orders?"),
                "recon mode leaked the legacy orders path: ${capturedUrl}")
    }

    @Test
    void reconModeSendsHalfOpenWindowParamsUnderTheirNewNames() {
        String capturedUrl = null
        OmsRestSourceSupport.setHttpClient { Map request ->
            capturedUrl = request.url as String
            return [statusCode: 200, body: reconBody([], false)]
        }

        OmsRestSourceSupport.extractOrders(baseConfig(), "2026-05-01T00:00:00Z", "2026-05-01T01:00:00Z",
                null, null, null, [reconEndpoint: true])

        assertTrue(capturedUrl.contains("orderDateFrom=1777593600000"), capturedUrl)
        assertTrue(capturedUrl.contains("orderDateThru=1777597200000"), capturedUrl)
        assertFalse(capturedUrl.contains("orderDate_from"),
                "recon mode must not send the legacy underscore window params: ${capturedUrl}")
        assertFalse(capturedUrl.contains("orderDate_thru"), capturedUrl)
    }

    @Test
    void reconModePaginatesUntilHasMoreIsFalse() {
        List<String> requestedUrls = []
        OmsRestSourceSupport.setHttpClient { Map request ->
            requestedUrls.add(request.url as String)
            int pageIndex = queryInt(request.url as String, "pageIndex")
            List orders = pageIndex < 2 ? [order("O${pageIndex}A"), order("O${pageIndex}B")] : [order("O2A")]
            return [statusCode: 200, body: reconBody(orders, pageIndex < 2, 5)]
        }

        Map result = OmsRestSourceSupport.extractOrders(baseConfig(), "2026-05-01T00:00:00Z", "2026-05-01T01:00:00Z",
                null, null, null, [reconEndpoint: true])

        assertTrue((result.errors as List).isEmpty(), result.errors.toString())
        assertEquals(3, requestedUrls.size(), "expected exactly one request per page: ${requestedUrls}")
        assertEquals(5, result.recordCount)
    }

    @Test
    void reconModeDoesNotProbeASecondPageWhenTheFirstPageHoldsFiftyRecords() {
        List<String> requestedUrls = []
        OmsRestSourceSupport.setHttpClient { Map request ->
            requestedUrls.add(request.url as String)
            List orders = (1..50).collect { order("O${it}") }
            return [statusCode: 200, body: reconBody(orders, false, 50)]
        }

        Map result = OmsRestSourceSupport.extractOrders(baseConfig(), "2026-05-01T00:00:00Z", "2026-05-01T01:00:00Z",
                null, null, null, [reconEndpoint: true])

        assertEquals(1, requestedUrls.size(),
                "the 50-record truncation probe must not run against an endpoint that reports hasMore: ${requestedUrls}")
        assertEquals(50, result.recordCount)
    }

    /**
     * hasMore is the ONLY termination signal on this path — the shrinking-page and repeated-page
     * heuristics are deliberately gone. So a response that omits it must fail loudly rather than be
     * read as "no more pages": treating absence as false would stop after page 0 and report SUCCESS
     * on a silently partial window, which is indistinguishable from a window that genuinely held one
     * page. Matters more than usual here because the endpoint's contract is not yet verified live.
     */
    @Test
    void reconModeFailsRatherThanTruncateWhenTheResponseOmitsHasMore() {
        OmsRestSourceSupport.setHttpClient { Map ignored ->
            return [statusCode: 200, body: JsonOutput.toJson([
                    orders     : [order("O100"), order("O200")],
                    ordersCount: 2,
                    pageIndex  : 0,
                    pageSize   : 500,
            ])]
        }

        Map result = OmsRestSourceSupport.extractOrders(baseConfig(), "2026-05-01T00:00:00Z", "2026-05-01T01:00:00Z",
                null, null, null, [reconEndpoint: true])

        List errors = result.errors as List
        assertFalse(errors.isEmpty(), "a missing hasMore must fail the extract, not silently end the window")
        assertTrue(errors.any { it.toString().contains("hasMore") }, errors.toString())
        assertEquals(0, result.recordCount)
        assertFalse(result.dataAvailable as boolean)
    }

    @Test
    void reconModeKeepsServerFilteredRecordsThatCarryNoOrderTypeId() {
        OmsRestSourceSupport.setHttpClient { Map ignored ->
            return [statusCode: 200, body: reconBody([order("O100"), order("O200")], false)]
        }

        Map result = OmsRestSourceSupport.extractOrders(baseConfig(), "2026-05-01T00:00:00Z", "2026-05-01T01:00:00Z",
                null, null, null, [reconEndpoint: true])

        assertEquals(2, result.recordCount,
                "the server already applied the SALES_ORDER filter; re-running it client-side drops every projected record")
    }

    @Test
    void reconModeDropsInlineExchangeOrdersFromTheComparisonRecords() {
        OmsRestSourceSupport.setHttpClient { Map ignored ->
            return [statusCode: 200, body: reconBody([
                    order("O100"),
                    order("O200") + [isExchange: true, originalOrderId: "O900", originalExternalId: "E900"],
                    order("O300"),
            ], false, 3, [exchangeOrderCount: 1])]
        }

        Map result = OmsRestSourceSupport.extractOrders(baseConfig(), "2026-05-01T00:00:00Z", "2026-05-01T01:00:00Z",
                null, null, null, [reconEndpoint: true])

        assertEquals(2, result.recordCount)
        assertFalse(result.outputText.contains("O200"),
                "an inline exchange order must not reach the compare stage: ${result.outputText}")
        assertEquals(1, result.requestMetadata.filters.excludedExchangeOrderCount)
    }

    @Test
    void reconModeBuildsTheExchangeManifestFromInlineFieldsWithoutPairLookups() {
        OmsRestSourceSupport.setHttpClient { Map ignored ->
            return [statusCode: 200, body: reconBody([
                    order("O100"),
                    order("O200") + [isExchange: true, originalOrderId: "O900", originalExternalId: "E900"],
            ], false, 2, [exchangeOrderCount: 1])]
        }

        Map result = OmsRestSourceSupport.extractOrders(baseConfig(), "2026-05-01T00:00:00Z", "2026-05-01T01:00:00Z",
                null, null, null, [reconEndpoint: true])

        List manifest = result.exchangeManifest as List
        assertEquals(1, manifest.size(), "expected one manifest entry: ${manifest}")
        Map entry = manifest[0] as Map
        assertEquals("O200", entry.omsOrderId)
        assertEquals("EXT-O200", entry.externalId)
        assertEquals("O900", entry.toOrderId,
                "toOrderId must come from the inline originalOrderId, with no lookup round-trip")
        assertEquals("E900", entry.originalExternalId)
    }

    @Test
    void reconModeMapsServerSideExclusionCountsIntoFilterMetadata() {
        OmsRestSourceSupport.setHttpClient { Map ignored ->
            return [statusCode: 200, body: reconBody([order("O100")], false, 1, [
                    excludedNonSalesOrderCount: 7,
                    missingExternalIdCount    : 3,
            ])]
        }

        Map result = OmsRestSourceSupport.extractOrders(baseConfig(), "2026-05-01T00:00:00Z", "2026-05-01T01:00:00Z",
                null, null, null, [reconEndpoint: true])

        Map filters = result.requestMetadata.filters as Map
        assertEquals(7, filters.excludedNonSalesOrderCount,
                "the server's non-sales count must be surfaced, not the client's (always 0) count")
        assertEquals(3, filters.missingExternalIdCount,
                "orders the server dropped for an empty join key must stay visible in run metadata")
    }

    @Test
    void reconModeFailsTerminallyWhenTheServerRejectsThePageSize() {
        OmsRestSourceSupport.setHttpClient { Map ignored ->
            return [statusCode: 400, body: JsonOutput.toJson([
                    errorCode: "PAGE_SIZE_TOO_LARGE",
                    message  : "pageSize 500 exceeds the maximum of 200.",
            ])]
        }

        Map result = OmsRestSourceSupport.extractOrders(baseConfig(), "2026-05-01T00:00:00Z", "2026-05-01T01:00:00Z",
                null, null, null, [reconEndpoint: true])

        List errors = result.errors as List
        assertFalse(errors.isEmpty(), "an over-max pageSize must fail the extract, never be silently capped")
        assertTrue(errors.any { it.toString().contains("PAGE_SIZE_TOO_LARGE") }, errors.toString())
        assertTrue(errors.any { it.toString().contains("maximum of 200") },
                "the server's stated limit must reach the operator: ${errors}")
        assertTrue(errors.any { it.toString().contains("ordersPageSize") },
                "the error must name the Darpan-side setting to change, not just the server's limit: ${errors}")
        assertEquals(0, result.recordCount)
    }

    @Test
    void legacyModeKeepsItsPathAndWindowParametersUnchanged() {
        String capturedUrl = null
        OmsRestSourceSupport.setHttpClient { Map request ->
            capturedUrl = request.url as String
            return [statusCode: 200, body: JsonOutput.toJson([orders: [
                    [orderId: "O100", externalId: "EXT-O100", orderTypeId: "SALES_ORDER"],
            ]])]
        }

        Map result = OmsRestSourceSupport.extractOrders(baseConfig(), "2026-05-01T00:00:00Z", "2026-05-01T01:00:00Z")

        assertTrue(capturedUrl.contains("/rest/s1/oms/orders"), capturedUrl)
        assertTrue(capturedUrl.contains("orderDate_from=1777593600000"), capturedUrl)
        assertFalse(capturedUrl.contains("orderDateFrom="), capturedUrl)
        assertEquals(1, result.recordCount)
    }

    private static Map<String, Object> order(String orderId) {
        return [
                orderId   : orderId,
                externalId: "EXT-${orderId}".toString(),
                orderName : "#${orderId}".toString(),
                grandTotal: 42.50,
                orderDate : 1777593600000L,
                statusId  : "ORDER_APPROVED",
                isExchange: false,
        ]
    }

    private static String reconBody(List orders, boolean hasMore, Integer ordersCount = null,
                                    Map<String, Object> extraCounts = [:]) {
        return JsonOutput.toJson([
                orders                    : orders,
                ordersCount               : ordersCount != null ? ordersCount : orders.size(),
                hasMore                   : hasMore,
                pageIndex                 : 0,
                pageSize                  : 500,
                exchangeOrderCount        : 0,
                excludedNonSalesOrderCount: 0,
                missingExternalIdCount    : 0,
        ] + extraCounts)
    }

    private static Map<String, Object> baseConfig(Map<String, Object> overrides = [:]) {
        return [
                omsRestSourceConfigId: "KREWE_OMS",
                companyUserGroupId   : "KREWE",
                baseUrl              : "https://dev-maarg.hotwax.io",
                ordersPath           : "/rest/s1/oms/orders",
                authType             : "NONE",
                connectTimeoutSeconds: 5,
                readTimeoutSeconds   : 10,
                isActive             : "Y",
                canReadOrders        : "Y",
        ] + overrides
    }

    private static int queryInt(String url, String key) {
        String query = new URI(url).rawQuery ?: ""
        String value = query.split("&").collect { it.split("=", 2) }
                .find { it[0] == key }?.getAt(1)
        return Integer.parseInt(value)
    }
}
