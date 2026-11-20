# FR-06 — Dashboard

| Item | Value |
| --- | --- |
| Status | Implemented |
| Scope | `GET /api/v1/links` |
| Source | Statement of Work v1.0, FR-06 and proposed endpoint inventory §7.2 |
| Related code | `LinkApiController`, `LinkServiceImp`, `LinkRepository`, `LinkDashboardResponse` |

## Requirement

> Authenticated users shall see a dashboard of links they own and a summary of their usage.

This repository is the backend only: it exposes the data the frontend needs to
render the dashboard. Redirects after login, page layout and client-side state
belong to the web application and are deliberately not implemented here.

## Endpoint

`GET /api/v1/links` — requires `Authorization: Bearer <access token>`; returns
`200` with `Cache-Control: no-store`.

| Parameter | Default | Rules |
| --- | --- | --- |
| `page` | `0` | must be `>= 0` |
| `size` | `20` | must be between `1` and `100` |
| `status` | none | one of `ACTIVE`, `DISABLED`, `BLOCKED`; `DELETED` is rejected |

Invalid parameters return `400` as an RFC 9457 problem detail with `instance`;
a missing or invalid bearer token returns `401`. Links are ordered by
`created_at DESC, id DESC`.

```json
{
  "items": [
    {
      "id": 12,
      "shortCode": "aB3xK9",
      "shortUrl": "https://sho.rt/aB3xK9",
      "destinationUrl": "https://example.com/page",
      "status": "ACTIVE",
      "effectiveStatus": "ACTIVE",
      "customAlias": false,
      "expiresAt": null,
      "createdAt": "2026-11-20T09:55:47Z",
      "updatedAt": "2026-11-20T09:55:47Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 4,
  "totalPages": 1,
  "summary": {
    "totalLinks": 4,
    "activeLinks": 2,
    "disabledLinks": 1,
    "blockedLinks": 0,
    "expiredLinks": 1,
    "customAliases": 1,
    "createdLast30Days": 4,
    "maxLinks": 100
  }
}
```

Password hashes are never projected. As with link creation, `shortUrl` is built
from the configured `app.base-url`, never from the request's `Host` header.

`status` is the stored row status. `effectiveStatus` also applies the FR-03
expiry rule, so an `ACTIVE` row whose `expiresAt` has passed is reported as
`EXPIRED`. This keeps the table consistent with `summary.activeLinks` and
`summary.expiredLinks`: filtering the list by `status=ACTIVE` can still include
an expired link, and the UI should use `effectiveStatus` when showing state.
Both fields are derived from the same application-clock instant as the summary.

## Ownership

The owner id is read from the authenticated `AuthenticatedUser` principal via
`CallerResolver`; the handler has no owner-id parameter. The JPA query filters
on `owner_id`, so another account's links cannot appear — a client-supplied
`ownerId` query parameter is ignored. Guest links (`owner_id IS NULL`) are
never in a registered user's dashboard. Spring Security rejects anonymous
requests before the controller runs.

## Usage summary

The summary is computed by one aggregate SQL query over **all** non-deleted
links owned by the caller. It is independent of `page`, `size` and `status`, so
moving pages or filtering does not change it.

- `activeLinks` counts links with status `ACTIVE` that have not expired.
- `expiredLinks` counts any non-deleted link whose `expiresAt` is in the past.
  Expiry is derived from the timestamp rather than stored as a status, so it
  cannot drift (FR-03 policy).
- `disabledLinks` / `blockedLinks` follow the stored status. Deleted links are
  excluded from every figure.
- `customAliases` counts links created with a custom short code (a paid
  entitlement today).
- `createdLast30Days` is a rolling window from the application clock, not a
  calendar month.
- `maxLinks` is the configured free-plan limit (`app.plans.free.max-links`)
  reported as context for the frontend; FR-15/FR-19 will make plan resolution
  dynamic.
- Click totals and trends are **not** included: no click data is collected yet.
  FR-11 will add collection and a separate analytics endpoint.

## Decisions

- **One request for the whole dashboard.** The SoW proposes `GET /links` but no
  separate summary route. Returning items and counts together keeps the
  backend a pure data source and lets the frontend decide how to display them.
- **Count-based summary now, click metrics later.** The schema has no click
  table; inventing placeholder click fields would freeze a contract that
  FR-11 will replace.
- **Offset pagination, newest first.** Deterministic and easy for a dashboard
  table; `size` is capped so a single caller cannot request an unbounded
  response.
- **Deleted links stay out.** They are only produced by FR-07's delete flow;
  until that lands, `status = DELETED` would be misleading, so it is rejected.

## Out of scope

- **FR-07:** per-link read, update and delete (the remaining `/links/{id}`
  handlers still return `501`).
- **FR-11:** click collection, totals and trends.
- **FR-15 / FR-19:** dynamic plan entitlements and quota enforcement.

## Test coverage

| Layer | Tests |
| --- | --- |
| Full stack | `LinkDashboardIntegrationTest` — ownership scoping, deleted/guest/other-user exclusion, summary counts, expiry derivation, paging, status filter, invalid parameters, auth, ignored `ownerId`, empty dashboard |
| Controller slice | `LinkApiControllerTest` — `GET /links` response contract and service call |
| Full stack regression | `LinkEndpointsIntegrationTest` — dashboard mount with an authenticated caller; other management routes remain `501` |
| Unit | `LinkServiceImpTest` — existing creation paths with the new clock dependency; `LinkDisplayStatusTest` — derived expiry and stored-status precedence |
