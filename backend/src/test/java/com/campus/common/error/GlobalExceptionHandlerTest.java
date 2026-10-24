package com.campus.common.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.campus.common.error.GlobalExceptionHandler.FieldErrorItem;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void mapsNotFoundExceptionToProblemDetail() {
        NotFoundException ex = new NotFoundException("ASSET_NOT_FOUND", "Asset 12 not found");

        ProblemDetail problem = handler.handleAppException(ex);

        assertEquals(404, problem.getStatus());
        assertEquals("Not Found", problem.getTitle());
        assertEquals("Asset 12 not found", problem.getDetail());
        assertEquals("ASSET_NOT_FOUND", problem.getProperties().get("code"));
    }

    @Test
    void mapsUnauthorizedExceptionTo401() {
        UnauthorizedException ex = new UnauthorizedException("INVALID_CREDENTIALS", "Invalid email or password");

        ProblemDetail problem = handler.handleAppException(ex);

        assertEquals(401, problem.getStatus());
        assertEquals("Unauthorized", problem.getTitle());
        assertEquals("INVALID_CREDENTIALS", problem.getProperties().get("code"));
    }

    @Test
    void mapsConflictAndStateTransitionExceptionsTo409() {
        ConflictException conflict = new ConflictException("SERIAL_NUMBER_TAKEN", "Serial already exists");
        InvalidStateTransitionException transition =
                new InvalidStateTransitionException("INVALID_ASSET_STATUS_TRANSITION", "Asset is retired");

        ProblemDetail conflictProblem = handler.handleAppException(conflict);
        ProblemDetail transitionProblem = handler.handleAppException(transition);

        assertEquals(409, conflictProblem.getStatus());
        assertEquals("SERIAL_NUMBER_TAKEN", conflictProblem.getProperties().get("code"));
        assertEquals(409, transitionProblem.getStatus());
        assertEquals(
                "INVALID_ASSET_STATUS_TRANSITION",
                transitionProblem.getProperties().get("code"));
    }

    @Test
    void mapsValidationErrorsWithFieldList() {
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        BindingResult bindingResult = mock(BindingResult.class);
        when(ex.getBindingResult()).thenReturn(bindingResult);
        when(bindingResult.getFieldErrors())
                .thenReturn(List.of(
                        new FieldError("registerRequest", "email", "must be a well-formed email address"),
                        new FieldError("registerRequest", "password", "size must be between 8 and 72")));

        ProblemDetail problem = handler.handleValidation(ex);

        assertEquals(400, problem.getStatus());
        assertEquals("Validation failed", problem.getDetail());
        assertEquals("VALIDATION_FAILED", problem.getProperties().get("code"));
        assertEquals(
                List.of(
                        new FieldErrorItem("email", "must be a well-formed email address"),
                        new FieldErrorItem("password", "size must be between 8 and 72")),
                problem.getProperties().get("errors"));
    }

    @Test
    void hidesJavaClassNamesOnTypeMismatch() {
        MethodArgumentTypeMismatchException ex =
                new MethodArgumentTypeMismatchException("abc", Long.class, "id", null, new NumberFormatException());

        ProblemDetail problem = handler.handleTypeMismatch(ex);

        assertEquals(400, problem.getStatus());
        assertEquals("INVALID_PARAMETER", problem.getProperties().get("code"));
        assertEquals("Invalid value for parameter 'id'", problem.getDetail());
    }

    @Test
    void preservesSpringErrorResponseStatusInUnexpectedHandler() {
        NoResourceFoundException ex = new NoResourceFoundException(HttpMethod.GET, "api/v1/unknown", "api/v1/unknown");

        ProblemDetail problem = handler.handleUnexpected(ex);

        assertEquals(HttpStatus.NOT_FOUND.value(), problem.getStatus());
        assertEquals("NOT_FOUND", problem.getProperties().get("code"));
    }

    @Test
    void hidesInternalDetailsOnUnexpectedException() {
        ProblemDetail problem = handler.handleUnexpected(new RuntimeException("select * from secret_table failed"));

        assertEquals(500, problem.getStatus());
        assertEquals("An unexpected error occurred", problem.getDetail());
        assertEquals("INTERNAL_ERROR", problem.getProperties().get("code"));
    }
}
