package com.campus.catalog.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.campus.catalog.api.AssetStatus;
import com.campus.catalog.domain.Asset;
import com.campus.catalog.domain.Category;
import com.campus.catalog.domain.LabRoom;
import com.campus.catalog.repository.AssetRepository;
import com.campus.catalog.repository.CategoryRepository;
import com.campus.catalog.repository.LabRoomRepository;
import com.campus.catalog.web.AssetResponse;
import com.campus.catalog.web.CreateAssetRequest;
import com.campus.catalog.web.UpdateAssetRequest;
import com.campus.common.audit.AuditService;
import com.campus.common.error.ConflictException;
import com.campus.common.error.InvalidParameterException;
import com.campus.common.error.InvalidStateTransitionException;
import com.campus.common.error.NotFoundException;
import com.campus.common.paging.PageResponse;
import com.campus.common.security.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

@ExtendWith(MockitoExtension.class)
class AssetServiceTest {

    private static final ZoneId CAMPUS_ZONE = ZoneId.of("Europe/Bucharest");
    // Sub-second nanos verify that AssetService truncates timestamps to whole seconds.
    private static final Instant FIXED_INSTANT = Instant.parse("2026-10-29T10:00:00.456789123Z");
    private static final LocalDateTime EXPECTED_NOW = LocalDateTime.of(2026, 10, 29, 12, 0, 0);

    @Mock
    private AssetRepository assetRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private LabRoomRepository labRoomRepository;

    @Mock
    private AuditService auditService;

    @Mock
    private CurrentUser currentUser;

    private AssetService assetService;
    private Category category;
    private LabRoom labRoom;

    @BeforeEach
    void setUp() {
        assetService = new AssetService(
                assetRepository,
                categoryRepository,
                labRoomRepository,
                auditService,
                currentUser,
                Clock.fixed(FIXED_INSTANT, CAMPUS_ZONE));
        category = new Category("Oscilloscopes", "Signal lab");
        labRoom = new LabRoom("EC105", "Electronics Lab", "EC");
    }

    @Test
    void getByIdReturnsAssetDetails() {
        Asset asset =
                new Asset("Rigol #1", "Rigol DS1054Z", "ds1za-001", "4-ch scope", category, labRoom, EXPECTED_NOW);
        when(assetRepository.findWithDetailsById(7L)).thenReturn(Optional.of(asset));

        AssetResponse response = assetService.getById(7L);

        assertEquals("Rigol #1", response.name());
        assertEquals("DS1ZA-001", response.serialNumber());
        assertEquals("Oscilloscopes", response.categoryName());
        assertEquals("EC105", response.labRoomCode());
    }

    @Test
    void getByIdThrowsNotFoundWhenMissing() {
        when(assetRepository.findWithDetailsById(99L)).thenReturn(Optional.empty());

        NotFoundException ex = assertThrows(NotFoundException.class, () -> assetService.getById(99L));

        assertEquals("ASSET_NOT_FOUND", ex.getCode());
    }

    @Test
    void createNormalizesSerialTruncatesSecondsAndRecordsAudit() {
        when(assetRepository.existsBySerialNumberIgnoreCase("DS1ZA-001")).thenReturn(false);
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(category));
        when(labRoomRepository.findById(2L)).thenReturn(Optional.of(labRoom));
        when(assetRepository.saveAndFlush(any(Asset.class))).thenAnswer(inv -> inv.getArgument(0));
        when(currentUser.id()).thenReturn(3L);

        AssetResponse response = assetService.create(
                new CreateAssetRequest("Rigol #1", "Rigol DS1054Z", " ds1za-001 ", "4-ch scope", 1L, 2L));

        assertEquals("DS1ZA-001", response.serialNumber());
        assertEquals(AssetStatus.AVAILABLE, response.status());
        assertEquals(EXPECTED_NOW, response.createdAt());
        verify(auditService).record(3L, "ASSET_CREATED", "ASSET", null, "Created asset Rigol #1 (S/N DS1ZA-001)");
    }

    @Test
    void createRejectsDuplicateSerialNumber() {
        when(assetRepository.existsBySerialNumberIgnoreCase("DS1ZA-001")).thenReturn(true);

        ConflictException ex = assertThrows(
                ConflictException.class,
                () -> assetService.create(
                        new CreateAssetRequest("Rigol #2", "Rigol DS1054Z", "ds1za-001", null, 1L, 2L)));

        assertEquals("SERIAL_NUMBER_TAKEN", ex.getCode());
        verify(assetRepository, never()).saveAndFlush(any());
    }

    @Test
    void createThrowsCategoryNotFoundWhenCategoryMissing() {
        when(assetRepository.existsBySerialNumberIgnoreCase("DS1ZA-001")).thenReturn(false);
        when(categoryRepository.findById(99L)).thenReturn(Optional.empty());

        NotFoundException ex = assertThrows(
                NotFoundException.class,
                () -> assetService.create(
                        new CreateAssetRequest("Rigol #1", "Rigol DS1054Z", "ds1za-001", null, 99L, 2L)));

        assertEquals("CATEGORY_NOT_FOUND", ex.getCode());
    }

    @Test
    void updateRejectsRetiredAsset() {
        Asset retired = new Asset("Old Uno", "Arduino Uno R3", "UNO-001", null, category, labRoom, EXPECTED_NOW);
        retired.retire(EXPECTED_NOW);
        when(assetRepository.findWithDetailsById(6L)).thenReturn(Optional.of(retired));
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(category));
        when(labRoomRepository.findById(2L)).thenReturn(Optional.of(labRoom));

        InvalidStateTransitionException ex = assertThrows(
                InvalidStateTransitionException.class,
                () -> assetService.update(6L, new UpdateAssetRequest("Updated Uno", "Arduino Uno R3", "Desc", 1L, 2L)));

        assertEquals("ASSET_RETIRED", ex.getCode());
    }

    @Test
    void retireMarksAssetRetiredAndRecordsAudit() {
        Asset asset = new Asset("Rigol #1", "Rigol DS1054Z", "DS1ZA-001", null, category, labRoom, EXPECTED_NOW);
        when(assetRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(asset));
        when(assetRepository.saveAndFlush(asset)).thenReturn(asset);
        when(currentUser.id()).thenReturn(3L);

        AssetResponse response = assetService.retire(7L);

        assertEquals(AssetStatus.RETIRED, response.status());
        assertEquals(EXPECTED_NOW, response.updatedAt());
        verify(auditService).record(3L, "ASSET_RETIRED", "ASSET", null, "Retired asset Rigol #1 (S/N DS1ZA-001)");
    }

    @Test
    void retireRejectsAlreadyRetiredAsset() {
        Asset asset = new Asset("Rigol #1", "Rigol DS1054Z", "DS1ZA-001", null, category, labRoom, EXPECTED_NOW);
        asset.retire(EXPECTED_NOW);
        when(assetRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(asset));

        InvalidStateTransitionException ex =
                assertThrows(InvalidStateTransitionException.class, () -> assetService.retire(7L));

        assertEquals("ASSET_RETIRED", ex.getCode());
        verify(auditService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchMapsPageResponseWithWhitelistedSortAndDeterministicIdTieBreaker() {
        Asset asset =
                new Asset("Rigol #1", "Rigol DS1054Z", "DS1ZA-001", "4-ch scope", category, labRoom, EXPECTED_NOW);
        when(currentUser.isAdmin()).thenReturn(false);
        when(assetRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(asset), PageRequest.of(0, 10), 1));

        PageResponse<AssetResponse> page =
                assetService.search("rigol", 2L, 1L, AssetStatus.AVAILABLE, 0, 10, "createdAt,desc");

        assertEquals(1, page.totalElements());
        assertEquals(1, page.content().size());
        assertEquals("Rigol #1", page.content().getFirst().name());

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(assetRepository).findAll(any(Specification.class), pageableCaptor.capture());
        Sort expectedSort = Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.ASC, "id"));
        assertEquals(expectedSort, pageableCaptor.getValue().getSort());
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchDefaultsBlankDirectionToAscending() {
        when(currentUser.isAdmin()).thenReturn(true);
        when(assetRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        PageResponse<AssetResponse> page = assetService.search(null, null, null, null, 0, 20, "model");

        assertEquals(0, page.totalElements());
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(assetRepository).findAll(any(Specification.class), pageableCaptor.capture());
        assertEquals(
                Sort.by(Sort.Direction.ASC, "model").and(Sort.by(Sort.Direction.ASC, "id")),
                pageableCaptor.getValue().getSort());
    }

    @Test
    void searchRejectsInvalidPaginationAndUnwhitelistedSort() {
        InvalidParameterException negativePage = assertThrows(
                InvalidParameterException.class, () -> assetService.search(null, null, null, null, -1, 20, "name,asc"));
        assertEquals("INVALID_PARAMETER", negativePage.getCode());

        InvalidParameterException zeroSize = assertThrows(
                InvalidParameterException.class, () -> assetService.search(null, null, null, null, 0, 0, "name,asc"));
        assertEquals("INVALID_PARAMETER", zeroSize.getCode());

        InvalidParameterException oversizedPage = assertThrows(
                InvalidParameterException.class, () -> assetService.search(null, null, null, null, 0, 101, "name,asc"));
        assertEquals("INVALID_PARAMETER", oversizedPage.getCode());

        InvalidParameterException unknownSort = assertThrows(
                InvalidParameterException.class,
                () -> assetService.search(null, null, null, null, 0, 20, "category,asc"));
        assertEquals("INVALID_PARAMETER", unknownSort.getCode());

        InvalidParameterException badDirection = assertThrows(
                InvalidParameterException.class,
                () -> assetService.search(null, null, null, null, 0, 20, "name,sideways"));
        assertEquals("INVALID_PARAMETER", badDirection.getCode());

        InvalidParameterException extraParts = assertThrows(
                InvalidParameterException.class,
                () -> assetService.search(null, null, null, null, 0, 20, "name,asc,id"));
        assertEquals("INVALID_PARAMETER", extraParts.getCode());
    }
}
