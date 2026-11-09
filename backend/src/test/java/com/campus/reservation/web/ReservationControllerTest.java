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

import com.campus.common.error.BusinessRuleException;
import com.campus.common.error.ConflictException;
import com.campus.common.error.GlobalExceptionHandler;
import com.campus.common.error.NotFoundException;
import com.campus.common.paging.PageResponse;
import com.campus.config.SecurityConfig;
import com.campus.reservation.api.ReservationStatus;
import com.campus.reservation.domain.TransferStatus;
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
    @DisplayName("POST /api/v1/reservations with Z offset returns 400 MALFORMED_REQUEST")
    void createWithZOffsetReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/reservations")
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
    @DisplayName("POST /api/v1/reservations with +05:00 offset returns 400 MALFORMED_REQUEST")
    void createWithOffsetReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                            "assetId": 10,
                            "startTime": "2026-11-02T14:00:00+05:00",
                            "endTime": "2026-11-02T16:00:00+05:00"
                        }
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    @DisplayName("POST /api/v1/reservations/bundle with Z offset returns 400 MALFORMED_REQUEST")
    void createMultiAssetWithZOffsetReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/reservations/bundle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                            "assetIds": [10, 20],
                            "startTime": "2026-11-02T14:00:00Z",
                            "endTime": "2026-11-02T16:00:00Z"
                        }
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    @DisplayName("POST /api/v1/reservations/{id}/extend with Z offset returns 400 MALFORMED_REQUEST")
    void extendReservationWithZOffsetReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/reservations/1/extend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                            "newEndTime": "2026-11-02T17:00:00Z"
                        }
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    @DisplayName("GET /api/v1/reservations/me returns 200 OK with PageResponse")
    void getMyReservationsReturns200() throws Exception {
        PageResponse<ReservationResponse> page = new PageResponse<>(List.of(sampleResponse), 0, 20, 1L, 1);
        when(reservationService.getMyReservations(
                        org.mockito.ArgumentMatchers.<List<ReservationStatus>>any(), anyInt(), anyInt()))
                .thenReturn(page);

        mockMvc.perform(get("/api/v1/reservations/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("GET /api/v1/reservations/me returns 400 when page is negative")
    void getMyReservationsInvalidPageReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/reservations/me").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("GET /api/v1/reservations/me returns 400 when size exceeds max 100")
    void getMyReservationsInvalidSizeReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/reservations/me").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("GET /api/v1/reservations/me/summary returns 200 OK with summary counts")
    void getMySummaryReturns200() throws Exception {
        when(reservationService.getMySummary()).thenReturn(new ReservationSummaryResponse(2L, 5L, 1L));

        mockMvc.perform(get("/api/v1/reservations/me/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeCount").value(2))
                .andExpect(jsonPath("$.completedCount").value(5))
                .andExpect(jsonPath("$.reassignedCount").value(1));
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
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    @DisplayName("GET /api/v1/reservations/availability with Z offset returns 400 INVALID_PARAMETER")
    void checkAvailabilityWithZOffsetReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/reservations/availability")
                        .param("assetId", "10")
                        .param("startTime", "2026-11-02T14:00:00Z")
                        .param("endTime", "2026-11-02T16:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    @DisplayName("GET /api/v1/reservations/availability with +05:00 offset returns 400 INVALID_PARAMETER")
    void checkAvailabilityWithOffsetReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/reservations/availability")
                        .param("assetId", "10")
                        .param("startTime", "2026-11-02T14:00:00+05:00")
                        .param("endTime", "2026-11-02T16:00:00+05:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    @DisplayName("POST /api/v1/reservations with malformed JSON body returns 400 MALFORMED_REQUEST")
    void createWithMalformedJsonReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{malformed-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    @DisplayName("POST /api/v1/reservations with missing required fields returns 400 Bad Request")
    void createWithInvalidBodyReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("POST /api/v1/reservations outside lab hours returns 409 Conflict")
    void createOutsideOpeningHoursReturns409() throws Exception {
        when(reservationService.createReservation(any()))
                .thenThrow(new BusinessRuleException(
                        "OUTSIDE_OPENING_HOURS", "Reservations can only be made between 08:00 and 20:00"));

        mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                            "assetId": 10,
                            "startTime": "2026-11-02T21:00:00",
                            "endTime": "2026-11-02T22:00:00"
                        }
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OUTSIDE_OPENING_HOURS"));
    }

    @Test
    @DisplayName("POST /api/v1/reservations with conflict returns 409 Conflict")
    void createWithConflictReturns409() throws Exception {
        when(reservationService.createReservation(any()))
                .thenThrow(new ConflictException("RESERVATION_CONFLICT", "Asset is already booked during this time"));

        mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                            "assetId": 10,
                            "startTime": "2026-11-02T14:00:00",
                            "endTime": "2026-11-02T16:00:00"
                        }
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_CONFLICT"));
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

    @Test
    @DisplayName("GET /api/v1/reservations/availability without assetId or categoryId returns 409 Conflict")
    void checkAvailabilityMissingBothIdsReturns409() throws Exception {
        when(reservationService.checkAvailability(isNull(), isNull(), any(), any()))
                .thenThrow(new BusinessRuleException(
                        "MISSING_PARAMETER", "Either assetId or categoryId must be provided"));

        mockMvc.perform(get("/api/v1/reservations/availability")
                        .param("startTime", "2026-11-02T14:00:00")
                        .param("endTime", "2026-11-02T16:00:00"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MISSING_PARAMETER"));
    }

    @Test
    @DisplayName("POST /api/v1/reservations/bundle returns 201 CREATED")
    void createBundleReturns201() throws Exception {
        java.util.UUID bundleId = java.util.UUID.randomUUID();
        MultiAssetReservationResponse response = new MultiAssetReservationResponse(
                bundleId,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                2,
                List.of(sampleResponse));

        when(reservationService.createMultiAssetReservation(any())).thenReturn(response);

        String json = """
                {
                    "assetIds": [10, 20],
                    "startTime": "2026-11-02T14:00:00",
                    "endTime": "2026-11-02T16:00:00"
                }
                """;

        mockMvc.perform(post("/api/v1/reservations/bundle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bundleId").value(bundleId.toString()))
                .andExpect(jsonPath("$.totalAssets").value(2));
    }

    @Test
    @DisplayName("GET /api/v1/reservations/bundle/{bundleId} returns 200 OK")
    void getBundleReturns200() throws Exception {
        java.util.UUID bundleId = java.util.UUID.randomUUID();
        MultiAssetReservationResponse response = new MultiAssetReservationResponse(
                bundleId,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                1,
                List.of(sampleResponse));

        when(reservationService.getBundle(bundleId)).thenReturn(response);

        mockMvc.perform(get("/api/v1/reservations/bundle/" + bundleId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bundleId").value(bundleId.toString()));
    }

    @Test
    @DisplayName("POST /api/v1/reservations/bundle/{bundleId}/cancel returns 200 OK")
    void cancelBundleReturns200() throws Exception {
        java.util.UUID bundleId = java.util.UUID.randomUUID();
        when(reservationService.cancelBundle(eq(bundleId), any())).thenReturn(List.of(sampleResponse));

        mockMvc.perform(post("/api/v1/reservations/bundle/" + bundleId + "/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1));
    }

    @Test
    @DisplayName("POST /api/v1/reservations/{id}/extend returns 200 OK")
    void extendReturns200() throws Exception {
        when(reservationService.extendReservation(eq(1L), any())).thenReturn(sampleResponse);

        String json = """
                {
                    "newEndTime": "2026-11-02T17:00:00"
                }
                """;

        mockMvc.perform(post("/api/v1/reservations/1/extend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    @DisplayName("POST /api/v1/reservations/{id}/transfers returns 201 CREATED")
    void initiateTransferReturns201() throws Exception {
        ReservationTransferResponse response = new ReservationTransferResponse(
                500L, 100L, 42L, 99L, TransferStatus.PENDING, LocalDateTime.of(2026, 11, 2, 10, 0), null);

        when(reservationService.initiateTransfer(eq(100L), any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/reservations/100/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipientUserId\": 99}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(500))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    @DisplayName("GET /api/v1/reservations/transfers/pending returns 200 OK")
    void getPendingTransfersReturns200() throws Exception {
        ReservationTransferResponse response = new ReservationTransferResponse(
                500L, 100L, 42L, 99L, TransferStatus.PENDING, LocalDateTime.of(2026, 11, 2, 10, 0), null);

        when(reservationService.getMyPendingTransfers()).thenReturn(List.of(response));

        mockMvc.perform(get("/api/v1/reservations/transfers/pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(500));
    }

    @Test
    @DisplayName("POST /api/v1/reservations/transfers/{id}/accept returns 200 OK")
    void acceptTransferReturns200() throws Exception {
        ReservationTransferResponse response = new ReservationTransferResponse(
                500L,
                100L,
                42L,
                99L,
                TransferStatus.ACCEPTED,
                LocalDateTime.of(2026, 11, 2, 10, 0),
                LocalDateTime.of(2026, 11, 2, 10, 15));

        when(reservationService.acceptTransfer(500L)).thenReturn(response);

        mockMvc.perform(post("/api/v1/reservations/transfers/500/accept"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
    }

    @Test
    @DisplayName("POST /api/v1/reservations/transfers/{id}/reject returns 200 OK")
    void rejectTransferReturns200() throws Exception {
        ReservationTransferResponse response = new ReservationTransferResponse(
                500L,
                100L,
                42L,
                99L,
                TransferStatus.REJECTED,
                LocalDateTime.of(2026, 11, 2, 10, 0),
                LocalDateTime.of(2026, 11, 2, 10, 15));

        when(reservationService.rejectTransfer(500L)).thenReturn(response);

        mockMvc.perform(post("/api/v1/reservations/transfers/500/reject"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    @DisplayName("POST /api/v1/reservations/transfers/{id}/cancel returns 200 OK")
    void cancelTransferReturns200() throws Exception {
        ReservationTransferResponse response = new ReservationTransferResponse(
                500L,
                100L,
                42L,
                99L,
                TransferStatus.CANCELLED,
                LocalDateTime.of(2026, 11, 2, 10, 0),
                LocalDateTime.of(2026, 11, 2, 10, 15));

        when(reservationService.cancelTransfer(500L)).thenReturn(response);

        mockMvc.perform(post("/api/v1/reservations/transfers/500/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }
}
