package com.campus.user.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.campus.config.SecurityConfig;
import com.campus.user.service.PickupPassService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Same setup as UserControllerTest: only the web layer, with our real security rules.
// What the code is and when it expires is tested in PickupPassServiceTest.
@WebMvcTest(PickupPassController.class)
@Import(SecurityConfig.class)
class PickupPassControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JsonMapper jsonMapper;

    @MockitoBean
    PickupPassService pickupPassService;

    @Test
    void requiresLogin() throws Exception {
        MockHttpServletResponse response =
                mockMvc.perform(get("/api/v1/users/me/pickup-pass")).andReturn().getResponse();

        assertThat(response.getStatus())
                .as("GET /users/me/pickup-pass without a token answers 401; see SecurityConfig.apiChain (/api/v1/**)")
                .isEqualTo(401);
        assertThat(jsonMapper
                        .readTree(response.getContentAsString())
                        .path("code")
                        .asString())
                .as("the 401 is our ProblemDetail JSON with a code; see SecurityErrorHandler")
                .isEqualTo("UNAUTHORIZED");
        verifyNoInteractions(pickupPassService);
    }

    @Test
    void returnsThePass() throws Exception {
        when(pickupPassService.currentPass())
                .thenReturn(new PickupPassResponse("004211", "campus-pickup:1:12:004211", 17));

        MockHttpServletResponse response = mockMvc.perform(
                        get("/api/v1/users/me/pickup-pass").with(jwt()))
                .andReturn()
                .getResponse();

        JsonNode body = jsonMapper.readTree(response.getContentAsString());
        assertThat(response.getStatus())
                .as("a logged-in user gets 200; see PickupPassController.currentPass")
                .isEqualTo(200);
        assertThat(body.path("code").asString())
                .as("the code is what PickupPassService.currentPass returned; see PickupPassController.currentPass")
                .isEqualTo("004211");
        assertThat(body.path("validForSeconds").asInt())
                .as("validForSeconds is passed through; see PickupPassController.currentPass")
                .isEqualTo(17);
    }

    @Test
    void codeIsAJsonString() throws Exception {
        when(pickupPassService.currentPass())
                .thenReturn(new PickupPassResponse("004211", "campus-pickup:1:12:004211", 17));

        MockHttpServletResponse response = mockMvc.perform(
                        get("/api/v1/users/me/pickup-pass").with(jwt()))
                .andReturn()
                .getResponse();

        assertThat(jsonMapper
                        .readTree(response.getContentAsString())
                        .path("code")
                        .isString())
                .as("the code is a JSON string, or the leading zeros of 004211 would be lost; see PickupPassResponse")
                .isTrue();
    }
}
