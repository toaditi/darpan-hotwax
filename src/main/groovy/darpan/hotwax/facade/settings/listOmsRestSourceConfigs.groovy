import darpan.facade.common.FacadeSupport
import darpan.facade.common.PaginationSupport
import darpan.facade.common.SharedConfigAccessSupport
import darpan.facade.common.TenantAccessSupport
import darpan.hotwax.oms.OmsRestSourceSupport

import static darpan.common.ValueSupport.boundedInt
import static darpan.common.ValueSupport.normalize
import static darpan.common.ValueSupport.normalizeInt

int page = Math.max(0, normalizeInt(pageIndex, 0))
int size = boundedInt(pageSize, 20, 1, 200)
String activeTenantUserGroupId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)

List<Map<String, Object>> rows = []
if (!activeTenantUserGroupId) {
    ec.message.addError("An active tenant is required to list OMS REST source configs.")
} else {
    // DAR-BE-005: owned rows plus rows shared to this tenant. The bare disableAuthz() this replaces
    // is gone; listAccessibleConfigRows routes through TenantScopedFinder on both halves.
    List configs = SharedConfigAccessSupport.listAccessibleConfigRows(ec,
            SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS)
    rows = configs.collect { cfg ->
        OmsRestSourceSupport.safeConfigMap(cfg) +
                [isShared: cfg.companyUserGroupId != activeTenantUserGroupId]
    }
}

String search = normalize(query)?.toLowerCase()
List<Map<String, Object>> filtered = search ? rows.findAll { row ->
    [row.omsRestSourceConfigId, row.description, row.baseUrl, row.ordersPath, row.timeZone, row.authType].any {
        it?.toString()?.toLowerCase()?.contains(search)
    }
} : rows

int totalCount = filtered.size()
omsRestSourceConfigs = PaginationSupport.pageRows(filtered, page, size)
pagination = PaginationSupport.pagination(page, size, totalCount)

Map envelope = FacadeSupport.envelope(ec)
ok = envelope.ok
messages = envelope.messages
errors = envelope.errors
