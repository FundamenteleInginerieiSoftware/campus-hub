package com.campus.reservation.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campus.common.error.BusinessRuleException;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminReservationController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@AutoConfigureMockMvc(addFilters = false)
class AdminReservationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReservationService reservationService;

    private final ReservationResponse checkedOutResponse = new ReservationResponse(
            1L,
            42L,
            10L,
            null,
            LocalDateTime.of(2026, 11, 2, 10, 0),
            LocalDateTime.of(2026, 11, 2, 12, 0),
            ReservationStatus.CHECKED_OUT,
            LocalDateTime.of(2026, 10, 30, 10, 0),
            LocalDateTime.of(2026, 11, 2, 10, 5),
            null,
            null,
            null);

    private final ReservationResponse returnedResponse = new ReservationResponse(
            1L,
            42L,
            10L,
            null,
            LocalDateTime.of(2026, 11, 2, 10, 0),
            LocalDateTime.of(2026, 11, 2, 12, 0),
            ReservationStatus.RETURNED,
            LocalDateTime.of(2026, 10, 30, 10, 0),
            LocalDateTime.of(2026, 11, 2, 10, 5),
            LocalDateTime.of(2026, 11, 2, 11, 55),
            null,
            null);

    @Test
    @DisplayName("GET /api/v1/admin/reservations returns 200 with PageResponse")
    void searchReturns200() throws Exception {
        PageResponse<ReservationResponse> page = new PageResponse<>(List.of(checkedOutResponse), 0, 20, 1L, 1);
        when(reservationService.getAdminReservations(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(page);

        mockMvc.perform(get("/api/v1/admin/reservations")
                        .param("status", "CHECKED_OUT")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(1L))
                .andExpect(jsonPath("$.content[0].status").value("CHECKED_OUT"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("POST /api/v1/admin/reservations/{id}/check-out returns 200 when successful")
    void checkOutReturns200() throws Exception {
        when(reservationService.checkOutReservation(1L)).thenReturn(checkedOutResponse);

        mockMvc.perform(post("/api/v1/admin/reservations/1/check-out"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1L))
                .andExpect(jsonPath("$.status").value("CHECKED_OUT"))
                .andExpect(jsonPath("$.checkedOutAt").isNotEmpty());
    }

    @Test
    @DisplayName("POST /api/v1/admin/reservations/{id}/check-out returns 409 when window is closed")
    void checkOutReturns409WhenWindowClosed() throws Exception {
        when(reservationService.checkOutReservation(1L))
                .thenThrow(
                        new BusinessRuleException(
                                "CHECKOUT_WINDOW_CLOSED",
                                "Check-out is only allowed between 15 minutes before slot start and 30 minutes after slot start"));

        mockMvc.perform(post("/api/v1/admin/reservations/1/check-out"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHECKOUT_WINDOW_CLOSED"));
    }

    @Test
    @DisplayName("POST /api/v1/admin/reservations/{id}/check-out returns 404 when reservation not found")
    void checkOutReturns404WhenNotFound() throws Exception {
        when(reservationService.checkOutReservation(999L))
                .thenThrow(new NotFoundException("RESERVATION_NOT_FOUND", "Reservation not found: 999"));

        mockMvc.perform(post("/api/v1/admin/reservations/999/check-out"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));
    }

    @Test
    @DisplayName("POST /api/v1/admin/reservations/{id}/return returns 200 when successful")
    void returnItemReturns200() throws Exception {
        when(reservationService.returnReservation(1L)).thenReturn(returnedResponse);

        mockMvc.perform(post("/api/v1/admin/reservations/1/return"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1L))
                .andExpect(jsonPath("$.status").value("RETURNED"))
                .andExpect(jsonPath("$.returnedAt").isNotEmpty());
    }
}
