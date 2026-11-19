package com.url.shortly.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import com.url.shortly.DTO.CallerContext;
import com.url.shortly.DTO.CallerResolver;
import com.url.shortly.enums.PlanTier;
import com.url.shortly.service.exeptions.RateLimitExceededException;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * FR-04: interceptor enforcement order and key selection.
 *
 * <p>These tests pin three contracts that are easy to regress: non-POST
 * requests never consume quota, keys are per-caller (never raw IPs) and a
 * multi-policy rejection reports the longest wait.
 */
class RateLimitInterceptorTest {

    private final CallerResolver callerResolver = mock(CallerResolver.class);
    private final ClientIpResolver clientIpResolver = mock(ClientIpResolver.class);
    private final RateLimitPolicyResolver policyResolver = mock(RateLimitPolicyResolver.class);
    private final RateLimiter rateLimiter = mock(RateLimiter.class);
    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    @SuppressWarnings("unchecked")
    private final ObjectProvider<MeterRegistry> registryProvider = mock(ObjectProvider.class);

    private final RateLimitInterceptor interceptor = new RateLimitInterceptor(
            callerResolver, clientIpResolver, policyResolver, rateLimiter, registryProvider);

    private final RateLimitPolicy burst = new RateLimitPolicy(
            "guest-link-creation", 5, Duration.ofMinutes(1), false);
    private final RateLimitPolicy daily = new RateLimitPolicy(
            "guest-link-creation-daily", 50, Duration.ofHours(24), false);

    private HandlerMethod limitedHandler;
    private HandlerMethod unlimitedHandler;

    @BeforeEach
    void setUp() throws NoSuchMethodException {
        given(registryProvider.getIfAvailable()).willReturn(meterRegistry);
        limitedHandler = new HandlerMethod(new TestHandler(), "limited");
        unlimitedHandler = new HandlerMethod(new TestHandler(), "unlimited");
    }

    @Test
    @DisplayName("OPTIONS and other non-POST requests skip the limiter")
    void nonPostRequestsAreSkipped() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/v1/links");

        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), limitedHandler))
                .isTrue();

        verifyNoInteractions(callerResolver, clientIpResolver, policyResolver, rateLimiter);
    }

    @Test
    @DisplayName("handlers without @RateLimited are never limited")
    void unannotatedHandlersAreSkipped() throws Exception {
        MockHttpServletRequest request = postRequest();

        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), unlimitedHandler))
                .isTrue();

        verifyNoInteractions(callerResolver, clientIpResolver, policyResolver, rateLimiter);
    }

    @Test
    @DisplayName("an allowed request passes through after consuming every policy")
    void allowedRequestProceeds() throws Exception {
        givenGuestCaller();
        given(policyResolver.policiesFor(RateLimitScope.LINK_CREATION, CallerContext.guest()))
                .willReturn(List.of(burst, daily));
        given(rateLimiter.tryAcquire("ip:hashed", burst))
                .willReturn(RateLimitDecision.allowed(burst.name()));
        given(rateLimiter.tryAcquire("ip:hashed", daily))
                .willReturn(RateLimitDecision.allowed(daily.name()));

        assertThat(interceptor.preHandle(postRequest(), new MockHttpServletResponse(), limitedHandler))
                .isTrue();

        verify(rateLimiter).tryAcquire("ip:hashed", burst);
        verify(rateLimiter).tryAcquire("ip:hashed", daily);
    }

    @Test
    @DisplayName("the rejection with the largest Retry-After wins and is tagged in metrics")
    void rejectionUsesLongestWait() {
        givenGuestCaller();
        given(policyResolver.policiesFor(RateLimitScope.LINK_CREATION, CallerContext.guest()))
                .willReturn(List.of(burst, daily));
        given(rateLimiter.tryAcquire("ip:hashed", burst))
                .willReturn(RateLimitDecision.rejected(burst.name(), 30));
        given(rateLimiter.tryAcquire("ip:hashed", daily))
                .willReturn(RateLimitDecision.rejected(daily.name(), 43_200));

        assertThatThrownBy(() ->
                interceptor.preHandle(postRequest(), new MockHttpServletResponse(), limitedHandler))
                .isInstanceOfSatisfying(RateLimitExceededException.class, ex -> {
                    assertThat(ex.policy()).isEqualTo("guest-link-creation-daily");
                    assertThat(ex.retryAfterSeconds()).isEqualTo(43_200);
                });

        assertThat(meterRegistry.get("shortly.rate_limit.rejected")
                .tag("policy", "guest-link-creation-daily")
                .counter().count()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("rejection metrics carry only the policy tag, never IP material")
    void rejectionMetricHasNoClientTags() {
        givenGuestCaller();
        given(policyResolver.policiesFor(RateLimitScope.LINK_CREATION, CallerContext.guest()))
                .willReturn(List.of(burst));
        given(rateLimiter.tryAcquire("ip:hashed", burst))
                .willReturn(RateLimitDecision.rejected(burst.name(), 30));

        assertThatThrownBy(() ->
                interceptor.preHandle(postRequest(), new MockHttpServletResponse(), limitedHandler))
                .isInstanceOf(RateLimitExceededException.class);

        List<Tag> tags = meterRegistry.find("shortly.rate_limit.rejected")
                .counter().getId().getTags();
        assertThat(tags).extracting(Tag::getKey).containsExactly("policy");
    }

    @Test
    @DisplayName("a guest is keyed by hashed address, never the raw IP")
    void guestUsesHashedAddress() throws Exception {
        givenGuestCaller();
        given(policyResolver.policiesFor(RateLimitScope.LINK_CREATION, CallerContext.guest()))
                .willReturn(List.of(burst));
        given(rateLimiter.tryAcquire("ip:hashed", burst))
                .willReturn(RateLimitDecision.allowed(burst.name()));

        interceptor.preHandle(postRequest(), new MockHttpServletResponse(), limitedHandler);

        verify(clientIpResolver).key(any());
        verify(rateLimiter).tryAcquire("ip:hashed", burst);
    }

    @Test
    @DisplayName("an authenticated caller is keyed by user id")
    void authenticatedUsesUserId() throws Exception {
        CallerContext user = new CallerContext(7L, PlanTier.FREE);
        RateLimitPolicy userPolicy = new RateLimitPolicy(
                "user-link-creation", 30, Duration.ofMinutes(1), false);
        given(callerResolver.current()).willReturn(user);
        given(policyResolver.policiesFor(RateLimitScope.LINK_CREATION, user))
                .willReturn(List.of(userPolicy));
        given(rateLimiter.tryAcquire("user:7", userPolicy))
                .willReturn(RateLimitDecision.allowed(userPolicy.name()));

        interceptor.preHandle(postRequest(), new MockHttpServletResponse(), limitedHandler);

        verify(rateLimiter).tryAcquire("user:7", userPolicy);
        verifyNoInteractions(clientIpResolver);
    }

    @Test
    @DisplayName("a global policy uses one shared bucket key")
    void globalPolicyUsesGlobalKey() throws Exception {
        RateLimitPolicy global = new RateLimitPolicy(
                "guest-link-creation-global", 1000, Duration.ofMinutes(1), true);
        givenGuestCaller();
        given(policyResolver.policiesFor(RateLimitScope.LINK_CREATION, CallerContext.guest()))
                .willReturn(List.of(global));
        given(rateLimiter.tryAcquire("global", global))
                .willReturn(RateLimitDecision.allowed(global.name()));

        interceptor.preHandle(postRequest(), new MockHttpServletResponse(), limitedHandler);

        verify(rateLimiter).tryAcquire("global", global);
    }

    @Test
    @DisplayName("an empty policy list means the limiter is disabled")
    void noPoliciesSkipsTheLimiter() throws Exception {
        givenGuestCaller();
        given(policyResolver.policiesFor(RateLimitScope.LINK_CREATION, CallerContext.guest()))
                .willReturn(List.of());

        assertThat(interceptor.preHandle(postRequest(), new MockHttpServletResponse(), limitedHandler))
                .isTrue();

        verifyNoInteractions(rateLimiter);
    }

    private void givenGuestCaller() {
        given(callerResolver.current()).willReturn(CallerContext.guest());
        given(clientIpResolver.key(any())).willReturn("hashed");
    }

    private static MockHttpServletRequest postRequest() {
        return new MockHttpServletRequest("POST", "/api/v1/links");
    }

    @SuppressWarnings("unused")
    public static final class TestHandler {

        @RateLimited(RateLimitScope.LINK_CREATION)
        public void limited() {
        }

        public void unlimited() {
        }
    }
}
