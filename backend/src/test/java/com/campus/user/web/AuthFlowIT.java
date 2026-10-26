package com.campus.user.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * The whole app on the campus_test database: register, log in, then use the token. Proves that the
 * token login signs is the one SecurityConfig accepts, and that its "roles" claim is read.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // MockMvc runs on the test thread, so the new user is rolled back after each test
class AuthFlowIT {

    @Autowired
    MockMvc mockMvc;

    @Test
    void registeredStudentCanLogInAndUseTheToken() throws Exception {
        register("flow@campus.test");
        String token = login("FLOW@campus.test");

        // Unknown path: 404 means the token got past security (without one it's 401).
        mockMvc.perform(get("/api/v1/does-not-exist").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        // A self-registered user is always a STUDENT, so admin paths stay closed.
        mockMvc.perform(get("/api/v1/admin/anything").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void sameEmailTwiceIs409() throws Exception {
        register("twice@campus.test");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("Twice@Campus.test")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));
    }

    private void register(String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(email)))
                .andExpect(status().isCreated());
    }

    private String login(String email) throws Exception {
        String json = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"" + email + "\", \"password\": \"secret123\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(json, "$.accessToken");
    }

    private static String registerBody(String email) {
        return "{\"email\": \"" + email + "\", \"password\": \"secret123\", \"fullName\": \"Flow Test\"}";
    }
}
