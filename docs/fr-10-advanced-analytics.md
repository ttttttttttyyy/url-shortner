# FR-10 — Advanced analytics

| Item | Value |
| --- | --- |
| Status | Implemented |
| Scope | `GET /api/v1/links/{linkId}/analytics/advanced` |
| Source | Statement of Work v1.0, FR-10 |
| Related code | `V4__advanced_analytics.sql`, `UserAgentClassifier`, `GeoResolver`, `AdvancedAnalyticsServiceImp`, `LinkApiController` |

## Requirement

> The system shall display geographic region/country, device, operating system, browser and referrer breakdowns when data is available and the user is entitled to view them.

## Entitlement

The endpoint requires `PlanTier.PAID` in the signed access token, plus ownership
of the link (the same `404` rule as details and basic analytics). Free callers
get `403 Advanced analytics requires the paid plan`.

As with FR-08, production accounts are `FREE` until FR-15 supplies a real
subscription source; integration tests stub `PlanTierResolver` to `PAID`. A
downgrade therefore leaves the old paid claim valid until the token expires
(up to 15 minutes), the same accepted lag documented in FR-05.

## Endpoint

`GET /api/v1/links/{linkId}/analytics/advanced?days=7` — bearer token required,
`days` between 1 and `app.analytics.retention-days` (365 by default, default
range 7 days), `Cache-Control: no-store`.

```json
{
  "linkId": 12,
  "rangeDays": 7,
  "countries": [{ "value": "US", "clicks": 8 }],
  "devices": [
    { "value": "Desktop", "clicks": 6 },
    { "value": "Mobile", "clicks": 2 }
  ],
  "operatingSystems": [{ "value": "Windows", "clicks": 6 }],
  "browsers": [{ "value": "Chrome", "clicks": 7 }],
  "referrers": [
    { "value": "google.com", "clicks": 5 },
    { "value": "Direct", "clicks": 3 }
  ]
}
```

Each breakdown is sorted by clicks descending, then value ascending — including
the `Other` bucket. Missing dimensions are omitted (an empty list) — notably
`countries` until a geo provider exists. Lists are capped at 20 entries with
the remainder grouped as `Other`. Counts across a dimension reconcile with
FR-09's `totalClicks`: rows recorded before V4 are classified from their stored
user agent at read time, so upgrading does not lose them from the breakdowns.

## Data capture

`V4__advanced_analytics.sql` adds nullable `country`, `device_type`,
`operating_system` and `browser` columns to `click_event`.

- **Device / OS / browser** — `UserAgentClassifier` performs coarse,
  dependency-free matching at record time. Unknown or non-browser agents
  (e.g. `curl`) are reported as `Unknown`, not guessed.
- **Referrer** — only the host is stored (`https://www.x/...?q=secret` → `x`),
  with a leading `www.` stripped, so query-string secrets are never persisted
  or exposed. The same normalization is applied at read time to rows written by
  FR-09 before host-only storage; a missing/unparseable referrer is `Direct`.
- **Country** — `GeoResolver` is a provider seam whose contract is ISO-3166
  alpha-2. Recording uppercases valid two-letter codes and drops anything
  else, so `us` and `US` do not fragment the breakdown. The default
  `UnavailableGeoResolver` returns `null`, so the country breakdown is empty,
  which is exactly the SoW's "when data is available" condition.

## Decisions

- **Separate paid endpoint.** Basic analytics stays available to all owners;
  a distinct route makes the entitlement explicit and lets FR-15 gate only
  advanced data.
- **Capture at redirect time, aggregate at read time.** The dimensions are
  stored with each click, so a later provider or classifier change does not
  rewrite history. Aggregation reuses FR-09's range query.
- **No GeoIP dependency yet.** Provider selection is still open; stubbing a
  resolver keeps the contract testable without inventing location data.
- **Bounded responses.** A cap plus an `Other` bucket prevents a long tail of
  referrer hosts from producing an unbounded payload; `Other` obeys the same
  ordering rule as every other entry.
- **Coarse UA caveat.** A bot that sends browser-like tokens can be classified
  as `Desktop`/a named browser. That is accepted for this MVP; a dedicated bot
  filter is a later concern.

## Out of scope

- **FR-11:** QR code generation.
- **FR-12:** expiration configuration/enforcement (expired links already do
  not redirect and therefore record no clicks).
- **GeoIP provider integration** and historical backfill.
- **FR-19:** high-volume rollups and shared analytics storage.

## Test coverage

| Layer | Tests |
| --- | --- |
| Unit | `UserAgentClassifierTest` — common agents, Edge, unknown/CLI |
| Unit | `AdvancedAnalyticsServiceImpTest` — sorting, missing dimensions, `Direct`, `Other` cap |
| Full stack | `AdvancedAnalyticsIntegrationTest` — paid breakdowns, referrer privacy, free/anonymous/non-owner refusal, range validation |
| Migration | `FlywayMigrationTest` — V4 columns apply and click rows cascade |
| Regression | Full FR-03…FR-09 suites unchanged |
