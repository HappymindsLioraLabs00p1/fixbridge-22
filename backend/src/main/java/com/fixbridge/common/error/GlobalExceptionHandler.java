package com.fixbridge.common.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

/**
 * Converts exceptions into safe {@link ApiError} bodies. Requirement: never display raw HTML,
 * JSON errors or stack traces to the client (spec §10.5, §17).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(ApiError.of(ex.getStatus().value(), ex.getStatus().getReasonPhrase(), ex.getMessage(), List.of()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::formatFieldError)
                .toList();
        return ResponseEntity.badRequest()
                .body(ApiError.of(400, "Bad Request", "Validation failed", details));
    }

    /**
     * A body that couldn't be parsed, or a path variable of the wrong type — a malformed UUID being
     * the common case. These are the caller's mistake, so they get a 400 rather than the 500 that
     * fell out of the catch-all and made a client error look like a server fault.
     *
     * <p>The parser's own message is not returned: it quotes the offending input, which may be
     * anything the caller sent.
     */
    @ExceptionHandler({HttpMessageNotReadableException.class,
                       MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> handleMalformedRequest(Exception ex) {
        log.debug("Malformed request", ex);
        String detail = ex instanceof MethodArgumentTypeMismatchException mismatch
                ? "Invalid value for '" + mismatch.getName() + "'"
                : "The request body could not be read";
        return ResponseEntity.badRequest()
                .body(ApiError.of(400, "Bad Request", detail, List.of()));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiError> handleBadCredentials(BadCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiError.of(401, "Unauthorized", "Invalid email or password", List.of()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of(403, "Forbidden", "You are not authorized to access this resource", List.of()));
    }

    /**
     * An unmatched path is a 404, not a server fault. Without this, every unknown URL — including
     * ordinary scanner traffic — is reported as a 500 and buries real failures in the logs.
     */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(
            org.springframework.web.servlet.resource.NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of(HttpStatus.NOT_FOUND.value(), "Not Found", "Not found", List.of()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        // Log the real cause server-side; return a generic message to the client.
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of(500, "Internal Server Error", "Something went wrong. Please try again.", List.of()));
    }

    private static String formatFieldError(FieldError fe) {
        return fe.getField() + ": " + fe.getDefaultMessage();
    }
}
