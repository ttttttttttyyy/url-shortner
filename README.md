# url-shortner
# URL Shortener Platform

A full-stack URL shortening platform designed to simplify link sharing while providing link management, traffic analytics, and subscription-based features.

## Project Overview

The platform allows users to convert long URLs into short, shareable links. It provides link management tools, traffic statistics, and additional features depending on the user's subscription plan.

The system also includes an administrative interface for managing users, monitoring platform activity, and moderating links.

## Technology Stack

| Layer          | Technology           |
| -------------- | -------------------- |
| Frontend       | Next.js, TypeScript  |
| Backend        | Spring Boot          |
| Authentication | Spring Security, JWT |
| Database       | PostgreSQL           |

## Main Features

### 1. URL Shortening

- Generate short links from long URLs.
- Automatically generate unique short codes.
- Copy and share shortened links.
- Support link expiration options.
- Generate QR codes for shortened URLs.

### 2. User Management

**Guest Users**

- Shorten URLs without creating an account.
- Copy and share generated links.

**Registered Users**

- Access a personal dashboard.
- Create, view, edit, and delete their links.
- Monitor basic link statistics.
- Manage link expiration settings.

**Premium Users**

- Create custom short URLs.
- Access advanced traffic analytics.
- Protect links with passwords.
- Benefit from an ad-free redirection experience.

### 3. Analytics

The platform provides statistics to help users understand how their links are accessed.

Depending on the subscription plan, analytics may include:

- Total clicks.
- Geographic distribution by country.
- Device and operating system information.
- Browser statistics.
- Referrer information.

### 4. Advertising

The platform uses an advertising-based model for free link redirections.

- Display clearly identified advertisements before redirection.
- Provide users with a clear action to continue to the destination.
- Support an ad-free experience for eligible premium users.

### 5. Administration

Administrators have access to a dedicated dashboard for platform management.

Main administrative capabilities include:

- View and manage registered users.
- Disable user accounts when necessary.
- Suspend or delete abusive links.
- Review user reports.
- Monitor platform statistics.
- Manage platform settings.
- Access administrative activity logs.

## Security

Security is an essential part of the platform.

The application uses:

- JWT-based authentication.
- Spring Security for authorization and access control.
- Password hashing for secure credential storage.
- Role-based access restrictions.
- Input validation and URL verification.
- Protection against abusive link creation through rate limiting.
- Moderation and reporting mechanisms.

## Configuration notes

Rate limiting for guest link creation (FR-04) is configured through environment
variables:

| Variable | Default | Purpose |
| --- | --- | --- |
| `RATE_LIMIT_ENABLED` | `true` | Master switch for all rate-limit policies |
| `RATE_LIMIT_WINDOW` | `1m` | Burst window (Spring `Duration` syntax) |
| `RATE_LIMIT_GUEST_CREATE` | `5` | Guest creations per address per burst window |
| `RATE_LIMIT_GUEST_CREATE_DAILY` | `50` | Guest sustained rate per 24 hours; `0` disables |
| `RATE_LIMIT_GLOBAL_GUEST_CREATE` | `0` | Optional per-instance global cap; `0` disables |
| `RATE_LIMIT_USER_CREATE` | `30` | Authenticated creations per user per window (FR-05) |
| `RATE_LIMIT_REGISTER` | `5` | Registrations per address per window (FR-05) |
| `RATE_LIMIT_LOGIN` | `10` | Login attempts per account per window (FR-05) |
| `RATE_LIMIT_LOGIN_IP` | `60` | Login attempts per address per window (FR-05) |
| `TRUSTED_PROXIES` | loopback only | Java regex of direct peers allowed to supply `X-Forwarded-For` |

Authentication (FR-05) uses a short-lived bearer JWT plus an opaque refresh
cookie. Configure it with:

| Variable | Default | Purpose |
| --- | --- | --- |
| `JWT_SECRET` | dev placeholder | HMAC key (at least 32 bytes); set this in production |
| `JWT_ISSUER` | `shortly` | Expected JWT issuer |
| `JWT_AUDIENCE` | `shortly-web` | Expected JWT audience |
| `JWT_ACCESS_TTL` | `15m` | Access token lifetime |
| `JWT_REFRESH_TTL` | `7d` | Refresh token lifetime |
| `AUTH_REFRESH_COOKIE_NAME` | `SHORTLY_REFRESH` | Refresh cookie name |
| `AUTH_REFRESH_COOKIE_PATH` | `/api/v1/auth` | Refresh cookie path |
| `AUTH_REFRESH_COOKIE_SECURE` | `true` | Disable only for local plain HTTP |
| `AUTH_REFRESH_COOKIE_SAME_SITE` | `Lax` | Refresh cookie `SameSite` attribute |
| `BCRYPT_STRENGTH` | `10` | Password hashing cost |
| `CORS_ALLOWED_ORIGINS` | localhost dev origins | Explicit browser origin allow-list |

`TRUSTED_PROXIES` is a regex, not a CIDR list. The default is:

```sh
TRUSTED_PROXIES='127\.0\.0\.1|::1'
```

Quote it in the shell so the `|` is not treated as a pipe. Set it to the address
of your reverse proxy or Docker bridge network; leaving the default on a
directly exposed port means clients can spoof their address and bypass per-IP
limits.

Rate-limit state is in memory: it is per instance and resets on restart. See
`docs/fr-04-guest-creation.md` for the full behavior, the guest-retention
deferral and the FR-04 boundaries. Authentication endpoints, token handling
and the deferred email flows are documented in
`docs/fr-05-authentication.md`. The authenticated dashboard data contract is in
`docs/fr-06-dashboard.md`, owned-link updates/deletion in
`docs/fr-07-link-management.md`, paid custom aliases in
`docs/fr-08-custom-aliases.md`, basic analytics in
`docs/fr-09-analytics.md`, and paid breakdowns in
`docs/fr-10-advanced-analytics.md`.

## Project Scope

The project covers the development of a complete URL shortening platform, including:

- Web application and user interface.
- Backend REST services.
- PostgreSQL database integration.
- Authentication and user management.
- URL creation and redirection.
- Link management and analytics.
- QR code generation.
- Subscription and premium feature management.
- Advertising integration.
- Administrative dashboard.
- Deployment preparation, testing, and documentation.

## Project Status

The project is under development. Features and integrations are implemented progressively according to the project specifications.

Some external services, including hosting, domain registration, advertising, payment processing, and email delivery, remain subject to provider selection and configuration.

## Contributors

Bichou Farouk , Mohamed Amine Bakali.
