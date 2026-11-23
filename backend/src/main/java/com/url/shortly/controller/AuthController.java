package com.url.shortly.controller;

import java.net.URI;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.url.shortly.DTO.AuthResponse;
import com.url.shortly.DTO.LoginRequest;
import com.url.shortly.DTO.RegisterRequest;
import com.url.shortly.DTO.UserProfile;
import com.url.shortly.ratelimit.ClientIpResolver;
import com.url.shortly.ratelimit.RateLimitScope;
import com.url.shortly.ratelimit.RateLimited;
import com.url.shortly.security.AuthenticatedUser;
import com.url.shortly.security.ClientMetadata;
import com.url.shortly.security.RefreshCookieFactory;
import com.url.shortly.service.AuthResult;
import com.url.shortly.service.AuthService;
import com.url.shortly.service.exeptions.AccountUnavailableException;
import com.url.shortly.service.exeptions.InvalidRefreshTokenException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * FR-05 authentication endpoints.
 *
 * <p>Register and login return an access token in the body and set the opaque
 * refresh token as an {@code HttpOnly} cookie. Refresh rotates that cookie;
 * logout revokes the presented session and clears it. Every auth response is
 * marked {@code no-store}.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication")
public class AuthController {

    private final AuthService authService;
    private final RefreshCookieFactory refreshCookies;
    private final ClientIpResolver clientIpResolver;

    public AuthController(AuthService authService,
                          RefreshCookieFactory refreshCookies,
                          ClientIpResolver clientIpResolver) {
        this.authService = authService;
        this.refreshCookies = refreshCookies;
        this.clientIpResolver = clientIpResolver;
    }

    @Operation(summary = "Register an account",
            description = "Creates a free account, signs it in and sets the refresh cookie.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Account created and signed in"),
            @ApiResponse(responseCode = "400", description = "Malformed body or invalid password"),
            @ApiResponse(responseCode = "409", description = "Email already registered"),
            @ApiResponse(responseCode = "429", description = "Registration rate limit exceeded")
    })
    @RateLimited(RateLimitScope.AUTH_REGISTRATION)
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(
            @Valid @RequestBody RegisterRequest request,
            HttpServletRequest httpRequest) {

        AuthResult result = authService.register(request, metadata(httpRequest));
        return authResponse(HttpStatus.CREATED, result);
    }

    @Operation(summary = "Sign in",
            description = "Authenticates an account and sets the refresh cookie.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Signed in"),
            @ApiResponse(responseCode = "400", description = "Malformed body"),
            @ApiResponse(responseCode = "401", description = "Invalid email or password"),
            @ApiResponse(responseCode = "429", description = "Login rate limit exceeded")
    })
    @RateLimited(RateLimitScope.AUTH_LOGIN)
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest) {

        AuthResult result = authService.login(request, metadata(httpRequest));
        return authResponse(HttpStatus.OK, result);
    }

    @Operation(summary = "Rotate the refresh session",
            description = "Reads the refresh cookie, rotates it and returns a new access token.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Session rotated"),
            @ApiResponse(responseCode = "401", description = "Missing, expired or reused refresh cookie")
    })
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(HttpServletRequest httpRequest) {
        AuthResult result = authService.refresh(readRefreshToken(httpRequest), metadata(httpRequest));
        return authResponse(HttpStatus.OK, result);
    }

    @Operation(summary = "Sign out",
            description = "Revokes the presented refresh session and clears the cookie.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Signed out (idempotent)")
    })
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest httpRequest) {
        authService.logout(readRefreshToken(httpRequest));

        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshCookies.clear().toString())
                .cacheControl(CacheControl.noStore())
                .build();
    }

    @Operation(summary = "Get the current profile")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Authenticated profile"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid access token")
    })
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/me")
    public ResponseEntity<UserProfile> me(
            @AuthenticationPrincipal AuthenticatedUser principal) {

        if (principal == null) {
            throw new AccountUnavailableException();
        }

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(authService.profile(principal.id()));
    }

    /**
     * Refresh failures must also clear the cookie. This handler lives on the
     * controller so the general API advice stays free of auth-cookie concerns.
     */
    @ExceptionHandler(InvalidRefreshTokenException.class)
    ResponseEntity<ProblemDetail> invalidRefresh(
            InvalidRefreshTokenException ex, HttpServletRequest request) {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, ex.getMessage());
        problem.setTitle("Invalid session");
        problem.setInstance(URI.create(request.getRequestURI()));

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.SET_COOKIE, refreshCookies.clear().toString())
                .cacheControl(CacheControl.noStore())
                .body(problem);
    }

    private ResponseEntity<AuthResponse> authResponse(HttpStatus status, AuthResult result) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, refreshCookies.create(result.refreshToken()).toString())
                .cacheControl(CacheControl.noStore())
                .body(AuthResponse.from(result));
    }

    private String readRefreshToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        String name = refreshCookies.cookieName();
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private ClientMetadata metadata(HttpServletRequest request) {
        String userAgent = request.getHeader(HttpHeaders.USER_AGENT);
        if (userAgent != null) {
            userAgent = userAgent.trim();
            if (userAgent.isEmpty()) {
                userAgent = null;
            } else if (userAgent.length() > 512) {
                userAgent = userAgent.substring(0, 512);
            }
        }
        return new ClientMetadata(userAgent, clientIpResolver.key(request));
    }
}
