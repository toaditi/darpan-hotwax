import darpan.hotwax.oms.OmsRestSourceSupport

ok = false
ordersByExternalId = [:]
errors = []

String configIdValue = omsRestSourceConfigId?.toString()?.trim()
String companyUserGroupIdValue = companyUserGroupId?.toString()?.trim()
if (!configIdValue) { errors = ["OMS REST Source Config ID is required."]; return }

def sourceConfig = ec.entity.find("darpan.hotwax.HotWaxOmsRestSourceConfig")
        .condition("omsRestSourceConfigId", configIdValue)
        .disableAuthz()
        .useCache(false)
        .one()

OmsRestSourceSupport.requireUsableOmsConfig(ec, sourceConfig, configIdValue, companyUserGroupIdValue, "OMS")
if (sourceConfig && (sourceConfig.isActive ?: "Y").toString().equalsIgnoreCase("N")) {
    ec.message.addError("OMS REST source config ${configIdValue} is inactive.")
}
if (ec.message.hasError()) { errors = (ec.message?.getErrors() ?: []) as List; return }

// Default window: 10 years back from tomorrow. Originals can be years older than their exchange
// (live run 2026-07-31: a 400-day default windowed out 18 old originals, turning every one into a
// false EXCHANGE_ORIGINAL_MISSING_IN_OMS row). The externalId filter does the narrowing; the date
// window exists only because the orders API requires the parameters.
long thruMillis = (windowEndMillis instanceof Number) ? ((Number) windowEndMillis).longValue()
        : System.currentTimeMillis() + 86400000L
long fromMillis = (windowStartMillis instanceof Number) ? ((Number) windowStartMillis).longValue()
        : thruMillis - 3650L * 86400000L

Map result = OmsRestSourceSupport.lookupOrdersByExternalId(sourceConfig, (List) externalIds, fromMillis, thruMillis)
ok = result.ok
ordersByExternalId = result.ordersByExternalId
errors = result.errors
