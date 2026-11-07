package com.campus.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.List;
import java.util.TreeMap;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Adds the "Authorize" button to Swagger UI (http://localhost:8080/swagger-ui.html). Log in with
 * POST /api/v1/auth/login, paste the accessToken there, and Swagger sends it as "Authorization:
 * Bearer ..." on every call.
 *
 * <p>The scheme applies to all endpoints, so teammates don't annotate their controllers for it.
 */
@Configuration
@OpenAPIDefinition(
        info = @Info(title = "Campus Equipment & Incident Hub API", version = "v1"),
        security = @SecurityRequirement(name = "bearerAuth"))
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
public class OpenApiConfig {

    /**
     * Makes Swagger show what SecurityConfig and the error handlers really do, so nobody repeats it on
     * every endpoint. springdoc calls this once, after it has read all the controllers:
     *
     * <ul>
     *   <li>/api/v1/auth/** loses the lock icon (no token needed);
     *   <li>every other endpoint gets a 401, and /api/v1/admin/** also a 403 (a hand-written one is
     *       kept);
     *   <li>every 4xx/5xx shows the ProblemDetail body. springdoc would otherwise copy the success
     *       type onto a hand-written {@code @ApiResponse(responseCode = "409")}, as if a conflict
     *       returned a reservation.
     * </ul>
     */
    @Bean
    OpenApiCustomizer securityAndErrorResponses() {
        return openApi -> {
            openApi.getComponents().addSchemas("ProblemDetail", problemDetailSchema());
            openApi.getPaths()
                    .forEach((path, item) -> item.readOperations().forEach(operation -> {
                        ApiResponses responses = operation.getResponses();
                        if (path.startsWith("/api/v1/auth/")) {
                            operation.setSecurity(List.of()); // an empty list overrides the global bearerAuth
                        } else {
                            responses.putIfAbsent(
                                    "401",
                                    new ApiResponse()
                                            .description(
                                                    "No token, an invalid or expired one, or a disabled or deleted account (UNAUTHORIZED)"));
                            if (path.startsWith("/api/v1/admin/")) {
                                responses.putIfAbsent(
                                        "403",
                                        new ApiResponse().description("Logged in, but not an admin (FORBIDDEN)"));
                            }
                        }
                        // Rebuilt in code order: putIfAbsent adds at the end, and Swagger lists them as given.
                        ApiResponses sorted = new ApiResponses();
                        new TreeMap<>(responses).forEach((code, response) -> {
                            if (code.startsWith("4") || code.startsWith("5")) {
                                response.setContent(problemContent());
                            }
                            sorted.addApiResponse(code, response);
                        });
                        operation.setResponses(sorted);
                    }));
        };
    }

    private static Content problemContent() {
        return new Content()
                .addMediaType(
                        "application/problem+json",
                        new MediaType().schema(new Schema<>().$ref("#/components/schemas/ProblemDetail")));
    }

    // The error body from docs/api-contract.md section 4 (SecurityErrorHandler and Liviu's
    // GlobalExceptionHandler both write it). Written by hand: Spring's ProblemDetail class keeps
    // "code" in a map, which springdoc would show as an extra "properties" object.
    private static Schema<?> problemDetailSchema() {
        return new ObjectSchema()
                .addProperty("type", new StringSchema().example("about:blank"))
                .addProperty("title", new StringSchema().example("Conflict"))
                .addProperty("status", new IntegerSchema().example(409))
                .addProperty(
                        "detail", new StringSchema().example("Requested slot overlaps with an existing reservation"))
                .addProperty("instance", new StringSchema().example("/api/v1/reservations"))
                .addProperty("code", new StringSchema().example("RESERVATION_CONFLICT"))
                .addProperty(
                        "errors",
                        new ArraySchema()
                                .description("Only on 400 VALIDATION_FAILED: one entry per broken field rule")
                                .items(new ObjectSchema()
                                        .addProperty("field", new StringSchema())
                                        .addProperty("message", new StringSchema())));
    }
}
