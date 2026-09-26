package com.fooddelivery.common.error;

import com.fooddelivery.security.JwtAuthenticationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.Instant;
import java.util.List;

/**
 * Turns every exception thrown from a controller into an RFC 9457 Problem Details response
 * with two extra fields: "code" (stable ErrorCode) and "timestamp".
 * Extending ResponseEntityExceptionHandler covers Spring's own MVC errors (malformed JSON,
 * type mismatch, unsupported method, ...) so they share the same shape.
 * Security-filter errors (401/403 before a controller runs) are handled in the security config.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    static final String CODE = "code";
    static final String TIMESTAMP = "timestamp";
    static final String ERRORS = "errors";

    // ---- our business exceptions ----

    @ExceptionHandler(ApiException.class)
    public ProblemDetail handleApiException(ApiException ex) {
        log.warn("{} {}: {}", ex.getStatus().value(), ex.getCode(), ex.getMessage());
        return problem(ex.getStatus(), ex.getCode(), ex.getMessage());
    }

    /** 503/429 with Retry-After, so well-behaved clients back off instead of hammering. */
    @ExceptionHandler(RetryLaterException.class)
    public ResponseEntity<ProblemDetail> handleRetryLater(RetryLaterException ex) {
        log.warn("{} {}: {}", ex.getStatus().value(), ex.getCode(), ex.getMessage());
        return ResponseEntity.status(ex.getStatus())
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
                .body(problem(ex.getStatus(), ex.getCode(), ex.getMessage()));
    }

    /** Lock wait timeout / deadlock that survived the retry: the DB is overloaded, so 503, not 500. */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleLockFailure(PessimisticLockingFailureException ex) {
        return handleRetryLater(new RetryLaterException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.SERVICE_BUSY,
                "The system is busy, please retry shortly", 2));
    }

    // ---- security ----

    /**
     * 401. Reached from controllers (login) and, via ProblemDetailsSecurityHandler, from the filter chain.
     * Bad credentials get one generic message so callers can't tell unknown email from wrong password.
     */
    @ExceptionHandler(AuthenticationException.class)
    public ProblemDetail handleAuthentication(AuthenticationException ex) {
        ProblemDetail pd;
        if (ex instanceof JwtAuthenticationException jwt) {
            pd = problem(HttpStatus.UNAUTHORIZED, jwt.getCode(), jwt.getMessage());
        } else if (ex instanceof BadCredentialsException) {
            pd = problem(HttpStatus.UNAUTHORIZED, ErrorCode.INVALID_CREDENTIALS, "Invalid email or password");
        } else if (ex instanceof DisabledException) {
            pd = problem(HttpStatus.UNAUTHORIZED, ErrorCode.ACCOUNT_DISABLED, "Account is disabled");
        } else {
            pd = problem(HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED, "Authentication required");
        }
        log.warn("401 {}: {}", pd.getProperties().get(CODE), ex.getMessage());
        return pd;
    }

    /** 403. Without this, @PreAuthorize denials would fall into the catch-all and become 500s. */
    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        log.warn("403 ACCESS_DENIED: {}", ex.getMessage());
        return problem(HttpStatus.FORBIDDEN, ErrorCode.ACCESS_DENIED,
                "You do not have permission to perform this action");
    }

    // ---- validation ----

    /** @Valid @RequestBody failed: report every invalid field at once. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldViolation(fe.getField(), fe.getDefaultMessage()))
                .toList();
        return validationResponse(errors);
    }

    /** Constraints on @PathVariable / @RequestParam (e.g. @Min(0) page). */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> errors = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(err -> new FieldViolation(
                                result.getMethodParameter().getParameterName(), err.getDefaultMessage())))
                .toList();
        return validationResponse(errors);
    }

    // ---- persistence / concurrency ----

    /** A DB constraint caught something (e.g. duplicate submit race). Never expose the SQL message. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        return problem(HttpStatus.CONFLICT, ErrorCode.DATA_INTEGRITY_VIOLATION,
                "Request conflicts with existing data");
    }

    /** @Version check failed: someone else changed the row first. */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ProblemDetail handleOptimisticLock(ObjectOptimisticLockingFailureException ex) {
        log.warn("Optimistic lock failure: {}", ex.getMessage());
        return problem(HttpStatus.CONFLICT, ErrorCode.CONCURRENT_MODIFICATION,
                "The resource was modified concurrently, please retry");
    }

    // ---- everything else ----

    /** Unexpected bug: full stack trace to the log, generic message to the client. */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR, "An unexpected error occurred");
    }

    /**
     * Hook for Spring's built-in MVC exceptions (malformed JSON, wrong param type, 405, ...).
     * They already produce a ProblemDetail; we add our code + timestamp for a uniform shape.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        // Some exceptions (e.g. NoResourceFoundException) arrive with body == null; Spring would
        // build the ProblemDetail later inside super, so build it here to be able to enrich it.
        if (body == null && ex instanceof ErrorResponse errorResponse) {
            body = errorResponse.getBody();
        }
        if (body instanceof ProblemDetail pd
                && (pd.getProperties() == null || !pd.getProperties().containsKey(CODE))) {
            pd.setProperty(CODE, codeFor(status));
            pd.setProperty(TIMESTAMP, Instant.now());
        }
        return super.handleExceptionInternal(ex, body, headers, status, request);
    }

    // ---- helpers ----

    private ResponseEntity<Object> validationResponse(List<FieldViolation> errors) {
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
                "Request has " + errors.size() + " invalid field(s)");
        pd.setProperty(ERRORS, errors);
        return ResponseEntity.badRequest().body(pd);
    }

    private static ProblemDetail problem(HttpStatus status, ErrorCode code, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setProperty(CODE, code);
        pd.setProperty(TIMESTAMP, Instant.now());
        return pd;
    }

    private static ErrorCode codeFor(HttpStatusCode status) {
        if (status.value() == 404) {
            return ErrorCode.RESOURCE_NOT_FOUND;
        }
        return status.is4xxClientError() ? ErrorCode.INVALID_REQUEST : ErrorCode.INTERNAL_ERROR;
    }

    record FieldViolation(String field, String message) {
    }
}
