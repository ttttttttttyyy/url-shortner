# FR-12 — Link expiration

| Item | Value |
| --- | --- |
| Status | Implemented |
| Scope | Expiry configuration on create/update, expiry evaluation on redirect |
| Source | Statement of Work v1.0, FR-12 |
| Related code | `LinkServiceImp`, `Link`, `LinkResolution`, `LinkStatusPage` |

## Requirement

> A link owner shall be able to configure an expiration date/time where permitted. Expired links shall no longer redirect.

## Configuration

| Field | Rule |
| --- | --- |
| `expiresAt` on `POST /api/v1/links` | Optional. Must be in the future and within the caller's tier window |
| `expiresAt` on `PATCH /api/v1/links/{linkId}` | Same rules; `null` means "leave unchanged" |
| `clearExpiry: true` on PATCH | Explicitly removes the expiry (a null `expiresAt` cannot express this) |
| No expiry | A link with `expires_at = null` never expires |

Windows come from configuration:

- Registered accounts (free and paid): `app.link.max-expiration-days` (3650).
- Guests: `app.link.guest-retention-days` (30), capped by the same maximum.
  A guest requesting a longer expiry gets `422`, rather than having the link
  silently deleted later (the FR-04 handoff).
- `app.link.default-expiration-days` (0) can set an automatic expiry at
  creation; that default is also checked against the tier window.

Past timestamps and values beyond the tier window are rejected with
`422 InvalidExpiryException` by `LinkServiceImp`.

## Evaluation

Expiry is derived from the `expires_at` column; there is no separate `EXPIRED`
status to fall out of sync. All layers evaluate against the same injected
application `Clock`:

- `LinkServiceImp.resolve` passes `clock.instant()` to `Link.isExpired(now)`.
  A link is expired as soon as `expiresAt` is reached (`!isAfter(now)`).
- The dashboard's `effectiveStatus` and `summary.expiredLinks` use the same
  instant (FR-06).
- Analytics count only visits that actually redirected; expired links render
  a status page and record no clicks (FR-09).

FR-03 precedence is unchanged: `DELETED > BLOCKED > DISABLED > EXPIRED >
PASSWORD_REQUIRED > ACTIVE`. An expired blocked link still shows the blocked
page. The public endpoint answers `410 Gone` with the "Link expired" status
page for an expired link.

Owners can still read, edit and delete expired links; only redirection stops.

## Decisions

- **Cap guests at creation time.** `guest-retention-days` was previously inert;
  enforcing it as an input bound keeps creation deterministic and avoids a
  background purge that would surprise API clients.
- **One clock everywhere.** The entity no longer reads `Instant.now()`
  directly, so tests and all consumers agree on the exact boundary instant.
- **No automatic guest default.** With `default-expiration-days: 0`, a guest
  link without an explicit expiry remains permanent; changing that is an owner
  decision, not an FR-12 side effect.

## Out of scope

- **FR-13:** password gate (expiry still wins over password per FR-03).
- **FR-14:** ad interstitial.
- **FR-19:** per-plan expiry entitlements and quotas.

## Test coverage

| Layer | Tests |
| --- | --- |
| Unit | `LinkServiceImpTest` — past expiry, per-tier maximum, guest cap, exact-boundary expiry |
| Full stack | `LinkExpirationIntegrationTest` — redirect before expiry, `410` after, dashboard reporting, clearing expiry, tier limits, moderation precedence |
| Regression | Full FR-03…FR-10 suites unchanged |
