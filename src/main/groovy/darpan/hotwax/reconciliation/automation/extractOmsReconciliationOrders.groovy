import darpan.facade.common.DataManagerSupport
import darpan.facade.common.TenantAccessSupport
import darpan.facade.reconciliation.RunObservability
import darpan.hotwax.oms.OmsRestSourceSupport

// Service edge for /rest/s1/oms/reconciliationOrders (DAR-BE-018).
//
// Deliberately mirrors extractOmsOrders.groovy step for step — same tenant gate, same output
// location, same atomic .partial move, same progress heartbeat, same exchange-manifest sidecar —
// and differs only by passing extractOptions.reconEndpoint. Everything that varies between the two
// endpoints lives in OmsRestSourceSupport, not here, so the automation edge, the on-disk
// {records, metadata} contract and the sidecar consumers stay identical across both connectors.
// The duplication follows the local pattern (extractOmsTransferOrders.groovy): a Moqui service
// binds one script location, and these scripts read service parameters as bare bindings.

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

if (companyUserGroupIdValue) {
    if (!sourceConfig) {
        ec.message.addError("OMS REST source config ${configIdValue} not found.")
    } else if (sourceConfig.companyUserGroupId?.toString()?.trim() != companyUserGroupIdValue) {
        ec.message.addError("OMS REST source config ${configIdValue} is not available in this automation tenant.")
    }
} else {
    TenantAccessSupport.requireTenantRecordAccess(
            ec,
            sourceConfig,
            "OMS REST source config ${configIdValue} not found.",
            "OMS REST source config ${configIdValue} is not available in your active tenant."
    )
}

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
        ? File.createTempFile("oms-recon-orders-extract-", ".partial", outputDirectory)
        : File.createTempFile("oms-recon-orders-extract-", ".partial")

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
    // The single switch that selects the reconciliationOrders contract: its own path, half-open
    // orderDateFrom/orderDateThru bounds, hasMore-driven termination, no client-side SALES_ORDER
    // re-filter, and inline exchange orders dropped into the sidecar instead of scanned for.
    Map extractOptions = [reconEndpoint: true]
    Map extraction = OmsRestSourceSupport.extractOrdersToFile(sourceConfig, windowStart, windowEnd, workFile,
            keepRecordFieldsValue, pageProgressListener, sourceFiltersValue, extractOptions)
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
        // Advisory, exactly as on the legacy path: the primary extract has already succeeded, so a
        // sidecar failure degrades to a warning rather than failing the run. The entries here are
        // built from the endpoint's inline originalOrderId/originalExternalId, so producing this
        // manifest costs no pair-lookup round-trips at all.
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
