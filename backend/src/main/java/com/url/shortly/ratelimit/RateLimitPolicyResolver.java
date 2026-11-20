package com.url.shortly.ratelimit;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.url.shortly.DTO.CallerContext;
import com.url.shortly.config.AppProperties;
import com.url.shortly.enums.PlanTier;

/**
 * Builds the ordered policy list for a scope and caller.
 *
 * <p>Guest link creation has two independent limits: a short burst window and
 * a rolling 24-hour cap. The optional global policy is a per-instance safety
 * valve and is off by default. Authenticated callers use a per-user policy
 * once FR-05 resolves a real principal.
 */
@Component
public class RateLimitPolicyResolver {

    public static final String GUEST_BURST = "guest-link-creation";
    public static final String GUEST_DAILY = "guest-link-creation-daily";
    public static final String GUEST_GLOBAL = "guest-link-creation-global";
    public static final String USER_BURST = "user-link-creation";
    public static final String AUTH_REGISTER_IP = "auth-register-ip";
    public static final String AUTH_LOGIN_IP = "auth-login-ip";
    public static final String AUTH_LOGIN_ACCOUNT = "auth-login-account";
    public static final String LINK_PASSWORD_VERIFY = "link-password-verify";

    private static final Duration DAILY_WINDOW = Duration.ofHours(24);

    private final AppProperties properties;

    public RateLimitPolicyResolver(AppProperties properties) {
        this.properties = properties;
    }

    public List<RateLimitPolicy> policiesFor(RateLimitScope scope, CallerContext caller) {
        AppProperties.Security security = properties.security();
        if (security == null || security.rateLimit() == null || !security.rateLimit().enabled()) {
            return List.of();
        }

        AppProperties.Security.RateLimit config = security.rateLimit();
        return switch (scope) {
            case LINK_CREATION -> linkCreationPolicies(config, caller);
            case AUTH_REGISTRATION -> singlePolicy(
                    AUTH_REGISTER_IP, config.registrationAttempts(), config);
            case AUTH_LOGIN -> singlePolicy(
                    AUTH_LOGIN_IP, config.loginIpAttempts(), config);
            case LINK_PASSWORD_VERIFY -> singlePolicy(
                    LINK_PASSWORD_VERIFY, config.passwordVerification(), config);
        };
    }

    private List<RateLimitPolicy> singlePolicy(
            String name, int limit, AppProperties.Security.RateLimit config) {
        List<RateLimitPolicy> policies = new ArrayList<>();
        add(policies, name, limit, config.window(), false);
        return List.copyOf(policies);
    }

    private List<RateLimitPolicy> linkCreationPolicies(
            AppProperties.Security.RateLimit config, CallerContext caller) {
        List<RateLimitPolicy> policies = new ArrayList<>();

        if (caller == null || caller.tier() == PlanTier.GUEST) {
            add(policies, GUEST_BURST, config.guestLinkCreation(), config.window(), false);
            add(policies, GUEST_DAILY, config.guestLinkCreationDaily(), DAILY_WINDOW, false);
            add(policies, GUEST_GLOBAL, config.globalGuestLinkCreation(), config.window(), true);
        } else {
            add(policies, USER_BURST, config.userLinkCreation(), config.window(), false);
        }

        return List.copyOf(policies);
    }

    private void add(List<RateLimitPolicy> policies, String name, int limit,
                     Duration window, boolean global) {
        if (limit > 0 && window != null && !window.isZero() && !window.isNegative()) {
            policies.add(new RateLimitPolicy(name, limit, window, global));
        }
    }
}
