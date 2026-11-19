package com.url.shortly.security;

import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.url.shortly.config.AppProperties;

/**
 * Stateless JWT security for the API.
 *
 * <p>Authorization rules are explicit and ordered: guest link creation and the
 * public authentication routes are open, the rest of {@code /api/v1} requires
 * a bearer token, and public short-link redirects remain anonymous. Everything
 * not listed is denied, so a new endpoint is protected until it is explicitly
 * opened.
 *
 * <p>CSRF is disabled because the API is token-based for state-changing calls.
 * The refresh cookie is {@code HttpOnly}, {@code SameSite=Lax} and scoped to
 * the auth routes, which prevents it from being attached to cross-site POSTs.
 * See {@code docs/fr-05-authentication.md} for the full rationale.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final int MIN_JWT_SECRET_BYTES = 32;

    @Bean
    public PasswordEncoder passwordEncoder(AppProperties properties) {
        int strength = properties.security().auth().bcryptStrength();
        if (strength < 4 || strength > 31) {
            throw new IllegalStateException("app.security.auth.bcrypt-strength must be between 4 and 31");
        }
        return new BCryptPasswordEncoder(strength);
    }

    @Bean
    public SecretKey jwtSecretKey(AppProperties properties) {
        String secret = properties.security().jwt().secret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "app.security.jwt.secret is required (set JWT_SECRET)");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_JWT_SECRET_BYTES) {
            throw new IllegalStateException(
                    "app.security.jwt.secret must be at least 32 bytes (256 bits)");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    public JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSecretKey));
    }

    @Bean
    public JwtDecoder jwtDecoder(SecretKey jwtSecretKey, AppProperties properties) {
        AppProperties.Security.Jwt config = properties.security().jwt();

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSecretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();

        OAuth2TokenValidator<Jwt> issuer =
                JwtValidators.createDefaultWithIssuer(config.issuer());
        OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>(
                JwtClaimNames.AUD,
                aud -> aud != null && aud.contains(config.audience()));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(issuer, audience));
        return decoder;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(AppProperties properties) {
        AppProperties.Security.Cors cors = properties.security().cors();
        CorsConfiguration configuration = new CorsConfiguration();

        if (cors != null) {
            List<String> origins = cors.allowedOrigins() == null ? List.of() : cors.allowedOrigins();
            if (cors.allowCredentials() && origins.contains("*")) {
                throw new IllegalStateException(
                        "CORS allowed-origins cannot contain '*' when allow-credentials is true");
            }
            configuration.setAllowedOrigins(origins);
            if (cors.allowedMethods() != null) {
                configuration.setAllowedMethods(cors.allowedMethods());
            }
            if (cors.allowedHeaders() != null) {
                configuration.setAllowedHeaders(cors.allowedHeaders());
            }
            if (cors.exposedHeaders() != null) {
                configuration.setExposedHeaders(cors.exposedHeaders());
            }
            configuration.setAllowCredentials(cors.allowCredentials());
            configuration.setMaxAge(cors.maxAge());
        }

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtDecoder jwtDecoder,
            ProblemDetailAuthenticationEntryPoint authenticationEntryPoint,
            ProblemDetailAccessDeniedHandler accessDeniedHandler) throws Exception {

        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // Guest creation (FR-04) and the public auth routes.
                        .requestMatchers(HttpMethod.POST, "/api/v1/links").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/auth/register",
                                "/api/v1/auth/login",
                                "/api/v1/auth/refresh",
                                "/api/v1/auth/logout").permitAll()
                        // Everything else under the API requires a bearer token.
                        .requestMatchers("/api/v1/**").authenticated()
                        .requestMatchers("/actuator/health", "/actuator/health/**",
                                "/actuator/info").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**",
                                "/swagger-ui.html").permitAll()
                        .requestMatchers("/error").permitAll()
                        // Public short-link redirects and their HEAD probes.
                        .requestMatchers(HttpMethod.GET, "/**").permitAll()
                        .requestMatchers(HttpMethod.HEAD, "/**").permitAll()
                        .anyRequest().denyAll())
                .addFilterBefore(
                        new JwtAuthenticationFilter(jwtDecoder, authenticationEntryPoint),
                        UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
