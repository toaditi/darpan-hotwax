package darpan.hotwax.facade.settings

import darpan.facade.common.TenantAccessSupport
import darpan.hotwax.oms.OmsRestSourceSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
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
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-005 B1 — the fifth seam Tasks 1-13 missed: RUNTIME CREDENTIAL *USE*. Task 7's
 * {@code OmsSharedConfigAccessTests} proved a peer tenant can SEE and EDIT a config shared with it
 * (the settings-list/save/delete facade). It never proved a peer tenant could actually RUN a
 * reconciliation against it — the extract/lookup scripts stayed strict owner-only, so a shared
 * config was visible and editable but every run against it from a peer tenant failed. This class
 * proves the fix ({@code OmsRestSourceSupport.requireUsableOmsConfig}, wired into
 * {@code extractOmsOrders.groovy} and its four siblings) by driving the real
 * {@code extract#HotWaxOmsOrders} service end to end through a fake HTTP client, for both standing
 * paths that script supports:
 *
 * <ul>
 *   <li><b>automation path</b> — an explicit, trusted {@code companyUserGroupId} service parameter
 *       (how the automation runner calls it; the tenant is server-derived from the already-gated
 *       automation record, not caller-supplied).</li>
 *   <li><b>interactive path</b> — no {@code companyUserGroupId}, gated against the session's active
 *       tenant instead (how "Run now" and the exchange-pair lookup call it).</li>
 * </ul>
 *
 * <p>Uses a real Moqui {@link ExecutionContext} via {@link ReconciliationSmokeTestSupport}, the same
 * pattern {@code OmsSharedConfigAccessTests} and {@code HotWaxOmsRestSourceConfigFacadeSmokeTests}
 * use and for the same reason: a stub {@code ec} has no {@code ec.service}, so it cannot dispatch the
 * real {@code type="script"} service that hosts the fix.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OmsSharedConfigRuntimeAccessTests {
    private static final String ENTITY_NAME = "darpan.hotwax.HotWaxOmsRestSourceConfig"
    private static final String GRANT_ENTITY_NAME = "darpan.auth.ConfigTenantAccess"
    private static final String CONFIG_TYPE = "SCFG_HOTWAX_OMS"
    private static final String TEST_USER_ID = "TEST_CUSTOMER_USER"
    private static final String OWNER = "OMS_RUNTIME_OWNER"
    private static final String MEMBER = "OMS_RUNTIME_MEMBER"
    private static final String STRANGER = "OMS_RUNTIME_STRANGER"
    private static final Timestamp TEST_FROM_DATE = Timestamp.valueOf("2026-05-01 00:00:00")
    private static final String FAKE_ORDERS_BODY = '{"orders":[{"orderId":"O1","orderTypeId":"SALES_ORDER"}]}'

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "hotwax-oms-shared-config-runtime-access")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        seedTenant(OWNER, "Runtime Owner")
        seedTenant(MEMBER, "Runtime Member")
        seedTenant(STRANGER, "Runtime Stranger")
        seedConfigTypeEnumeration()
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void resetState() {
        ec.message.clearErrors()
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, OWNER)
        OmsRestSourceSupport.setHttpClient { Map ignored -> [statusCode: 200, body: FAKE_ORDERS_BODY] }
    }

    @AfterEach
    void resetHttpClient() {
        OmsRestSourceSupport.resetHttpClient()
    }

    // --- automation path (explicit companyUserGroupId) -------------------

    @Test
    void automationPathAllowsAPeerTenantWithAnActiveGrant() {
        String configId = "RUNTIME_AUTOMATION_MEMBER"
        seedHotWaxFixture(configId, "Automation member fixture")
        seedGrant(configId, MEMBER)

        Map<String, Object> result = extractFacade([
                omsRestSourceConfigId: configId,
                companyUserGroupId   : MEMBER,
        ])
        assertTrue((result.errors ?: []).isEmpty(), result.errors?.toString())
        assertTrue(result.dataAvailable as boolean,
                "the whole point of this feature: a peer tenant's automation run against a shared " +
                "config must actually succeed, not just be visible in the settings list")
        assertEquals(1, result.recordCount)
    }

    @Test
    void automationPathDeniesAStrangerWithTheSameTextForARealAndNonexistentConfig() {
        String configId = "RUNTIME_AUTOMATION_STRANGER_TARGET"

        Map<String, Object> missing = extractFacade([
                omsRestSourceConfigId: configId,
                companyUserGroupId   : STRANGER,
        ])
        assertFalse((missing.errors ?: []).isEmpty())

        ec.message.clearErrors()
        seedHotWaxFixture(configId, "Automation stranger target")
        Map<String, Object> foreign = extractFacade([
                omsRestSourceConfigId: configId,
                companyUserGroupId   : STRANGER,
        ])
        assertFalse((foreign.errors ?: []).isEmpty())
        assertFalse(foreign.dataAvailable as boolean)

        assertEquals(missing.errors, foreign.errors,
                "a stranger automation tenant (no ownership, no grant) must get byte-identical text " +
                "whether the config id is real-but-foreign or does not exist at all — divergence here " +
                "is a cross-tenant existence oracle")
        assertTrue((foreign.errors as List).join(" ").contains("not found"),
                "denial text must collapse to the plain not-found message, never a distinguishable " +
                "'not available in this automation tenant': ${foreign.errors}")
    }

    @Test
    void automationPathZeroGrantsPreservesOwnerAcceptAndForeignReject() {
        String configId = "RUNTIME_AUTOMATION_ZERO_GRANT"
        seedHotWaxFixture(configId, "Zero grant automation fixture")
        // Deliberately no seedGrant call — proves behavior is unchanged from pre-DAR-BE-005 with an
        // empty ConfigTenantAccess table.

        Map<String, Object> ownerResult = extractFacade([
                omsRestSourceConfigId: configId,
                companyUserGroupId   : OWNER,
        ])
        assertTrue((ownerResult.errors ?: []).isEmpty(), ownerResult.errors?.toString())
        assertTrue(ownerResult.dataAvailable as boolean)

        ec.message.clearErrors()
        Map<String, Object> foreignResult = extractFacade([
                omsRestSourceConfigId: configId,
                companyUserGroupId   : STRANGER,
        ])
        assertFalse((foreignResult.errors ?: []).isEmpty(),
                "with zero grants a foreign automation tenant must still be rejected exactly as before")
        assertFalse(foreignResult.dataAvailable as boolean)
    }

    // --- interactive path (session active tenant) -------------------------

    @Test
    void interactivePathAllowsAPeerTenantWithAnActiveGrant() {
        String configId = "RUNTIME_INTERACTIVE_MEMBER"
        seedHotWaxFixture(configId, "Interactive member fixture")
        seedGrant(configId, MEMBER)

        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, MEMBER)
        Map<String, Object> result = extractFacade([omsRestSourceConfigId: configId])
        assertTrue((result.errors ?: []).isEmpty(), result.errors?.toString())
        assertTrue(result.dataAvailable as boolean,
                "an interactive 'Run now' from a peer tenant's active session must succeed against a " +
                "config shared with it")
    }

    @Test
    void interactivePathDeniesAStrangerWithTheSameTextForARealAndNonexistentConfig() {
        String configId = "RUNTIME_INTERACTIVE_STRANGER_TARGET"
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, STRANGER)

        Map<String, Object> missing = extractFacade([omsRestSourceConfigId: configId])
        assertFalse((missing.errors ?: []).isEmpty())

        ec.message.clearErrors()
        seedHotWaxFixture(configId, "Interactive stranger target")
        Map<String, Object> foreign = extractFacade([omsRestSourceConfigId: configId])
        assertFalse((foreign.errors ?: []).isEmpty())

        assertEquals(missing.errors, foreign.errors,
                "an interactive stranger (no ownership, no grant) must get byte-identical text " +
                "whether the config id is real-but-foreign or does not exist at all")
        assertTrue((foreign.errors as List).join(" ").contains("not found"),
                "denial text must collapse to the plain not-found message, never a distinguishable " +
                "'not available in your active tenant': ${foreign.errors}")
    }

    @Test
    void interactivePathZeroGrantsPreservesOwnerAcceptAndForeignReject() {
        String configId = "RUNTIME_INTERACTIVE_ZERO_GRANT"
        seedHotWaxFixture(configId, "Zero grant interactive fixture")
        // Deliberately no seedGrant call.

        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, OWNER)
        Map<String, Object> ownerResult = extractFacade([omsRestSourceConfigId: configId])
        assertTrue((ownerResult.errors ?: []).isEmpty(), ownerResult.errors?.toString())
        assertTrue(ownerResult.dataAvailable as boolean)

        ec.message.clearErrors()
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, STRANGER)
        Map<String, Object> foreignResult = extractFacade([omsRestSourceConfigId: configId])
        assertFalse((foreignResult.errors ?: []).isEmpty(),
                "with zero grants a foreign active tenant must still be rejected exactly as before")
    }

    // --- helpers -----------------------------------------------------------

    private Map<String, Object> extractFacade(Map<String, Object> parameters) {
        Map<String, Object> base = [
                windowStart   : Timestamp.valueOf("2026-05-01 00:00:00"),
                windowEnd     : Timestamp.valueOf("2026-05-02 00:00:00"),
                outputLocation: "runtime://tmp/oms-shared-config-runtime-access-test",
        ]
        return (Map<String, Object>) ec.service.sync()
                .name("reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsOrders")
                .parameters(base + parameters)
                .disableAuthz()
                .call()
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
    }

    private void seedHotWaxFixture(String configId, String description) {
        upsertEntityValue(ENTITY_NAME, [omsRestSourceConfigId: configId], [
                omsRestSourceConfigId: configId,
                description          : description,
                companyUserGroupId   : OWNER,
                createdByUserId      : TEST_USER_ID,
                baseUrl              : "https://${configId.toLowerCase()}.hotwax.io".toString(),
                ordersPath           : "/rest/s1/oms/orders",
                authType             : "NONE",
                connectTimeoutSeconds: 30,
                readTimeoutSeconds   : 60,
                isActive             : "Y",
                canReadOrders        : "Y",
                createdDate          : TEST_FROM_DATE,
                lastUpdatedDate      : TEST_FROM_DATE,
        ])
    }

    /** ConfigTenantAccess.configTypeEnumId has a DB FK to moqui.basic.Enumeration. Production loads
     *  this row from darpan/data/SecuritySeedData.xml; this isolated test DB does not auto-load seed
     *  data, so it is hand-seeded here too rather than pulling in the whole seed file (matches
     *  OmsSharedConfigAccessTests' own seedConfigTypeEnumeration). */
    private void seedConfigTypeEnumeration() {
        upsertEntityValue("moqui.basic.EnumerationType", [enumTypeId: "DarpanSharedConfigType"], [
                enumTypeId : "DarpanSharedConfigType",
                description: "Darpan API source config types that support cross-tenant sharing",
        ])
        upsertEntityValue("moqui.basic.Enumeration", [enumId: CONFIG_TYPE], [
                enumId     : CONFIG_TYPE,
                description: "HotWax OMS REST source config (darpan.hotwax.HotWaxOmsRestSourceConfig)",
                enumTypeId : "DarpanSharedConfigType",
        ])
    }

    private void seedGrant(String configId, String tenantUserGroupId) {
        upsertEntityValue(GRANT_ENTITY_NAME, [
                configTypeEnumId : CONFIG_TYPE,
                configId         : configId,
                tenantUserGroupId: tenantUserGroupId,
        ], [
                configTypeEnumId : CONFIG_TYPE,
                configId         : configId,
                tenantUserGroupId: tenantUserGroupId,
                fromDate         : TEST_FROM_DATE,
                thruDate         : null,
                grantedByUserId  : TEST_USER_ID,
        ])
    }

    private void upsertEntityValue(String entityName, Map<String, Object> pkFields, Map<String, Object> fields) {
        boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
        ArtifactExecutionInfo aei = ec.artifactExecution.push(
                "seedOmsSharedConfigRuntimeAccess",
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
