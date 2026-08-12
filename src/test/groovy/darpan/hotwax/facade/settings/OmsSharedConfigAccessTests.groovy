package darpan.hotwax.facade.settings

import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
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

/**
 * DAR-BE-005 Task 7 — Seam A wired into darpan-hotwax's own facade services: list/save/delete for
 * darpan.hotwax.HotWaxOmsRestSourceConfig honouring cross-tenant ConfigTenantAccess grants.
 *
 * <p><strong>Why this does not reuse darpan's SharedConfigAccessSupportTests stubs (FinderStub /
 * UserStub / EntityFacadeStub / MessageFacadeStub), even though the build.gradle dependency makes
 * them reachable here:</strong> those stubs model {@code ec.user}, {@code ec.entity}, and
 * {@code ec.message} only — there is no {@code ec.service} on the stub Expando anywhere in
 * SharedConfigPropagationTests.groovy (Task 6's own test, the one the brief for this task pointed
 * to as precedent). They exist to unit-test a static support method directly (e.g.
 * {@code ReconciliationSavedRunSupport.validateHotWaxOmsConfig(stubEc, ...)}), bypassing Moqui's
 * service engine. The three properties this task must prove are FACADE SERVICE behavior — real
 * service dispatch through {@code facade.HotWaxOmsFacadeServices}, real XML out-parameter shape,
 * real entity persistence — which a stub {@code ec.service.sync()} cannot provide. This class
 * follows HotWaxOmsRestSourceConfigFacadeSmokeTests' own real-Moqui-context pattern instead
 * (ReconciliationSmokeTestSupport), which is the only pattern in this codebase that actually
 * exercises these three scripts end to end.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OmsSharedConfigAccessTests {
    private static final String ENTITY_NAME = "darpan.hotwax.HotWaxOmsRestSourceConfig"
    private static final String GRANT_ENTITY_NAME = "darpan.auth.ConfigTenantAccess"
    private static final String CONFIG_TYPE = "SCFG_HOTWAX_OMS"
    private static final String TEST_USER_ID = "TEST_CUSTOMER_USER"
    private static final String OWNER = "OMS_SHARE_OWNER"
    private static final String MEMBER = "OMS_SHARE_MEMBER"
    private static final String STRANGER = "OMS_SHARE_STRANGER"
    private static final Timestamp TEST_FROM_DATE = Timestamp.valueOf("2026-05-01 00:00:00")

    // Distinct config ids per test so tests remain order-independent under
    // @TestInstance(PER_CLASS) — the delete test consumes its row.
    private static final String LIST_CONFIG_ID = "OWNER_LIST_SHARED_HOTWAX"
    private static final String SAVE_CONFIG_ID = "OWNER_SAVE_SHARED_HOTWAX"
    private static final String DELETE_CONFIG_ID = "OWNER_DELETE_SHARED_HOTWAX"

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "hotwax-oms-shared-config-access")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        seedTenant(OWNER, "Share Owner")
        seedTenant(MEMBER, "Share Member")
        seedTenant(STRANGER, "Share Stranger")
        seedConfigTypeEnumeration()

        seedHotWaxFixture(LIST_CONFIG_ID, "List fixture")
        seedHotWaxFixture(SAVE_CONFIG_ID, "Save fixture")
        seedHotWaxFixture(DELETE_CONFIG_ID, "Delete fixture")

        seedGrant(LIST_CONFIG_ID)
        seedGrant(SAVE_CONFIG_ID)
        seedGrant(DELETE_CONFIG_ID)
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void clearErrors() {
        ec.message.clearErrors()
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, OWNER)
    }

    @Test
    void listFlagsASharedConfigAsSharedForTheMemberButNotForTheOwner() {
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, MEMBER)
        Map<String, Object> memberResult = listFacade([:])
        assertTrue((Boolean) memberResult.ok, memberResult.errors?.toString())
        Map<String, Object> memberRow = (memberResult.omsRestSourceConfigs as List).find {
            it.omsRestSourceConfigId == LIST_CONFIG_ID
        }
        assertNotNull(memberRow, "a member tenant must see a config shared with it in its own list")
        assertTrue(memberRow.isShared as boolean, "a config owned by another tenant must be flagged isShared")

        ec.message.clearErrors()
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, OWNER)
        Map<String, Object> ownerResult = listFacade([:])
        Map<String, Object> ownerRow = (ownerResult.omsRestSourceConfigs as List).find {
            it.omsRestSourceConfigId == LIST_CONFIG_ID
        }
        assertNotNull(ownerRow)
        assertFalse(ownerRow.isShared as boolean, "the owner's own config must never be flagged isShared")
    }

    @Test
    void saveAcceptsAMemberEditOfEveryFieldAndNeverTransfersOwnership() {
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, MEMBER)

        Map<String, Object> result = saveFacade([
            omsRestSourceConfigId: SAVE_CONFIG_ID,
            description          : "Edited by member",
            baseUrl              : "https://owner-shared-save.hotwax.io",
            ordersPath           : "/rest/s1/oms/orders",
            authType             : "NONE",
            timeZone             : "America/Chicago",
            connectTimeoutSeconds: 45,
            readTimeoutSeconds   : 90,
            isActive             : true,
            canReadOrders        : false,
        ])
        assertTrue((Boolean) result.ok, result.errors?.toString())

        def stored = findOne(SAVE_CONFIG_ID)
        assertEquals("Edited by member", stored.description,
                "a member tenant must be able to edit every field of a shared config")
        assertEquals("https://owner-shared-save.hotwax.io", stored.baseUrl)
        assertEquals("N", stored.canReadOrders)
        assertEquals(OWNER, stored.companyUserGroupId,
                "a member's save must never transfer ownership of the shared row")
        Map<String, Object> saved = (Map<String, Object>) result.savedOmsRestSourceConfig
        assertTrue(saved.isShared as boolean, "the save response must also flag the row isShared for the peer")
    }

    /**
     * DAR-BE-005 Task 9: a config with an active grant cannot be deleted by ANYONE, including its
     * owner — configId is polymorphic with no DB FK, so a delete would cascade nothing and leave
     * the peer's automation failing at run time with a confusing "not found" instead of failing
     * loudly here. This complements (does not replace) the peer-cannot-delete rule Task 7 already
     * proved: a peer never deletes; an owner deletes only once the group is empty.
     */
    @Test
    void deleteRejectsAMemberTenantAndTheOwnerWhileAGrantRemainsActive() {
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, MEMBER)
        Map<String, Object> blockedResult = deleteFacade(DELETE_CONFIG_ID)
        assertFalse((Boolean) blockedResult.ok)
        assertTrue((blockedResult.errors ?: []).join(" ").contains("Stop sharing it instead of deleting it"),
                "a peer must be told to stop sharing instead of delete: ${blockedResult.errors}")
        assertNotNull(findOne(DELETE_CONFIG_ID), "the shared row must survive a peer's delete attempt")

        ec.message.clearErrors()
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, OWNER)
        Map<String, Object> ownerResult = deleteFacade(DELETE_CONFIG_ID)
        assertFalse((Boolean) ownerResult.ok,
                "the owner must not be able to delete a config that still has an active grant")
        assertTrue((ownerResult.errors ?: []).join(" ").contains(
                "Stop sharing it with every tenant before deleting it"),
                "the owner-cannot-delete-while-shared guard must fire: ${ownerResult.errors}")
        assertNotNull(findOne(DELETE_CONFIG_ID),
                "the config must survive even the owner's delete attempt while a grant is active")
    }

    /**
     * DAR-BE-005 Task 9, the other half: once every grant on a config is revoked, the owner may
     * delete it normally — the guard is about live dependents, not a permanent lock.
     */
    @Test
    void deleteAcceptsTheOwnerOnceEveryGrantIsRevoked() {
        String configId = "OWNER_DELETE_AFTER_REVOKE_HOTWAX"
        seedHotWaxFixture(configId, "Delete-after-revoke fixture")
        seedGrant(configId)

        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, OWNER)
        Map<String, Object> blockedResult = deleteFacade(configId)
        assertFalse((Boolean) blockedResult.ok,
                "the owner must not be able to delete a config that still has an active grant")
        assertNotNull(findOne(configId), "a blocked delete must not remove the row")

        ec.message.clearErrors()
        revokeGrant(configId, Timestamp.valueOf("2026-05-02 00:00:00"))

        Map<String, Object> ownerResult = deleteFacade(configId)
        assertTrue((Boolean) ownerResult.ok, ownerResult.errors?.toString())
        assertEquals(true, ownerResult.deleted)
        assertNull(findOne(configId), "once every grant is revoked the owner may delete normally")
    }

    /**
     * DAR-BE-005 Task 7 review finding: the collapse was verified by construction (reading the
     * source) but never proven by a test that a genuine STRANGER — owns nothing, holds no
     * ConfigTenantAccess grant — gets identical text for a real foreign-owned config as for a
     * nonexistent one. Uses the SAME literal configId for both calls (sequenced: first while the
     * id does not exist anywhere, then again after a real row is seeded under that exact id) —
     * the "was not found" message echoes the caller-supplied id, so two DIFFERENT ids would
     * trivially produce different text regardless of whether the collapse holds. This is the exact
     * leak class Task 6 accidentally reopened one task before this one.
     */
    @Test
    void deleteGivesAStrangerTheIdenticalMessageForARealAndANonexistentConfigId() {
        String targetConfigId = "OMS_STRANGER_ORACLE_TARGET"
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, STRANGER)

        // Pass 1: the id does not exist anywhere yet.
        Map<String, Object> missingResult = deleteFacade(targetConfigId)
        assertFalse((Boolean) missingResult.ok)

        // Pass 2: a real config now exists under that SAME id, owned by another tenant, with no
        // grant to this stranger.
        ec.message.clearErrors()
        seedHotWaxFixture(targetConfigId, "Stranger oracle target")
        Map<String, Object> foreignResult = deleteFacade(targetConfigId)
        assertFalse((Boolean) foreignResult.ok)
        assertNotNull(findOne(targetConfigId),
                "a stranger's delete attempt on a real foreign config must not delete it")

        assertEquals(missingResult.errors, foreignResult.errors,
                "a stranger (no ownership, no grant) must get byte-identical text whether the " +
                "config id is real-but-foreign or does not exist at all — divergence here is a " +
                "cross-tenant existence oracle")
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
            "replaceHotWaxSharedConfigTenantPermission",
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

    private void seedHotWaxFixture(String configId, String description) {
        upsertEntityValue(ENTITY_NAME, [omsRestSourceConfigId: configId], [
            omsRestSourceConfigId : configId,
            description           : description,
            companyUserGroupId    : OWNER,
            createdByUserId       : TEST_USER_ID,
            baseUrl               : "https://${configId.toLowerCase()}.hotwax.io".toString(),
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

    /** ConfigTenantAccess.configTypeEnumId has a DB FK to moqui.basic.Enumeration. Production loads
     *  this row from darpan/data/SecuritySeedData.xml; this isolated test DB does not auto-load seed
     *  data (the existing smoke tests hand-seed their own permission-group rows for the same reason),
     *  so it is hand-seeded here too rather than pulling in the whole seed file. */
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

    private void seedGrant(String configId) {
        upsertEntityValue(GRANT_ENTITY_NAME, [
            configTypeEnumId : CONFIG_TYPE,
            configId         : configId,
            tenantUserGroupId: MEMBER,
            fromDate         : TEST_FROM_DATE,
        ], [
            configTypeEnumId : CONFIG_TYPE,
            configId         : configId,
            tenantUserGroupId: MEMBER,
            fromDate         : TEST_FROM_DATE,
            thruDate         : null,
            grantedByUserId  : TEST_USER_ID,
        ])
    }

    /** Soft-revokes the MEMBER grant seeded by {@link #seedGrant} — sets thruDate rather than
     *  deleting the row, matching production revoke semantics (SharedConfigGrantSupport.revokeAccess). */
    private void revokeGrant(String configId, Timestamp thruDate) {
        boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
        ArtifactExecutionInfo aei = ec.artifactExecution.push(
            "revokeOmsSharedConfigAccess",
            ArtifactExecutionInfo.AT_OTHER,
            ArtifactExecutionInfo.AUTHZA_ALL,
            false
        )
        ec.artifactExecution.setAnonymousAuthorizedAll()
        try {
            ec.service.sync()
                .name("update#${GRANT_ENTITY_NAME}")
                .parameters([
                    configTypeEnumId : CONFIG_TYPE,
                    configId         : configId,
                    tenantUserGroupId: MEMBER,
                    fromDate         : TEST_FROM_DATE,
                    thruDate         : thruDate,
                ])
                .disableAuthz()
                .call()
        } finally {
            ec.artifactExecution.pop(aei)
            if (!alreadyDisabled) ec.artifactExecution.enableAuthz()
        }
    }

    private void upsertEntityValue(String entityName, Map<String, Object> pkFields, Map<String, Object> fields) {
        boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
        ArtifactExecutionInfo aei = ec.artifactExecution.push(
            "seedOmsSharedConfigAccess",
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
