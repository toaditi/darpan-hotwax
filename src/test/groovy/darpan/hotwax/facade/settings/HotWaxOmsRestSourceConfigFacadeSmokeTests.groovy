package darpan.hotwax.facade.settings

import darpan.facade.common.TenantAccessSupport
import darpan.hotwax.oms.OmsRestSourceSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import groovy.json.JsonSlurper
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ArtifactExecutionInfo
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HotWaxOmsRestSourceConfigFacadeSmokeTests {
    private static final JsonSlurper JSON_SLURPER = new JsonSlurper()
    private static final String ENTITY_NAME = "darpan.hotwax.HotWaxOmsRestSourceConfig"
    private static final String TEST_USER_ID = "TEST_CUSTOMER_USER"
    private static final String KREWE = "KREWE"
    private static final String GORJANA = "GORJANA"
    private static final Timestamp TEST_FROM_DATE = Timestamp.valueOf("2026-05-01 00:00:00")

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "hotwax-oms-rest-source-config-smoke")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        seedPermissionGroup(TenantAccessSupport.DARPAN_COMPANY_VIEW_ONLY_GROUP_ID, "Can view tenant-scoped Darpan data but cannot mutate it")
        replaceTenantPermission(KREWE, TenantAccessSupport.DARPAN_COMPANY_VIEW_ONLY_GROUP_ID)
        seedTenant(GORJANA, "Gorjana")
        seedHotWaxFixtures()
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void clearErrors() {
        ec.message.clearErrors()
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, KREWE)
    }

    @Test
    void tenantAdminCanDeleteOwnConfigButViewOnlyTenantCannotDelete() {
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, GORJANA)

        Map<String, Object> createResult = saveFacade([
            omsRestSourceConfigId: "GORJANA_DELETE_HOTWAX",
            description          : "Delete HotWax",
            baseUrl              : "https://delete.hotwax.io",
            ordersPath           : "/rest/s1/oms/orders",
            authType             : "NONE",
            timeZone             : "America/Chicago",
            connectTimeoutSeconds: 30,
            readTimeoutSeconds   : 60,
            isActive             : true,
            canReadOrders        : false,
        ])
        assertTrue((Boolean) createResult.ok, createResult.errors?.toString())
        def createdConfig = findOne("GORJANA_DELETE_HOTWAX")
        assertEquals(GORJANA, createdConfig.companyUserGroupId)
        assertEquals("America/Chicago", createdConfig.timeZone)
        assertEquals("America/Chicago", ((Map<String, Object>) createResult.savedOmsRestSourceConfig).timeZone)
        assertEquals("N", createdConfig.canReadOrders)
        assertFalse(((Map<String, Object>) createResult.savedOmsRestSourceConfig).canReadOrders as boolean)

        ec.message.clearErrors()
        Map<String, Object> deleteResult = deleteFacade("GORJANA_DELETE_HOTWAX")
        assertTrue((Boolean) deleteResult.ok, deleteResult.errors?.toString())
        assertEquals(true, deleteResult.deleted)
        assertEquals("GORJANA_DELETE_HOTWAX", deleteResult.deletedOmsRestSourceConfigId)
        assertNull(findOne("GORJANA_DELETE_HOTWAX"))

        ec.message.clearErrors()
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, KREWE)
        Map<String, Object> blockedResult = deleteFacade("KREWE_HOTWAX")
        assertFalse((Boolean) blockedResult.ok)
        assertTrue((blockedResult.errors ?: []).join(" ").contains("read-only"))
        assertNotNull(findOne("KREWE_HOTWAX"))
    }

    @Test
    void automationExtractionUsesAutomationTenantWhenActiveTenantDiffers() {
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, GORJANA)
        OmsRestSourceSupport.setHttpClient { Map ignored ->
            [statusCode: 200, body: '{"orders":[{"orderId":"O100","orderTypeId":"SALES_ORDER"}]}']
        }

        try {
            Map<String, Object> result = (Map<String, Object>) ec.service.sync()
                .name("reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsOrders")
                .parameters([
                    omsRestSourceConfigId: "KREWE_HOTWAX",
                    companyUserGroupId   : KREWE,
                    automationExecutionId: "AUTO_EXEC_KREWE",
                    windowStart          : Timestamp.valueOf("2026-05-01 00:00:00"),
                    windowEnd            : Timestamp.valueOf("2026-05-02 00:00:00"),
                    outputLocation        : "runtime://tmp/hotwax-automation-extraction-test",
                ])
                .disableAuthz()
                .call()

            assertTrue((result.errors ?: []).isEmpty(), result.errors?.toString())
            assertTrue(result.dataAvailable as boolean)
            assertEquals(1, result.recordCount)
            assertTrue((result.fileLocation as String).contains("hotwax-automation-extraction-test"))
        } finally {
            OmsRestSourceSupport.resetHttpClient()
        }
    }

    @Test
    void automationExtractionWritesExchangeManifestSidecarWhenWindowHasExchangeOrders() {
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, GORJANA)
        OmsRestSourceSupport.setHttpClient { Map ignored ->
            [statusCode: 200, body: groovy.json.JsonOutput.toJson([orders: [
                    [orderId: "O100", externalId: "E100", orderTypeId: "SALES_ORDER"],
                    [orderId: "M750653", externalId: "6941645013123", orderName: "EXC-#GOR196990495-1",
                            orderTypeId: "SALES_ORDER", statusId: "ORDER_COMPLETED", grandTotal: 50.0,
                            orderDate: 1785260782199L,
                            itemAssocs: [[orderItemAssocTypeId: "EXCHANGE", toOrderId: "M686331"]]],
            ]])]
        }

        try {
            Map<String, Object> result = (Map<String, Object>) ec.service.sync()
                .name("reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsOrders")
                .parameters([
                    omsRestSourceConfigId: "KREWE_HOTWAX",
                    companyUserGroupId   : KREWE,
                    automationExecutionId: "AUTO_EXEC_KREWE_EXCHANGE",
                    windowStart          : Timestamp.valueOf("2026-05-01 00:00:00"),
                    windowEnd            : Timestamp.valueOf("2026-05-02 00:00:00"),
                    outputLocation        : "runtime://tmp/hotwax-automation-exchange-manifest-test",
                ])
                .disableAuthz()
                .call()

            assertTrue((result.errors ?: []).isEmpty(), result.errors?.toString())
            assertTrue(result.dataAvailable as boolean)
            assertEquals(1, result.recordCount)

            // The kept sales order is written to the primary extract file; the exchange order is
            // excluded from it (unchanged exclusion behavior) but captured into the sidecar below.
            File extractFile = ec.resource.getLocationReference(result.fileLocation as String).getFile()
            String extractText = extractFile.text
            assertTrue(extractText.contains("O100"))
            assertFalse(extractText.contains("M750653"))

            assertNotNull(result.exchangeManifestFileLocation)
            String manifestLocation = result.exchangeManifestFileLocation as String
            assertTrue(manifestLocation.contains("hotwax-automation-exchange-manifest-test"))
            File manifestFile = ec.resource.getLocationReference(manifestLocation).getFile()
            assertNotNull(manifestFile)
            assertTrue(manifestFile.exists())

            Map manifestDoc = JSON_SLURPER.parseText(manifestFile.text) as Map
            List manifest = manifestDoc.manifest as List
            assertEquals(1, manifest.size())
            assertEquals("M750653", manifest[0].omsOrderId)
            assertEquals("6941645013123", manifest[0].externalId)
            assertEquals("M686331", manifest[0].toOrderId)
            assertFalse(manifestDoc.truncated as boolean)
            assertEquals(result.fileName, manifestDoc.sourceFileName)
        } finally {
            OmsRestSourceSupport.resetHttpClient()
        }
    }

    @Test
    void saveRejectsDisallowedBaseUrls() {
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, GORJANA)

        Map<String, Object> plaintext = saveFacade([
            omsRestSourceConfigId: "GORJANA_SSRF_HTTP",
            baseUrl              : "http://oms.example.com",
            ordersPath           : "/rest/s1/oms/orders",
            authType             : "NONE",
            timeZone             : "America/Chicago",
            connectTimeoutSeconds: 30,
            readTimeoutSeconds   : 60,
            isActive             : true,
            canReadOrders        : true,
        ])
        assertFalse((Boolean) plaintext.ok)
        assertTrue((plaintext.errors ?: []).join(" ").contains("OMS Base URL is not allowed"))
        assertNull(findOne("GORJANA_SSRF_HTTP"))

        ec.message.clearErrors()
        Map<String, Object> metadataIp = saveFacade([
            omsRestSourceConfigId: "GORJANA_SSRF_METADATA",
            baseUrl              : "https://169.254.169.254/latest/meta-data",
            ordersPath           : "/rest/s1/oms/orders",
            authType             : "NONE",
            timeZone             : "America/Chicago",
            connectTimeoutSeconds: 30,
            readTimeoutSeconds   : 60,
            isActive             : true,
            canReadOrders        : true,
        ])
        assertFalse((Boolean) metadataIp.ok)
        assertTrue((metadataIp.errors ?: []).join(" ").contains("OMS Base URL is not allowed"))
        assertNull(findOne("GORJANA_SSRF_METADATA"))
    }

    @Test
    void listIsTenantScopedSearchableAndPaginated() {
        upsertEntityValue(ENTITY_NAME, [omsRestSourceConfigId: "GORJANA_LIST_HOTWAX"], [
            omsRestSourceConfigId: "GORJANA_LIST_HOTWAX",
            description          : "Gorjana List",
            companyUserGroupId   : GORJANA,
            baseUrl              : "https://gorjana-list.hotwax.io",
            ordersPath           : "/rest/s1/oms/orders",
            authType             : "NONE",
            isActive             : "Y",
            canReadOrders        : "Y",
            createdDate          : TEST_FROM_DATE,
            lastUpdatedDate      : TEST_FROM_DATE,
        ])
        upsertEntityValue(ENTITY_NAME, [omsRestSourceConfigId: "KREWE_HOTWAX_2"], [
            omsRestSourceConfigId: "KREWE_HOTWAX_2",
            description          : "Krewe Second",
            companyUserGroupId   : KREWE,
            baseUrl              : "https://krewe2.hotwax.io",
            ordersPath           : "/rest/s1/oms/orders",
            authType             : "NONE",
            isActive             : "Y",
            canReadOrders        : "Y",
            createdDate          : TEST_FROM_DATE,
            lastUpdatedDate      : TEST_FROM_DATE,
        ])

        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, KREWE)
        Map<String, Object> all = listFacade([:])
        assertTrue((Boolean) all.ok, all.errors?.toString())
        List rows = all.omsRestSourceConfigs as List
        List ids = rows.collect { it.omsRestSourceConfigId }
        assertTrue(ids.contains("KREWE_HOTWAX"))
        assertTrue(ids.contains("KREWE_HOTWAX_2"))
        assertFalse(ids.contains("GORJANA_LIST_HOTWAX"))
        rows.each { row ->
            assertTrue(row.containsKey("hasApiToken"))
            assertTrue(row.containsKey("hasPassword"))
            assertFalse(row.containsKey("apiToken"))
            assertFalse(row.containsKey("password"))
        }
        int totalCount = (all.pagination as Map).totalCount as int
        assertTrue(totalCount >= 2)

        ec.message.clearErrors()
        Map<String, Object> searched = listFacade([query: "second"])
        List searchedIds = (searched.omsRestSourceConfigs as List).collect { it.omsRestSourceConfigId }
        assertTrue(searchedIds.contains("KREWE_HOTWAX_2"))
        assertFalse(searchedIds.contains("KREWE_HOTWAX"))

        ec.message.clearErrors()
        Map<String, Object> paged = listFacade([pageSize: 1])
        Map pagination = paged.pagination as Map
        assertEquals(totalCount, pagination.totalCount)
        assertEquals(totalCount, pagination.pageCount)
        assertEquals(1, (paged.omsRestSourceConfigs as List).size())

        ec.message.clearErrors()
        Map<String, Object> beyond = listFacade([pageIndex: 99, pageSize: 1])
        assertTrue((Boolean) beyond.ok)
        assertTrue((beyond.omsRestSourceConfigs as List).isEmpty())
    }

    @Test
    void preservesSecretsOnBlankUpdateAndRequiresSecretForNewBearer() {
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, GORJANA)

        Map<String, Object> created = saveFacade([
            omsRestSourceConfigId: "GORJANA_BEARER_HOTWAX",
            description          : "Bearer One",
            baseUrl              : "https://bearer.hotwax.io",
            ordersPath           : "/rest/s1/oms/orders",
            authType             : "BEARER",
            apiToken             : "tok-12345",
            timeZone             : "America/Chicago",
            connectTimeoutSeconds: 30,
            readTimeoutSeconds   : 60,
            isActive             : true,
            canReadOrders        : true,
        ])
        assertTrue((Boolean) created.ok, created.errors?.toString())

        ec.message.clearErrors()
        Map<String, Object> updated = saveFacade([
            omsRestSourceConfigId: "GORJANA_BEARER_HOTWAX",
            description          : "Bearer One Updated",
            baseUrl              : "https://bearer.hotwax.io",
            ordersPath           : "/rest/s1/oms/orders",
            authType             : "BEARER",
            timeZone             : "America/Chicago",
            connectTimeoutSeconds: 30,
            readTimeoutSeconds   : 60,
            isActive             : true,
            canReadOrders        : true,
        ])
        assertTrue((Boolean) updated.ok, updated.errors?.toString())
        def stored = findOne("GORJANA_BEARER_HOTWAX")
        assertEquals("Bearer One Updated", stored.description)
        assertEquals("tok-12345", stored.apiToken?.toString())

        ec.message.clearErrors()
        Map<String, Object> rejected = saveFacade([
            omsRestSourceConfigId: "GORJANA_BEARER_MISSING",
            baseUrl              : "https://bearer2.hotwax.io",
            ordersPath           : "/rest/s1/oms/orders",
            authType             : "BEARER",
            timeZone             : "America/Chicago",
            connectTimeoutSeconds: 30,
            readTimeoutSeconds   : 60,
            isActive             : true,
            canReadOrders        : true,
        ])
        assertFalse((Boolean) rejected.ok)
        assertTrue((rejected.errors ?: []).join(" ").contains("API token is required for BEARER auth."))
        assertNull(findOne("GORJANA_BEARER_MISSING"))
    }

    @Test
    void deleteReportsNotFoundForMissingConfig() {
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, GORJANA)
        Map<String, Object> result = deleteFacade("GORJANA_DOES_NOT_EXIST")
        assertFalse((Boolean) result.ok)
        assertTrue((result.errors ?: []).join(" ").contains("was not found"))
        assertEquals(false, result.deleted)
    }

    @Test
    void extractionBlocksInactiveConfigAndAutomationTenantMismatch() {
        Map<String, Object> mismatch = (Map<String, Object>) ec.service.sync()
            .name("reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsOrders")
            .parameters([
                omsRestSourceConfigId: "KREWE_HOTWAX",
                companyUserGroupId   : GORJANA,
                windowStart          : Timestamp.valueOf("2026-05-01 00:00:00"),
                windowEnd            : Timestamp.valueOf("2026-05-02 00:00:00"),
            ])
            .disableAuthz()
            .call()
        // DAR-BE-005 B1: a foreign automation tenant with no standing (not owner, not a peer) now
        // gets the same collapsed "not found" text as a nonexistent config id — see
        // OmsRestSourceSupport.requireUsableOmsConfig's Javadoc for why the two messages merged.
        assertTrue((mismatch.errors ?: []).join(" ").contains("not found"))
        assertFalse(mismatch.dataAvailable as boolean)
        assertEquals(0, mismatch.recordCount)
        assertNull(mismatch.fileLocation)

        // Clear the mismatch error before the next service call: Moqui skips a service invocation when
        // ec.message already has errors, which would otherwise return null for the inactive-config call.
        ec.message.clearErrors()
        upsertEntityValue(ENTITY_NAME, [omsRestSourceConfigId: "KREWE_INACTIVE_HOTWAX"], [
            omsRestSourceConfigId: "KREWE_INACTIVE_HOTWAX",
            description          : "Krewe Inactive",
            companyUserGroupId   : KREWE,
            baseUrl              : "https://krewe-inactive.hotwax.io",
            ordersPath           : "/rest/s1/oms/orders",
            authType             : "NONE",
            isActive             : "N",
            canReadOrders        : "Y",
            createdDate          : TEST_FROM_DATE,
            lastUpdatedDate      : TEST_FROM_DATE,
        ])
        Map<String, Object> inactive = (Map<String, Object>) ec.service.sync()
            .name("reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsOrders")
            .parameters([
                omsRestSourceConfigId: "KREWE_INACTIVE_HOTWAX",
                companyUserGroupId   : KREWE,
                windowStart          : Timestamp.valueOf("2026-05-01 00:00:00"),
                windowEnd            : Timestamp.valueOf("2026-05-02 00:00:00"),
            ])
            .disableAuthz()
            .call()
        assertTrue((inactive.errors ?: []).join(" ").contains("is inactive"))
        assertFalse(inactive.dataAvailable as boolean)
        assertEquals(0, inactive.recordCount)
    }

    private Map<String, Object> listFacade(Map<String, Object> parameters) {
        return (Map<String, Object>) ec.service.sync()
            .name("facade.HotWaxOmsFacadeServices.list#HotWaxOmsRestSourceConfigs")
            .parameters(parameters)
            .disableAuthz()
            .call()
    }

    private Map<String, Object> saveFacade(Map<String, Object> parameters) {
        return (Map<String, Object>) ec.service.sync()
            .name("facade.HotWaxOmsFacadeServices.save#HotWaxOmsRestSourceConfig")
            .parameters(parameters)
            .disableAuthz()
            .call()
    }

    private Map<String, Object> deleteFacade(String configId) {
        return (Map<String, Object>) ec.service.sync()
            .name("facade.HotWaxOmsFacadeServices.delete#HotWaxOmsRestSourceConfig")
            .parameters([omsRestSourceConfigId: configId])
            .disableAuthz()
            .call()
    }

    private def findOne(String configId) {
        return ec.entity.find(ENTITY_NAME)
            .condition("omsRestSourceConfigId", configId)
            .disableAuthz()
            .useCache(false)
            .one()
    }

    private void seedPermissionGroup(String permissionGroupId, String description) {
        upsertEntityValue("moqui.security.UserGroup", [userGroupId: permissionGroupId], [
            userGroupId    : permissionGroupId,
            description    : description,
            groupTypeEnumId: TenantAccessSupport.DARPAN_PERMISSION_GROUP_TYPE_ENUM_ID,
        ])
    }

    private void seedTenant(String tenantId, String label) {
        upsertEntityValue("moqui.security.UserGroup", [userGroupId: tenantId], [
            userGroupId    : tenantId,
            description    : label,
            groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID,
        ])
        upsertEntityValue("moqui.security.UserGroupMember", [
            userGroupId: tenantId,
            userId     : TEST_USER_ID,
            fromDate   : TEST_FROM_DATE,
        ], [
            userGroupId: tenantId,
            userId     : TEST_USER_ID,
            fromDate   : TEST_FROM_DATE,
        ])
        replaceTenantPermission(tenantId, TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID)
    }

    private void replaceTenantPermission(String tenantId, String permissionGroupId) {
        boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
        ArtifactExecutionInfo aei = ec.artifactExecution.push(
            "replaceHotWaxTenantPermission",
            ArtifactExecutionInfo.AT_OTHER,
            ArtifactExecutionInfo.AUTHZA_ALL,
            false
        )
        ec.artifactExecution.setAnonymousAuthorizedAll()
        try {
            ec.entity.find(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME)
                .condition("tenantUserGroupId", tenantId)
                .condition("userId", TEST_USER_ID)
                .disableAuthz()
                .useCache(false)
                .list()
                .each { it.delete() }
        } finally {
            ec.artifactExecution.pop(aei)
            if (!alreadyDisabled) ec.artifactExecution.enableAuthz()
        }
        upsertEntityValue(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME, [
            tenantUserGroupId    : tenantId,
            userId               : TEST_USER_ID,
            permissionUserGroupId: permissionGroupId,
            fromDate             : TEST_FROM_DATE,
        ], [
            tenantUserGroupId    : tenantId,
            userId               : TEST_USER_ID,
            permissionUserGroupId: permissionGroupId,
            fromDate             : TEST_FROM_DATE,
        ])
    }

    private void seedHotWaxFixtures() {
        upsertEntityValue(ENTITY_NAME, [omsRestSourceConfigId: "KREWE_HOTWAX"], [
            omsRestSourceConfigId : "KREWE_HOTWAX",
            description           : "Krewe HotWax",
            companyUserGroupId    : KREWE,
            createdByUserId       : TEST_USER_ID,
            baseUrl               : "https://krewe.hotwax.io",
            ordersPath            : "/rest/s1/oms/orders",
            authType              : "NONE",
            connectTimeoutSeconds : 30,
            readTimeoutSeconds    : 60,
            isActive              : "Y",
            canReadOrders         : "Y",
            createdDate           : TEST_FROM_DATE,
            lastUpdatedDate       : TEST_FROM_DATE,
        ])
    }

    private void upsertEntityValue(String entityName, Map<String, Object> pkFields, Map<String, Object> fields) {
        boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
        ArtifactExecutionInfo aei = ec.artifactExecution.push(
            "seedHotWaxOmsRestSourceConfig",
            ArtifactExecutionInfo.AT_OTHER,
            ArtifactExecutionInfo.AUTHZA_ALL,
            false
        )
        ec.artifactExecution.setAnonymousAuthorizedAll()
        try {
            def existing = ec.entity.find(entityName)
                .condition(pkFields)
                .disableAuthz()
                .useCache(false)
                .one()
            if (existing != null) return

            ec.service.sync()
                .name("store#${entityName}")
                .parameters(fields)
                .disableAuthz()
                .call()
        } finally {
            ec.artifactExecution.pop(aei)
            if (!alreadyDisabled) ec.artifactExecution.enableAuthz()
        }
    }
}
