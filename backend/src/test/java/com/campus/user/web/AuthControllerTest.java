package com.campus.user.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campus.common.error.UnauthorizedException;
import com.campus.config.SecurityConfig;
import com.campus.user.api.Role;
import com.campus.user.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// @WebMvcTest starts only the web layer (fast) and does not load our @Configuration classes.
// Without @Import, Spring's default security would apply and every request would get 401.
// Teammates: copy this pair of annotations for your own controller tests.
@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
class AuthControllerTest {

    @Autowired
    MockMvc mockMvc;

    // The web layer only: the service is a mock, its logic is tested in AuthServiceTest.
    @MockitoBean
    AuthService authService;

    @Test
    void pingIsPublicAndReturnsPong() throws Exception {
        mockMvc.perform(get("/api/v1/auth/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("pong"));
    }

    @Test
    void registerIsPublicAndReturns201() throws Exception {
        when(authService.register(any())).thenReturn(new UserResponse(7L, "new@campus.test", "New One", Role.STUDENT));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "new@campus.test", "password": "secret123", "fullName": "New One"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.role").value("STUDENT"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void registerWithBadEmailAndShortPasswordIs400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "not-an-email", "password": "short", "fullName": "X"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.length()").value(2));
        verifyNoInteractions(authService);
    }

    @Test
    void loginReturnsToken() throws Exception {
        when(authService.login(any()))
                .thenReturn(new LoginResponse(
                        "token-value", 28800, new UserResponse(1L, "admin@campus.test", "Admin", Role.ADMIN)));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "admin@campus.test", "password": "password"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("token-value"))
                .andExpect(jsonPath("$.expiresIn").value(28800))
                .andExpect(jsonPath("$.user.role").value("ADMIN"));
    }

    @Test
    void loginWithWrongPasswordIs401InvalidCredentials() throws Exception {
        when(authService.login(any()))
                .thenThrow(new UnauthorizedException("INVALID_CREDENTIALS", "Wrong email or password"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "admin@campus.test", "password": "wrong"}"""))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }
}
