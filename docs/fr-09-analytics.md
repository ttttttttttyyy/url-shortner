# FR-09 — Basic analytics

| Item | Value |
| --- | --- |
| Status | Implemented (uncommitted) |
| Scope | Click recording on eligible redirects, `GET /api/v1/links/{linkId}/analytics` |
| Source | Statement of Work v1.0, FR-09 and proposed endpoint inventory §7.2 |
| Related code | `V3__click_analytics.sql`, `ClickEvent`, `ClickAnalyticsServiceImp`, `LinkController`, `LinkApiController` |

## Requirement

> The system shall record eligible link visits and display click totals, unique-click estimates and time-based trends.

## What is recorded

A click is recorded only when a public short link actually redirects: stored
status `ACTIVE`, not expired, and no password gate (FR-13). Disabled, blocked,
deleted, expired and unknown codes render their status page instead and record
nothing. A malformed stored destination returns the FR-03 internal-error page
and is not counted.

Recording is best effort: if analytics persistence fails, the redirect still
succeeds and the failure is logged. The visitor is identified by the salted
hash from `ClientIpResolver` — raw IP addresses are never stored. Referrer and
user-agent are stored (truncated to 512 chars) for FR-10, but are not exposed
by this endpoint.

`V3__click_analytics.sql` adds `click_event` with an `ON DELETE CASCADE`
foreign key to `link`, plus indexes on `(link_id, occurred_at)` and
`(link_id, visitor_hash, occurred_at)`. Deleting a link removes its history.

## Endpoint

`GET /api/v1/links/{linkId}/analytics?days=7` — requires a bearer token and
ownership of the link (the same `404` rule as details/updates). `days` must be
between 1 and 365 (`app.analytics.retention-days` default) and defaults to 7.
Successful responses carry `Cache-Control: no-store`.

```json
{
  "linkId": 12,
  "totalClicks": 8,
  "uniqueClicks": 5,
  "rangeDays": 7,
  "uniqueWindowSeconds": 86400,
  "series": [
    { "day": "2026-11-14T00:00:00Z", "clicks": 0, "uniqueClicks": 0 },
    { "day": "2026-11-20T00:00:00Z", "clicks": 8, "uniqueClicks": 5 }
  ]
}
```

### Metric definitions

- **`totalClicks`** — all eligible visits whose `occurred_at` is within the
  rolling `days` window.
- **`uniqueClicks`** — an *estimate*: a visitor hash is counted once, and again
  only after `app.analytics.unique-click-window` (24h) has elapsed since the
  last counted visit from that hash. The estimate is per link.
- **`series`** — UTC daily buckets from the first calendar day touched by the
  window through today, including zero days. Bucket `uniqueClicks` uses the
  same rule as the total, so the buckets sum to `totalClicks` and
  `uniqueClicks`.

## Decisions

- **Basic analytics for every owner.** The SoW feature matrix gives click
  totals and trends to free and paid accounts alike; geography/device/browser
  breakdowns (FR-10) are the paid part.
- **Java aggregation.** The reporting window is small (≤365 days) and the
  query stays portable between PostgreSQL and H2. FR-19 can replace it with
  SQL rollups if volume grows.
- **Rolling window, calendar buckets.** `days=7` means "last 7×24 hours";
  because today and the boundary day are only partly covered, the series can
  contain 8 calendar buckets. This is documented rather than silently
  truncating to whole days.
- **Salted visitor hashes.** Privacy-friendly by construction, but the salt is
  per instance, so unique counts can drift upward after a restart. A stable
  anonymous visitor id would need a cookie and a privacy decision.
- **No bot filtering or async queue yet.** Every eligible request is counted
  synchronously; abuse filtering and buffering are later concerns.

## Out of scope

- **FR-10:** geography, device, OS, browser and referrer breakdowns.
- **FR-12:** retention purge of old click rows.
- **FR-13:** password gate (excluded from eligibility until implemented).
- **FR-19:** high-volume aggregation and shared analytics storage.

## Test coverage

| Layer | Tests |
| --- | --- |
| Unit | `ClickAnalyticsServiceImpTest` — unique-window dedupe, empty-day buckets, visitor hashing on record |
| Full stack | `ClickAnalyticsIntegrationTest` — real redirects recorded, disabled links ignored, ownership, auth, range validation |
| Controller slice | `LinkApiControllerTest` — analytics response mapping |
| Migration | `FlywayMigrationTest` — V3 applies and click rows cascade with their link |
| Regression | Full FR-03…FR-08 suites unchanged |
