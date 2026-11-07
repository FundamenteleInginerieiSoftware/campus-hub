package com.campus.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The React app as the Docker image serves it (SpaConfig + the GET rule in SecurityConfig).
 * src/test/resources/static/index.html stands in for the real React build.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SpaFrontendTest {

    private static final String REACT_APP = "campus-hub-spa";

    @Autowired
    MockMvc mockMvc;

    // For "/" Spring Boot's welcome page forwards to index.html inside the server. MockMvc doesn't
    // follow forwards, so check where it forwards to (the real server then serves the file).
    @Test
    void startPageIsPublic() throws Exception {
        mockMvc.perform(get("/")).andExpect(status().isOk()).andExpect(forwardedUrl("index.html"));
    }

    @Test
    void reloadingAReactPageGivesTheReactApp() throws Exception {
        mockMvc.perform(get("/reservations"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(REACT_APP)));
        mockMvc.perform(get("/admin/incidents"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(REACT_APP)));
    }

    // Spring's own static file handling still works after SpaConfig replaced the default "/**" handler.
    @Test
    void existingFileIsServedDirectly() throws Exception {
        MockHttpServletResponse response =
                mockMvc.perform(get("/index.html")).andReturn().getResponse();

        assertThat(response.getStatus())
                .as("GET of a file in static/ answers 200; see SpaConfig.IndexHtmlFallback.getResource")
                .isEqualTo(200);
        assertThat(response.getContentAsString())
                .as("GET of a file in static/ returns that file; see SpaConfig.IndexHtmlFallback.getResource")
                .contains(REACT_APP);
    }

    @Test
    void missingFileIsA404NotTheReactApp() throws Exception {
        mockMvc.perform(get("/assets/index-old.js")).andExpect(status().isNotFound());
    }

    @Test
    void unknownApiPathStaysAJson401WithoutToken() throws Exception {
        mockMvc.perform(get("/api/v1/_security-probe"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
}
