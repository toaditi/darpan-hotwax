import darpan.facade.common.FacadeSupport
import darpan.facade.common.SharedConfigAccessSupport
import darpan.facade.common.SharedConfigGrantSupport
import darpan.facade.common.TenantAccessSupport
import darpan.hotwax.oms.OmsRestSourceSupport

import static darpan.common.ValueSupport.normalize

String configId = normalize(omsRestSourceConfigId)
String activeTenantUserGroupId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
deleted = false

if (!configId) {
    ec.message.addError("HotWax OMS REST source config ID is required.")
}

def config = null
if (!ec.message.hasError()) {
    config = ec.entity.find("darpan.hotwax.HotWaxOmsRestSourceConfig")
        .condition("omsRestSourceConfigId", configId)
        .disableAuthz()
        .useCache(false)
        .one()

    boolean isOwner = config != null && TenantAccessSupport.canAccessTenantRecord(ec, config)

    try {
        // Only an owned row is ever forwarded here (or none) — the peer-vs-stranger split below
        // owns the ownership-mismatch decision for delete, so this call is left doing exactly what
        // it always did (tenant-required, read-only) and never leaks via its own mismatch message.
        OmsRestSourceSupport.requireWritableTenantConfig(
            isOwner ? [companyUserGroupId: config.companyUserGroupId] : null,
            activeTenantUserGroupId,
            TenantAccessSupport.hasActiveTenantWriteAccess(ec)
        )
    } catch (IllegalArgumentException e) {
        ec.message.addError(e.message)
    }

    if (!ec.message.hasError() && !isOwner) {
        if (config != null && SharedConfigAccessSupport.canActiveTenantUseConfig(ec,
                SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS, config)) {
            // A peer may edit a shared config but must not delete the row its peers depend on.
            // configId is polymorphic with no DB FK, so nothing would cascade the grants either.
            ec.message.addError("OMS source config '${configId}' is shared from another " +
                    "tenant. Stop sharing it instead of deleting it.")
        } else {
            // DAR-BE-005 oracle collapse: a nonexistent config and one this tenant has no
            // standing on (not owner, not a peer) must be indistinguishable to the caller — the
            // pre-Task-7 behavior threw a distinguishing "not available in your active tenant"
            // message for the foreign-owned case here, which let any authenticated caller probe
            // arbitrary ids for existence.
            ec.message.addError("HotWax OMS REST source config '${configId}' was not found.")
        }
    }

    // Task 9: even the owner cannot delete a config that still has active grants. configId is
    // polymorphic with no DB FK, so a delete cascades nothing and every peer tenant's automation
    // would break at run time with a confusing "not found" instead of failing loudly here. Runs
    // AFTER the standing check above (isOwner only) so a peer/stranger denial is never displaced
    // by this message.
    if (!ec.message.hasError() && isOwner &&
            SharedConfigGrantSupport.hasActiveGrants(ec,
                    SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS, configId)) {
        ec.message.addError("OMS source config '${configId}' is shared with other tenants. " +
                "Stop sharing it with every tenant before deleting it.")
    }
}

if (!ec.message.hasError()) {
    ec.service.sync()
        .name("delete#darpan.hotwax.HotWaxOmsRestSourceConfig")
        .parameters([omsRestSourceConfigId: configId])
        .disableAuthz()
        .call()
    deleted = true
    deletedOmsRestSourceConfigId = configId
    ec.message.addMessage("Deleted HotWax OMS REST source config ${configId}.")
}

Map envelope = FacadeSupport.envelope(ec)
ok = envelope.ok
messages = envelope.messages
errors = envelope.errors
