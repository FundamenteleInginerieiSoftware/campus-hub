package com.campus.user.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.campus.config.SecurityConfig;
import com.campus.user.api.Role;
import com.campus.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
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
}
