package com.url.shortly.controller;

import java.net.URI;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import com.url.shortly.service.exeptions.AccountUnavailableException;
import com.url.shortly.service.exeptions.AliasNotAllowedException;
import com.url.shortly.service.exeptions.EmailAlreadyRegisteredException;
import com.url.shortly.service.exeptions.InvalidAliasException;
import com.url.shortly.service.exeptions.IncorrectLinkPasswordException;
import com.url.shortly.service.exeptions.InvalidCredentialsException;
import com.url.shortly.service.exeptions.InvalidExpiryException;
import com.url.shortly.service.exeptions.InvalidLinkPasswordException;
import com.url.shortly.service.exeptions.InvalidLinkUpdateException;
import com.url.shortly.service.exeptions.InvalidPasswordException;
import com.url.shortly.service.exeptions.InvalidUrlException;
import com.url.shortly.service.exeptions.LinkNotProtectedException;
import com.url.shortly.service.exeptions.LinkNotFoundException;
import com.url.shortly.service.exeptions.LinkNotVerifiableException;
import com.url.shortly.service.exeptions.PasswordNotAllowedException;
import com.url.shortly.service.exeptions.RateLimitExceededException;
import com.url.shortly.service.exeptions.ShortCodeConflictException;
import com.url.shortly.service.exeptions.ShortCodeGenerationException;
import com.url.shortly.service.exeptions.StatusChangeNotAllowedException;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Maps domain exceptions to RFC 9457 problem-detail responses.
 *
 * <p>Without this advice every failure would surface as an opaque 500.
 * Handlers for {@link AliasNotAllowedException} and
 * {@link InvalidAliasException} are separate so Spring resolves the most
 * specific match first: entitlement failures become {@code 403}, format and
 * reserved-word failures become {@code 422}.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(InvalidUrlException.class)
    ProblemDetail invalidUrl(InvalidUrlException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid destination URL", ex.getMessage());
    }

    @ExceptionHandler(InvalidExpiryException.class)
    ProblemDetail invalidExpiry(InvalidExpiryException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid expiration", ex.getMessage());
    }

    @ExceptionHandler(PasswordNotAllowedException.class)
    ProblemDetail passwordNotAllowed(PasswordNotAllowedException ex) {
        return problem(HttpStatus.FORBIDDEN, "Password protection not available", ex.getMessage());
    }

    @ExceptionHandler(AliasNotAllowedException.class)
    ProblemDetail aliasNotAllowed(AliasNotAllowedException ex) {
        return problem(HttpStatus.FORBIDDEN, "Custom aliases require the paid plan", ex.getMessage());
    }

    @ExceptionHandler(InvalidAliasException.class)
    ProblemDetail invalidAlias(InvalidAliasException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid alias", ex.getMessage());
    }

    @ExceptionHandler(ShortCodeConflictException.class)
    ProblemDetail conflict(ShortCodeConflictException ex) {
        return problem(HttpStatus.CONFLICT, "Short code already taken", ex.getMessage());
    }

    @ExceptionHandler(ShortCodeGenerationException.class)
    ProblemDetail exhausted(ShortCodeGenerationException ex) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE,
                "Could not allocate a short code", ex.getMessage());
    }

    @ExceptionHandler(LinkNotFoundException.class)
    ResponseEntity<ProblemDetail> linkNotFound(LinkNotFoundException ex, HttpServletRequest request) {
        return problemResponse(HttpStatus.NOT_FOUND, "Link not found", ex.getMessage(), request);
    }

    @ExceptionHandler(InvalidLinkUpdateException.class)
    ResponseEntity<ProblemDetail> invalidLinkUpdate(InvalidLinkUpdateException ex,
                                                     HttpServletRequest request) {
        return problemResponse(
                HttpStatus.UNPROCESSABLE_ENTITY, "Invalid link update", ex.getMessage(), request);
    }

    @ExceptionHandler(InvalidLinkPasswordException.class)
    ProblemDetail invalidLinkPassword(InvalidLinkPasswordException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid link password", ex.getMessage());
    }

    @ExceptionHandler(LinkNotProtectedException.class)
    ProblemDetail linkNotProtected(LinkNotProtectedException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Link not protected", ex.getMessage());
    }

    @ExceptionHandler(LinkNotVerifiableException.class)
    ProblemDetail linkNotVerifiable(LinkNotVerifiableException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Link not verifiable", ex.getMessage());
    }

    @ExceptionHandler(IncorrectLinkPasswordException.class)
    ResponseEntity<ProblemDetail> incorrectLinkPassword(
            IncorrectLinkPasswordException ex, HttpServletRequest request) {
        return problemResponse(HttpStatus.UNAUTHORIZED, "Incorrect password",
                ex.getMessage(), request);
    }

    @ExceptionHandler(StatusChangeNotAllowedException.class)
    ResponseEntity<ProblemDetail> statusChangeNotAllowed(StatusChangeNotAllowedException ex,
                                                          HttpServletRequest request) {
        return problemResponse(HttpStatus.FORBIDDEN, "Status change not allowed",
                ex.getMessage(), request);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> concurrentModification(OptimisticLockingFailureException ex,
                                                          HttpServletRequest request) {
        return problemResponse(HttpStatus.CONFLICT, "Concurrent modification",
                "The link was changed by another request. Reload it and retry.", request);
    }

    @ExceptionHandler(EmailAlreadyRegisteredException.class)
    ProblemDetail emailAlreadyRegistered(EmailAlreadyRegisteredException ex) {
        return problem(HttpStatus.CONFLICT, "Email already registered", ex.getMessage());
    }

    @ExceptionHandler(InvalidPasswordException.class)
    ProblemDetail invalidPassword(InvalidPasswordException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid password", ex.getMessage());
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    ResponseEntity<ProblemDetail> invalidCredentials(InvalidCredentialsException ex) {
        ProblemDetail problem = problem(
                HttpStatus.UNAUTHORIZED, "Invalid credentials", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"shortly\"")
                .cacheControl(CacheControl.noStore())
                .body(problem);
    }

    @ExceptionHandler(AccountUnavailableException.class)
    ResponseEntity<ProblemDetail> accountUnavailable(AccountUnavailableException ex) {
        ProblemDetail problem = problem(
                HttpStatus.UNAUTHORIZED, "Authentication required", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"shortly\"")
                .cacheControl(CacheControl.noStore())
                .body(problem);
    }

    @ExceptionHandler(RateLimitExceededException.class)
    ResponseEntity<ProblemDetail> rateLimited(RateLimitExceededException ex, HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.TOO_MANY_REQUESTS, "Too many requests", ex.getMessage());
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("policy", ex.policy());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(ex.retryAfterSeconds()))
                .cacheControl(CacheControl.noStore())
                .body(problem);
    }

    /** Keeps framework-level 4xx errors in the same RFC 9457 contract. */
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ProblemDetail> responseStatus(ResponseStatusException ex,
                                                  HttpServletRequest request) {
        HttpStatusCode code = ex.getStatusCode();
        HttpStatus resolved = HttpStatus.resolve(code.value());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code, ex.getReason());
        problem.setTitle(resolved == null ? "Request failed" : resolved.getReasonPhrase());
        problem.setInstance(URI.create(request.getRequestURI()));
        return ResponseEntity.status(code)
                .cacheControl(CacheControl.noStore())
                .body(problem);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemDetail> typeMismatch(MethodArgumentTypeMismatchException ex,
                                                HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST,
                "Invalid request parameter", "Invalid value for " + ex.getName());
        problem.setInstance(URI.create(request.getRequestURI()));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .cacheControl(CacheControl.noStore())
                .body(problem);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalidBody(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .findFirst()
                .orElse("Request body failed validation");
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", detail);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail unreadableBody(HttpMessageNotReadableException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", "Malformed request body");
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }

    private ResponseEntity<ProblemDetail> problemResponse(HttpStatus status, String title,
                                                           String detail, HttpServletRequest request) {
        ProblemDetail problem = problem(status, title, detail);
        problem.setInstance(URI.create(request.getRequestURI()));
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(problem);
    }
}
