package com.campus.user.admin;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campus.common.error.BusinessRuleException;
import com.campus.common.error.GlobalExceptionHandler;
import com.campus.common.error.InvalidParameterException;
import com.campus.common.error.NotFoundException;
import com.campus.common.paging.PageResponse;
import com.campus.config.SecurityConfig;
import com.campus.user.api.Role;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminUserController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class AdminUserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminUserService adminUserService;

    private static AdminUserResponse user(Role role, boolean enabled) {
        return new AdminUserResponse(
                5L, "maria@campus.test", "Maria Ionescu", role, enabled, LocalDateTime.of(2026, 10, 20, 9, 0));
    }

    // ---- security ----------------------------------------------------------------------------

    @Test
    @DisplayName("every user admin endpoint is 401 without a token")
    void anonymousGets401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin/users/5")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/admin/users/5/disable")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/admin/users/5/enable")).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/v1/admin/users/5/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(adminUserService);
    }

    @Test
    @WithMockUser(roles = "STUDENT")
    @DisplayName("every user admin endpoint is 403 for a student")
    void studentGets403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/users/5")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/users/5/disable")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/users/5/enable")).andExpect(status().isForbidden());
        // A student promoting themselves must be stopped before the service is even called.
        mockMvc.perform(put("/api/v1/admin/users/5/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(adminUserService);
    }

    // ---- GET list ----------------------------------------------------------------------------

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET list returns a page, page 0 of 20 by default, no filters")
    void listDefaults() throws Exception {
        when(adminUserService.search(null, null, null, 0, 20))
                .thenReturn(new PageResponse<>(List.of(user(Role.STUDENT, true)), 0, 20, 1, 1));

        mockMvc.perform(get("/api/v1/admin/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].email").value("maria@campus.test"))
                .andExpect(jsonPath("$.content[0].role").value("STUDENT"))
                .andExpect(jsonPath("$.content[0].enabled").value(true))
                .andExpect(jsonPath("$.content[0].passwordHash").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET list passes q, role, enabled, page and size on to the service")
    void listWithFilters() throws Exception {
        when(adminUserService.search("maria", Role.ADMIN, false, 2, 50))
                .thenReturn(new PageResponse<>(List.of(), 2, 50, 0, 0));

        mockMvc.perform(get("/api/v1/admin/users")
                        .param("q", "maria")
                        .param("role", "ADMIN")
                        .param("enabled", "false")
                        .param("page", "2")
                        .param("size", "50"))
                .andExpect(status().isOk());

        verify(adminUserService).search("maria", Role.ADMIN, false, 2, 50);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET list returns 400 INVALID_PARAMETER for an unknown role")
    void listWithUnknownRoleReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users").param("role", "TEACHER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(adminUserService);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET list returns 400 INVALID_PARAMETER for a size over 100")
    void listWithTooBigSizeReturns400() throws Exception {
        when(adminUserService.search(null, null, null, 0, 500))
                .thenThrow(new InvalidParameterException("Page size must be between 1 and 100"));

        mockMvc.perform(get("/api/v1/admin/users").param("size", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    // ---- GET one -----------------------------------------------------------------------------

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET one returns the user with enabled and createdAt")
    void getOne() throws Exception {
        when(adminUserService.get(5L)).thenReturn(user(Role.STUDENT, false));

        mockMvc.perform(get("/api/v1/admin/users/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.fullName").value("Maria Ionescu"))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET one returns 404 USER_NOT_FOUND for an unknown id")
    void getUnknownReturns404() throws Exception {
        when(adminUserService.get(404L)).thenThrow(new NotFoundException("USER_NOT_FOUND", "User 404 not found"));

        mockMvc.perform(get("/api/v1/admin/users/404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET one returns 400 INVALID_PARAMETER for a text id")
    void getTextIdReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(adminUserService);
    }

    // ---- disable / enable --------------------------------------------------------------------

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST disable returns 200 with enabled = false")
    void disableReturns200() throws Exception {
        when(adminUserService.disable(5L)).thenReturn(user(Role.STUDENT, false));

        mockMvc.perform(post("/api/v1/admin/users/5/disable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST disable on yourself returns 409 CANNOT_MODIFY_SELF")
    void disableSelfReturns409() throws Exception {
        when(adminUserService.disable(1L))
                .thenThrow(new BusinessRuleException("CANNOT_MODIFY_SELF", "You cannot disable your own account"));

        mockMvc.perform(post("/api/v1/admin/users/1/disable"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANNOT_MODIFY_SELF"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST disable on the last admin returns 409 LAST_ADMIN_REQUIRED")
    void disableLastAdminReturns409() throws Exception {
        when(adminUserService.disable(2L))
                .thenThrow(new BusinessRuleException("LAST_ADMIN_REQUIRED", "At least one enabled admin must remain"));

        mockMvc.perform(post("/api/v1/admin/users/2/disable"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LAST_ADMIN_REQUIRED"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST enable returns 200 with enabled = true")
    void enableReturns200() throws Exception {
        when(adminUserService.enable(5L)).thenReturn(user(Role.STUDENT, true));

        mockMvc.perform(post("/api/v1/admin/users/5/enable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
    }

    // ---- PUT role ----------------------------------------------------------------------------

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PUT role returns 200 with the new role")
    void changeRoleReturns200() throws Exception {
        when(adminUserService.changeRole(5L, Role.ADMIN)).thenReturn(user(Role.ADMIN, true));

        mockMvc.perform(put("/api/v1/admin/users/5/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PUT role without a role returns 400 VALIDATION_FAILED")
    void changeRoleWithoutRoleReturns400() throws Exception {
        mockMvc.perform(put("/api/v1/admin/users/5/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(adminUserService);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PUT role with an unknown role returns 400 MALFORMED_REQUEST")
    void changeRoleWithUnknownRoleReturns400() throws Exception {
        mockMvc.perform(put("/api/v1/admin/users/5/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"TEACHER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        verifyNoInteractions(adminUserService);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PUT role demoting yourself returns 409 CANNOT_MODIFY_SELF")
    void demoteSelfReturns409() throws Exception {
        when(adminUserService.changeRole(1L, Role.STUDENT))
                .thenThrow(new BusinessRuleException("CANNOT_MODIFY_SELF", "You cannot remove your own admin role"));

        mockMvc.perform(put("/api/v1/admin/users/1/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"STUDENT\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANNOT_MODIFY_SELF"));
    }
}
