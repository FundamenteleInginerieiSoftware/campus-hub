package com.campus.catalog.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campus.catalog.api.AssetStatus;
import com.campus.catalog.service.AssetService;
import com.campus.common.error.ConflictException;
import com.campus.common.error.InvalidParameterException;
import com.campus.common.error.InvalidStateTransitionException;
import com.campus.common.error.NotFoundException;
import com.campus.common.paging.PageResponse;
import com.campus.config.SecurityConfig;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AssetController.class)
@Import(SecurityConfig.class)
class AssetControllerTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 29, 12, 0);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AssetService assetService;

    private static AssetResponse sampleAsset(AssetStatus status) {
        return new AssetResponse(
                7L,
                "Rigol DS1054Z no. 1",
                "Rigol DS1054Z",
                "DS1ZA2419003",
                "4-channel oscilloscope",
                status,
                2L,
                "Oscilloscopes",
                1L,
                "EC105",
                "Electronics Lab",
                NOW,
                NOW);
    }

    @Test
    void unauthenticatedSearchReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/assets"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verifyNoInteractions(assetService);
    }

    @Test
    @WithMockUser(roles = "STUDENT")
    void studentCanSearchAssetsWithDefaultPagingAndFilters() throws Exception {
        PageResponse<AssetResponse> page = new PageResponse<>(List.of(sampleAsset(AssetStatus.AVAILABLE)), 0, 20, 1, 1);
        when(assetService.search("rigol", 2L, 1L, AssetStatus.AVAILABLE, 0, 20, "name,asc"))
                .thenReturn(page);

        mockMvc.perform(get("/api/v1/assets")
                        .param("search", "rigol")
                        .param("categoryId", "2")
                        .param("labRoomId", "1")
                        .param("status", "AVAILABLE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(7))
                .andExpect(jsonPath("$.content[0].name").value("Rigol DS1054Z no. 1"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    @WithMockUser(roles = "STUDENT")
    void searchWithInvalidEnumOrUnsupportedSortReturns400InvalidParameter() throws Exception {
        mockMvc.perform(get("/api/v1/assets").param("status", "BROKEN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        when(assetService.search(null, null, null, null, 0, 20, "secret,asc"))
                .thenThrow(new InvalidParameterException("Unsupported sort property 'secret'"));

        mockMvc.perform(get("/api/v1/assets").param("sort", "secret,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    @WithMockUser(roles = "STUDENT")
    void studentCanReadAssetDetails() throws Exception {
        when(assetService.getById(7L)).thenReturn(sampleAsset(AssetStatus.AVAILABLE));

        mockMvc.perform(get("/api/v1/assets/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.serialNumber").value("DS1ZA2419003"))
                .andExpect(jsonPath("$.status").value("AVAILABLE"));
    }

    @Test
    @WithMockUser(roles = "STUDENT")
    void missingAssetReturns404() throws Exception {
        when(assetService.getById(99L)).thenThrow(new NotFoundException("ASSET_NOT_FOUND", "Asset 99 was not found"));

        mockMvc.perform(get("/api/v1/assets/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ASSET_NOT_FOUND"));
    }

    @Test
    @WithMockUser(roles = "STUDENT")
    void studentCannotCreateOrRetireAsset() throws Exception {
        mockMvc.perform(post("/api/v1/admin/assets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Rigol #1",
                                  "model": "Rigol DS1054Z",
                                  "serialNumber": "DS1ZA-001",
                                  "categoryId": 2,
                                  "labRoomId": 1
                                }"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(post("/api/v1/admin/assets/7/retire"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verifyNoInteractions(assetService);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCreateAssetReturns201() throws Exception {
        when(assetService.create(any(CreateAssetRequest.class))).thenReturn(sampleAsset(AssetStatus.AVAILABLE));

        mockMvc.perform(post("/api/v1/admin/assets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Rigol DS1054Z no. 1",
                                  "model": "Rigol DS1054Z",
                                  "serialNumber": "DS1ZA2419003",
                                  "description": "4-channel oscilloscope",
                                  "categoryId": 2,
                                  "labRoomId": 1
                                }"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.status").value("AVAILABLE"));

        verify(assetService).create(any(CreateAssetRequest.class));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void createWithMissingFieldsReturns400ValidationFailed() throws Exception {
        mockMvc.perform(post("/api/v1/admin/assets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "", "model": "", "serialNumber": ""}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(assetService);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void duplicateSerialReturns409SerialNumberTaken() throws Exception {
        when(assetService.create(any(CreateAssetRequest.class)))
                .thenThrow(new ConflictException("SERIAL_NUMBER_TAKEN", "Serial number already exists"));

        mockMvc.perform(post("/api/v1/admin/assets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Rigol #2",
                                  "model": "Rigol DS1054Z",
                                  "serialNumber": "DS1ZA2419003",
                                  "categoryId": 2,
                                  "labRoomId": 1
                                }"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SERIAL_NUMBER_TAKEN"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void updateRetiredAssetReturns409AssetRetired() throws Exception {
        when(assetService.update(eq(7L), any(UpdateAssetRequest.class)))
                .thenThrow(new InvalidStateTransitionException("ASSET_RETIRED", "Cannot update a retired asset"));

        mockMvc.perform(put("/api/v1/admin/assets/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Rigol #1",
                                  "model": "Rigol DS1054Z",
                                  "categoryId": 2,
                                  "labRoomId": 1
                                }"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASSET_RETIRED"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void retireAssetReturns200WithRetiredStatus() throws Exception {
        when(assetService.retire(7L)).thenReturn(sampleAsset(AssetStatus.RETIRED));

        mockMvc.perform(post("/api/v1/admin/assets/7/retire"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RETIRED"));
    }
}
