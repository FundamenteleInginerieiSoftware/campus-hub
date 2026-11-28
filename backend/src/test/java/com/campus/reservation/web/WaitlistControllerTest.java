package com.campus.reservation.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campus.common.error.GlobalExceptionHandler;
import com.campus.common.error.NotFoundException;
import com.campus.common.paging.PageResponse;
import com.campus.config.SecurityConfig;
import com.campus.reservation.domain.WaitlistStatus;
import com.campus.reservation.service.WaitlistService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(WaitlistController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@AutoConfigureMockMvc(addFilters = false)
class WaitlistControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WaitlistService waitlistService;

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 2, 10, 0);
    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 2, 14, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 11, 2, 16, 0);

    @Test
    @DisplayName("POST /api/v1/reservations/waitlist returns 201 Created")
    void joinWaitlist_returns201() throws Exception {
        WaitlistEntryResponse response =
                new WaitlistEntryResponse(1L, 10L, 42L, START, END, WaitlistStatus.WAITING, null, NOW, NOW);
        when(waitlistService.joinWaitlist(any(JoinWaitlistRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/reservations/waitlist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                            "assetId": 10,
                            "startTime": "2026-11-02T14:00:00",
                            "endTime": "2026-11-02T16:00:00"
                        }
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.status").value("WAITING"));
    }

    @Test
    @DisplayName("POST /api/v1/reservations/waitlist with Z offset returns 400 MALFORMED_REQUEST")
    void joinWaitlist_withZOffsetReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/reservations/waitlist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                            "assetId": 10,
                            "startTime": "2026-11-02T14:00:00Z",
                            "endTime": "2026-11-02T16:00:00Z"
                        }
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        verifyNoInteractions(waitlistService);
    }

    @Test
    @DisplayName("GET /api/v1/reservations/waitlist/me returns 200 OK with PageResponse")
    void getMyWaitlist_returns200Page() throws Exception {
        WaitlistEntryResponse entry =
                new WaitlistEntryResponse(1L, 10L, 42L, START, END, WaitlistStatus.WAITING, null, NOW, NOW);
        when(waitlistService.getMyWaitlist(0, 20)).thenReturn(new PageResponse<>(List.of(entry), 0, 20, 1, 1));

        mockMvc.perform(get("/api/v1/reservations/waitlist/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("DELETE /api/v1/reservations/waitlist/{id} returns 204 No Content")
    void cancelWaitlist_returns204() throws Exception {
        mockMvc.perform(delete("/api/v1/reservations/waitlist/7")).andExpect(status().isNoContent());

        verify(waitlistService).cancelWaitlistEntry(7L);
    }

    @Test
    @DisplayName("DELETE /api/v1/reservations/waitlist/{id} returns 404 WAITLIST_ENTRY_NOT_FOUND when missing")
    void cancelWaitlist_notFoundReturns404Code() throws Exception {
        doThrow(new NotFoundException("WAITLIST_ENTRY_NOT_FOUND", "Waitlist entry not found: 99"))
                .when(waitlistService)
                .cancelWaitlistEntry(99L);

        mockMvc.perform(delete("/api/v1/reservations/waitlist/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WAITLIST_ENTRY_NOT_FOUND"));
    }
}
