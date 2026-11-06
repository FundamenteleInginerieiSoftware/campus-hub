package com.campus.reservation.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReservationOpenApiCustomizerTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Bucharest");

    @Test
    @DisplayName("customise: sets next weekday 14:00-16:00 when today is Friday (skips weekend to Monday)")
    void customiseSkipsWeekendToMonday() {
        // 2026-11-06 is Friday 10:00 UTC
        Clock fridayClock = Clock.fixed(Instant.parse("2026-11-06T10:00:00Z"), ZONE);
        ReservationOpenApiCustomizer customizer = new ReservationOpenApiCustomizer(fridayClock);

        OpenAPI openApi = buildTestOpenApi();
        customizer.customise(openApi);

        // Monday is 2026-11-09
        String expectedStart = "2026-11-09T14:00:00";
        String expectedEnd = "2026-11-09T16:00:00";

        var schemas = openApi.getComponents().getSchemas();
        var requestProps = schemas.get("CreateReservationRequest").getProperties();
        assertThat(((io.swagger.v3.oas.models.media.Schema<?>) requestProps.get("startTime")).getExample())
                .isEqualTo(expectedStart);
        assertThat(((io.swagger.v3.oas.models.media.Schema<?>) requestProps.get("endTime")).getExample())
                .isEqualTo(expectedEnd);

        List<Parameter> params = openApi.getPaths()
                .get("/api/v1/reservations/availability")
                .getGet()
                .getParameters();
        assertThat(params.stream()
                        .filter(p -> p.getName().equals("startTime"))
                        .findFirst()
                        .orElseThrow()
                        .getExample())
                .isEqualTo(expectedStart);
        assertThat(params.stream()
                        .filter(p -> p.getName().equals("endTime"))
                        .findFirst()
                        .orElseThrow()
                        .getExample())
                .isEqualTo(expectedEnd);
    }

    @Test
    @DisplayName("customise: sets next weekday 14:00-16:00 when today is Monday (tomorrow is Tuesday)")
    void customiseSetsNextDayOnWeekday() {
        // 2026-11-02 is Monday 10:00 UTC
        Clock mondayClock = Clock.fixed(Instant.parse("2026-11-02T10:00:00Z"), ZONE);
        ReservationOpenApiCustomizer customizer = new ReservationOpenApiCustomizer(mondayClock);

        OpenAPI openApi = buildTestOpenApi();
        customizer.customise(openApi);

        // Tuesday is 2026-11-03
        String expectedStart = "2026-11-03T14:00:00";
        String expectedEnd = "2026-11-03T16:00:00";

        var schemas = openApi.getComponents().getSchemas();
        var requestProps = schemas.get("CreateReservationRequest").getProperties();
        assertThat(((io.swagger.v3.oas.models.media.Schema<?>) requestProps.get("startTime")).getExample())
                .isEqualTo(expectedStart);
        assertThat(((io.swagger.v3.oas.models.media.Schema<?>) requestProps.get("endTime")).getExample())
                .isEqualTo(expectedEnd);
    }

    @Test
    @DisplayName("customise: handles null components or paths safely without exceptions")
    void customiseNullSafe() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-02T10:00:00Z"), ZONE);
        ReservationOpenApiCustomizer customizer = new ReservationOpenApiCustomizer(clock);

        OpenAPI emptyOpenApi = new OpenAPI();
        customizer.customise(emptyOpenApi);

        assertThat(emptyOpenApi.getComponents()).isNull();
    }

    private OpenAPI buildTestOpenApi() {
        OpenAPI openApi = new OpenAPI();

        ObjectSchema requestSchema = new ObjectSchema();
        requestSchema.addProperty("startTime", new StringSchema().example("old-start"));
        requestSchema.addProperty("endTime", new StringSchema().example("old-end"));

        Components components = new Components();
        components.addSchemas("CreateReservationRequest", requestSchema);
        openApi.setComponents(components);

        Parameter startParam = new Parameter().name("startTime").schema(new StringSchema());
        Parameter endParam = new Parameter().name("endTime").schema(new StringSchema());
        Operation getAvailability = new Operation().parameters(List.of(startParam, endParam));

        PathItem pathItem = new PathItem().get(getAvailability);
        Paths paths = new Paths();
        paths.addPathItem("/api/v1/reservations/availability", pathItem);
        openApi.setPaths(paths);

        return openApi;
    }
}
