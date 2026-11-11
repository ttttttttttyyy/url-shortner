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
