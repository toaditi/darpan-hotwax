# OMS Reconciliation Data Requirements — Orders and Returns

| | |
|---|---|
| Date | 2026-08-03 |
| Status | DRAFT — not yet reviewed by the HotWax OMS team. Open questions in §9 are addressed to them. |
| Consumer | Darpan reconciliation (component `darpan-hotwax` is the integration edge) |
| Implementer | HotWax OMS ("maarg") team — their architect owns all design |
| Supersedes | `2026-07-24-oms-recon-export-requirements.md` (same folder). That document specified orders only, and specified an async export-job-plus-file transport. Both decisions are revised here — see "What changed" below. |
| Still current | `rest-order-extraction.md` (same folder) documents the integration in service today and stays accurate until this work lands. |

This is a requirements document. It states WHAT data Darpan needs and the
externally observable contract it depends on. HOW the OMS produces it —
entities, services, query plans, caching — is the OMS architect's design
freedom and is deliberately absent.

**What changed from the 2026-07-24 document**

1. **Scope widened to returns.** That document listed return reconciliation as
   an explicit non-requirement. Darpan is now building it, at both return-header
   and return-line-item level, so returns data is in scope here (Part B).
2. **Transport simplified from async export job to a faster synchronous
   endpoint.** The earlier document decided on create-job / poll / download-file.
   That is withdrawn. The request now is the same request/response shape the OMS
   already serves, made fast by filtering and projecting server-side and by
   fixing pagination (§6). This is a deliberate reversal by the Darpan product
   owner, made to reduce what the OMS team has to build: no job framework, no
   file retention, no expiry semantics. The performance target is unchanged.

Everything else from the superseded document — the join key, the field set, the
filter rules, the exclusion counts, the auth modes — is carried forward intact,
along with its five unanswered questions (§9, OQ-3 through OQ-7).

---

## 1. What Darpan does with this data

Darpan compares two systems' records of the same commercial events and reports
what does not line up. For each reconciliation run it pulls a date window of
records from each side, joins them on a configured key, and reports three
outcomes: present in both, missing on one side, or present on both with a field
that differs.

Two consequences drive every requirement below.

**The join key is not optional.** Darpan can only compare records it can pair.
The pairing is a value-equality join on a key both systems carry. For orders
that key exists and is proven (§4.1). For returns it is the single largest open
question in this document (§9, OQ-8) — if the OMS return record does not carry a
reference to its Shopify counterpart, return-header reconciliation cannot be
built as specified and needs the fallback in §5.1.

**Darpan compares a handful of fields, not whole documents.** The comparison
reads the key plus a small set of business fields. Every other byte the OMS
sends is transferred, parsed, and discarded. That is the whole of the
performance problem in §3.

Reconciliation runs two ways: scheduled automations (unattended, retried on
transient failure, windows split at 28 days) and human-triggered runs where an
operator is watching a progress bar. Both call the same endpoints.

## 2. Reading this document

- **RQ-n** — a requirement. Numbering continues from the superseded document
  where a requirement is carried forward unchanged, so RQ-1 here is RQ-1 there.
  Gaps in the sequence (RQ-5 to RQ-9, RQ-15 to RQ-20) were export-job lifecycle
  requirements — create, poll, download, retention, expiry — withdrawn with the
  transport change. New requirements start at RQ-21.
- **OQ-n** — an open question for the OMS team, likewise continuing.
- Timestamps are epoch milliseconds UTC unless stated otherwise.
- "Shopify side" columns are shown for context only. They describe what Darpan
  reads from Shopify and are **not** requirements on the OMS. They are included
  so the OMS team can see what their data is being matched against.

## 3. The measured problem

Measured on run 100000 (saved run `RS_GORJANA_PROD`; one day-scale window; local
Darpan against a prod-like OMS on EC2 us-east-1; figures supplied by the Darpan
product owner 2026-07-24, not independently re-run since):

| Observation | Value |
|---|---|
| Payload received | 57 MB |
| Comparison-eligible orders in it | 4,154 |
| Per order on the wire | ~14 KB (derived) |
| Extract wall-clock | 272 s (~260 KB/s transfer) |
| Same logical dataset from Shopify | 22.5 s |
| Sequential round-trips | ~84 (derived: 4,154 ÷ 50) |

Four separate causes, each independently fixable:

1. **Silent page cap.** Some OMS deployments cap a page at 50 records however
   large a `pageSize` is requested. Darpan codes around this
   (`OmsRestSourceSupport.groovy:42-44`, `OMS_DEFAULT_SERVER_PAGE_SIZE = 50`) by
   treating a 50-record page as possibly truncated. A window that should have
   been a handful of requests became ~84 sequential ones.
2. **No server-side filtering.** Darpan keeps only `orderTypeId=SALES_ORDER` and
   drops orders carrying an `EXCHANGE` order-item association. The OMS transfers
   orders that are discarded on arrival.
3. **No server-side field projection.** Darpan needs six fields per order —
   `orderId, orderName, externalId, grandTotal, orderDate, statusId`, declared in
   the connector registry as `keepFieldsBase`
   (`darpan/data/SourceSystemConnectorSeedData.xml`). It receives a full ~14 KB
   order document. That is roughly a 50× over-fetch.
4. **Termination is inferred, not told.** The response carries no total count and
   no "more pages" flag, so Darpan stops paginating when a page comes back empty,
   repeated, or shorter than the previous one (`rest-order-extraction.md`,
   Extractor section). This heuristic is why a 50-record page has to trigger a
   speculative probe request, and it is fragile against any result-set drift
   mid-pagination.

Business goal: a reconciliation window costs seconds and megabytes rather than
minutes and tens of megabytes, on both scheduled and human-triggered runs.

## 4. Part A — Orders

### 4.1 Join key

| | OMS field | Shopify field (context only) |
|---|---|---|
| Join key | `externalId` | `id` |

Single-field on both sides. Read from the live production rule set
`RS_GORJANA_PROD` / compare scope `CS_RS_GORJANA_PROD_COMPARE_SCOPE` on
2026-07-24. This is the production join key today, not a proposal.

**RQ-2a (Key always present).** Every order in the response carries a non-empty
`externalId`. An order whose `externalId` is null or empty cannot be reconciled;
if such orders exist in the window they must be reported in the exclusion counts
(RQ-3) rather than delivered with an empty key, which would collide with every
other empty-keyed record on the join.

### 4.2 Required field set

**RQ-2 (Deterministic per-order field set).** Each order record carries these
fields and nothing more:

| Field | Purpose |
|---|---|
| `externalId` | Join key (§4.1) |
| `orderId` | OMS-side traceability; what an operator quotes to OMS support |
| `orderName` | Operator display in the Darpan diff UI |
| `grandTotal` | Diff display; anticipated first amount-comparison rule |
| `orderDate` | Diff display; window verification |
| `statusId` | Diff display; cancellation context |

This is exactly the connector registry's `keepFieldsBase`. Two notes on why the
last three are required even though nothing compares them yet:

- The production rule set currently defines **zero field-comparison rules** —
  reconciliation today is presence-only ("does this order exist in both
  systems"). Verified against the live rule set 2026-07-24.
- These three fields nonetheless populate the diff rows operators read, and are
  the anticipated first field-comparison rules. Shipping them now avoids a
  contract change on the day a tenant adds an amount check.

Item-level order reconciliation is **not** configured for any tenant and is out
of scope. Adding it later is a contract extension, not a hidden requirement.

### 4.3 Server-side filters

**RQ-1 (Comparison-eligible orders only).** The response contains only orders
that are comparison-eligible:

- `orderTypeId = SALES_ORDER`
- excluding any order carrying an order-item association of type `EXCHANGE`

These are the same two rules Darpan applies client-side today
(`OmsRestSourceSupport.filterComparableOrderRecords`). Moving them server-side
is the point: the response should never contain an order Darpan would throw away.

**RQ-3 (Exclusion visibility).** The response reports the delivered record count
and, separately, how many orders each rule excluded. Darpan records these today
as `excludedNonSalesOrderCount` and `excludedExchangeOrderCount` and surfaces
them in run metadata; the new contract must preserve that visibility so a run can
still distinguish "matched the window" from "was comparison-eligible". Without
it, a filtering bug on either side is invisible.

**RQ-3a (Excluded exchange identities).** For orders excluded by the EXCHANGE
rule, Darpan additionally needs the identity of each excluded order, not just the
count: `orderId`, `externalId`, `orderName`, the association's `toOrderId`,
`grandTotal`, `orderDate`, `statusId`. Darpan's exchange-pair verification stage
consumes exactly this set (captured today as a sidecar manifest at extraction,
capped at 500 entries per window). Delivering it alongside the filtered response
— in a separate block, not mixed into `records` — lets that stage keep working
after the filtering moves server-side.

### 4.4 Window semantics

**RQ-4 (Window).** The window is supplied as two epoch-millisecond UTC bounds
with **half-open semantics `[from, thru)`**: an order whose filter timestamp
equals `thru` is not included. Filtering is on the same order timestamp the
current endpoint filters on (`orderDate_from` / `orderDate_thru`).

Half-open matters because Darpan splits long windows into consecutive chunks. If
both bounds are inclusive, every chunk boundary double-counts the orders sitting
exactly on it.

**OQ-3 (carried forward, still unanswered).** Is the current endpoint's
`orderDate_thru` inclusive or exclusive? Count parity between the old and new
paths cannot be verified without this.

**RQ-10 (Rejection clarity).** Invalid windows — malformed, `from >= thru`, or
exceeding any maximum the OMS enforces — are rejected up front with a
machine-distinguishable, human-actionable error. Never silently truncated,
never silently auto-split: either would corrupt count parity in a way that looks
like a reconciliation discrepancy.

## 5. Part B — Returns

New capability. Darpan does not reconcile returns today, so unlike Part A there
is no measured over-fetch to point at — this section specifies what the data
must contain for the reconciliation to be buildable at all.

The comparison is **two-level**: return headers against each other, and return
line items against each other. Both levels are required.

### 5.1 Join keys

**Header level.**

| | OMS field | Shopify field (context only) |
|---|---|---|
| Join key | the Shopify return reference stored on the OMS return — **existence unconfirmed, see OQ-8** | `Return.id` |

**OQ-8 (blocking).** Does the OMS return record store a reference to the Shopify
`Return` it originated from? This is the single most consequential question in
this document. If yes, name the field and state its format (GID such as
`gid://shopify/Return/123` versus bare numeric id) — Darpan normalizes either,
but must be told which. If no, header-level return reconciliation as specified
here cannot be built, and Darpan falls back to the composite key below for both
levels, accepting that it cannot distinguish two returns raised against the same
order for the same SKU.

**Line level.** A composite key, since no single field identifies a return line
across both systems:

| Position | OMS field | Shopify field (context only) |
|---|---|---|
| 1 | the return's Shopify reference (or, under the OQ-8 fallback, the linked order's `externalId`) | `Return.id` / `Order.id` |
| 2 | the line's SKU | `returnLineItems[].fulfillmentLineItem.lineItem.sku` |

Darpan supports composite keys natively (shipped 2026-07-09); the two positions
are concatenated with a `U+001F` delimiter on both sides.

The Shopify-side paths in the two tables above are drawn from the Admin GraphQL
schema and have **not** been live-probed from Darpan — the only part of Shopify's
`Return` type Darpan has ever queried is the exchange subset
(`returns → exchangeLineItems`). They are shown to give the OMS team a concrete
picture of the counterpart, and need confirming before the Shopify side is built.
Nothing in Part B's OMS requirements depends on them being exact. **OQ-9:** is SKU the
right second position, or does OMS identify a return line by
`orderItemSeqId`/line sequence with SKU only as an attribute? If the latter, the
second position changes to whatever identifier Shopify can also produce — this
must be a value both systems independently carry, not an OMS-internal sequence.

**OQ-9 resolved (2026-08-12):** `sku`. It is the only candidate both systems
independently carry — `returnItemSeqId` is OMS-internal (not something Shopify
has any concept of), and `orderItemExternalId` references an *order* item, not a
return line, so it identifies the wrong object. This closes the question without
needing an OMS answer; see the design doc's gating-verification pass (§9.3 of
`roadmap/handoffs/2026-08-12-DAR-BE-018-returns-identity-design.md`). Two
caveats this resolution does **not** close: the Shopify-side
`returnLineItems[].fulfillmentLineItem.lineItem.sku` path is still unverified
against a live return (only order-line and exchange-line `sku` selections are
proven), and SKU is not guaranteed unique per return line — a duplicate
⟨header, SKU⟩ collision is a phase-2 design decision, not resolved by this
answer.

### 5.2 Required field set — return header

| Field | Purpose | Notes |
|---|---|---|
| OMS return id | OMS-side traceability | Required |
| Shopify return reference | Join key | Subject to OQ-8 |
| Linked order reference (`externalId` of the order being returned against) | Fallback join; operator context; lets Darpan tie a return diff to an order diff | Required regardless of OQ-8's answer |
| Return status | Diff display; lifecycle stage | Required |
| Return created timestamp | Window field (§5.4) | Required |
| Return total amount | The amount comparison | Required |
| Currency | Amount comparison is meaningless without it | Required |

### 5.3 Required field set — return line item

| Field | Purpose |
|---|---|
| Parent return reference | Ties the line to its header |
| SKU | Composite key position 2; the product identity both systems share |
| Returned quantity | The primary line-level comparison |
| Line amount | Amount comparison at line level |
| Return reason | Diff display; operator triage |

Nothing else. As with orders, every additional field is transferred and
discarded.

**RQ-21 (Delivery shape for two levels).** Return lines are delivered nested
inside their header record, not as a separate flat endpoint. Darpan's extractor
writes one record stream per source and its rule engine addresses nested arrays
by JSON path; two endpoints would mean two runs and no guarantee they saw the
same instant.

### 5.4 Window semantics

**RQ-22 (Return window field).** The window filters on the **OMS return record's
own creation timestamp**, half-open `[from, thru)`, epoch milliseconds UTC — the
same semantics as RQ-4. Not the linked order's date.

This is deliberate and evidence-backed. In a live-probed production exchange
(gorjana, 2026-07-30), the order was placed 2026-07-16, the customer initiated
the return 07-23, and the warehouse processed it 07-28. Windowing returns on the
order date would put a return outside every window its order appears in.

The same probe measured the OMS return record being created **~38 minutes** after
the corresponding Shopify `Return`. So the two systems' creation timestamps are
close but not identical, which has one direct consequence:

**RQ-23 (Boundary tolerance).** A return created minutes before a window boundary
in one system and minutes after it in the other will appear in different windows
and read as a false discrepancy on both sides. Darpan handles this on its side
with a grace period during which a return present in only one system is reported
as pending rather than missing (the exchange stage uses 3 hours, sized from the
same measurement). No OMS-side behavior is required — this is recorded so the
OMS team understands why exact count parity between the two systems is not
expected for returns the way it is for orders.

### 5.5 Filters and counts

**RQ-24.** If any category of OMS return is not comparison-eligible against
Shopify — returns not originating from the Shopify channel are the obvious
candidate — the OMS filters them out server-side and reports the excluded count
separately, exactly as RQ-1 and RQ-3 do for orders. **OQ-10:** which categories
are these, and what distinguishes them? Darpan cannot specify this filter without
knowing the OMS return taxonomy.

**OQ-10 is now a decision, not just an open question (2026-08-12).** Phase 1
already ships a client-side answer to the same problem: a `returnChannelEnumId`
exclusion pill on the `OMS_RETURNS` connector (`rest-return-extraction.md`,
"Configurable record exclusion"), reusing the same
`darpan.reconciliation.source.SourceFilterSupport` mechanism orders use. If the
OMS later implements RQ-24's server-side channel filter, that **retires** the
client-side pill rather than layering under it — they are alternative
placements of the identical rule, not two independent filters. Running both
would not double-exclude anything (the two would simply agree), but the
client-side pill would degrade to a permanent no-op filtering an
already-filtered population, exactly the "matched nothing, no error" trap the
pill mechanism otherwise guards against. Whoever answers OQ-10 must also decide
**where the rule lives** — OMS-side (RQ-24) or Darpan-side (the pill) — not
both. See the design doc's §5 ("Tension with RQ-24 — alternative placements,
not layers") for the full analysis.

## 6. Cross-cutting endpoint requirements

This section is the "make it faster" list. Each item traces to a specific,
measured cost in §3.

**RQ-25 (Honour the requested page size).** A requested `pageSize` is either
honoured or rejected with an error stating the maximum. It is never silently
capped. This is the single highest-value fix in this document: the 50-record
silent cap is what turned the reference window into ~84 sequential round-trips.
State the maximum so Darpan can request it directly.

**RQ-26 (Filter server-side).** Per RQ-1 (orders) and RQ-24 (returns). The
response never contains a record the consumer would discard.

**RQ-27 (Project fields server-side).** The response carries the documented field
set (§4.2, §5.2, §5.3) and nothing more. If field selection is easier to
implement as a client-supplied parameter than as a fixed shape, that is
acceptable and arguably better — Darpan already passes a field list internally
(`keepRecordFields`) and would simply forward it. Either design satisfies this
requirement; a full document does not.

**RQ-28 (Keep gzip working).** Responses compress when the client advertises
`Accept-Encoding: gzip`. This works today and needs no OMS change — it is
recorded only so a future endpoint does not regress it. On the long-haul link
where the 260 KB/s was measured, it is a large multiplier.

**RQ-29 (Tell the client when to stop).** The response states either a total
matching record count or an explicit "more results exist" flag. Darpan currently
infers termination from page shape — stopping on an empty, repeated, or shorter
page — which forces speculative probe requests and is fragile if the underlying
result set shifts mid-pagination. This is a cheap change with an outsized effect
on both correctness and round-trip count.

**RQ-30 (Stable pagination).** Within one window, paging is deterministic and
stable: a record is returned exactly once across the page sequence, and the
ordering does not change between pages. Unstable ordering silently drops or
duplicates records, which surfaces as a phantom reconciliation discrepancy — the
worst possible failure mode, because it looks exactly like a real finding.

**RQ-31 (Multi-value point lookup).** The orders endpoint accepts multiple
`externalId` values in one request and returns all matching orders. It honours a
single `externalId` today (live-verified 2026-07-30) and Darpan's exchange
verification exploits that — but one sequential GET per id. At gorjana's measured
~486 exchanges/day that path is the current bottleneck for exchange checking, and
it is capped at 50 pairs per run purely to bound latency. Batching collapses it.
State the maximum number of ids per request.

**RQ-32 (Actionable errors).** Failures return a machine-distinguishable code and
a message an operator can act on without reading OMS server logs. Darpan's
automation executor distinguishes retryable from terminal failures to decide
between retry and dead-letter; a generic 500 forces it to guess.

## 7. Performance targets and acceptance

**RQ-11 (Reference window).** A window equivalent to the measured reference —
4,154 comparison-eligible orders, today 272 s and 57 MB — completes in **well
under 60 s** with a payload on the order of **1–2 MB compressed**, not 57 MB.

**RQ-12 (Month-scale windows).** A 28-day window at reference-tenant volume
completes successfully. 28 days is Darpan's automation split maximum, so this is
the largest window the OMS will be asked for. **OQ-7 (carried forward):** if the
OMS caps window size, state the cap; it must be ≥ 28 days, and over-cap requests
must be rejected with a clear error rather than truncated.

Acceptance criteria:

- **AC-1 (Reference window).** The reference window — the exact dates of run
  100000 / saved run `RS_GORJANA_PROD`, readable from that run's recorded query
  params — returns in ≤ 60 s, ≤ 2 MB compressed, and its delivered record count
  matches the current endpoint's post-filter count exactly. Exclusion counts
  match Darpan's current `excludedNonSalesOrderCount` and
  `excludedExchangeOrderCount` for the same window.
- **AC-2 (Reconciliation parity).** A Darpan run against the new endpoint over a
  frozen window produces an identical diff — same matched, missing, and
  mismatched orders — to a run against the current endpoint over the same window.
- **AC-3 (Page size honoured).** A request for the stated maximum page size
  returns that many records when that many exist, on every deployment.
- **AC-4 (Pagination integrity).** Paging a multi-page window returns each record
  exactly once, and the union across pages equals the stated total count.
- **AC-5 (Month window).** A 28-day window at reference-tenant volume completes.
- **AC-6 (Returns round-trip).** For a known return present in both systems, the
  returns endpoint delivers the header and its lines with a key that joins to the
  Shopify side, and quantities that match.
- **AC-7 (Batch lookup).** A multi-value `externalId` request returns all matching
  orders in one response.

## 8. Auth, tenancy, and non-requirements

**RQ-13 (Auth parity).** The same modes the current integration supports:
`BASIC`, `BEARER`, and `API_KEY` via the Swagger-documented `api_key` header. No
new credential type.

**RQ-14 (Tenant scoping).** A credential reads only the order and return data it
is entitled to today. The returns endpoint is scoped identically to the orders
endpoint — it is new surface area over data that already has an access model, and
must not widen it.

Explicit non-requirements, recorded so nobody builds them by accident:

- No async export job, no file delivery, no retention or expiry semantics. This
  was the 2026-07-24 shape and is withdrawn (see "What changed").
- No general-purpose bulk API. These endpoints serve reconciliation.
- No inventory or shipment reconciliation data.
- No webhook or push notification. Darpan polls.
- No item-level **order** reconciliation. Item level applies to returns only.
- No change to the current `/rest/s1/oms/orders` contract. Darpan migrates by
  adding a connector registry row, so both paths must work against the same
  deployment during migration — AC-2 compares them on the same window.

## 9. Open questions for the OMS team

| # | Question | Feeds | Status |
|---|---|---|---|
| OQ-3 | Is the current endpoint's `orderDate_thru` inclusive or exclusive? | RQ-4, AC-1 | Carried forward from 2026-07-24, unanswered |
| OQ-6 | Is there a concurrency limit on these endpoints per tenant or credential? Darpan's automation can run several tenants' windows in parallel. | RQ-32 | Carried forward, unanswered |
| OQ-7 | Maximum requestable window size? Must be ≥ 28 days; reject-with-error strongly preferred over auto-split. | RQ-12 | Carried forward, unanswered |
| OQ-8 | **Does the OMS return record store a reference to its Shopify `Return`? If so, which field and in what format?** Blocking for return-header reconciliation. | §5.1 | New |
| OQ-9 | How does OMS identify a return line — SKU, or an internal sequence with SKU as an attribute? The composite key's second position must be a value both systems independently carry. | §5.1 | **Resolved 2026-08-12 — `sku`**, by elimination (`returnItemSeqId` is OMS-internal; `orderItemExternalId` identifies the wrong object). No OMS answer needed. Shopify-side return-line `sku` path still unverified; SKU-uniqueness collision still a phase-2 decision. |
| OQ-10 | Which categories of OMS return are not comparison-eligible against Shopify, and what distinguishes them? | RQ-24 | **Decision, not just a question, as of 2026-08-12.** Phase 1 ships a client-side `returnChannelEnumId` pill answering the same problem; implementing RQ-24 server-side would retire that pill (alternative placements of one rule, not two layers — see §5.5 and design doc §5). The OMS taxonomy answer is still needed to know *which values*, but the placement question is now Darpan's to decide, not the OMS's. |
| OQ-11 | What is the maximum page size the endpoints can honour, and the maximum number of `externalId` values per batch request? | RQ-25, RQ-31 | New |
| OQ-12 | Is the 50-record cap a deployment configuration or built into the endpoint? Determines whether existing deployments can be fixed by config ahead of any new endpoint. | RQ-25 | New |

OQ-4 (pre-signed download URLs) and OQ-5 (file retention window) from the
superseded document are both closed — there is no file to download.

## 10. Evidence and provenance

| Claim | Source | Confidence |
|---|---|---|
| Current request shape, auth modes, client-side filter rules, exclusion counts, pagination fallback and stop heuristic | `darpan-hotwax/docs/oms/rest-order-extraction.md` | Read from source |
| Six-field requirement (`orderId, orderName, externalId, grandTotal, orderDate, statusId`) | `darpan/data/SourceSystemConnectorSeedData.xml`, `keepFieldsBase` | Read from source |
| Silent 50-record page cap | `darpan-hotwax` `OmsRestSourceSupport.groovy:42-44`, `OMS_DEFAULT_SERVER_PAGE_SIZE` | Read from source |
| 57 MB / 4,154 orders / 272 s / ~260 KB/s / Shopify 22.5 s | Run 100000, saved run `RS_GORJANA_PROD`, measured 2026-07-24 | Supplied by Darpan product owner; not independently re-run |
| ~14 KB per order, ~84 round-trips, ~50× over-fetch | Derived from the above | Derived |
| Join key `externalId` ↔ Shopify `id`, single-field; zero field-comparison rules configured | Live rule-set read 2026-07-24 (`RS_GORJANA_PROD`, tenant GORJANA) | Live-verified |
| OMS honours a single `externalId=` query filter | Live probe 2026-07-30, status 200, 2 records returned | Live-verified |
| Return lifecycle spans days (order 07-16, return initiated 07-23, processed 07-28); OMS return record created ~38 min after the Shopify `Return` | Live probe of the gorjana `#GOR196990495` pair, 2026-07-30 | Live-verified, single sample |
| ~486 exchanges/day at gorjana | Measured live, 2026-07-31 | Live-verified |
| Shopify exchanges are reachable only via `Order.returns → exchangeLineItems`; `-return_status:no_return` search negation works | Shopify Admin GraphQL 2025-07 schema + live probe, 2026-07-30 | Live-verified |
| Shopify return **line item** paths (`Return.returnLineItems[].fulfillmentLineItem.lineItem.sku`) and the absence of a top-level returns sweep | Shopify Admin GraphQL schema | **Not live-probed.** Darpan has only ever queried the exchange subset of `Return`. Confirm before building the Shopify side |

**Single-sample caveat.** The return-lifecycle timings come from one probed
production exchange. The ~38-minute figure should be read as "the same order of
magnitude as minutes, not days", which is what RQ-23 relies on — not as a bound.

## 11. Sign-off

Not signed off. Pending review by the HotWax OMS team (OQ-3, OQ-6 through OQ-12)
and confirmation by the Darpan product owner that the transport reversal in
"What changed" is intended. Any downstream implementation-start decision must
disclose this unsigned state.
