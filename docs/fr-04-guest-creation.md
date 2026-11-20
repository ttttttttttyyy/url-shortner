# FR-04 — Guest link creation with rate limits

| Item | Value |
| --- | --- |
| Status | Implemented |
| Scope | `POST /api/v1/links` for unauthenticated guests |
| Source | Statement of Work v1.0, FR-04 |
| Related code | `RateLimitInterceptor`, `RateLimitPolicyResolver`, `TokenBucketRateLimiter`, `ClientIpResolver`, `ApiExceptionHandler` |

## Requirement

> A guest shall be able to create an automatic short link without account registration, subject to rate limits and abuse controls.

## Guest creation path

The existing creation path already supports guests: no registration is
required, `owner_id` is `null`, the short code is generated automatically and
guests cannot request aliases or password protection. FR-04 adds enforcement in
front of that path; it does not add a second creation flow.

The limiter runs in a Spring MVC interceptor *before* request-body binding and
validation. Malformed JSON, blank fields and invalid destinations therefore
consume quota: abusing parser/validation work is not a cheap way around the
limit. `GET /{shortCode}` is never rate limited.

## Limits

Two guest policies are evaluated for every `POST` that reaches the interceptor,
plus an optional global safety valve:

| Policy | Default | Window | Purpose |
| --- | --- | --- | --- |
| `guest-link-creation` | 5 | 1 minute | Per-address burst |
| `guest-link-creation-daily` | 50 | 24 hours | Sustained-abuse rate |
| `guest-link-creation-global` | 0 (off) | 1 minute | Optional per-instance safety valve |

The daily policy is a 24-hour token bucket: capacity 50, refilling at 50
tokens per 24 hours. It is a rolling rate, not a UTC calendar-day counter, so
there is no midnight burst. As with any token bucket, an address that has been
idle can accumulate up to the full capacity before spending it; the long-run
rate is what the limit enforces.

`guest-link-creation-daily: 0` disables only the daily policy. The global
policy is off by default because in-memory state is per instance: during a
traffic spike, one node could throttle legitimate guests while another node
still has capacity. Enable it only for a single-node deployment.

Authenticated callers use `user-link-creation` (default 30/minute) keyed by
user id. Authentication itself is FR-05.

All limits are configurable; see the environment-variable table in the
top-level `README.md`.

## Client identity and proxy trust

Guests are keyed by a salted SHA-256 digest of the request's remote address.
The raw address is never stored, logged or used as a metric tag. The salt is
generated per application instance, so keys are not portable between restarts
or nodes.

To prevent a client from minting a new bucket with every request, the
application no longer trusts `X-Forwarded-For` from every peer. It uses
`server.forward-headers-strategy: native`, which activates Tomcat's
`RemoteIpValve`. The header is honored only when the direct TCP peer matches
`server.tomcat.remoteip.internal-proxies` (`TRUSTED_PROXIES`). The default trusts
only loopback (`127.0.0.1` and `::1`), suitable for a local reverse proxy.

Deployments behind Docker, a bridge network or a non-local reverse proxy must
set `TRUSTED_PROXIES` to a regex matching the proxy's address. Leaving it open
on a directly exposed port makes per-address limits trivial to bypass.

## Rejection contract

When a policy rejects a request, the response is:

- status `429 Too Many Requests`;
- `Retry-After` in integer seconds;
- `Cache-Control: no-store`;
- RFC 9457 problem detail with the request path as `instance` and a `policy` property.

Example:

```http
HTTP/1.1 429 Too Many Requests
Retry-After: 30
Cache-Control: no-store
Content-Type: application/problem+json

{
  "title": "Too many requests",
  "status": 429,
  "detail": "Too many requests. Try again later.",
  "instance": "/api/v1/links",
  "policy": "guest-link-creation"
}
```

All policies are evaluated even when an earlier one rejects. If several reject
at once, the response reports the **largest** `Retry-After` and its policy, so
the result does not depend on policy ordering. A rejected request can therefore
spend a token from a policy that did not reject; this is deliberate, because it
keeps the decision independent of order. The rejection is counted in
`shortly.rate_limit.rejected{policy=...}`; the only tag is the policy name.
No IP address, hash, user id or other client identity is added to metrics.

`OPTIONS` requests never consume quota and never reach the policy resolver, so
a browser's CORS preflight cannot exhaust a guest's allowance. Requests to
methods without `@RateLimited` are also skipped.

## Decisions

### In-memory token bucket, behind an interface

`RateLimiter` is the seam. `TokenBucketRateLimiter` uses a Caffeine cache with
`maximumSize(100_000)` and `expireAfterAccess(48h)`, so spoofed or long-tail
addresses cannot grow memory without bound. Refill-and-consume is one atomic
`asMap().compute(...)`, which is required for correctness under concurrency; a
read/check/write sequence over-admits.

State resets on restart and is per instance. If the deployment runs more than
one node, the effective limit is roughly N × the configured value; that is the
signal to move the implementation to Redis under FR-19.

### NAT and shared addresses

Guests behind one NAT (campus, office, carrier-grade NAT) share a bucket.
This is accepted for FR-04: IP-based limits cannot distinguish users behind
the same address, and the alternative — a cookie or device fingerprint — is
both weaker and more invasive. Registered users get per-user keys under FR-05.
Support should expect occasional shared-address false positives.

### Fixed window rejected

A fixed window permits a burst at the boundary (for example, 10 requests in a
few milliseconds around the minute mark). The token bucket smooths refill and
provides a meaningful `Retry-After`, which FR-04 requires.

### Guest retention is enforced at creation (FR-12)

`app.link.guest-retention-days: 30` was inert in FR-04. FR-12 now applies it as
an upper bound on a guest's requested `expiresAt`, as the handoff proposed: a
guest link with no requested expiry still never expires by default. Registered
accounts use the larger `app.link.max-expiration-days` window instead.

## Out of scope

- **FR-05:** authentication and per-user identity.
- **FR-12:** implemented — expiry configuration and the guest retention cap.
- **FR-13:** password-protected links.
- **FR-14:** ad interstitial.
- **FR-16:** CAPTCHA, denylist, moderation and reporting workflow.
- **FR-17:** abuse reports and triage.
- **FR-19:** plan quotas, distributed limiter state and horizontal scaling.

## Operational notes

- Set `RATE_LIMIT_ENABLED=false` only for local debugging or load tests that
  must not be throttled; it disables every policy.
- Set `RATE_LIMIT_GUEST_CREATE_DAILY=0` to keep only the burst limit.
- Set `RATE_LIMIT_GLOBAL_GUEST_CREATE=0` to keep the global valve off.
- `TRUSTED_PROXIES` is a Java regex matched against the direct peer address,
  not a comma-separated CIDR list. Escape dots and describe a range with a
  regex, e.g. `10\\.0\\.\\d{1,3}\\.\\d{1,3}`.
- Rate-limit state is not shared across instances and does not survive a
  restart.

## Test coverage

| Layer | Tests |
| --- | --- |
| Token bucket | `TokenBucketRateLimiterTest` — exact burst, refill, capacity cap, key isolation, concurrent over-admission |
| Identity | `ClientIpResolverTest` — opaque, stable and per-instance salted keys |
| Policy | `RateLimitPolicyResolverTest` — guest/user/global composition, disabled feature, zero limits |
| Interceptor | `RateLimitInterceptorTest` — method/HTTP gating, key selection, longest `Retry-After`, metric tags |
| Controller slice | `LinkApiControllerTest` — 429 contract, no service call, preflight skip, malformed-body quota |
| Full stack | `RateLimitIntegrationTest`, `DailyRateLimitIntegrationTest` — burst/daily rejection, persisted-row checks, redirects still work |
| Proxy trust | `ProxyTrustIntegrationTest`, `ProxySpoofIntegrationTest` — real HTTP with `RANDOM_PORT`: trusted `X-Forwarded-For` is honored, untrusted spoofing is ignored |
