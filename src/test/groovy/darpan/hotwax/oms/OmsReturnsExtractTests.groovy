package darpan.hotwax.oms

import groovy.json.JsonOutput
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * Extractor behaviour for OMS `/rest/s1/oms/reconciliationReturns` (DAR-BE-018, design §7).
 *
 * CONTRACT CAVEAT: as of 2026-08-12 no swagger, fixture, or captured live response for this
 * endpoint exists in the repo. The shapes below are transcribed from the 2026-08-11 handoff and
 * are NOT verified against live gorjana. Treat a mismatch found during live probing as a defect
 * in these fixtures, not necessarily in the extractor.
 */
class OmsReturnsExtractTests {

    @AfterEach
    void resetClient() {
        OmsReturnsSourceSupport.resetHttpClient()
    }

    @Test
    void callsTheReturnsPathNotTheOrdersPath() {
        String capturedUrl = null
        OmsReturnsSourceSupport.setHttpClient { Map request ->
            capturedUrl = request.url as String
            return [statusCode: 200, body: returnsBody([], false)]
        }

        OmsReturnsSourceSupport.extractReturns(baseConfig(), "2026-05-01T00:00:00Z",
                "2026-05-02T00:00:00Z", null, null, null, [:])

        assertNotNull(capturedUrl)
        assertTrue(capturedUrl.contains("/rest/s1/oms/reconciliationReturns"),
                "returns extractor must pin the reconciliationReturns path: ${capturedUrl}")
        assertFalse(capturedUrl.contains("/rest/s1/oms/orders"),
                "returns extractor leaked an orders path: ${capturedUrl}")
    }

    @Test
    void sendsHalfOpenReturnDateWindowParams() {
        String capturedUrl = null
        OmsReturnsSourceSupport.setHttpClient { Map request ->
            capturedUrl = request.url as String
            return [statusCode: 200, body: returnsBody([], false)]
        }

        OmsReturnsSourceSupport.extractReturns(baseConfig(), "2026-05-01T00:00:00Z",
                "2026-05-02T00:00:00Z", null, null, null, [:])

        assertTrue(capturedUrl.contains("returnDateFrom="), "missing returnDateFrom: ${capturedUrl}")
        assertTrue(capturedUrl.contains("returnDateThru="), "missing returnDateThru: ${capturedUrl}")
    }

    @Test
    void terminatesOnHasMoreFalseRatherThanPageShrinkHeuristics() {
        int callCount = 0
        OmsReturnsSourceSupport.setHttpClient { Map request ->
            callCount++
            if (callCount == 1) return [statusCode: 200, body: returnsBody([returnRecord("1001", "5001")], true)]
            return [statusCode: 200, body: returnsBody([returnRecord("1002", "5002")], false)]
        }

        Map result = OmsReturnsSourceSupport.extractReturns(baseConfig(), "2026-05-01T00:00:00Z",
                "2026-05-02T00:00:00Z", null, null, null, [:])

        assertEquals(2, callCount, "must stop as soon as hasMore is false")
        assertEquals(2, result.recordCount)
    }

    @Test
    void preservesNestedItemsOnEachReturnRecord() {
        OmsReturnsSourceSupport.setHttpClient { Map request ->
            return [statusCode: 200, body: returnsBody([returnRecord("1001", "5001")], false)]
        }

        Map result = OmsReturnsSourceSupport.extractReturns(baseConfig(), "2026-05-01T00:00:00Z",
                "2026-05-02T00:00:00Z", null, null, null, [:])

        List records = (List) result.records
        Map first = (Map) records[0]
        List items = (List) first.get("items")
        assertNotNull(items, "items[] must survive to the record — RQ-21 nested contract")
        assertEquals(1, items.size())
        assertEquals("SKU-1", ((Map) items[0]).get("sku"))
    }

    @Test
    void mapsServerCountsIntoRequestMetadataFilters() {
        OmsReturnsSourceSupport.setHttpClient { Map request ->
            return [statusCode: 200, body: returnsBody([returnRecord("1001", "5001")], false,
                    [returnsCount: 7, excludedNoShopifyRefCount: 3])]
        }

        Map result = OmsReturnsSourceSupport.extractReturns(baseConfig(), "2026-05-01T00:00:00Z",
                "2026-05-02T00:00:00Z", null, null, null, [:])

        Map filters = (Map) ((Map) result.requestMetadata).get("filters")
        assertEquals(3, filters.get("excludedNoShopifyRefCount"))
        assertEquals(7, filters.get("serverReportedReturnsCount"))
    }

    // --- fixtures -------------------------------------------------------

    private static Map baseConfig() {
        return [
                omsRestSourceConfigId: "TEST_CFG",
                baseUrl              : "https://oms.example.com",
                apiKey               : "test-key",
                isActive             : "Y",
        ]
    }

    private static Map returnRecord(String returnId, String externalId) {
        return [
                returnId            : returnId,
                externalId          : externalId,
                orderExternalId     : "7025799037059",
                statusId            : "RETURN_COMPLETED",
                entryDate           : "2026-05-01T10:00:00Z",
                returnTotal         : "42.00",
                currencyUomId       : "USD",
                returnChannelEnumId : "SHOPIFY_RETURN_CHANNEL",
                items               : [[
                        returnItemSeqId    : "00001",
                        orderItemExternalId: "oi-1",
                        productId          : "P1",
                        sku                : "SKU-1",
                        returnQuantity     : 1,
                        receivedQuantity   : 1,
                        returnPrice        : "42.00",
                        lineAmount         : "42.00",
                        returnReasonId     : "DAMAGED",
                        returnItemStatusId : "RETURN_ITEM_COMPLETED",
                ]],
        ]
    }

    private static String returnsBody(List returns, boolean hasMore, Map extras = [:]) {
        Map body = [
                returns                 : returns,
                returnsCount            : returns.size(),
                hasMore                 : hasMore,
                pageIndex               : 0,
                pageSize                : 500,
                excludedNoShopifyRefCount: 0,
        ]
        body.putAll(extras)
        return JsonOutput.toJson(body)
    }
}
