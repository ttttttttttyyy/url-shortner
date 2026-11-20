# FR-07 — Link management

| Item | Value |
| --- | --- |
| Status | Implemented |
| Scope | `GET`, `PATCH`, `DELETE /api/v1/links/{linkId}` |
| Source | Statement of Work v1.0, FR-07 and proposed endpoint inventory §7.2 |
| Related code | `LinkApiController`, `LinkServiceImp`, `LinkRepository`, `ApiExceptionHandler` |

## Requirement

> An authenticated user shall be able to view, edit eligible properties of, and delete their own links. Users shall not access another user's private links.

All three routes require `Authorization: Bearer <access token>`. Successful
reads/updates carry `Cache-Control: no-store`; delete returns `204`.

| Method | Path | Success | Failures |
| --- | --- | --- | --- |
| `GET` | `/api/v1/links/{linkId}` | `200` link details | `401`, `404` |
| `PATCH` | `/api/v1/links/{linkId}` | `200` updated details | `401`, `403`, `404`, `409`, `422` |
| `DELETE` | `/api/v1/links/{linkId}` | `204` (idempotent) | `401`, `404` |

The detail response never includes a password hash; it only exposes a
`passwordProtected` boolean. `shortUrl` is built from `app.base-url`, not the
request's `Host` header. As in the dashboard list, `status` is the stored row
status and `effectiveStatus` also accounts for expiry, so an expired `ACTIVE`
row is never shown as active. Both use the application clock.

Failures are RFC 9457 problem details with `instance` and
`Cache-Control: no-store`, consistent with the other API endpoints.

## Ownership

Every query is keyed by the owner id from the authenticated principal. There is
no client-supplied owner parameter. A link that is missing, belongs to another
user, is a guest link (`owner_id IS NULL`) or has been soft-deleted — by status
**or** by `deletedAt`, exactly as in FR-03 — produces the same `404 Link not
found`. Using the same response prevents callers from
enumerating which link ids exist.

## Editable properties

| Field | Behaviour |
| --- | --- |
| `url` | Validated and normalized by the FR-02 rules; invalid or blank values → `422` |
| `expiresAt` | Must be in the future and within `app.link.max-expiration-days` → `422` on failure. `null` means "leave unchanged" |
| `clearExpiry` | `true` explicitly clears the expiry; mutually exclusive with `expiresAt` → `422` otherwise |
| `active` | `false` → `DISABLED`, `true` → `ACTIVE`. An expired `ACTIVE` link is still expired at redirect time, but `DISABLED` and `BLOCKED` win over expiry (FR-03 precedence). Changing `active` on a `BLOCKED` link is rejected with `403` |
| `alias` | Paid callers may claim or rename a custom alias (FR-08); everyone else gets `403`, blank values get `422`. The code stays bound to the same link id |
| `password` | Rejected with `403` until FR-13 implements paid password protection |
| no fields | `422 Invalid link update` |

A disallowed field is checked before any mutation, so a rejected PATCH cannot
partially apply. Optimistic locking on `link.version` turns a concurrent update
into `409 Concurrent modification` instead of silently overwriting changes.
The owner can only toggle `ACTIVE ↔ DISABLED`; `BLOCKED` and `DELETED` are
administrator states (FR-16/FR-12) and are never reachable from this API.

## Deletion

Deletion is currently a reversible **soft delete**: the row is kept, `status`
becomes `DELETED` and `deletedAt` is stamped with the application clock. Rows
that already carry `deletedAt` are treated as deleted even if their status was
not updated. The
short code remains reserved, the audit trail is preserved, and the link is
hidden from the dashboard (FR-06) and public redirects (FR-03). A second delete
returns `204` without changing anything. A retention/purge policy (the SoW's
"delete or deactivate according to retention policy") is not defined yet; when
FR-12 settles it, purging can be added without changing this API.

## Decisions

- **404, not 403, for foreign links.** A 403 would confirm that the id exists
  and belongs to someone else.
- **Keep the existing `UpdateLinkRequest` shape.** `active` is a boolean rather
  than a status enum, so callers cannot set `BLOCKED` or `DELETED` — those are
  admin concerns for FR-16.
- **Soft delete now, purge later.** The schema already has `deleted_at` and
  `DELETED` status; a hard delete would lose history and free a short code
  that may still appear in shared links.
- **No rate limit on CRUD.** The SoW only requires limits on creation and
  authentication; ownership checks already prevent anonymous abuse.

## Out of scope

- **FR-08:** custom aliases (including renaming an existing link).
- **FR-11:** per-link analytics.
- **FR-12:** retention enforcement and purge jobs.
- **FR-13:** password protection.
- **FR-16:** admin moderation, `BLOCKED` status and any owner override.

## Test coverage

| Layer | Tests |
| --- | --- |
| Full stack | `LinkManagementIntegrationTest` — owner read, hidden foreign/guest/unknown/`deletedAt` links, permitted and rejected updates (including blank fields, `clearExpiry`, blocked-status protection), non-owner writes, soft delete/idempotence, derived detail status, auth |
| Controller slice | `LinkApiControllerTest` — `GET`/`PATCH`/`DELETE` routing, DTO mapping, service delegation, 409 problem detail |
| Unit | `LinkServiceImpTest` — expiry validation with a fixed clock; `LinkDisplayStatusTest` (FR-06) covers the shared derivation |
| Regression | Full FR-03/FR-04/FR-05/FR-06 suites unchanged |
