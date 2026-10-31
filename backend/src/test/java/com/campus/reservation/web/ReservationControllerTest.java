package com.campus.reservation.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campus.common.error.GlobalExceptionHandler;
import com.campus.common.error.NotFoundException;
import com.campus.common.paging.PageResponse;
import com.campus.config.SecurityConfig;
import com.campus.reservation.api.ReservationStatus;
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

@WebMvcTest(ReservationController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@AutoConfigureMockMvc(addFilters = false)
class ReservationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReservationService reservationService;

    private final ReservationResponse sampleResponse = new ReservationResponse(
            1L,
            42L,
            10L,
            null,
            LocalDateTime.of(2026, 11, 2, 14, 0),
            LocalDateTime.of(2026, 11, 2, 16, 0),
            ReservationStatus.CONFIRMED,
            LocalDateTime.of(2026, 11, 2, 10, 0),
            null,
            null,
            null,
            null);

    @Test
    @DisplayName("POST /api/v1/reservations returns 201 CREATED")
    void createReturns201() throws Exception {
        when(reservationService.createReservation(any())).thenReturn(sampleResponse);

        mockMvc.perform(post("/api/v1/reservations")
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
                .andExpect(jsonPath("$.userId").value(42))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    @DisplayName("GET /api/v1/reservations/me returns 200 OK with PageResponse")
    void getMyReservationsReturns200() throws Exception {
        PageResponse<ReservationResponse> page = new PageResponse<>(List.of(sampleResponse), 0, 20, 1L, 1);
        when(reservationService.getMyReservations(any(), anyInt(), anyInt())).thenReturn(page);

        mockMvc.perform(get("/api/v1/reservations/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("GET /api/v1/reservations/{id} returns 200 OK for owner or admin")
    void getByIdReturns200() throws Exception {
        when(reservationService.getById(1L)).thenReturn(sampleResponse);

        mockMvc.perform(get("/api/v1/reservations/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    @DisplayName("GET /api/v1/reservations/{id} returns 404 on IDOR (unauthorized user)")
    void getByIdIdorReturns404() throws Exception {
        when(reservationService.getById(999L))
                .thenThrow(new NotFoundException("RESERVATION_NOT_FOUND", "Reservation not found: 999"));

        mockMvc.perform(get("/api/v1/reservations/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("Reservation not found: 999"));
    }

    @Test
    @DisplayName("POST /api/v1/reservations/{id}/cancel returns 200 OK")
    void cancelReturns200() throws Exception {
        ReservationResponse cancelled = new ReservationResponse(
                1L,
                42L,
                10L,
                null,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                ReservationStatus.CANCELLED,
                LocalDateTime.of(2026, 11, 2, 10, 0),
                null,
                null,
                LocalDateTime.of(2026, 11, 2, 11, 0),
                "Cancelled by user");
        when(reservationService.cancelReservation(1L)).thenReturn(cancelled);

        mockMvc.perform(post("/api/v1/reservations/1/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    @DisplayName("POST /api/v1/reservations/{id}/cancel returns 404 on IDOR")
    void cancelIdorReturns404() throws Exception {
        when(reservationService.cancelReservation(999L))
                .thenThrow(new NotFoundException("RESERVATION_NOT_FOUND", "Reservation not found: 999"));

        mockMvc.perform(post("/api/v1/reservations/999/cancel"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));
    }

    @Test
    @DisplayName("GET /api/v1/reservations/availability returns 200 with availability evaluation")
    void checkAvailabilityReturns200() throws Exception {
        AvailabilityResponse resp = AvailabilityResponse.forAsset(
                10L, LocalDateTime.of(2026, 11, 2, 14, 0), LocalDateTime.of(2026, 11, 2, 16, 0), true, null);
        when(reservationService.checkAvailability(eq(10L), isNull(), any(), any()))
                .thenReturn(resp);

        mockMvc.perform(get("/api/v1/reservations/availability")
                        .param("assetId", "10")
                        .param("startTime", "2026-11-02T14:00:00")
                        .param("endTime", "2026-11-02T16:00:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetId").value(10))
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.availableAssetIds[0]").value(10));
    }

    @Test
    @DisplayName("GET /api/v1/reservations/availability returns 400 when startTime is missing")
    void checkAvailabilityMissingParamReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/reservations/availability")
                        .param("assetId", "10")
                        .param("endTime", "2026-11-02T16:00:00"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/v1/reservations/availability with categoryId returns 200 with free assets")
    void checkAvailabilityCategoryReturns200() throws Exception {
        AvailabilityResponse resp = AvailabilityResponse.forCategory(
                5L, LocalDateTime.of(2026, 11, 2, 14, 0), LocalDateTime.of(2026, 11, 2, 16, 0), List.of(10L, 20L));
        when(reservationService.checkAvailability(isNull(), eq(5L), any(), any()))
                .thenReturn(resp);

        mockMvc.perform(get("/api/v1/reservations/availability")
                        .param("categoryId", "5")
                        .param("startTime", "2026-11-02T14:00:00")
                        .param("endTime", "2026-11-02T16:00:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categoryId").value(5))
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.availableAssetIds.length()").value(2));
    }
}
