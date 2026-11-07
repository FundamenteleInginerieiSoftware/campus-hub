package com.campus.reservation.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.stereotype.Component;

/**
 * Dynamically updates OpenAPI example timestamps for reservation endpoints.
 * <p>
 * Fixed timestamp examples (e.g. {@code 2026-11-10T14:00:00}) expire after two weeks due to the
 * 14-day {@code BOOKING_TOO_FAR_AHEAD} limit and weekday/opening-hours validation rules. This
 * customizer calculates the next upcoming weekday at 14:00-16:00 using the shared {@link Clock},
 * ensuring "Try it out" in Swagger UI always provides a valid booking slot for live demos and defense.
 */
@Component
@RequiredArgsConstructor
public class ReservationOpenApiCustomizer implements OpenApiCustomizer {

    private final Clock clock;

    @Override
    @SuppressWarnings("rawtypes")
    public void customise(OpenAPI openApi) {
        LocalDate nextWeekday = LocalDate.now(clock).plusDays(1);
        while (nextWeekday.getDayOfWeek() == DayOfWeek.SATURDAY || nextWeekday.getDayOfWeek() == DayOfWeek.SUNDAY) {
            nextWeekday = nextWeekday.plusDays(1);
        }

        String startExample = nextWeekday.atTime(14, 0).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        String endExample = nextWeekday.atTime(16, 0).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

        // 1. Update CreateReservationRequest schema properties (request body example in Swagger UI)
        if (openApi.getComponents() != null && openApi.getComponents().getSchemas() != null) {
            Schema<?> requestSchema = openApi.getComponents().getSchemas().get("CreateReservationRequest");
            if (requestSchema != null && requestSchema.getProperties() != null) {
                Map<String, Schema> properties = requestSchema.getProperties();
                Schema startTimeSchema = properties.get("startTime");
                if (startTimeSchema != null) {
                    startTimeSchema.setExample(startExample);
                }
                Schema endTimeSchema = properties.get("endTime");
                if (endTimeSchema != null) {
                    endTimeSchema.setExample(endExample);
                }
            }
        }

        // 2. Update GET /api/v1/reservations/availability query parameter examples
        if (openApi.getPaths() != null && openApi.getPaths().get("/api/v1/reservations/availability") != null) {
            var pathItem = openApi.getPaths().get("/api/v1/reservations/availability");
            if (pathItem.getGet() != null && pathItem.getGet().getParameters() != null) {
                for (Parameter param : pathItem.getGet().getParameters()) {
                    if ("startTime".equals(param.getName())) {
                        param.setExample(startExample);
                        if (param.getSchema() != null) {
                            param.getSchema().setExample(startExample);
                        }
                    } else if ("endTime".equals(param.getName())) {
                        param.setExample(endExample);
                        if (param.getSchema() != null) {
                            param.getSchema().setExample(endExample);
                        }
                    }
                }
            }
        }
    }
}
