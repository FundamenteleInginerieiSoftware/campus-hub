package com.campus.user.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.campus.common.error.BusinessRuleException;
import com.campus.config.SecurityConfig;
import com.campus.user.service.PickupPassService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Same setup as UserControllerTest. jwt() with a ROLE_ authority is what SecurityConfig's
// /api/v1/admin/** rule checks, so the admin and student cases use the real rule.
@WebMvcTest(AdminPickupPassController.class)
@Import(SecurityConfig.class)
class AdminPickupPassControllerTest {

    private static final String URL = "/api/v1/admin/pickup-pass/verify";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JsonMapper jsonMapper;

    @MockitoBean
    PickupPassService pickupPassService;

    private MockHttpServletResponse verify(String role, String json) throws Exception {
        return mockMvc.perform(post(URL)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andReturn()
                .getResponse();
    }

    private void assertValidationFailed(String json) throws Exception {
        MockHttpServletResponse response = verify("ADMIN", json);

        assertThat(response.getStatus())
                .as("a bad body answers 400; see VerifyPickupPassRequest; body: " + response.getContentAsString())
                .isEqualTo(400);
        assertThat(jsonMapper
                        .readTree(response.getContentAsString())
                        .path("code")
                        .asString())
                .as("the 400 carries VALIDATION_FAILED; see GlobalExceptionHandler")
                .isEqualTo("VALIDATION_FAILED");
        verifyNoInteractions(pickupPassService);
    }

    @Test
    void studentGets403() throws Exception {
        MockHttpServletResponse response = verify("STUDENT", "{\"userId\":12,\"code\":\"493027\"}");

        assertThat(response.getStatus())
                .as("a student calling verify gets 403; see SecurityConfig (/api/v1/admin/**)")
                .isEqualTo(403);
        verifyNoInteractions(pickupPassService);
    }

    @Test
    void verifiedReturnsTheStudent() throws Exception {
        when(pickupPassService.verify(12L, "493027"))
                .thenReturn(new PickupPassVerifiedResponse(12L, "Ana Pop", "ana@campus.test"));

        MockHttpServletResponse response = verify("ADMIN", "{\"userId\":12,\"code\":\"493027\"}");

        assertThat(response.getStatus())
                .as("an admin with a right code gets 200; see AdminPickupPassController.verify")
                .isEqualTo(200);
        JsonNode body = jsonMapper.readTree(response.getContentAsString());
        assertThat(body.path("fullName").asString())
                .as("the answer names the student; see PickupPassService.verify")
                .isEqualTo("Ana Pop");
    }

    @Test
    void invalidCodeIs409() throws Exception {
        when(pickupPassService.verify(12L, "000000"))
                .thenThrow(new BusinessRuleException("PICKUP_CODE_INVALID", "The code is wrong or has expired"));

        MockHttpServletResponse response = verify("ADMIN", "{\"userId\":12,\"code\":\"000000\"}");

        String body = response.getContentAsString();
        assertThat(response.getStatus())
                .as("a wrong code answers 409; see PickupPassService.verify; body: " + body)
                .isEqualTo(409);
        assertThat(jsonMapper.readTree(body).path("code").asString())
                .as("the 409 carries PICKUP_CODE_INVALID; see PickupPassService.verify; body: " + body)
                .isEqualTo("PICKUP_CODE_INVALID");
    }

    @Test
    void rejectsFiveDigits() throws Exception {
        assertValidationFailed("{\"userId\":12,\"code\":\"49302\"}");
    }

    @Test
    void rejectsLetters() throws Exception {
        assertValidationFailed("{\"userId\":12,\"code\":\"49302a\"}");
    }

    @Test
    void rejectsCodeWithSpace() throws Exception {
        assertValidationFailed("{\"userId\":12,\"code\":\"493 027\"}");
    }

    @Test
    void rejectsMissingUserId() throws Exception {
        assertValidationFailed("{\"code\":\"493027\"}");
    }
}
