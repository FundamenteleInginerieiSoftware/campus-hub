package com.campus.reservation.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campus.common.error.GlobalExceptionHandler;
import com.campus.config.SecurityConfig;
import com.campus.reservation.api.ReservationStatus;
import com.campus.reservation.domain.HoldStatus;
import com.campus.reservation.service.ReservationService;
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

@WebMvcTest(ReservationHoldController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@AutoConfigureMockMvc(addFilters = false)
class ReservationHoldControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReservationService reservationService;

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 2, 10, 0);
    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 2, 14, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 11, 2, 16, 0);

    @Test
    @DisplayName("POST /api/v1/reservations/holds returns 201 Created and hold payload")
    void createHold_returns201() throws Exception {
        ReservationHoldResponse response =
                new ReservationHoldResponse(1L, 10L, 42L, START, END, NOW.plusMinutes(1), HoldStatus.ACTIVE, NOW, 60);

        when(reservationService.createHold(any(CreateHoldRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/reservations/holds")
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
                .andExpect(jsonPath("$.assetId").value(10))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.remainingSeconds").value(60));
    }

    @Test
    @DisplayName("POST /api/v1/reservations/holds returns 400 when body has missing fields")
    void createHold_returns400OnValidationFailure() throws Exception {
        mockMvc.perform(post("/api/v1/reservations/holds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                            "assetId": 10
                        }
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("POST /api/v1/reservations/holds with Z offset returns 400 MALFORMED_REQUEST")
    void createHold_withZOffsetReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/reservations/holds")
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
    }

    @Test
    @DisplayName("POST /api/v1/reservations/holds/{id}/confirm returns 200 OK and reservation response")
    void confirmHold_returns200() throws Exception {
        ReservationResponse res = new ReservationResponse(
                100L, 42L, 10L, null, START, END, ReservationStatus.CONFIRMED, NOW, null, null, null, null);

        when(reservationService.confirmHold(1L)).thenReturn(res);

        mockMvc.perform(post("/api/v1/reservations/holds/1/confirm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(100))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    @DisplayName("POST /api/v1/reservations/holds/{id}/release returns 200 OK and released hold")
    void releaseHold_returns200() throws Exception {
        ReservationHoldResponse response =
                new ReservationHoldResponse(1L, 10L, 42L, START, END, NOW.plusMinutes(1), HoldStatus.RELEASED, NOW, 0);

        when(reservationService.releaseHold(1L)).thenReturn(response);

        mockMvc.perform(post("/api/v1/reservations/holds/1/release"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.status").value("RELEASED"));
    }

    @Test
    @DisplayName("GET /api/v1/reservations/holds/active returns 200 OK and list of active holds")
    void getMyActiveHolds_returns200() throws Exception {
        ReservationHoldResponse response =
                new ReservationHoldResponse(1L, 10L, 42L, START, END, NOW.plusMinutes(1), HoldStatus.ACTIVE, NOW, 55);

        when(reservationService.getMyActiveHolds()).thenReturn(List.of(response));

        mockMvc.perform(get("/api/v1/reservations/holds/active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].remainingSeconds").value(55));
    }
}
