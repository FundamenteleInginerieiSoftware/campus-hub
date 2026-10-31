package com.campus.catalog.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campus.catalog.service.LabRoomService;
import com.campus.common.error.ConflictException;
import com.campus.config.SecurityConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(LabRoomController.class)
@Import(SecurityConfig.class)
class LabRoomControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LabRoomService labRoomService;

    @Test
    @WithMockUser(roles = "STUDENT")
    void studentCanListLabRooms() throws Exception {
        when(labRoomService.listAll()).thenReturn(List.of(new LabRoomResponse(1L, "EC105", "Electronics Lab", "EC")));

        mockMvc.perform(get("/api/v1/lab-rooms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("EC105"));
    }

    @Test
    @WithMockUser(roles = "STUDENT")
    void studentCannotCreateLabRoom() throws Exception {
        mockMvc.perform(post("/api/v1/admin/lab-rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code": "EC105", "name": "Electronics Lab", "building": "EC"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        verifyNoInteractions(labRoomService);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCreateReturns201() throws Exception {
        when(labRoomService.create(any(LabRoomRequest.class), any()))
                .thenReturn(new LabRoomResponse(2L, "EC203", "Embedded Lab", "EC"));

        mockMvc.perform(post("/api/v1/admin/lab-rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code": "EC203", "name": "Embedded Lab", "building": "EC"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(2))
                .andExpect(jsonPath("$.code").value("EC203"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void blankCodeReturns400ValidationFailed() throws Exception {
        mockMvc.perform(post("/api/v1/admin/lab-rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code": "", "name": "Embedded Lab", "building": "EC"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(labRoomService);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void duplicateCodeReturns409() throws Exception {
        when(labRoomService.create(any(LabRoomRequest.class), any()))
                .thenThrow(new ConflictException("LAB_ROOM_CODE_TAKEN", "Lab room code already exists"));

        mockMvc.perform(post("/api/v1/admin/lab-rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code": "EC203", "name": "Embedded Lab", "building": "EC"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LAB_ROOM_CODE_TAKEN"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminDeleteReturns204() throws Exception {
        mockMvc.perform(delete("/api/v1/admin/lab-rooms/2")).andExpect(status().isNoContent());
        org.mockito.Mockito.verify(labRoomService).delete(eq(2L), any());
    }
}
