package com.campus.common.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.campus.common.error.GlobalExceptionHandler.FieldErrorItem;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
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
    void mapsConstraintViolationExceptionWithFieldList() {
        @SuppressWarnings("unchecked")
        ConstraintViolation<Object> violation = mock(ConstraintViolation.class);
        Path path = mock(Path.class);
        when(path.toString()).thenReturn("getLogs.page");
        when(violation.getPropertyPath()).thenReturn(path);
        when(violation.getMessage()).thenReturn("must be greater than or equal to 0");

        ConstraintViolationException ex = new ConstraintViolationException("Validation failed", Set.of(violation));

        ProblemDetail problem = handler.handleConstraintViolation(ex);

        assertEquals(400, problem.getStatus());
        assertEquals("VALIDATION_FAILED", problem.getProperties().get("code"));
        assertEquals("Validation failed", problem.getDetail());
        @SuppressWarnings("unchecked")
        List<FieldErrorItem> errors =
                (List<FieldErrorItem>) problem.getProperties().get("errors");
        assertEquals(1, errors.size());
        assertEquals("getLogs.page", errors.getFirst().field());
        assertEquals("must be greater than or equal to 0", errors.getFirst().message());
    }

    @Test
    void mapsConstraintViolationExtractingLeafPropertyNameWhenNodesPresent() {
        @SuppressWarnings("unchecked")
        ConstraintViolation<Object> violation = mock(ConstraintViolation.class);
        Path path = mock(Path.class);
        Path.Node methodNode = mock(Path.Node.class);
        when(methodNode.getName()).thenReturn("getLogs");
        Path.Node paramNode = mock(Path.Node.class);
        when(paramNode.getName()).thenReturn("page");
        when(path.iterator()).thenReturn(List.of(methodNode, paramNode).iterator());
        when(violation.getPropertyPath()).thenReturn(path);
        when(violation.getMessage()).thenReturn("must be greater than or equal to 0");

        ConstraintViolationException ex = new ConstraintViolationException("Validation failed", Set.of(violation));

        ProblemDetail problem = handler.handleConstraintViolation(ex);

        assertEquals(400, problem.getStatus());
        @SuppressWarnings("unchecked")
        List<FieldErrorItem> errors =
                (List<FieldErrorItem>) problem.getProperties().get("errors");
        assertEquals("page", errors.getFirst().field());
    }

    @Test
    void mapsHandlerMethodValidationExceptionWithParameterErrors() {
        HandlerMethodValidationException ex = mock(HandlerMethodValidationException.class);
        ParameterValidationResult result = mock(ParameterValidationResult.class);
        MethodParameter methodParam = mock(MethodParameter.class);
        when(methodParam.getParameterName()).thenReturn("size");
        when(result.getMethodParameter()).thenReturn(methodParam);
        MessageSourceResolvable resolvable = mock(MessageSourceResolvable.class);
        when(resolvable.getDefaultMessage()).thenReturn("must be less than or equal to 100");
        when(result.getResolvableErrors()).thenReturn(List.of(resolvable));
        when(ex.getParameterValidationResults()).thenReturn(List.of(result));

        ProblemDetail problem = handler.handleHandlerMethodValidation(ex);

        assertEquals(400, problem.getStatus());
        assertEquals("VALIDATION_FAILED", problem.getProperties().get("code"));
        @SuppressWarnings("unchecked")
        List<FieldErrorItem> errors =
                (List<FieldErrorItem>) problem.getProperties().get("errors");
        assertEquals(List.of(new FieldErrorItem("size", "must be less than or equal to 100")), errors);
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
