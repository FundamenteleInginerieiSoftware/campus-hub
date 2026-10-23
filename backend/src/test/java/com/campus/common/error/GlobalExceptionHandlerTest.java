package com.campus.common.error;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.campus.common.paging.PageResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ProblemDetail;

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
    void hidesInternalDetailsOnUnexpectedException() {
        ProblemDetail problem = handler.handleUnexpected(new RuntimeException("select * from secret_table failed"));

        assertEquals(500, problem.getStatus());
        assertEquals("An unexpected error occurred", problem.getDetail());
        assertEquals("INTERNAL_ERROR", problem.getProperties().get("code"));
    }

    @Test
    void convertsSpringPageToPageResponse() {
        Page<String> springPage = new PageImpl<>(List.of("a", "b"), PageRequest.of(0, 2), 5);

        PageResponse<String> response = PageResponse.from(springPage, String::toUpperCase);

        assertEquals(List.of("A", "B"), response.content());
        assertEquals(0, response.page());
        assertEquals(2, response.size());
        assertEquals(5L, response.totalElements());
        assertEquals(3, response.totalPages());
    }
}
