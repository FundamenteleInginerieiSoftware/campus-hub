package com.campus.user.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.campus.common.error.BusinessRuleException;
import com.campus.common.error.UnauthorizedException;
import com.campus.config.SecurityConfig;
import com.campus.user.api.Role;
import com.campus.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

// Same setup as AuthControllerTest: only the web layer, with our real security rules.
// jwt() puts a valid token's user into the request directly, so no UserApi bean is needed
// (SecurityConfig fetches it through an ObjectProvider only for real tokens).
@WebMvcTest(UserController.class)
@Import(SecurityConfig.class)
class UserControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JsonMapper jsonMapper;

    // Which user "me" is (CurrentUser + the database) is tested in UserServiceTest.
    @MockitoBean
    UserService userService;

    @Test
    void meReturnsTheAccountOfTheTokensUser() throws Exception {
        UserResponse account = new UserResponse(1L, "student1@campus.test", "Student One", Role.STUDENT);
        when(userService.me()).thenReturn(account);

        MockHttpServletResponse response =
                mockMvc.perform(get("/api/v1/users/me").with(jwt())).andReturn().getResponse();

        assertThat(response.getStatus())
                .as("GET /users/me with a token answers 200; see UserController.me")
                .isEqualTo(200);
        assertThat(jsonMapper.readValue(response.getContentAsString(), UserResponse.class))
                .as("GET /users/me returns what UserService.me() found; see UserController.me")
                .isEqualTo(account);
    }

    @Test
    void meWithoutATokenIs401() throws Exception {
        MockHttpServletResponse response =
                mockMvc.perform(get("/api/v1/users/me")).andReturn().getResponse();

        assertThat(response.getStatus())
                .as("GET /users/me without a token answers 401; see SecurityConfig.apiChain (/api/v1/**)")
                .isEqualTo(401);
        assertThat(jsonMapper
                        .readTree(response.getContentAsString())
                        .path("code")
                        .asString())
                .as("the 401 is our ProblemDetail JSON with a code; see SecurityErrorHandler")
                .isEqualTo("UNAUTHORIZED");
        verifyNoInteractions(userService);
    }

    @Test
    void changePasswordReturns204ForValidPayloadWithToken() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(put("/api/v1/users/me/password")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword": "oldpass123", "newPassword": "newpass123"}"""))
                .andReturn()
                .getResponse();

        assertThat(response.getStatus())
                .as(
                        "PUT /users/me/password with valid payload and token returns 204 (body: %s); see UserController.changePassword",
                        response.getContentAsString())
                .isEqualTo(204);
        assertThat(response.getContentAsString())
                .as("204 No Content must have an empty body; see UserController.changePassword")
                .isEmpty();
        verify(userService).changePassword(new ChangePasswordRequest("oldpass123", "newpass123"));
    }

    @Test
    void changePasswordPreservesSpacesInPasswords() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(put("/api/v1/users/me/password")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword": " oldpass123 ", "newPassword": " newpass123 "}"""))
                .andReturn()
                .getResponse();

        assertThat(response.getStatus())
                .as(
                        "passwords with spaces are valid and must not be trimmed (body: %s); see ChangePasswordRequest",
                        response.getContentAsString())
                .isEqualTo(204);
        verify(userService).changePassword(new ChangePasswordRequest(" oldpass123 ", " newpass123 "));
    }

    @Test
    void changePasswordWithoutTokenReturns401Unauthorized() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(put("/api/v1/users/me/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword": "oldpass123", "newPassword": "newpass123"}"""))
                .andReturn()
                .getResponse();

        assertThat(response.getStatus())
                .as(
                        "PUT /users/me/password without a token answers 401 (body: %s); see SecurityConfig.apiChain (/api/v1/**)",
                        response.getContentAsString())
                .isEqualTo(401);
        assertThat(codeOf(response))
                .as("unauthenticated request must return UNAUTHORIZED ProblemDetail; see SecurityErrorHandler")
                .isEqualTo("UNAUTHORIZED");
        verifyNoInteractions(userService);
    }

    @Test
    void changePasswordRejectsBlankFieldsWith400ValidationFailed() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(put("/api/v1/users/me/password")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword": "   ", "newPassword": ""}"""))
                .andReturn()
                .getResponse();

        assertThat(response.getStatus())
                .as(
                        "blank currentPassword or newPassword must fail validation with 400 (body: %s); see ChangePasswordRequest",
                        response.getContentAsString())
                .isEqualTo(400);
        assertThat(codeOf(response))
                .as("validation failure must return VALIDATION_FAILED; see GlobalExceptionHandler.handleValidation")
                .isEqualTo("VALIDATION_FAILED");
        verifyNoInteractions(userService);
    }

    @Test
    void changePasswordRejectsShortNewPasswordWith400ValidationFailed() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(put("/api/v1/users/me/password")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword": "oldpass123", "newPassword": "short12"}"""))
                .andReturn()
                .getResponse();

        assertThat(response.getStatus())
                .as(
                        "newPassword shorter than 8 characters must fail validation with 400 (body: %s); see ChangePasswordRequest",
                        response.getContentAsString())
                .isEqualTo(400);
        assertThat(codeOf(response))
                .as("too-short newPassword must return VALIDATION_FAILED; see ChangePasswordRequest")
                .isEqualTo("VALIDATION_FAILED");
        verifyNoInteractions(userService);
    }

    @Test
    void changePasswordRejectsNewPasswordOver72Utf8BytesWith400ValidationFailed() throws Exception {
        // 37 x "ș" = 37 characters (passes @Size) but 74 UTF-8 bytes (exceeds BCrypt's 72-byte limit).
        String payload = jsonMapper.writeValueAsString(new ChangePasswordRequest("oldpass123", "ș".repeat(37)));

        MockHttpServletResponse response = mockMvc.perform(put("/api/v1/users/me/password")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andReturn()
                .getResponse();

        assertThat(response.getStatus())
                .as(
                        "newPassword over 72 UTF-8 bytes must fail validation with 400 (body: %s); see ChangePasswordRequest.isNewPasswordWithinBcryptLimit",
                        response.getContentAsString())
                .isEqualTo(400);
        assertThat(codeOf(response))
                .as("over-72-byte newPassword must return VALIDATION_FAILED; see ChangePasswordRequest")
                .isEqualTo("VALIDATION_FAILED");
        verifyNoInteractions(userService);
    }

    @Test
    void changePasswordAcceptsNewPasswordOfExactly72Utf8Bytes() throws Exception {
        String exactly72Bytes = "ș".repeat(36);
        String payload = jsonMapper.writeValueAsString(new ChangePasswordRequest("oldpass123", exactly72Bytes));

        MockHttpServletResponse response = mockMvc.perform(put("/api/v1/users/me/password")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andReturn()
                .getResponse();

        assertThat(response.getStatus())
                .as(
                        "newPassword of exactly 72 UTF-8 bytes must be accepted (body: %s); see ChangePasswordRequest.isNewPasswordWithinBcryptLimit",
                        response.getContentAsString())
                .isEqualTo(204);
        verify(userService).changePassword(new ChangePasswordRequest("oldpass123", exactly72Bytes));
    }

    @Test
    void changePasswordPropagates401InvalidCredentialsFromService() throws Exception {
        doThrow(new UnauthorizedException("INVALID_CREDENTIALS", "Current password is incorrect"))
                .when(userService)
                .changePassword(any());

        MockHttpServletResponse response = mockMvc.perform(put("/api/v1/users/me/password")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword": "wrongpass", "newPassword": "newpass123"}"""))
                .andReturn()
                .getResponse();

        assertThat(response.getStatus())
                .as(
                        "UnauthorizedException from UserService must map to 401 (body: %s); see GlobalExceptionHandler.handleAppException",
                        response.getContentAsString())
                .isEqualTo(401);
        assertThat(codeOf(response))
                .as("wrong current password must propagate INVALID_CREDENTIALS; see UserService.changePassword")
                .isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void changePasswordPropagates409PasswordUnchangedFromService() throws Exception {
        doThrow(new BusinessRuleException(
                        "PASSWORD_UNCHANGED", "New password must be different from the current password"))
                .when(userService)
                .changePassword(any());

        MockHttpServletResponse response = mockMvc.perform(put("/api/v1/users/me/password")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword": "samepass123", "newPassword": "samepass123"}"""))
                .andReturn()
                .getResponse();

        assertThat(response.getStatus())
                .as(
                        "BusinessRuleException from UserService must map to 409 (body: %s); see GlobalExceptionHandler.handleAppException",
                        response.getContentAsString())
                .isEqualTo(409);
        assertThat(codeOf(response))
                .as("reused password must propagate PASSWORD_UNCHANGED; see UserService.changePassword")
                .isEqualTo("PASSWORD_UNCHANGED");
    }

    private String codeOf(MockHttpServletResponse response) throws Exception {
        return jsonMapper.readTree(response.getContentAsString()).path("code").asString();
    }
}
