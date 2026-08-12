import darpan.facade.common.DataManagerSupport
import darpan.facade.reconciliation.RunObservability
import darpan.hotwax.oms.OmsRestSourceSupport
import darpan.hotwax.oms.OmsReturnsSourceSupport

// Service edge for /rest/s1/oms/reconciliationReturns (DAR-BE-018).
//
// Mirrors extractOmsReconciliationOrders.groovy step for step — same tenant gate, same output
// location, same atomic .partial move, same progress heartbeat — and differs only in calling the
// returns support class and writing no exchange-manifest sidecar (returns carry no exchange
// association). Everything returns-specific lives in OmsReturnsSourceSupport, not here.

String configIdValue = omsRestSourceConfigId?.toString()?.trim()
String companyUserGroupIdValue = companyUserGroupId?.toString()?.trim()
if (!configIdValue) {
    errors = ["OMS REST Source Config ID is required."]
    warnings = []
    dataAvailable = false
    recordCount = 0
    return
}

def sourceConfig = ec.entity.find("darpan.hotwax.HotWaxOmsRestSourceConfig")
        .condition("omsRestSourceConfigId", configIdValue)
        .disableAuthz()
        .useCache(false)
        .one()

OmsRestSourceSupport.requireUsableOmsConfig(ec, sourceConfig, configIdValue, companyUserGroupIdValue)

if (sourceConfig && (sourceConfig.isActive ?: "Y").toString().equalsIgnoreCase("N")) {
    ec.message.addError("OMS REST source config ${configIdValue} is inactive.")
}

if (ec.message.hasError()) {
    errors = (ec.message?.getErrors() ?: []) as List
    warnings = []
    dataAvailable = false
    recordCount = 0
    return
}

String timestamp = DataManagerSupport.formatRunTimestamp(ec)
String outputBaseLocation = outputLocation ?: DataManagerSupport.resolveReconciliationRunLocation(
        ec,
        automationExecutionId ?: configIdValue,
        timestamp
)

File outputDirectory = DataManagerSupport.resolveDirectoryFile(ec, outputBaseLocation, true)
File workFile = outputDirectory != null
        ? File.createTempFile("oms-returns-extract-", ".partial", outputDirectory)
        : File.createTempFile("oms-returns-extract-", ".partial")

final long PROGRESS_MIN_INTERVAL_MS = 2000L
Closure pageProgressListener = null
String progressRunId = reconciliationRunResultId?.toString()?.trim()
String progressStage = progressStageCode?.toString()?.trim() ?: RunObservability.STAGE_EXTRACT_FILE2
Integer progressExpectedTotal = null
try {
    progressExpectedTotal = expectedRecordCount != null ? (expectedRecordCount as Integer) : null
} catch (Exception ignored) {
}
if (progressRunId) {
    Integer expectedTotal = progressExpectedTotal != null && progressExpectedTotal > 0 ? progressExpectedTotal : null
    long lastReportedAtMs = 0L
    pageProgressListener = { Object cumulativeRawCount ->
        long nowMs = System.currentTimeMillis()
        if (nowMs - lastReportedAtMs < PROGRESS_MIN_INTERVAL_MS) return
        lastReportedAtMs = nowMs
        RunObservability.heartbeatStageProgress(ec, progressRunId, progressStage, cumulativeRawCount, expectedTotal)
    }
}

try {
    List keepRecordFieldsValue = (keepRecordFields instanceof List) ? (List) keepRecordFields : null
    List sourceFiltersValue = (sourceFilters instanceof List) ? (List) sourceFilters : null
    Map extraction = OmsReturnsSourceSupport.extractReturnsToFile(sourceConfig, windowStart, windowEnd,
            workFile, keepRecordFieldsValue, pageProgressListener, sourceFiltersValue, [:])
    warnings = extraction.warnings ?: []
    errors = extraction.errors ?: []
    requestMetadata = extraction.requestMetadata ?: [:]
    recordCount = extraction.recordCount ?: 0
    dataAvailable = extraction.dataAvailable == true

    if (errors) {
        fileLocation = null
        fileName = null
        return
    }

    String outputFileName = OmsReturnsSourceSupport.safeReturnsFileName(fileName ?: extraction.fileName)
    fileName = outputFileName
    fileLocation = DataManagerSupport.childLocation(outputBaseLocation, outputFileName)
    DataManagerSupport.moveIntoLocation(ec, workFile, fileLocation as String)
} finally {
    if (workFile.exists()) workFile.delete()
}
