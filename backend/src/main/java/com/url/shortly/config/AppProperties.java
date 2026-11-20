package com.url.shortly.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Type-safe binding for the {@code app.*} settings declared in application.yml.
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
		@DefaultValue("http://localhost:10004") String baseUrl,
		ShortCode shortCode,
		Link link,
		Security security,
		Ads ads,
		Plans plans,
		Analytics analytics) {

	public record ShortCode(
			@DefaultValue("6") int guestMinLength,
			@DefaultValue("5") int freeMinLength,
			@DefaultValue("4") int paidMinLength,
			@DefaultValue("32") int maxLength,
			@DefaultValue("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz") String alphabet,
			@DefaultValue("10") int maxGenerationAttempts,
			List<String> reservedAliases) {

		public ShortCode {
			if (reservedAliases == null) {
				reservedAliases = List.of();
			}
		}
	}

	public record Link(
			@DefaultValue("0") int defaultExpirationDays,
			@DefaultValue("3650") int maxExpirationDays,
			@DefaultValue("30") int guestRetentionDays,
			Password password) {

		public record Password(
				@DefaultValue("8") int minLength,
				@DefaultValue("128") int maxLength) {
		}
	}

	public record Security(
			Jwt jwt,
			Cors cors,
			RateLimit rateLimit,
			Auth auth) {

		public record Jwt(
				@DefaultValue("shortly") String issuer,
				@DefaultValue("shortly-web") String audience,
				String secret,
				@DefaultValue("15m") Duration accessTokenTtl,
				@DefaultValue("7d") Duration refreshTokenTtl) {
		}

		public record Cors(
				List<String> allowedOrigins,
				List<String> allowedMethods,
				List<String> allowedHeaders,
				List<String> exposedHeaders,
				@DefaultValue("true") boolean allowCredentials,
				@DefaultValue("3600") long maxAge) {
		}

		/**
		 * Refresh-session and password-hashing settings. The refresh cookie is
		 * host-only and path-scoped to the authentication routes; it must be
		 * {@code Secure} everywhere except plain-HTTP local development.
		 */
		public record Auth(
				@DefaultValue("SHORTLY_REFRESH") String refreshCookieName,
				@DefaultValue("/api/v1/auth") String refreshCookiePath,
				@DefaultValue("true") boolean refreshCookieSecure,
				@DefaultValue("Lax") String refreshCookieSameSite,
				@DefaultValue("10") int bcryptStrength) {
		}

		public record RateLimit(
				@DefaultValue("true") boolean enabled,
				@DefaultValue("1m") Duration window,
				@DefaultValue("5") int guestLinkCreation,
				@DefaultValue("50") int guestLinkCreationDaily,
				@DefaultValue("0") int globalGuestLinkCreation,
				@DefaultValue("30") int userLinkCreation,
				@DefaultValue("10") int loginAttempts,
				@DefaultValue("10") int passwordVerification,
				@DefaultValue("60") int loginIpAttempts,
				@DefaultValue("5") int registrationAttempts) {
		}
	}

	public record Ads(
			@DefaultValue("true") boolean enabled,
			Interstitial interstitial) {

		public record Interstitial(
				@DefaultValue("true") boolean enabled,
				@DefaultValue("none") String provider,
				String scriptUrl,
				@DefaultValue("0") int continueDelaySeconds,
				@DefaultValue("true") boolean requireUserAction) {
		}
	}

	public record Plans(
			Plan free,
			Plan paid) {

		/**
		 * Descriptive capabilities of a plan. FR-08/FR-13 enforce entitlement
		 * from the signed tier claim; FR-15 will connect these flags to the
		 * resolved subscription instead of creating a second gate.
		 */
		public record Plan(
				int maxLinks,
				boolean customAliases,
				boolean advancedAnalytics,
				boolean passwordProtection,
				boolean adFreeRedirects) {
		}
	}

	public record Analytics(
			@DefaultValue("24h") Duration uniqueClickWindow,
			@DefaultValue("365") int retentionDays,
			@DefaultValue("country") String geoGranularity,
			@DefaultValue("true") boolean anonymizeIp) {
	}
}
