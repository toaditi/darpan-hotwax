package darpan.hotwax.oms

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
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

    // --- extractReturnsToFile cleanup contract ---------------------------
    //
    // Mirrors OmsRestSourceSupport.extractOrdersToFile / OrdersDocumentSink: a mid-window failure
    // must never leave a well-formed-looking but silently partial JSON file on disk, and the file
    // mode's result Map must never carry a `records` key, success or failure.

    @Test
    void extractReturnsToFileLeavesNoFileOnMidPaginationHttpFailure() {
        File target = File.createTempFile("oms-returns-http-failure-", ".json")
        target.delete() // createTempFile pre-creates an empty file; start from a clean, absent path
        try {
            int callCount = 0
            OmsReturnsSourceSupport.setHttpClient { Map request ->
                callCount++
                // Page 1 succeeds and writes a real record to the file — this proves abort() is
                // deleting an actually-partial file, not just skipping a file that was never opened.
                if (callCount == 1) return [statusCode: 200, body: returnsBody([returnRecord("1001", "5001")], true)]
                return [statusCode: 500, body: "internal error"]
            }

            Map result = OmsReturnsSourceSupport.extractReturnsToFile(baseConfig(), "2026-05-01T00:00:00Z",
                    "2026-05-02T00:00:00Z", target, null, null, null, [:])

            assertFalse((result.errors as List).isEmpty(), "a mid-pagination HTTP failure must be reported as an error")
            assertFalse(target.exists(),
                    "a mid-pagination HTTP failure must leave no file on disk, not a silently partial one: ${target}")
        } finally {
            target.delete()
        }
    }

    @Test
    void extractReturnsToFileLeavesNoFileOnMidPaginationJsonParseFailure() {
        File target = File.createTempFile("oms-returns-json-failure-", ".json")
        target.delete()
        try {
            int callCount = 0
            OmsReturnsSourceSupport.setHttpClient { Map request ->
                callCount++
                if (callCount == 1) return [statusCode: 200, body: returnsBody([returnRecord("1001", "5001")], true)]
                return [statusCode: 200, body: "not valid json"]
            }

            Map result = OmsReturnsSourceSupport.extractReturnsToFile(baseConfig(), "2026-05-01T00:00:00Z",
                    "2026-05-02T00:00:00Z", target, null, null, null, [:])

            assertFalse((result.errors as List).isEmpty(), "a mid-pagination JSON-parse failure must be reported as an error")
            assertFalse(target.exists(),
                    "a mid-pagination JSON-parse failure must leave no file on disk, not a silently partial one: ${target}")
        } finally {
            target.delete()
        }
    }

    @Test
    void extractReturnsToFileFailureResultCarriesNoRecordsKey() {
        OmsReturnsSourceSupport.setHttpClient { Map request ->
            return [statusCode: 500, body: "internal error"]
        }
        File target = File.createTempFile("oms-returns-no-records-key-", ".json")
        target.delete()
        try {
            Map result = OmsReturnsSourceSupport.extractReturnsToFile(baseConfig(), "2026-05-01T00:00:00Z",
                    "2026-05-02T00:00:00Z", target, null, null, null, [:])

            assertFalse((result.errors as List).isEmpty())
            assertFalse(result.containsKey("records"),
                    "extractReturnsToFile's contract is 'same Map minus records' on every path, including failure: ${result.keySet()}")
        } finally {
            target.delete()
        }
    }

    @Test
    void extractReturnsToFileWritesAValidDocumentOnSuccessAndOmitsRecordsFromTheResult() {
        OmsReturnsSourceSupport.setHttpClient { Map request ->
            return [statusCode: 200, body: returnsBody([returnRecord("1001", "5001")], false)]
        }
        File target = File.createTempFile("oms-returns-success-", ".json")
        target.delete()
        try {
            Map result = OmsReturnsSourceSupport.extractReturnsToFile(baseConfig(), "2026-05-01T00:00:00Z",
                    "2026-05-02T00:00:00Z", target, null, null, null, [:])

            assertTrue((result.errors as List).isEmpty(), result.errors.toString())
            assertFalse(result.containsKey("records"),
                    "extractReturnsToFile's contract is 'same Map minus records', even on success: ${result.keySet()}")
            assertTrue(target.exists(), "a successful extraction must write the file")

            Map written = (Map) new JsonSlurper().parse(target)
            List writtenRecords = (List) written.get("records")
            assertEquals(1, writtenRecords.size())
            assertNotNull(((Map) writtenRecords[0]).get("items"), "items[] must survive the file-mode write too")
        } finally {
            target.delete()
        }
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

    // --- configured channel exclusion + count hygiene (DAR-BE-018 Task 3, design §5, §9.5) -------

    @Test
    void excludesReturnsMatchingTheConfiguredChannelRule() {
        OmsReturnsSourceSupport.setHttpClient { Map request ->
            return [statusCode: 200, body: returnsBody([
                    returnRecord("1001", "5001"),
                    posReturnRecord("1002", "5002"),
            ], false)]
        }

        Map result = OmsReturnsSourceSupport.extractReturns(baseConfig(), "2026-05-01T00:00:00Z",
                "2026-05-02T00:00:00Z", null, null, channelExclusion(), [:])

        assertEquals(1, result.recordCount, "the POS-channel return must be dropped")
        Map filters = (Map) ((Map) result.requestMetadata).get("filters")
        List configured = (List) filters.get("configuredExclusions")
        assertEquals(1, ((Map) configured[0]).get("excludedCount"))
    }

    @Test
    void keepsReturnsThatLackTheConfiguredFieldEntirely() {
        OmsReturnsSourceSupport.setHttpClient { Map request ->
            Map noChannel = returnRecord("1001", "5001")
            noChannel.remove("returnChannelEnumId")
            return [statusCode: 200, body: returnsBody([noChannel], false)]
        }

        Map result = OmsReturnsSourceSupport.extractReturns(baseConfig(), "2026-05-01T00:00:00Z",
                "2026-05-02T00:00:00Z", null, null, channelExclusion(), [:])

        assertEquals(1, result.recordCount,
                "a record lacking the configured field is KEPT — a rule removes only on a matching value")
    }

    @Test
    void reportsAConfiguredRuleThatMatchedNothingWithZeroRatherThanOmittingIt() {
        OmsReturnsSourceSupport.setHttpClient { Map request ->
            return [statusCode: 200, body: returnsBody([returnRecord("1001", "5001")], false)]
        }

        Map result = OmsReturnsSourceSupport.extractReturns(baseConfig(), "2026-05-01T00:00:00Z",
                "2026-05-02T00:00:00Z", null, null, channelExclusion(), [:])

        List configured = (List) ((Map) ((Map) result.requestMetadata).get("filters")).get("configuredExclusions")
        assertEquals(1, configured.size(), "a rule that matched nothing must still be reported")
        assertEquals(0, ((Map) configured[0]).get("excludedCount"))
    }

    @Test
    void omitsConfiguredExclusionsEntirelyWhenNoRulesAreConfigured() {
        OmsReturnsSourceSupport.setHttpClient { Map request ->
            return [statusCode: 200, body: returnsBody([returnRecord("1001", "5001")], false)]
        }

        Map result = OmsReturnsSourceSupport.extractReturns(baseConfig(), "2026-05-01T00:00:00Z",
                "2026-05-02T00:00:00Z", null, null, null, [:])

        Map filters = (Map) ((Map) result.requestMetadata).get("filters")
        assertFalse(filters.containsKey("configuredExclusions"),
                "absent entirely, not an empty list — backward-compatible metadata")
    }

    @Test
    void serverExcludedReturnsAreNeverCountedByAConfiguredRule() {
        // The server already dropped 4 no-Shopify-ref returns; they never reach the client filter,
        // so the two counts describe disjoint populations and cannot double-count (design §9.5).
        OmsReturnsSourceSupport.setHttpClient { Map request ->
            return [statusCode: 200, body: returnsBody([posReturnRecord("1002", "5002")], false,
                    [excludedNoShopifyRefCount: 4])]
        }

        Map result = OmsReturnsSourceSupport.extractReturns(baseConfig(), "2026-05-01T00:00:00Z",
                "2026-05-02T00:00:00Z", null, null, channelExclusion(), [:])

        Map filters = (Map) ((Map) result.requestMetadata).get("filters")
        assertEquals(4, filters.get("excludedNoShopifyRefCount"))
        List configured = (List) filters.get("configuredExclusions")
        assertEquals(1, ((Map) configured[0]).get("excludedCount"),
                "the configured rule counts only what actually reached the client")
    }

    private static Map posReturnRecord(String returnId, String externalId) {
        Map record = returnRecord(returnId, externalId)
        record.put("returnChannelEnumId", "POS_RETURN_CHANNEL")
        return record
    }

    private static List channelExclusion() {
        return [[
                sequenceNum    : 1,
                fieldExpression: "returnChannelEnumId",
                operator       : "EXCLUDE_IN",
                filterValues   : "POS_RETURN_CHANNEL",
        ]]
    }
}
