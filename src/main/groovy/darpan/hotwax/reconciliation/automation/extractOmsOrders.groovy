import darpan.facade.common.DataManagerSupport
import darpan.facade.reconciliation.RunObservability
import darpan.hotwax.oms.OmsRestSourceSupport

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

OmsRestSourceSupport.requireUsableOmsConfig(ec, sourceConfig, configIdValue, companyUserGroupIdValue, "OMS")

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

// Stream the extraction page by page into a work file (in the output directory when it is
// file-backed, so the final move is an atomic same-volume rename) and only move it into place
// after the whole window succeeded. The window is never materialized in heap, and a mid-window
// failure never leaves a partial file where reconciliation could read it.
File outputDirectory = DataManagerSupport.resolveDirectoryFile(ec, outputBaseLocation, true)
File workFile = outputDirectory != null
        ? File.createTempFile("oms-orders-extract-", ".partial", outputDirectory)
        : File.createTempFile("oms-orders-extract-", ".partial")

// Live progress (advisory): with a run id, heartbeat the running order count onto this stage's
// RUNNING extract step as pages are consumed. The count IS the progress — an expected total is
// optional and only adds a percent, which the first extract of a run can never have because
// nothing has finished yet to divide against.
//
// Throttled on elapsed time rather than on percent change: percent-throttling needed the total,
// and a count has no natural "changed enough" threshold when page sizes vary by an order of
// magnitude across sources (a 50-record OMS page vs a Shopify bulk page). Writing faster than the
// UI polls buys nothing, so one heartbeat every couple of seconds is the useful ceiling — that
// also bounds the write volume on a 99k-order window to a few hundred updates instead of ~2000.
// The stage code is threaded in because this same extractor runs as file1 or file2 depending on
// the saved run's source order; it used to be hardcoded to file2, so every run with OMS on the
// first side silently looked up a step that was not RUNNING yet and reported nothing.
// Never fails the extraction.
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
    Map extraction = OmsRestSourceSupport.extractOrdersToFile(sourceConfig, windowStart, windowEnd, workFile,
            keepRecordFieldsValue, pageProgressListener, sourceFiltersValue)
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

    String outputFileName = OmsRestSourceSupport.safeFileName(fileName ?: extraction.fileName)
    fileName = outputFileName
    fileLocation = DataManagerSupport.childLocation(outputBaseLocation, outputFileName)
    DataManagerSupport.moveIntoLocation(ec, workFile, fileLocation as String)

    exchangeManifestFileLocation = null
    List exchangeManifestValue = (List) (extraction.exchangeManifest ?: [])
    if (exchangeManifestValue) {
        // The sidecar is advisory: it unlocks exchange-pair verification but the primary extract
        // above already succeeded, so a sidecar failure (including temp-file creation) must never
        // fail the extraction — degrade to a warning and move on.
        File manifestWorkFile = null
        try {
            String manifestFileName = OmsRestSourceSupport.exchangeManifestFileName(outputFileName)
            manifestWorkFile = outputDirectory != null
                    ? File.createTempFile("oms-exchange-manifest-", ".partial", outputDirectory)
                    : File.createTempFile("oms-exchange-manifest-", ".partial")
            manifestWorkFile.text = groovy.json.JsonOutput.toJson([
                    manifest: exchangeManifestValue,
                    truncated: extraction.exchangeManifestTruncated == true,
                    sourceFileName: outputFileName,
            ])
            String manifestLocation = DataManagerSupport.childLocation(outputBaseLocation, manifestFileName)
            DataManagerSupport.moveIntoLocation(ec, manifestWorkFile, manifestLocation as String)
            exchangeManifestFileLocation = manifestLocation
        } catch (Exception e) {
            warnings = (warnings ?: []) + ["Exchange manifest sidecar could not be written: ${e.message} — exchange pair verification will be unavailable for this run.".toString()]
        } finally {
            if (manifestWorkFile != null && manifestWorkFile.exists()) manifestWorkFile.delete()
        }
    }
} finally {
    if (workFile.exists()) workFile.delete()
}
