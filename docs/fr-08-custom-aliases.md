# FR-08 — Custom aliases

| Item | Value |
| --- | --- |
| Status | Implemented |
| Scope | Alias validation on `POST /api/v1/links` and `PATCH /api/v1/links/{linkId}` |
| Source | Statement of Work v1.0, FR-08 |
| Related code | `ShortCodeGenerator`, `LinkServiceImp`, `PlanTierResolver`, `JwtService` |

## Requirement

> Paid users shall be able to request a custom alias. The system shall validate its format, uniqueness, reserved words and entitlement.

## Entitlement

Tier is resolved through `PlanTierResolver`, which `JwtService` uses when it
issues the `tier` claim in the access token. `CallerResolver` turns that claim
into the `CallerContext.tier()` used by link creation and updates.

The default `FreePlanTierResolver` returns `FREE` for every account: no billing
provider exists until FR-15, so production accounts cannot claim aliases yet.
FR-15 will replace that bean with subscription lookup; token issuance and
entitlement checks need no changes. Integration tests stub the resolver to
`PAID` to exercise the feature end to end. There is deliberately no
configuration flag that grants paid access in a deployed environment.

## Alias rules

| Rule | Detail |
| --- | --- |
| Entitlement | `PlanTier.PAID` only; otherwise `403 Custom aliases require the paid plan` |
| Length | 1–32 characters. Longer values are stopped by the request DTO (`400`); the generator also enforces the ceiling in unit tests |
| Format | Must match `[A-Za-z0-9][A-Za-z0-9_-]*` and end with a letter/digit — no spaces, dots, `!`, leading/trailing `-` or `_`, or non-ASCII characters |
| Reserved words | Case-insensitive list from `app.short-code.reserved-aliases` (`api`, `admin`, `actuator`, `auth`, `billing`, `login`, `robots.txt`, …) |
| Uniqueness | Enforced by the `ux_link_short_code` unique index. The database is the authority; a lost race surfaces as `409` |
| Case | Short codes are case-sensitive (`My-Link` and `my-link` are different codes), matching generated base62 codes |

Invalid format/reserved values return `422 Invalid alias`; a duplicate returns
`409 Short code already taken`; a blank alias on update returns
`422 Invalid link update`.

## Endpoints

- **Create:** `POST /api/v1/links` with `{"url": "...", "alias": "My-Alias_1"}`.
  A paid caller gets that exact `shortCode`, `customAlias: true`, and a public
  short URL built from `app.base-url`.
- **Rename:** `PATCH /api/v1/links/{linkId}` with `{"alias": "new-alias"}`.
  Only the owner can rename their link; the old code stops resolving and the
  new one does. An alias already used by any link (including another of the
  caller's links) returns `409`. A generated link can be upgraded to a custom
  alias this way. The link id and destination are unchanged.

Other editable fields (`url`, `expiresAt`, `clearExpiry`, `active`) and their
status/moderation rules remain as documented in `fr-07-link-management.md`.
Passwords are still reserved for FR-13.

## Decisions

- **Tier comes from a signed claim, not a request parameter.** Callers cannot
  claim paid status by sending `tier` or `plan` fields.
- **No paid bypass flag.** A dev-only override would be one misconfiguration
  away from granting free users paid features in production.
- **Uniqueness stays in the database.** A pre-check alone would admit lost
  races; the unique index plus conflict translation is the authority.
- **Rename is allowed for paid owners.** The short code is part of the link's
  identity but is safe to change while the numeric id stays stable; public
  links using the old code naturally stop working, which is the expected
  consequence of a rename.

## Out of scope

- **FR-15:** subscriptions, checkout and the real tier source.
- **FR-13:** password-protected links.
- **FR-16:** admin moderation of aliases and links.

## Test coverage

| Layer | Tests |
| --- | --- |
| Unit | `ShortCodeGeneratorTest` — entitlement, format, length, reserved words |
| Full stack | `PaidAliasIntegrationTest` — paid creation, validation, duplicate `409`, rename, conflict on rename, tier claim/profile |
| Full stack | `LinkManagementIntegrationTest` — free account blocked from create and rename |
| Controller slice | `LinkApiControllerTest` — PATCH routing with `CallerContext` tier |
| Regression | Full FR-03…FR-07 suites unchanged |
