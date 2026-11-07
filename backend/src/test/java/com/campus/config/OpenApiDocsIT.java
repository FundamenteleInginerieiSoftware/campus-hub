package com.campus.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * M1 asks for a complete Swagger. This reads the spec the running app really serves (/v3/api-docs)
 * and checks every endpoint, so a new endpoint from any teammate is checked as soon as it exists.
 * SoftAssertions lists every broken endpoint at once instead of stopping at the first.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiDocsIT {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JsonMapper jsonMapper;

    @Test
    void everyEndpointIsDocumentedLikeSecurityConfigBehaves() throws Exception {
        String json = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode paths = jsonMapper.readTree(json).get("paths");
        SoftAssertions softly = new SoftAssertions();

        for (String path : paths.propertyNames()) {
            for (String method : paths.get(path).propertyNames()) {
                JsonNode operation = paths.get(path).get(method);
                JsonNode responses = operation.get("responses");
                String name = method.toUpperCase() + " " + path;

                softly.assertThat(operation.path("summary").asString(""))
                        .as("%s has an @Operation summary", name)
                        .isNotBlank();

                if (path.startsWith("/api/v1/auth/")) {
                    // An empty list overrides the global bearerAuth: no lock icon on public endpoints.
                    softly.assertThat(operation.has("security")
                                    && operation.get("security").isEmpty())
                            .as("%s is public, so it shows no lock", name)
                            .isTrue();
                } else {
                    softly.assertThat(operation.has("security"))
                            .as("%s keeps the global bearerAuth lock", name)
                            .isFalse();
                    softly.assertThat(responses.has("401"))
                            .as("%s documents 401", name)
                            .isTrue();
                }
                if (path.startsWith("/api/v1/admin/")) {
                    softly.assertThat(responses.has("403"))
                            .as("%s documents 403", name)
                            .isTrue();
                }

                for (String code : responses.propertyNames()) {
                    JsonNode content = responses.get(code).path("content");
                    if (code.startsWith("4") || code.startsWith("5")) {
                        softly.assertThat(content.path("application/problem+json")
                                        .path("schema")
                                        .path("$ref")
                                        .asString(""))
                                .as("%s %s returns a ProblemDetail", name, code)
                                .isEqualTo("#/components/schemas/ProblemDetail");
                    } else {
                        softly.assertThat(content.has("*/*"))
                                .as("%s %s names its media type", name, code)
                                .isFalse();
                    }
                }
            }
        }
        softly.assertAll();
    }
}
