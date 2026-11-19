# FR-03 — Public redirection policy

| Item | Value |
| --- | --- |
| Status | Implemented |
| Scope | `GET /{shortCode}` — public link resolution |
| Source | Statement of Work v1.0, FR-03 |
| Related code | `LinkController`, `LinkStatusPage`, `LinkServiceImp#resolve`, `LinkResolution` |

## Requirement

> A request to a valid short URL shall resolve to the saved destination using an HTTP redirect. Expired, deleted, disabled or blocked links shall show an appropriate status page and shall not redirect.

## Resolution model

`LinkService#resolve` returns a `LinkResolution` instead of a raw `Link`. The
controller never inspects lifecycle fields; it only asks whether the outcome
redirects. The record's compact constructor rejects a non-`NOT_FOUND` outcome
without a link and a `NOT_FOUND` outcome with one, so invalid states cannot be
constructed.

Precedence, highest first:

1. `DELETED` — `deletedAt != null` or `status = DELETED`
2. `BLOCKED` — moderation (FR-16)
3. `DISABLED` — owner/administrator action
4. `EXPIRED` — `expiresAt != null && !expiresAt.isAfter(now)`
5. `PASSWORD_REQUIRED` — `passwordHash != null` (FR-13 seam)
6. `ACTIVE`
7. `NOT_FOUND` — no row

Status beats expiry: a blocked link stays blocked after its expiry date.
Expiry beats password: an expired link reports "expired" without asking a
stranger to prove knowledge of a secret first. FR-13 must preserve this order
and add a regression test for `expired + passwordHash -> EXPIRED`.

## HTTP mapping

| Outcome | Status | Body |
| --- | --- | --- |
| `ACTIVE` | `302 Found` | None; `Location` = stored destination |
| `NOT_FOUND` | `404 Not Found` | Static status page |
| `EXPIRED` | `410 Gone` | Static status page |
| `DELETED` | `410 Gone` | Static status page |
| `DISABLED` | `403 Forbidden` | Static status page |
| `BLOCKED` | `403 Forbidden` | Static status page |
| `PASSWORD_REQUIRED` | `401 Unauthorized` | Placeholder page; FR-13 replaces it |

`302` is deliberate: `301` is cached permanently by browsers and proxies and
would keep serving a redirect after the link is deleted, blocked or expired.

Every redirect and every status page carries:

- `Cache-Control: no-store`
- `Referrer-Policy: no-referrer`
- `X-Robots-Tag: noindex`

The status page also sets `<meta name="robots" content="noindex">`. It never
contains the destination URL, the short code, or a moderation reason, so it has
no user-controlled value to escape and leaks nothing beyond the outcome.

## Decisions

### `BLOCKED -> 403` reveals the code exists

An unknown code returns `404`; a blocked code returns `403`. Anyone who guesses
a slug can therefore distinguish "never existed" from "existed and was
moderated". This is accepted: moderation feedback is useful to the visitor, and
the page does not mention moderation or the destination. The alternative
(collapse every non-active outcome to `404`) was rejected because it would make
`410 Gone` for expiry/deletion indistinguishable from a typo.

### `PASSWORD_REQUIRED -> 401` is a placeholder

A bare `401` normally carries `WWW-Authenticate`. Adding it would trigger a
native browser credential dialog, which is not the intended UX. The page is a
documented seam; FR-13 replaces it with a password form and its own challenge.

### Trailing slash is not a short code

Spring Boot's `PathPatternParser` does not match `/abc/` to
`/{shortCode}` by default. `GET /abc/` therefore falls through to the generic
`404` without a database lookup. This is intentional: aliases never end with
`/`, and treating both forms as valid would create duplicate URLs for the same
link. A test pins the behavior.

### Reserved words are an FR-08 dependency

The route regex admits `api`, `admin`, `login`, `logout`, `actuator` and the
other configured `app.short-code.reserved-aliases` as syntactically valid
codes. Existing Spring mappings win by specificity today, but FR-08 must reject
these aliases at creation time (the generator already validates reserved words)
and the list must stay aligned with every new top-level route.

### Legacy rows with a malformed destination

All writes go through `UrlValidator`, but a manually seeded row could contain a
string that `URI.create` rejects. The active branch catches that failure and
returns a generic `500` page with no destination or exception detail in the
response; the short code is logged without the URL.

## Route guard

`GET /{shortCode:[A-Za-z0-9_-]{1,32}}` is the only route that queries the
database:

- `/favicon.ico`, `/robots.txt`, `/apple-touch-icon.png` and other static paths
  contain `.` and do not match;
- codes longer than 32 characters do not match the column limit;
- codes outside `[A-Za-z0-9_-]` do not match the alias format.

The pattern must stay aligned with `ShortCodeGenerator.ALIAS_PATTERN` and the
`link.short_code` column length.

## Completion status

FR-03 is complete. The ad interstitial is deferred to FR-14 per its own
requirement; the `ACTIVE` branch in `LinkController#resolveShortLink` is the
designated hook. Click recording is deferred to FR-09; its hook is the same
`ACTIVE` branch, after the `redirects()` check succeeds and before the `302`
response is returned.

## Future seams

- **FR-09 analytics:** record a click only after an `ACTIVE` decision. The hook
  goes in `LinkController#resolveShortLink` after the `redirects()` check
  succeeds and before the `302` response is built — the same place Sink (collect
  access log after the gate checks) and Shlink (trackIfApplicable after
  resolution) put it. Password pages, blocked/disabled/expired pages and unknown
  codes must not count.
- **FR-13 password:** replace the `401` page with a form, verify the hash, then
  continue to the redirect branch. Preserve expiry-before-password.
- **FR-14 ad interstitial:** insert the interstitial between `ACTIVE` and the
  redirect for eligible free links. Paid links bypass it.
- **FR-16 moderation:** the admin action sets `status = BLOCKED`; this policy
  already handles the visitor-facing result.

## Test coverage

| Layer | Tests |
| --- | --- |
| Value object | `LinkResolutionTest` — invariants, only `ACTIVE` redirects |
| Service | `LinkServiceImpTest` — every outcome, precedence pairs, expiry ±1s |
| Controller slice | `LinkControllerTest` — status, headers, HTML, route guards, malformed URI |
| Integration | `LinkEndpointsIntegrationTest` — real persistence for every status mutation and redirect headers |
