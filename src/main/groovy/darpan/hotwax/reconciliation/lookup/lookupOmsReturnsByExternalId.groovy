import darpan.hotwax.oms.OmsRestSourceSupport
import darpan.hotwax.oms.OmsReturnsSourceSupport

ok = false
foundIds = []
missingIds = []
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

Map result = OmsReturnsSourceSupport.lookupReturnsByExternalId(sourceConfig, (List) externalIds)

ok = result.ok
foundIds = result.foundIds
missingIds = result.missingIds
errors = result.errors
