package com.campus.common.error;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import java.net.URI;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Converts exceptions thrown across all modules into RFC 9457 {@link ProblemDetail} JSON responses
 * with a machine-readable {@code code} property matching {@code docs/api-contract.md}.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    public record FieldErrorItem(String field, String message) {}

    private ProblemDetail buildProblem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("about:blank"));
        problem.setProperty("code", code);
        return problem;
    }

    @ExceptionHandler(AppException.class)
    public ProblemDetail handleAppException(AppException ex) {
        return buildProblem(ex.getStatus(), ex.getCode(), ex.getMessage());
    }

    // Collects all field validation errors in a list so multiple violations on the same field are preserved.
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        List<FieldErrorItem> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> new FieldErrorItem(err.getField(), err.getDefaultMessage()))
                .toList();
        ProblemDetail problem = buildProblem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Validation failed");
        problem.setProperty("errors", errors);
        return problem;
    }

    // Handles parameter-level constraints (e.g. @Min/@Max on @RequestParam) from @Validated components.
    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException ex) {
        List<FieldErrorItem> errors = ex.getConstraintViolations().stream()
                .map(v -> new FieldErrorItem(extractPropertyName(v), v.getMessage()))
                .toList();
        ProblemDetail problem = buildProblem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Validation failed");
        problem.setProperty("errors", errors);
        return problem;
    }

    // Handles Spring's controller method parameter validation (Spring 6.1+ / Boot 4).
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ProblemDetail handleHandlerMethodValidation(HandlerMethodValidationException ex) {
        List<FieldErrorItem> errors = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(err -> new FieldErrorItem(
                                result.getMethodParameter().getParameterName(), err.getDefaultMessage())))
                .toList();
        ProblemDetail problem = buildProblem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Validation failed");
        problem.setProperty("errors", errors);
        return problem;
    }

    private String extractPropertyName(ConstraintViolation<?> violation) {
        Path path = violation.getPropertyPath();
        if (path == null) {
            return "";
        }
        String leaf = null;
        try {
            java.util.Iterator<Path.Node> it = path.iterator();
            if (it != null) {
                while (it.hasNext()) {
                    leaf = it.next().getName();
                }
            }
        } catch (Exception ignored) {
            // Mocks or custom Path implementations may not support iterator()
        }
        return (leaf != null && !leaf.isBlank()) ? leaf : path.toString();
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleMalformedRequest(HttpMessageNotReadableException ex) {
        return buildProblem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Malformed request body");
    }

    // Uses a clean message instead of ex.getMessage() so internal Java class names are never leaked to the client.
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return buildProblem(
                HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", "Invalid value for parameter '" + ex.getName() + "'");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ProblemDetail handleMissingParameter(MissingServletRequestParameterException ex) {
        return buildProblem(
                HttpStatus.BAD_REQUEST,
                "INVALID_PARAMETER",
                "Required parameter '" + ex.getParameterName() + "' is missing");
    }

    @ExceptionHandler(PropertyReferenceException.class)
    public ProblemDetail handleInvalidSortProperty(PropertyReferenceException ex) {
        return buildProblem(
                HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", "Unknown sort property '" + ex.getPropertyName() + "'");
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ProblemDetail handleStaleVersion(ObjectOptimisticLockingFailureException ex) {
        return buildProblem(
                HttpStatus.CONFLICT,
                "STALE_VERSION",
                "The resource was modified by another request; please reload and try again");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        return buildProblem(
                HttpStatus.CONTENT_TOO_LARGE, "PHOTO_TOO_LARGE", "Uploaded file exceeds the maximum allowed size");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        if (ex instanceof ErrorResponse spring) {
            HttpStatus status = HttpStatus.valueOf(spring.getStatusCode().value());
            return buildProblem(status, status.name(), spring.getBody().getDetail());
        }
        log.error("Unexpected error", ex);
        return buildProblem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred");
    }
}
