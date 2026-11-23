package com.url.shortly.ratelimit;

import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import com.url.shortly.DTO.CallerContext;
import com.url.shortly.DTO.CallerResolver;
import com.url.shortly.service.exeptions.RateLimitExceededException;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Enforces {@link RateLimited} before request-body binding and persistence.
 *
 * <p>Non-POST requests are skipped so CORS preflight traffic never consumes a
 * guest's allowance. When several policies reject at once, the rejection with
 * the largest {@code Retry-After} wins; that keeps the contract deterministic
 * regardless of policy order.
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    static final String REJECTED_METRIC = "shortly.rate_limit.rejected";

    private final CallerResolver callerResolver;
    private final ClientIpResolver clientIpResolver;
    private final RateLimitPolicyResolver policyResolver;
    private final RateLimiter rateLimiter;
    private final ObjectProvider<MeterRegistry> meterRegistry;

    public RateLimitInterceptor(CallerResolver callerResolver,
                                ClientIpResolver clientIpResolver,
                                RateLimitPolicyResolver policyResolver,
                                RateLimiter rateLimiter,
                                ObjectProvider<MeterRegistry> meterRegistry) {
        this.callerResolver = callerResolver;
        this.clientIpResolver = clientIpResolver;
        this.policyResolver = policyResolver;
        this.rateLimiter = rateLimiter;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        RateLimited limited = handlerMethod.getMethodAnnotation(RateLimited.class);
        if (limited == null || !HttpMethod.POST.matches(request.getMethod())) {
            return true;
        }

        CallerContext caller = callerResolver.current();
        List<RateLimitPolicy> policies = policyResolver.policiesFor(limited.value(), caller);
        if (policies.isEmpty()) {
            return true;
        }

        String baseKey = limited.value().usesUserKey()
                && caller != null && caller.userId() != null
                ? "user:" + caller.userId()
                : "ip:" + clientIpResolver.key(request);

        long maxRetryAfter = 0L;
        String rejectedPolicy = null;

        for (RateLimitPolicy policy : policies) {
            String key = policy.global() ? "global" : baseKey;
            RateLimitDecision decision = rateLimiter.tryAcquire(key, policy);
            if (!decision.allowed() && decision.retryAfterSeconds() > maxRetryAfter) {
                maxRetryAfter = decision.retryAfterSeconds();
                rejectedPolicy = decision.policy();
            }
        }

        if (rejectedPolicy != null) {
            recordRejection(rejectedPolicy);
            throw new RateLimitExceededException(rejectedPolicy, maxRetryAfter);
        }

        return true;
    }

    private void recordRejection(String policy) {
        MeterRegistry registry = meterRegistry.getIfAvailable();
        if (registry != null) {
            registry.counter(REJECTED_METRIC, "policy", policy).increment();
        }
    }
}
