# FR-05 — User authentication

| Item | Value |
| --- | --- |
| Status | Implemented |
| Scope | `POST /api/v1/auth/register`, `/login`, `/refresh`, `/logout`, `GET /api/v1/auth/me` |
| Source | Statement of Work v1.0, FR-05 (routes were proposed pending the OpenAPI contract) |
| Related code | `SecurityConfig`, `JwtAuthenticationFilter`, `JwtService`, `AuthServiceImp`, `AuthController`, `V2__identity_and_auth.sql` |

## Requirement

> Users shall be able to register, sign in, sign out and retrieve their authenticated account profile.

The SoW route list was explicitly *proposed* pending the contract. This
implementation keeps those four routes and adds `POST /auth/refresh`, which is
needed to keep access tokens short-lived without forcing a full sign-in every
15 minutes. Email verification and password reset are deferred until an email
provider is chosen; the schema intentionally has no `user_token` table yet.

## Endpoints

| Method | Path | Auth | Success | Notes |
| --- | --- | --- | --- | --- |
| `POST` | `/api/v1/auth/register` | none | `201` | Creates a `USER` account, signs it in, returns tokens, sets refresh cookie |
| `POST` | `/api/v1/auth/login` | none | `200` | Generic `401` on unknown email, wrong password or disabled account |
| `POST` | `/api/v1/auth/refresh` | refresh cookie | `200` | Rotates the refresh token; `401` clears the cookie |
| `POST` | `/api/v1/auth/logout` | refresh cookie | `204` | Revokes the current session, clears the cookie; idempotent |
| `GET` | `/api/v1/auth/me` | bearer token | `200` | Reads the profile from the database on every request |

Successful auth responses and authentication/rate-limit failures (401/429)
carry `Cache-Control: no-store`. All failures use the RFC 9457 problem-detail
contract. `GET /auth/me` is the only route that fails
with `401` when the account is disabled, even if the access token is still
cryptographically valid; other claims live in the token until expiry.

### Response shape

`register`, `login` and `refresh` return the same body; the refresh token is
never in it:

```json
{
  "accessToken": "<signed JWT>",
  "tokenType": "Bearer",
  "expiresIn": 900,
  "user": {
    "id": 7,
    "email": "alice@example.com",
    "displayName": "Alice",
    "role": "USER",
    "planTier": "FREE",
    "emailVerified": false,
    "createdAt": "2026-11-19T14:25:25.929Z"
  }
}
```

## Token model

### Access token

- Short-lived HMAC-SHA256 JWT (15 minutes by default).
- Sent in `Authorization: Bearer <token>`.
- Claims: `sub` (numeric user id), `iss`, `aud`, `iat`, `exp`, `jti`, `email`,
  `role`, `tier`.
- `JwtService` signs it; `JwtDecoder` validates signature, issuer, audience and
  expiry. The secret must be at least 32 bytes, otherwise startup fails.
- `JwtAuthenticationFilter` turns it into an `AuthenticatedUser` principal.
  A *missing* header leaves the request anonymous; a *present but invalid*
  header is rejected with `401` even on public endpoints, so a stale token
  cannot silently turn an authenticated request into a guest request.
- Tier is `FREE` until FR-15 resolves subscriptions. The claim is signed at
  issuance, so a role change or a paid-tier downgrade keeps its old value for
  up to 15 minutes — the same accepted lag as account disabling. `me` resolves
  the tier fresh on each request (and still checks that the account is active),
  so it can report the new tier before older tokens do.

### Refresh token

- 256 bits of `SecureRandom`, Base64URL-encoded, sent only as a cookie.
- Cookie: `SHORTLY_REFRESH=<token>; Path=/api/v1/auth; HttpOnly; Secure; SameSite=Lax; Max-Age=604800`.
  `Secure` is disabled only by the `dev`/`test` profiles for plain HTTP.
  There is no `Domain` attribute (host-only cookie).
- The server stores only `SHA-256(token)` in `auth_session`; the raw value
  exists in the cookie and nowhere else.
- Each `refresh` rotates the token: the old row is revoked and a new row in the
  same `family_id` is inserted. Rotation is serialized with a pessimistic row
  lock, so concurrent refreshes cannot both succeed with the same token.
- Presenting a rotated token revokes every session in its family. This is the
  standard theft-detection response: an attacker replaying a stolen token
  cannot keep a session alive, and the legitimate client must sign in again.
- `logout` revokes only the presented session; it does not invalidate other
  devices. Access tokens are not revoked on logout — they remain valid for at
  most their 15-minute lifetime, which is the accepted trade-off for a
  stateless API.
- A scheduled cleanup removes sessions expired more than 30 days ago, keeping
  rotated rows available for reuse detection during that window.

## Data model

Flyway `V2__identity_and_auth.sql` adds:

- `app_user` — normalized unique `email`, bcrypt `password_hash`, `role`
  (`USER`/`ADMIN`), `status` (`ACTIVE`/`DISABLED`), timestamps and a version
  column. Emails are lowercased by the application before insert and lookup,
  so the plain unique constraint is case-insensitive in practice.
- `auth_session` — `user_id` (cascade delete), token hash (unique), `family_id`,
  hashed client address and truncated user agent (never the raw address),
  expiry, revocation and usage timestamps.
- `link.owner_id` gains a foreign key to `app_user` with `ON DELETE SET NULL`.
  Existing `V1` rows can only be guest links, so no backfill is required.

Admins (`ADMIN` role) are reserved for FR-16; registration never grants one.
Only `ACTIVE` accounts can sign in, refresh or read their profile.

## Abuse prevention

| Scope | Key | Limit | Where |
| --- | --- | --- | --- |
| `AUTH_REGISTRATION` | salted hash of client address | 5 / window | `RateLimited` interceptor, before body binding |
| `AUTH_LOGIN` (address) | salted hash of client address | 60 / window | `RateLimited` interceptor, before body binding |
| `AUTH_LOGIN` (account) | SHA-256 of normalized email | 10 / window | `AuthRateLimitGuard` inside `AuthServiceImp` |

The address limit is deliberately higher for login because NAT shares one
address. The per-account key is a hash so raw addresses and email addresses are
never used as cache keys or metric tags. Every attempt consumes quota,
successful or not; there is no permanent lockout, only a rolling token bucket.
Rejections return `429` with `Retry-After`, `Cache-Control: no-store` and a
`policy` property naming the bucket that rejected the request. As in FR-04,
auth buckets are in memory: per instance and reset on restart.

## Security decisions

- **Passwords.** Bcrypt (strength 10 in production, 4 in tests). Passwords
  above bcrypt's 72-byte input limit are rejected with `400`. Unknown-email
  logins run a dummy bcrypt verification so account existence is not leaked
  through response time.
- **Non-enumeration.** Wrong password, unknown email and disabled accounts all
  return the same `401` problem detail. Duplicate registration is the one
  intentional exception (`409`), as agreed in the contract decisions.
- **CSRF.** Disabled at the filter chain. State-changing API calls need a
  bearer token; the refresh cookie is `HttpOnly`, `SameSite=Lax`, host-only and
  confined to `/api/v1/auth`, so it is not attached to other routes or to
  cross-site POSTs. CORS origins come from an explicit allow-list
  (`CORS_ALLOWED_ORIGINS`); wildcard origins with credentials are rejected at
  startup.
- **Least privilege.** `/api/v1/**` requires authentication except for guest
  link creation and the four public auth routes. Everything else is denied by
  default. Public short-link redirects stay anonymous, and actuator/OAuth docs
  endpoints are explicitly permitted.
- **Secrets.** JWT secret, cookie flags and rate limits come from environment
  variables; no secret is committed. `JWT_SECRET` has no production default
  beyond the clearly labelled development value.

## Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `JWT_SECRET` | dev placeholder | HMAC key, at least 32 bytes; **set in production** |
| `JWT_ISSUER` | `shortly` | Expected `iss` claim |
| `JWT_AUDIENCE` | `shortly-web` | Expected `aud` claim |
| `JWT_ACCESS_TTL` | `15m` | Access token lifetime |
| `JWT_REFRESH_TTL` | `7d` | Refresh token / cookie lifetime |
| `AUTH_REFRESH_COOKIE_NAME` | `SHORTLY_REFRESH` | Cookie name |
| `AUTH_REFRESH_COOKIE_PATH` | `/api/v1/auth` | Cookie path |
| `AUTH_REFRESH_COOKIE_SECURE` | `true` | Set `false` only for plain-HTTP dev |
| `AUTH_REFRESH_COOKIE_SAME_SITE` | `Lax` | Cookie `SameSite` attribute |
| `BCRYPT_STRENGTH` | `10` | Password hashing cost (4–31) |
| `RATE_LIMIT_LOGIN` | `10` | Failed/successful logins per account per window |
| `RATE_LIMIT_LOGIN_IP` | `60` | Login attempts per address per window |
| `RATE_LIMIT_REGISTER` | `5` | Registrations per address per window |
| `CORS_ALLOWED_ORIGINS` | localhost dev origins | Comma-separated explicit allow-list |

## Out of scope

- **FR-06 / FR-07:** dashboard listing and ownership-aware link CRUD. The
  authenticated create path already records `owner_id`; the remaining routes
  are not implemented yet.
- **FR-15:** paid subscriptions. All principals are `PlanTier.FREE` today.
- **FR-16:** admin endpoints and the `ADMIN` role.
- **Email verification and password reset:** deferred until a provider is
  selected. `emailVerified` is always `false` and no user tokens are issued.
- **Distributed rate limits and session revocation lists:** in-memory and
  per-instance today; revisit with FR-19 or multi-node deployment.

## Test coverage

| Layer | Tests |
| --- | --- |
| End-to-end | `AuthEndpointsIntegrationTest` — normalization, bcrypt hashing, cookie flags, JWT claims, generic `401`, duplicate `409`, profile, rotation, reuse detection, expiry, logout idempotence, disabled account, authenticated link ownership |
| Abuse | `AuthRateLimitIntegrationTest` — per-address registration, per-address and per-account login, independent buckets, `429` contract |
| Migration | `FlywayMigrationTest` — V1+V2 on H2 PostgreSQL mode, uniqueness and foreign-key actions |
| Config | `AppPropertiesTest`, `RateLimitPolicyResolverTest` — auth defaults and fixed per-address policies |
| Regression | Full FR-03/FR-04 suites continue to pass with Spring Security enabled |
