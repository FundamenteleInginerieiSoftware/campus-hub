package com.campus.catalog.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campus.catalog.service.CategoryService;
import com.campus.common.error.ConflictException;
import com.campus.common.error.NotFoundException;
import com.campus.config.SecurityConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CategoryController.class)
@Import(SecurityConfig.class)
class CategoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CategoryService categoryService;

    @Test
    @WithMockUser(roles = "STUDENT")
    void studentCanListCategories() throws Exception {
        when(categoryService.listAll()).thenReturn(List.of(new CategoryResponse(1L, "Oscilloscopes", "Signal lab")));

        mockMvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].name").value("Oscilloscopes"));
    }

    @Test
    @WithMockUser(roles = "STUDENT")
    void studentCannotCreateCategory() throws Exception {
        mockMvc.perform(post("/api/v1/admin/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Oscilloscopes", "description": "Signal lab"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        verifyNoInteractions(categoryService);
    }

    @Test
    void adminCreateReturns201AndExtractsJwtSubject() throws Exception {
        when(categoryService.create(any(CategoryRequest.class), eq(3L)))
                .thenReturn(new CategoryResponse(10L, "Oscilloscopes", "Signal lab"));

        mockMvc.perform(post("/api/v1/admin/categories")
                        .with(jwt().jwt(j -> j.subject("3")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Oscilloscopes", "description": "Signal lab"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.name").value("Oscilloscopes"));

        verify(categoryService).create(any(CategoryRequest.class), eq(3L));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void blankCategoryNameReturns400ValidationFailed() throws Exception {
        mockMvc.perform(post("/api/v1/admin/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "   ", "description": "Signal lab"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(categoryService);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void updateMissingCategoryReturns404() throws Exception {
        when(categoryService.update(eq(99L), any(CategoryRequest.class), any()))
                .thenThrow(new NotFoundException("CATEGORY_NOT_FOUND", "Category 99 was not found"));

        mockMvc.perform(put("/api/v1/admin/categories/99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Cameras", "description": "Media"}"""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CATEGORY_NOT_FOUND"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void deleteInUseCategoryReturns409() throws Exception {
        doThrow(new ConflictException("CATEGORY_IN_USE", "Category cannot be deleted"))
                .when(categoryService)
                .delete(eq(1L), any());

        mockMvc.perform(delete("/api/v1/admin/categories/1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATEGORY_IN_USE"));
    }
}
