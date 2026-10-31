package com.campus.reservation.web;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campus.common.error.GlobalExceptionHandler;
import com.campus.config.SecurityConfig;
import com.campus.reservation.api.ReservationStatus;
import com.campus.reservation.service.ReservationLifecycleService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminReservationLifecycleController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class AdminReservationLifecycleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReservationLifecycleService lifecycleService;

    private final ReservationResponse sampleResponse = new ReservationResponse(
            1L,
            2L,
            10L,
            null,
            LocalDateTime.of(2026, 11, 2, 10, 0),
            LocalDateTime.of(2026, 11, 2, 12, 0),
            ReservationStatus.CHECKED_OUT,
            LocalDateTime.of(2026, 11, 2, 9, 0),
            LocalDateTime.of(2026, 11, 2, 10, 0),
            null,
            null,
            null);

    @Test
    @WithMockUser(roles = "STUDENT")
    @DisplayName("Non-admin user gets 403 FORBIDDEN on check-out, return and run-lifecycle endpoints")
    void studentGets403OnAdminLifecycleEndpoints() throws Exception {
        mockMvc.perform(post("/api/v1/admin/reservations/1/check-out"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(post("/api/v1/admin/reservations/1/return"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(post("/api/v1/admin/reservations/jobs/run-lifecycle"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verifyNoInteractions(lifecycleService);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Admin user gets 200 OK on check-out, return and run-lifecycle endpoints")
    void adminGets200OnAdminLifecycleEndpoints() throws Exception {
        when(lifecycleService.checkOut(1L)).thenReturn(sampleResponse);
        when(lifecycleService.returnAsset(1L)).thenReturn(sampleResponse);
        when(lifecycleService.runLifecycleSweep())
                .thenReturn(new LifecycleSweepSummary(LocalDateTime.of(2026, 11, 2, 10, 35), 1, 1, 1, List.of(33L)));

        mockMvc.perform(post("/api/v1/admin/reservations/1/check-out"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.status").value("CHECKED_OUT"));

        mockMvc.perform(post("/api/v1/admin/reservations/1/return"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));

        mockMvc.perform(post("/api/v1/admin/reservations/jobs/run-lifecycle"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.noShowReleasedCount").value(1))
                .andExpect(jsonPath("$.markedOverdueCount").value(1))
                .andExpect(jsonPath("$.preventivelyReassignedCount").value(1))
                .andExpect(jsonPath("$.preventivelyReassignedReservationIds[0]").value(33));
    }
}
