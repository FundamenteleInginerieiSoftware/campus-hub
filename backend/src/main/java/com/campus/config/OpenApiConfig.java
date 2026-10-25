package com.campus.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/**
 * Adds the "Authorize" button to Swagger UI (http://localhost:8080/swagger-ui.html). Log in with
 * POST /api/v1/auth/login, paste the accessToken there, and Swagger sends it as "Authorization:
 * Bearer ..." on every call.
 *
 * <p>The scheme applies to all endpoints, so teammates don't annotate their controllers for it.
 * Public ones (ping, login, register) simply ignore the header.
 */
@Configuration
@OpenAPIDefinition(
        info = @Info(title = "Campus Equipment & Incident Hub API", version = "v1"),
        security = @SecurityRequirement(name = "bearerAuth"))
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
public class OpenApiConfig {}
