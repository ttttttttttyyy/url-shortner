package com.url.shortly.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.url.shortly.service.exeptions.AliasNotAllowedException;
import com.url.shortly.service.exeptions.InvalidAliasException;
import com.url.shortly.service.exeptions.InvalidExpiryException;
import com.url.shortly.service.exeptions.InvalidUrlException;
import com.url.shortly.service.exeptions.PasswordNotAllowedException;
import com.url.shortly.service.exeptions.ShortCodeConflictException;
import com.url.shortly.service.exeptions.ShortCodeGenerationException;

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
}
