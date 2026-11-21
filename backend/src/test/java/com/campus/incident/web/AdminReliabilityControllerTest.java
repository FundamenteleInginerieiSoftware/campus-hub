package com.campus.incident.web;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campus.common.error.GlobalExceptionHandler;
import com.campus.config.SecurityConfig;
import com.campus.incident.service.ReliabilityService;
import java.math.BigDecimal;
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

@WebMvcTest(AdminReliabilityController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class AdminReliabilityControllerTest {

    private static final String URL = "/api/v1/admin/reliability";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 10, 10, 0);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReliabilityService reliabilityService;

    @Test
    @DisplayName("GET reliability returns 401 when anonymous")
    void requiresLogin() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());

        verifyNoInteractions(reliabilityService);
    }

    @Test
    @WithMockUser(roles = "STUDENT")
    @DisplayName("GET reliability returns 403 for a student")
    void studentGets403() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isForbidden());

        verifyNoInteractions(reliabilityService);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET reliability uses a 180-day window by default")
    void defaultWindowIs180() throws Exception {
        when(reliabilityService.report(180)).thenReturn(report(List.of()));

        mockMvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.windowDays").value(180));

        verify(reliabilityService).report(180);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET reliability returns 400 VALIDATION_FAILED for days below 30")
    void rejectsDaysBelow30() throws Exception {
        mockMvc.perform(get(URL).param("days", "29"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(reliabilityService);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET reliability returns 400 VALIDATION_FAILED for days above 365")
    void rejectsDaysAbove365() throws Exception {
        mockMvc.perform(get(URL).param("days", "366"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(reliabilityService);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET reliability returns the ranked units")
    void returnsTheReport() throws Exception {
        when(reliabilityService.report(90)).thenReturn(report(List.of(lemon())));

        mockMvc.perform(get(URL).param("days", "90"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].assetId").value(2))
                .andExpect(jsonPath("$.items[0].level").value("INSPECT"))
                .andExpect(jsonPath("$.items[0].failuresPer100h").value(5.0))
                .andExpect(jsonPath("$.items[0].reason")
                        .value("2 incidents in the last 180 days; fails 6.0× more often than other DS1054Z units"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET reliability sends missing numbers as JSON null, never NaN or Infinity")
    void nullNumbersAreJsonNull() throws Exception {
        AssetReliabilityItem noData = new AssetReliabilityItem(
                3L,
                "Fluke 115 #3",
                "Fluke 115",
                "AVAILABLE",
                1,
                0,
                new BigDecimal("0.0"),
                new BigDecimal("10.0"),
                null,
                null,
                null,
                ReliabilityLevel.WATCH,
                "1 incident in the last 180 days");
        when(reliabilityService.report(180)).thenReturn(report(List.of(noData)));

        mockMvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].vsModel").hasJsonPath())
                .andExpect(jsonPath("$.items[0].vsModel").value((Object) null))
                .andExpect(jsonPath("$.items[0].mtbfHours").hasJsonPath())
                .andExpect(jsonPath("$.items[0].mtbfHours").value((Object) null))
                .andExpect(jsonPath("$.items[0].avgRepairHours").hasJsonPath())
                .andExpect(jsonPath("$.items[0].avgRepairHours").value((Object) null));
    }

    private static ReliabilityReportResponse report(List<AssetReliabilityItem> items) {
        return new ReliabilityReportResponse(180, NOW.minusDays(180), NOW, items);
    }

    private static AssetReliabilityItem lemon() {
        return new AssetReliabilityItem(
                2L,
                "DS1054Z #2",
                "DS1054Z",
                "AVAILABLE",
                2,
                1,
                new BigDecimal("40.0"),
                new BigDecimal("5.0"),
                new BigDecimal("20.0"),
                null,
                new BigDecimal("6.0"),
                ReliabilityLevel.INSPECT,
                "2 incidents in the last 180 days; fails 6.0× more often than other DS1054Z units");
    }
}
