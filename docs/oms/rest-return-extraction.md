# HotWax OMS REST Return Extraction

Component: `darpan-hotwax` (OMS side), with `shopify-darpan` (Shopify side) and `darpan`
(connector registry + match rule) as the other two legs of this feature (DAR-BE-018).

New capability. Darpan did not reconcile returns before this — there is no legacy client-side
path to preserve or migrate, unlike orders (`rest-order-extraction.md`). This document covers
all three legs because they ship together and their verification state is shared: a claim about
one leg's test coverage says nothing about the other two.

## Source Config

Returns reuse the order extractor's config entity, `darpan.hotwax.HotWaxOmsRestSourceConfig` —
same `baseUrl`, same credentials, same auth modes (`NONE`, `BASIC`, `BEARER`, `API_KEY`). There is
no `canReadReturns` flag analogous to `canReadOrders`; a config usable for orders is usable for
returns extraction with no separate opt-in on the config row itself.

## Extractor

Service: `reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsReturns`
(`darpan-hotwax/service/reconciliation/HotWaxOmsExtractionServices.xml:172-245`)
Support class: `darpan.hotwax.oms.OmsReturnsSourceSupport`
Edge script: `darpan/hotwax/reconciliation/automation/extractOmsReturns.groovy`

```text
GET {baseUrl}/rest/s1/oms/reconciliationReturns?returnDateFrom=<startEpochMillis>&returnDateThru=<endEpochMillis>&pageIndex=<n>&pageSize=<n>
```

The path is **pinned by the extractor** (`DEFAULT_RETURNS_PATH`,
`OmsReturnsSourceSupport.groovy:26`), not read from the config's `ordersPath` column — same
pattern as the `/reconciliationOrders` endpoint. `buildReturnsUrl`
(`OmsReturnsSourceSupport.groovy:224-233`) appends the window and paging params; header/auth
construction and the actual HTTP call are reused from `OmsRestSourceSupport`, not reimplemented
(`OmsReturnsSourceSupport.groovy:36-49, 104-109, 139`).

**Window.** `returnDateFrom`/`returnDateThru` are half-open `[from, thru)` on the OMS
`ReturnHeader.entryDate` — the return's own creation timestamp, not the linked order's date (RQ-22
in the requirements doc). This is deliberate: a live-probed gorjana exchange showed the order
placed 07-16, the return initiated 07-23, and the warehouse processing it 07-28, so windowing on
the order date would put a return outside every window its order appears in.

**Termination.** The endpoint reports `hasMore` directly
(`OmsReturnsSourceSupport.groovy:130,179`), so none of the legacy orders-path heuristics apply
here — no shrinking-page probe, no repeated-page detection, no `OMS_DEFAULT_SERVER_PAGE_SIZE`
workaround. The loop stops as soon as `hasMore` is `false`, capped defensively at
`MAX_RETURNS_PAGE_COUNT` (20,000 pages) as a runaway guard, not an expected ceiling.

**Response key.** Records arrive under `body.returns` (not `records`, not `returnHeaders`) —
`OmsReturnsSourceSupport.groovy:154`. `body.returnsCount` and `body.excludedNoShopifyRefCount`
are read from the same top-level response object.

**Credentials.** Config parsing uses `OmsRestSourceSupport.toPlainMap`, not the UI-safe
`safeConfigMap` — the latter redacts auth secrets into `hasUsername`/`hasPassword`/`hasApiToken`
booleans for listing responses and would silently strip the real token `buildHeaders` needs
(`OmsReturnsSourceSupport.groovy:84-91`, mirroring the same documented distinction in
`OmsRestSourceSupport.lookupOrdersByExternalId`). As with orders, the extractor's own output never
includes credentials or authorization header values — only header names.

### Nested `items[]` contract

Return line items arrive nested inside their header as `items[]` (RQ-21 in the requirements
doc) and are written through nested, never flattened. `projectRecord`
(`OmsReturnsSourceSupport.groovy:239-248`) always keeps `items` regardless of the caller's
`keepRecordFields` list — `keep.add("items")` runs unconditionally before the projection filter —
so a projection naming only header fields cannot strip line data. Phase 2's composite key
(`⟨header key⟩ ␟ ⟨SKU⟩`) addresses the lines by JSON path against this nested shape; phase 1 does
not read into `items[]` at all.

### Configurable record exclusion

Unlike orders, there is no built-in `SALES_ORDER`/`EXCHANGE`-style client-side filter for returns.
The endpoint itself excludes returns with no Shopify reference **server-side**
(`excludedNoShopifyRefCount`); everything Darpan configures on top of that is a **client-side**
per-field exclusion rule, run through the same connector-agnostic
`darpan.reconciliation.source.SourceFilterSupport` (component `darpan`) that orders use
(`OmsReturnsSourceSupport.groovy:163-168`). The expected rule excludes returns whose
`returnChannelEnumId` never has a Shopify counterpart.

Semantics match the orders contract exactly:

- **Field names** are matched case-sensitively against top-level keys; **values** case-insensitively.
- A record that **lacks** the configured field is **kept** — a rule removes only on a matching
  value, never on a missing one (`OmsReturnsExtractTests.groovy`,
  `keepsReturnsThatLackTheConfiguredFieldEntirely`).
- `SourceFilterSupport.parseRules` runs before any HTTP call (`OmsReturnsSourceSupport.groovy:114-119`);
  a malformed rule fails pre-flight with no request ever sent.

**Which fields are offered.** The rules-board pill list is
`AutomationFacadeSupport.HOTWAX_OMS_RETURN_FIELD_OPTIONS`
(`darpan/src/main/groovy/darpan/facade/reconciliation/AutomationFacadeSupport.groovy:136-145`):
`returnId`, `externalId`, `orderExternalId`, `statusId`, `entryDate`, `returnTotal`,
`currencyUomId`, `returnChannelEnumId`. `returnChannelEnumId` is offered here even though its
orders analog `salesChannelEnumId` is **not** offered on the `/reconciliationOrders` connector —
the difference is that the returns endpoint's server-side projection includes the channel field,
so a rule drawn on it actually sees a value; the orders recon endpoint's projection does not
include `salesChannelEnumId`, so a rule there would validate, persist, and exclude nothing. `items[]`
is deliberately absent from the pill list — it is a nested array, not a top-level scalar key,
and `SourceFilterSupport` matches only top-level keys.

`fieldOptionsForSystem` dispatches on `systemEnumId`
(`AutomationFacadeSupport.groovy:1414-1421`) and returns `null` for anything it does not
recognize — a new systemEnumId that falls through hides the exclusion control silently, with no
error. `OMS_RETURNS` has its own branch; a test guards it
(`darpan/src/test/groovy/darpan/facade/reconciliation/*AutomationFacadeSupport*`).

The `OMS_RETURNS` connector row declares `filterParameterName="sourceFilters"`
(`darpan/data/SourceSystemConnectorSeedData.xml:172`) — **required**, not optional, because
exclusion dispatch keys on this attribute; without it the rules board would hide the exclusion
control even though the extractor supports it.

### Count semantics (mirrors the orders §9.5 conventions)

Two exclusion counts exist and describe **disjoint populations by construction**, not by branch
ordering. `excludedNoShopifyRefCount` is **server-side** — those returns never reach Darpan's
filter chain at all. The configured `returnChannelEnumId` (or any other configured) exclusion is
**client-side**, run only on the population that survives the server filter. Because the
populations cannot overlap, double-counting is structurally impossible — a **stronger** guarantee
than orders, where the same non-overlap depends on `filterComparableOrderRecords` evaluating its
three rejection branches in a fixed order (`rest-order-extraction.md:118-124`).

```json
{
  "records": [],
  "metadata": {
    "filters": {
      "excludedNoShopifyRefCount": 4,
      "serverReportedReturnsCount": 7,
      "configuredExclusions": [
        {
          "sequenceNum": 1,
          "fieldExpression": "returnChannelEnumId",
          "operator": "EXCLUDE_IN",
          "values": ["POS_RETURN_CHANNEL"],
          "excludedCount": 1
        }
      ]
    }
  }
}
```

(`OmsReturnsSourceSupport.buildMetadata`, `OmsReturnsSourceSupport.groovy:250-273`.)

- Every configured rule appears, including one that matched nothing in the window —
  `excludedCount` is `0` rather than the rule disappearing; a missing entry would read as "not
  applied" (`OmsReturnsExtractTests.groovy`,
  `reportsAConfiguredRuleThatMatchedNothingWithZeroRatherThanOmittingIt`).
- `configuredExclusions` is **absent entirely**, not `[]`, when the source has no configured
  exclusion rules (`omitsConfiguredExclusionsEntirelyWhenNoRulesAreConfigured`) — backward-compatible
  metadata, same as orders.
- All counts here — `excludedNoShopifyRefCount`, `serverReportedReturnsCount`, and every
  `configuredExclusions[].excludedCount` — are **diagnostic run metadata only**, deliberately
  **not surfaced in the UI**. The rules board shows the configured field/values (what will be
  excluded), never how many records a run actually excluded.

### Failure handling

The extractor streams to a `.partial` work file and never leaves a well-formed-looking but
silently truncated document on a mid-window failure: an HTTP error or JSON-parse error triggers
`sink.abort()` (writes no closing bracket/metadata), and the file-mode caller deletes the partial
file (`OmsReturnsSourceSupport.groovy:182-206`; guarded by
`extractReturnsToFileLeavesNoFileOnMidPaginationHttpFailure` /
`...JsonParseFailure` in the test file). `extractReturnsToFile`'s result contract omits the
`records` key on every path, success or failure — matching `extractOrdersToFile`.

## Groovy Justification

- `src/main/groovy/darpan/hotwax/oms/OmsReturnsSourceSupport.groovy`: a separate class from
  `OmsRestSourceSupport` on purpose — that file already carries ~1,900 lines of sales-order
  contract (`SALES_ORDER`/`EXCHANGE` filtering, exchange manifest, pagination-strategy fallbacks)
  that returns do not share. HTTP execution, auth headers, URL building, and window parsing are
  reused from it as statics rather than re-implemented.
- `src/main/groovy/darpan/hotwax/reconciliation/automation/extractOmsReturns.groovy`: the service
  edge, step for step the same shape as `extractOmsReconciliationOrders.groovy` — same tenant
  gate, same output location, same atomic `.partial` move, same progress heartbeat — differing
  only in calling the returns support class and writing no exchange-manifest sidecar (returns
  carry no exchange association).

## Automation Integration

Connector row: `OMS_RETURNS` (`darpan/data/SourceSystemConnectorSeedData.xml:156-174`)

```text
systemEnumId=OMS_RETURNS
extractServiceName=reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsReturns
expectedSourceConfigType=HOTWAX_OMS_REST_RETURNS
configEntityName=darpan.hotwax.HotWaxOmsRestSourceConfig
keepFieldsBase=returnId,externalId,orderExternalId,statusId,entryDate,returnTotal,currencyUomId,returnChannelEnumId
filterParameterName=sourceFilters
```

`sendUrlTemplate` documents the endpoint for the registry and remote label; it does **not** route
the extractor, which pins `DEFAULT_RETURNS_PATH` regardless of what this template says
(`SourceSystemConnectorSeedData.xml:147-148`). `remoteId` is shared with the OMS order family
because the credentials and `baseUrl` are the same tenant config row.

## The Shopify side: refund and return id extraction

New connector, no legacy path: `SHOPIFY_RETURN_REFS`
(`darpan/data/SourceSystemConnectorSeedData.xml:180-192`), served by
`reconciliation.ShopifyOrderExtractionServices.extract#ShopifyOrderReturnRefs`
(`shopify-darpan/service/reconciliation/ShopifyOrderExtractionServices.xml`, edge script
`shopify-darpan/src/main/groovy/shopify/reconciliation/automation/extractShopifyReturnRefs.groovy`).
Support class: `shopify.reconciliation.automation.ShopifyReturnRefsSupport`. Catalog source:
`ShopifySourceCatalog.SHOPIFY_ORDER_RETURN_REFS`
(`shopify-darpan/src/main/groovy/shopify/graphql/ShopifySourceCatalog.groovy:121-178`). No
`filterParameterName` — like the canonical Shopify orders connector, it carries no tenant
exclusion rules, so offering the control would be a silent no-op.

**Cursor pagination, not Bulk Operations.** `refunds` and `returns` are GraphQL connections/lists,
and `ShopifyGraphqlQueryBuilder.buildBulkQuery` rejects connection-bearing fields outright — bulk
JSONL would emit their children as separate `__parentId` lines with nothing here to re-nest them.
Cursor pagination returns naturally nested per-order objects, which is exactly the id-set shape the
match rule wants (`ShopifyReturnRefsSupport.groovy:16-24`). `ShopifySourceCatalog`'s
`defaultBulkSelectedFieldPaths` for this source is deliberately an empty list, not absent, so
`copySource()`'s unconditional `new ArrayList(...)` never NPEs
(`ShopifySourceCatalog.groovy:141-148`).

**The live-probed schema shape (2026-08-13, Shopify Admin API `2026-01`, `gorjana-sandbox.myshopify.com`):**

```text
Order.refunds : NON_NULL -> LIST,             args = [first]
Order.returns : NON_NULL -> ReturnConnection, args = [first, after, last, before, reverse, query]
```

The two are **asymmetric** and the extractor's handling reflects it exactly
(`ShopifyReturnRefsSupport.groovy:158-196`, `ShopifySourceCatalog.groovy:160-176`):

- `Order.refunds` is a **plain list** — no `edges`, no `nodes` wrapper, and critically **no
  `pageInfo`**. There is no signal at all for whether the list was truncated at `first`. The
  extractor's only defense is a saturation heuristic: if the returned size is `>= requestedFirst`,
  it warns that the set may be incomplete. This over-warns on an order holding exactly `first`
  refunds; that false positive is treated as far cheaper than a silently truncated id set feeding
  a phantom "missing" diff.
- `Order.returns` is a real `ReturnConnection` with `nodes{}` and a `pageInfo.hasNextPage` that is
  **authoritative** — the connection is a real connection, and truncation is detected exactly, not
  heuristically.

**Anyone "tidying" these into one shape breaks it.** The code carries this warning verbatim at the
point where it would be easiest to "simplify" (`ShopifyReturnRefsSupport.groovy:158-172`).

Ids are emitted **bare** (GID tail stripped): `gid://shopify/Refund/123` → `123`,
`gid://shopify/Return/456` → `456` (`ShopifyReturnRefsSupport.bareId`,
`ShopifyReturnRefsSupport.groovy:33-42`). This mirrors
`CompareDatasetSupport.applyIdNormalizer`'s `SHOPIFY_GID_TAIL` regex
(`darpan/src/main/groovy/darpan/reconciliation/core/CompareDatasetSupport.groovy:23-39`), whose
type segment (`[^/]+`) is a wildcard — a `Refund` GID and a `Return` GID with the same numeric tail
normalize to the same value. This is why the match rule below can compare a bare OMS `externalId`
against either id space with no type tag on either side; it is also why a false match would require
a refund and a return **on the same order** sharing a numeric id, which the match rule's
order-scoping (`orderExternalId`) makes structurally near-impossible, not merely unlikely.

`createdAt` is selected and required downstream, not decorative — `ReturnPresenceVerificationSupport`'s
reverse pass uses it for the grace check; if absent, every Shopify order reads as old and young
refunds are reported missing instead of pending (`ShopifyReturnRefsSupport.groovy:141-146`).

## Match rule: return presence verification

Class: `darpan.facade.reconciliation.ReturnPresenceVerificationSupport`
(`darpan/src/main/groovy/darpan/facade/reconciliation/ReturnPresenceVerificationSupport.groovy`).
Wired into `runSavedRunDiff` as a `STAGE_VERIFY` step
(`darpan/src/main/groovy/darpan/facade/reconciliation/runSavedRunDiff.groovy:636-674`), gated on
resolving both a `SYSTEM_HOTWAX_OMS_RETURNS` side and a `SYSTEM_SHOPIFY_RETURN_REFS` side for the
run — an orders reconciliation never enters this path.

Phase 1 is **presence-only** (did every return sync across Shopify↔OMS), not amount or line
correctness:

- **Forward** (is each OMS return present in Shopify?): match OMS `externalId` against that
  order's Shopify **refund ids** (primary), then its **return ids** (backup, for the permanent
  minority of returns imported while `IN PROGRESS`, whose OMS `externalId` is stamped with the
  Shopify return id and never backfilled to the refund id once one issues). Neither matches →
  missing in Shopify.
- **Reverse** (is each Shopify refund present in OMS?): a refund whose id is no OMS return's
  `externalId` on that order → candidate missing in OMS. **Suppressed** when the order already
  matched forward, because the event is demonstrably captured under the return id — this is a
  **known phase-1 limitation**, not an oversight: the suppression is per-order, not per-event, so
  on an order with more than one return, a genuinely-missing second refund would not be caught
  independently. `verifyReturnPresence` counts these suppressions and discloses them in the audit
  note whenever at least one applies (`ReturnPresenceVerificationSupport.groovy:228-246`).
- **Grace/pending:** a one-sided return younger than `graceHours` (default 3, matching the exchange
  stage) reports as pending, not missing — sized from a measured ~38-minute Shopify→OMS sync skew.
- All matching is **scoped by `orderExternalId`** — never a cross-order comparison.

## Known follow-ups (owed, not fixed here)

1. **Return-presence audit notes are misclassified in chat notifications.**
   `TenantNotificationSupport.partitionAuditNotes`
   (`darpan/src/main/groovy/darpan/reconciliation/notification/TenantNotificationSupport.groovy:368-382`)
   recognizes only `ExchangePairVerificationSupport.AUDIT_NOTE_PREFIX` and
   `MissingDiffVerificationSupport.AUDIT_NOTE_PREFIX`. `ReturnPresenceVerificationSupport`'s own
   `AUDIT_NOTE_PREFIX` constant (`ReturnPresenceVerificationSupport.groovy:31-37`, documented
   in-line as not yet consumed) is not in that list, so every returns-verification run's audit
   note — **including an all-clear run** — falls through to the generic `warnings` bucket instead
   of `auditNotes`, and reads as "WITH ISSUES" in the chat notification regardless of outcome.
2. **Reverse-suppression is per-order, not per-event** (documented above under Match rule) — an
   accepted phase-1 limitation, not a bug, but one that will under-report on multi-return orders
   until the design's §8 typed-field hedge or a Shopify Return→Refund link lands.

## Status

**Unit/fixture-verified (all green, zero failures):**

- `darpan`: 905 tests
- `darpan-hotwax`: 145 tests
- `shopify-darpan`: 90 tests

This proves the extraction, exclusion, metadata, and match-rule *logic* against fixtures. It does
not prove any of it against a live system.

**Live-verified, and ONLY this:** the Shopify GraphQL *schema shape* documented above (`Order.refunds`
as a plain list with no `pageInfo`, `Order.returns` as a `ReturnConnection` with authoritative
`pageInfo.hasNextPage`) was probed against `gorjana-sandbox.myshopify.com` on Admin API `2026-01`
on 2026-08-13, and the `gorjana_uat` connection diagnostics passed (credential readable, shop
reachable, API version supported, orders readable). That is the full extent of live verification
on this feature.

**NOT verified, and owed:**

- **No live OMS `reconciliationReturns` response has ever been captured.** Every OMS fixture in
  this feature — the record shape used by `OmsReturnsExtractTests.groovy` included — is
  transcribed from a prose handoff, not a captured response. `OmsReturnsExtractTests.groovy:13-19`
  states this explicitly: "as of 2026-08-12 no swagger, fixture, or captured live response for
  this endpoint exists in the repo... Treat a mismatch found during live probing as a defect in
  these fixtures, not necessarily in the extractor."
- **No end-to-end returns reconciliation has run against real data.** Count/diff parity between
  the two sides is unproven; the grace-window sizing under real skew and the reverse-suppression
  heuristic on multi-return orders are proven only against hand-built fixtures.
- **`returnChannelEnumId`'s actual value domain is unknown.** The exclusion mechanism ships and is
  tested against a placeholder value (`POS_RETURN_CHANNEL`); which value(s) a tenant would
  actually configure needs the OMS return-channel enum, which is not in this repo.
- **Fixture-verified is not live-verified.** Nothing above should be read as more assurance than a
  green fixture-backed test suite provides. A defect that only manifests against a real response
  shape — a wrong field name, an unexpected null, a paging edge case the fixtures did not
  anticipate — would pass every test listed above and still fail in production.
