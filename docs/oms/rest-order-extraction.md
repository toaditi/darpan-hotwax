# HotWax OMS REST Order Extraction

Component: `darpan-hotwax`

The component owns HotWax/OMS integration artifacts that do not belong in the core `darpan` component. The first contract is tenant-scoped order extraction for reconciliation automation.

## Source Config

Entity: `darpan.hotwax.HotWaxOmsRestSourceConfig`

Facade services:

- `facade.HotWaxOmsFacadeServices.list#HotWaxOmsRestSourceConfigs`
- `facade.HotWaxOmsFacadeServices.save#HotWaxOmsRestSourceConfig`
- `facade.HotWaxOmsFacadeServices.delete#HotWaxOmsRestSourceConfig`

Delete is allow-remote and authenticated, and like save it requires active-tenant write access and verifies the stored config's owner before removing it.

Configs are scoped by `companyUserGroupId`. Save operations require active-tenant write access and preserve existing encrypted secrets when blank secret inputs are sent on update. List/save responses return safe metadata only: secret fields are represented as boolean flags and are not returned in clear text. `canReadOrders` controls whether the orders endpoint is advertised to the UI for the selected HotWax config; it defaults to enabled for existing configs.

Required fields:

- `omsRestSourceConfigId`
- `baseUrl`, for example `https://dev-maarg.hotwax.io`. If an OMS base path or full orders endpoint is entered, the extractor avoids duplicating the configured orders path.
- `ordersPath`, default `/rest/s1/oms/orders`
- `timeZone`, default `UTC`, used by setup and source metadata displays for HotWax date-window interpretation
- `canReadOrders`, default `Y`

The dev OMS Swagger document describes the orders API at `/rest/s1/oms/orders`, with `GET /` used to list `OrderHeader` records and Basic or `api_key` header authentication available. Darpan uses that Swagger contract for setup metadata and keeps `/rest/s1/oms/orders` as the default orders path. At runtime the extractor retries the list route with a trailing slash if the first request returns `404`, matching Swagger implementations that require `/rest/s1/oms/orders/`.

Supported auth modes are `NONE`, `BASIC`, `BEARER`, and `API_KEY`. `API_KEY` stores the token in the encrypted API token field and sends it in the Swagger-documented `api_key` header.

## Extractor

Service: `reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsOrders`

The extractor calls:

```text
GET {baseUrl}{ordersPath}?orderDate_from=<startEpochMillis>&orderDate_thru=<endEpochMillis>&pageSize=<n>&pageIndex=<n>
```

`windowStart` and `windowEnd` accept `Timestamp`, `Date`, ISO-8601 text, SQL timestamp text emitted by Moqui service serialization, or epoch milliseconds. Both parameters are always sent as epoch milliseconds.

The extractor paginates order reads before writing the normalized source file. It first uses `pageIndex`/`pageSize`, then falls back to `viewIndex`/`viewSize` for OMS environments that expose that list contract. Pagination stops when the next page is empty, repeats the previous page, or is shorter than the previous page. The default requested page size is 500, with a high safety ceiling to avoid runaway loops while still allowing month-scale reconciliation runs.

Requests advertise `Accept-Encoding: gzip` and decode compressed responses, which multiplies effective throughput on long-haul connections (the OMS itself needs no change — this is standard HTTP content negotiation). Once the pagination strategy is committed, remaining pages are fetched through a small concurrent window (`ordersFetchConcurrency`, default 2, capped at 4) while output is written strictly in page order. Worker threads hand back pre-filtered, pre-serialized page text rather than parsed record graphs, so the window costs roughly one serialized page of memory per slot — sized for resource-constrained production hosts. A stop condition (empty/repeated/shorter page) may leave up to window-minus-one speculative page requests already issued; their responses are discarded.

When the caller passes `keepRecordFields` (the SourceSystemConnector registry declares the parameter and base field set; dispatch adds the rule set's join-key fields), each order is projected down to those top-level fields after the reconciliation filter and before writing. Full ~14 KB order documents become a few hundred bytes each — a 99k-order window writes tens of MB instead of ~1.4 GB, which also keeps the Spark compare read (unsplittable multiLine JSON) inside constrained heaps. Projection is skipped automatically for rule sets that define field-comparison rules until rule-field extraction is wired into the keep-list.

Each fetched page is filtered and appended to the output file on disk before the next page is requested; the extractor never holds more than roughly one page of orders in memory, so month-scale windows do not scale heap usage. The extractor keeps only HotWax orders with `orderTypeId` equal to `SALES_ORDER` and excludes orders that contain an order item association with `orderItemAssocTypeId` equal to `EXCHANGE`. Non-sales orders and exchange orders are not written to the normalized source file and are therefore not compared against Shopify orders. Output metadata includes `filters.excludedNonSalesOrderCount` and `filters.excludedExchangeOrderCount` so the run can distinguish fetched HotWax records from comparison-eligible records. Excluded exchange orders are additionally captured into a sidecar `<extract-name>.exchange-manifest.json` file (`{manifest, truncated, sourceFileName}`, entries are ids/amounts only — `omsOrderId`, `externalId`, `orderName`, `toOrderId`, `grandTotal`, `orderDate`, `statusId` — capped at 500 entries), which the exchange pair verify stage consumes to check exchange orders against their linked original order rather than silently dropping them. `filters.exchangeManifestTruncated` marks windows where the cap was hit.

### Reconciliation Orders endpoint (opt-in)

Service: `reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsReconciliationOrders`
Connector row: `OMS_RECON_ORDERS` / `expectedSourceConfigType=HOTWAX_OMS_REST_RECON`

A second, **additive** OMS orders connector served by a purpose-built endpoint that does server-side
what the extractor above does client-side. The legacy connector is unchanged and remains the default;
a rule set adopts this one by pointing its source at `HOTWAX_OMS_REST_RECON`, against the **same**
`HotWaxOmsRestSourceConfig` row (same credentials, same `baseUrl`).

```text
GET {baseUrl}/rest/s1/oms/reconciliationOrders?orderDateFrom=<startEpochMillis>&orderDateThru=<endEpochMillis>&pageSize=<n>&pageIndex=<n>
```

The path is **fixed by the extractor**, not read from the config's `ordersPath` column — that column
holds the legacy `/rest/s1/oms/orders` on every existing tenant config, so honouring it here would
send recon-shaped requests to the legacy endpoint.

Five differences from the legacy path, all of them inside `OmsRestSourceSupport`; the service
contract, the on-disk `{records, metadata}` file and the sidecar shape are identical, so the
automation edge and the Spark compare stage are unchanged.

| | Legacy `/orders` | `/reconciliationOrders` |
|---|---|---|
| Window params | `orderDate_from` / `orderDate_thru` | `orderDateFrom` / `orderDateThru`, half-open `[from, thru)` |
| Sales-order filter | client-side, on `orderTypeId` | server-side; the client filter is skipped (`orderTypeId` is not in the projection, so re-running it would discard every record) |
| Exchange orders | excluded client-side by scanning order-item associations | delivered inline flagged `isExchange`; the client drops them and builds the manifest from inline `originalOrderId` / `originalExternalId` — **no pair-lookup round-trips** |
| Termination | inferred from empty / repeated / shorter pages, plus a 50-record truncation probe | `hasMore`; the probe and the strategy fallback are both skipped |
| Projection | client-side via `keepRecordFields` | server-side, fixed set |

Response counts are surfaced in `requestMetadata.filters`: the server's
`excludedNonSalesOrderCount` replaces the (structurally zero) client count under the same key,
`ordersCount` appears as `serverReportedOrdersCount`, and `missingExternalIdCount` — orders the
server dropped for an empty join key, which has no legacy equivalent — is reported alongside them.
`excludedExchangeOrderCount` stays the client's own count, because this client still does that drop.

An over-maximum `pageSize` is **rejected**, never silently capped: the extract fails with the
server's error code and stated limit rather than working around it.

**Gating — verify before enabling for a rule set.** The endpoint returns a fixed field set
(`orderId`, `externalId`, `orderName`, `grandTotal`, `orderDate`, `statusId`). Confirm the rule set's
join key and every field-comparison field fall inside it; anything wider must stay on
`extract#HotWaxOmsOrders`. The same limit narrows the rules-board pills: `salesChannelEnumId` and
`productStoreId` are offered on the legacy connector only, because an exclusion rule naming a field
the endpoint never returns would validate, persist, and then exclude nothing.

Configured exclusions (below) still run **client-side** on this connector — the endpoint has no
knowledge of tenant exclusion rules.

> **Status.** Shape verified against fixtures only. As of 2026-08-11 no live response from this
> endpoint has been captured, and count/diff parity against the legacy connector over a frozen
> window has not been run.

### Configurable record exclusion

Beyond the two built-in filters above, a rule set can configure its own per-field exclusion rules on either source's extraction. These are ordinary tenant-configured rules — a field to test and a set of values that disqualify a record — layered on top of the built-in `SALES_ORDER`/`EXCHANGE` filtering rather than replacing it.

`extract#HotWaxOmsOrders` accepts an optional `sourceFilters` parameter: a list of rule Maps, each with `sequenceNum` (Integer), `fieldExpression`, `operator`, and `filterValues` (comma-separated). The parsing and matching semantics live in the connector-agnostic `darpan.reconciliation.source.SourceFilterSupport` (component `darpan`), which `OmsRestSourceSupport` calls into so configured and built-in exclusions behave identically:

- **Field names** are trimmed and matched **case-sensitively** against each record's top-level keys.
- **Values** are trimmed and matched **case-insensitively** against the field's value.
- A record that **lacks the configured field is kept** — an exclusion rule can only remove a record for carrying a matching value, never for missing one.

`SourceFilterSupport.parseRules` validates and normalizes the raw rule list once, before extraction starts (`extractOrdersInternal`, `OmsRestSourceSupport.groovy`), ahead of building request headers or issuing any HTTP call. A malformed rule (no field, no values, an unsupported operator, or more than `MAX_RULES_PER_SOURCE`/`MAX_VALUES_PER_RULE`) is recorded as a pre-flight error and the extraction returns immediately with no request ever sent — never a failure discovered mid-window on some later page.

**Rejection order matters.** `filterComparableOrderRecords` evaluates each record against exactly three rejection branches, in this fixed order:

1. Not `orderTypeId == SALES_ORDER` → counted in `excludedNonSalesOrderCount`.
2. Carries an `EXCHANGE` order-item association → counted in `excludedExchangeOrderCount` (and captured into the exchange manifest).
3. Matches a configured exclusion rule → counted per-rule.

The two built-in branches are checked first, and a record is attributed to exactly one bucket — the first branch it matches. This means a record that would be caught by both a built-in filter and a configured rule is counted only in the built-in bucket, and `excludedNonSalesOrderCount`/`excludedExchangeOrderCount` never shift when a tenant adds or changes a configured exclusion.

**Filtering runs before projection.** A page is filtered (`filterComparableOrderRecords`) before the `keepRecordFields` projection trims it (`prepareOrdersPage`), because the `EXCHANGE`-association scan needs the full order document. A practical consequence: a configured exclusion can target a field such as `salesChannelEnumId` or `productStoreId` even though neither is part of the connector's declared keep-field set — filtering sees the untrimmed record regardless of what the eventual output projection keeps.

**Which fields the rules board offers.** The exclusion editor has no free-text field entry: a field is excludable only if it appears in the connector's pill list, `AutomationFacadeSupport.HOTWAX_OMS_ORDER_FIELD_OPTIONS` (component `darpan`). That list is `keepFieldsBase` plus the two exclusion-only fields above. Because a record that *lacks* the configured field is kept, a pill naming a field the order document does not actually carry would persist a rule that matches nothing, excludes nothing, and reports no error — so every entry must name a real top-level key on the OMS order document (`org.apache.ofbiz.order.order.OrderHeader`). Nested paths do not work either: `SourceFilterSupport` reduces a stored expression via `CompareIdExpressionSupport.topLevelRecordField`, which keeps only the first segment after `[*]`.

Matched exclusions are reported in `requestMetadata.filters.configuredExclusions`, one entry per configured rule:

```json
{
  "sequenceNum": 1,
  "fieldExpression": "salesChannelEnumId",
  "operator": "EXCLUDE_IN",
  "values": ["POS_SALES_CHANNEL"],
  "excludedCount": 0
}
```

Every configured rule appears here, including a rule that matched nothing in the window — `excludedCount` is simply `0` in that case, rather than the rule disappearing from the metadata (a missing entry would read as "not applied"). Conversely, `configuredExclusions` is **absent entirely**, not an empty list, when the source has no configured exclusion rules — `sourceFilters` empty/omitted means fully backward-compatible metadata.

**Exclusions apply to the window extract only, not to pair lookups.** `lookup#HotWaxOmsOrdersByExternalId` (`lookupOrdersByExternalId`) resolves specific orders by ID and applies no exclusion rules — neither the built-in `SALES_ORDER`/`EXCHANGE` filters nor configured `sourceFilters`. A `STAGE_VERIFY` point lookup can therefore surface an order the window extract deliberately dropped. This is pre-existing behavior shared with the built-in filters, not something configured exclusions introduced.

Excluded counts — both the built-in `excludedNonSalesOrderCount`/`excludedExchangeOrderCount` and the per-rule `configuredExclusions[].excludedCount` — are diagnostic run metadata only. They are deliberately **not surfaced in the UI**; the rules board and rule set manager display the configured field/values themselves (what will be excluded), never how many records a run actually excluded.

The extractor streams into a `.partial` work file in the run folder and moves it to its final name only after the whole window succeeds, so a mid-window failure never leaves a partial extract where reconciliation could read it.

The output is normalized compact JSON. `records` is written first (pages are appended as they arrive) and `metadata` last, once counts and pagination are known; consumers read the file with JSON parsers (Spark multiLine JSON, JSONPath), so object key order is not part of the contract:

```json
{
  "records": [],
  "metadata": {
    "sourceType": "HOTWAX_OMS_REST_ORDERS",
    "omsRestSourceConfigId": "KREWE_OMS",
    "ordersPath": "/rest/s1/oms/orders",
    "queryParams": {
      "orderDate_from": 1777573800000,
      "orderDate_thru": 1777577400000
    },
    "pagination": {
      "pageSize": 500,
      "pageCount": 1,
      "strategy": "pageIndexPageSize",
      "totalFetched": 2,
      "truncated": false
    },
    "filters": {
      "requiredOrderTypeId": "SALES_ORDER",
      "excludedNonSalesOrderCount": 0,
      "excludedOrderItemAssocTypeIds": ["EXCHANGE"],
      "excludedExchangeOrderCount": 0
    },
    "extractedRecordCount": 2
  }
}
```

The `metadata` block above is illustrative; the extractor always also emits the request shape it derived (`method`, `baseUrl`, `authType`, `timeZone`, `headerNames`, `statusCode`, `attemptCount`, and `windowStart/EndEpochMillis`). Credentials and authorization header values are never included — only header names.

The default output folder is `runtime://datamanager/reconciliation-runs/{automationExecutionId}/{timestamp}/`. When `automationExecutionId` is omitted, the config id is used as the run folder token.

The service returns `fileLocation`, `fileName`, `recordCount`, `requestMetadata`, `warnings`, and `errors`. Request metadata excludes credentials and authorization headers.

## Groovy Justification

Service XML owns the public contracts for this component. Groovy is retained only where XML actions would either hide non-trivial branching inside inline expressions or duplicate reusable integration logic.

- `src/main/groovy/darpan/hotwax/oms/OmsRestSourceSupport.groovy`: retained for HTTP execution, auth-header construction, URL/path overlap handling, query encoding, pagination fallback across documented OMS list conventions, date parsing, safe metadata shaping, JSON parsing, and sales/exchange-order filtering. This is integration and transformation logic, not service orchestration.
- `src/main/groovy/darpan/hotwax/reconciliation/automation/extractOmsOrders.groovy`: retained as the service edge that combines tenant-safe config access, extractor invocation, data-manager path resolution, safe file naming, and output writing. Keeping this in XML would push the same branching into dense action expressions while still depending on the Groovy extractor.
- `src/main/groovy/darpan/hotwax/facade/settings/saveOmsRestSourceConfig.groovy`: retained for validation that depends on existing encrypted secret state, auth-mode-specific requirements, timezone validation, outbound-URL (SSRF) policy checks, headers-JSON validation (rejecting invalid or non-object JSON, oversized header values, and control-character / header-smuggling attempts), tenant writability checks, secret preservation on blank updates, and safe response shaping.
- `src/main/groovy/darpan/hotwax/facade/settings/listOmsRestSourceConfigs.groovy`: retained for safe-row projection, credential redaction, tenant-scoped filtering, case-insensitive multi-field search, and bounded pagination. This keeps the XML service definition declarative and avoids repeating redaction logic in XML actions.
- `src/main/groovy/darpan/hotwax/facade/settings/deleteOmsRestSourceConfig.groovy`: retained because delete is not a pure entity delete; it resolves the active tenant, verifies write access against the stored owner, returns the shared facade envelope, and emits the deletion result. Converting it to XML would duplicate tenant-safety checks already centralized in support code.

## Automation Integration

The core automation executor can call this extractor from an `AUT_IN_API_RANGE` automation source when the source row includes extractor metadata.

Required source-row fields:

- `sourceTypeEnumId=AUT_SRC_API`
- `systemEnumId` matching the saved-run file side, usually `OMS`
- `safeMetadataJson.extractServiceName=reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsOrders`
- `safeMetadataJson.parameters.omsRestSourceConfigId=<config id>`
- `dateFromParameterName=windowStart`
- `dateToParameterName=windowEnd`

Example source metadata:

```json
{
  "extractServiceName": "reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsOrders",
  "parameters": {
    "omsRestSourceConfigId": "GORJANA_OMS"
  }
}
```

The extractor writes normalized JSON to the data-manager run folder and returns the file location/counts needed by `reconciliation.ReconciliationAutomationServices.execute#Automation`. Keep config secrets in encrypted fields only; the extractor response and metadata should include header names but not header values or credentials.

`sourceFilters` is never part of `safeMetadataJson` above. On the scheduled path, `AutomationExecutionSupport.callConfiguredSourceExtractor` (component `darpan`) loads the automation's own `ReconciliationAutomationSourceFilter` rows for the relevant `fileSide` and passes them as `sourceFilters` when it invokes this extractor. Those rows are a frozen snapshot copied from the rule set's `RuleSetCompareSourceFilter` rows once, at automation creation time — the automation wizard has no exclusion UI of its own, so an automation that carries exclusions got them by seeding, not by an operator setting them in the wizard. Later edits to the rule set's exclusions do not reach an already-created automation.
