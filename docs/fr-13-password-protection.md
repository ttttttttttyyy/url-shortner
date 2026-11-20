# FR-13 — Password protection

| Item | Value |
| --- | --- |
| Status | Implemented |
| Scope | `password` / `clearPassword` on link writes, `POST /api/v1/links/{shortCode}/verify`, redirect gate |
| Source | Statement of Work v1.0, FR-13 and proposed endpoint inventory §7.2 |
| Related code | `LinkPasswordServiceImp`, `LinkServiceImp`, `LinkApiController`, `LinkController`, `SecurityConfig` |

## Requirement

> Paid users shall be able to protect eligible links with a password. A visitor must pass the password check before being redirected.

## Owner flow

- `POST /api/v1/links` with `{"url": "...", "password": "..."}` creates a
  protected link. The password (8–128 characters) is bcrypt-hashed before
  storage; the raw value and the hash are never returned in any response.
- `PATCH /api/v1/links/{linkId}` with `password` adds/changes the password;
  `clearPassword: true` removes it. The two fields are mutually exclusive.
- Entitlement comes from the signed `tier` claim: only `PAID` callers may set a
  password, and guests/free callers get `403`. Owners may always remove one.
- As with FR-08, production accounts are `FREE` until FR-15 supplies a real
  subscription source; tests stub `PlanTierResolver` to `PAID`.

## Visitor flow

1. `GET /{shortCode}` on a protected link renders the FR-03 `PASSWORD_REQUIRED`
   status page (`401`). No destination is disclosed.
2. `POST /api/v1/links/{shortCode}/verify` with `{"password": "..."}` is a
   **public** route (no account required). It returns:

   ```json
   {
     "accessToken": "<signed, link-scoped JWT>",
     "redirectUrl": "https://sho.rt/<shortCode>?access=<token>",
     "expiresIn": 1800
   }
   ```

   It also sets an HttpOnly cookie `SHORTLY_LINK_ACCESS` scoped to
   `/{shortCode}`, for deployments where API and short-link routes share a
   host. The response is `Cache-Control: no-store`.
3. `GET /{shortCode}?access=<token>` (or the scoped cookie) follows the
   redirect. Without a valid token the password page is shown again.

FR-03 precedence is preserved: `DELETED > BLOCKED > DISABLED > EXPIRED >
PASSWORD_REQUIRED > ACTIVE`. A token only overrides `PASSWORD_REQUIRED`; it
cannot unlock an expired, disabled, blocked or deleted link.

## Token design

- Short-lived JWT (default 30 minutes, `LINK_ACCESS_TTL`), HMAC-signed with the
  same secret as access tokens but with a distinct `link-access` audience, so
  an API bearer token can never be used to unlock a link.
- Bound to both the numeric link id (`sub`) and the short code (`code` claim).
  A token for one protected link is rejected by another.
- Includes `iat`, `exp`, `jti` and a random nonce so tokens are unique.
- The token is delivered in the response body because the verify route may be
  on a different host from the public short link. Redirect responses already
  set `Referrer-Policy: no-referrer`, so a token in the URL is not leaked via
  the Referer header.
- No new table: verification state is stateless and expires naturally.

## Abuse prevention

Two throttles apply to `verify`, consuming quota on every attempt:

- Per client address via the `RateLimited` interceptor (`LINK_PASSWORD_VERIFY`).
- Per link via `LinkPasswordRateLimitGuard`, keyed by a hash of the short code,
  so a distributed attack cannot brute-force one link.

Both use `app.security.rate-limit.password-verification` (10 per window),
answer `429` with `Retry-After` and `policy=link-password-verify`, and have no
permanent lockout.

## Error contract

| Status | Cause |
| --- | --- |
| `400` | Link has no password, or a blank/unusable password was supplied on write |
| `401` | Incorrect password |
| `404` | Unknown short code |
| `422` | Link is blocked/disabled/deleted/expired, or write fields conflict |
| `429` | Verification rate limit exceeded |

## Decisions

- **Signed token instead of a session table.** Stateless verification keeps
  the schema small; the audience separation and expiry bound the blast radius.
- **Cookie *and* body token.** Same-host deployments get a transparent browser
  flow; cross-host deployments can append the returned token to the short URL.
- **Hash at write time, never log.** The bcrypt hash is stored in the existing
  `link.password_hash` column; verification uses constant-work `matches`.
- **Public verify route.** Visitors have no account, so the route is explicitly
  permitted in `SecurityConfig` and rate limited instead.

## Out of scope

- **FR-14:** ad interstitial ordering relative to the password gate.
- **FR-16:** admin bypass of a password gate.
- **Email/password reset for accounts** (FR-05 deferral, unchanged).

## Test coverage

| Layer | Tests |
| --- | --- |
| Unit | `LinkPasswordServiceImpTest` — scoped tokens, wrong password, unprotected/unavailable links |
| Unit | `LinkServiceImpTest` — bcrypt hashing, paid entitlement, blank password |
| Full stack | `PasswordProtectionIntegrationTest` — create/verify/redirect, cookie, link scoping, add/remove password, free denial |
| Full stack | `PasswordRateLimitIntegrationTest` — `429` with `Retry-After` |
| Regression | Full FR-03…FR-12 suites unchanged |
