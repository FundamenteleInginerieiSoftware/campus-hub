package com.campus.config;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Checks that errors raised inside Spring MVC (validation, bad JSON, unknown path) reach Liviu's
 * GlobalExceptionHandler and keep the "code" the frontend relies on (docs/api-contract.md §4).
 * Spring Boot's own error handler used to answer these first, without a code.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ErrorJsonTest.EchoController.class)
class ErrorJsonTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void validationErrorHasCodeAndFieldErrors() throws Exception {
        mockMvc.perform(post("/api/v1/test-echo")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @Test
    void unreadableJsonHasCode() throws Exception {
        mockMvc.perform(post("/api/v1/test-echo")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void unknownPathHasCode() throws Exception {
        mockMvc.perform(get("/api/v1/does-not-exist").with(jwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // Test-only endpoint: main has no endpoint with a @Valid body yet.
    @RestController
    static class EchoController {

        record EchoRequest(@NotBlank String name) {}

        @PostMapping("/api/v1/test-echo")
        EchoRequest echo(@Valid @RequestBody EchoRequest request) {
            return request;
        }
    }
}
