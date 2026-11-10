package com.campus.catalog.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.campus.catalog.api.AssetRetiredEvent;
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
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
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
    private ApplicationEventPublisher eventPublisher;

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
                eventPublisher,
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
    void createRejectsDuplicateSerialOnConcurrentRace() {
        // First check returns false, but concurrent insert causes the second check in the catch block to return true.
        when(assetRepository.existsBySerialNumberIgnoreCase("DS1ZA-001")).thenReturn(false, true);
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(category));
        when(labRoomRepository.findById(2L)).thenReturn(Optional.of(labRoom));
        when(assetRepository.saveAndFlush(any(Asset.class)))
                .thenThrow(new DataIntegrityViolationException("unique index constraint"));

        ConflictException ex = assertThrows(
                ConflictException.class,
                () -> assetService.create(
                        new CreateAssetRequest("Rigol #1", "Rigol DS1054Z", "ds1za-001", null, 1L, 2L)));

        assertEquals("SERIAL_NUMBER_TAKEN", ex.getCode());
        verify(auditService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void createRethrowsDataIntegrityViolationWhenSerialNotTaken() {
        // Serial is NOT taken, meaning the DataIntegrityViolationException is a FK constraint failure (#167).
        when(assetRepository.existsBySerialNumberIgnoreCase("DS1ZA-001")).thenReturn(false);
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(category));
        when(labRoomRepository.findById(2L)).thenReturn(Optional.of(labRoom));
        when(assetRepository.saveAndFlush(any(Asset.class)))
                .thenThrow(new DataIntegrityViolationException("foreign key violation on category_id"));

        assertThrows(
                DataIntegrityViolationException.class,
                () -> assetService.create(
                        new CreateAssetRequest("Rigol #1", "Rigol DS1054Z", "ds1za-001", null, 1L, 2L)));
        verify(auditService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void createRejectsSerialNumberWhenLengthExpandsBeyondLimitAndAcceptsExact100Chars() {
        String exact100Serial = "A".repeat(100);
        when(assetRepository.existsBySerialNumberIgnoreCase(exact100Serial)).thenReturn(false);
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(category));
        when(labRoomRepository.findById(2L)).thenReturn(Optional.of(labRoom));
        when(assetRepository.saveAndFlush(any(Asset.class))).thenAnswer(inv -> inv.getArgument(0));
        when(currentUser.id()).thenReturn(3L);

        AssetResponse validResponse =
                assetService.create(new CreateAssetRequest("Scope", "DS1054Z", exact100Serial, null, 1L, 2L));
        assertEquals(exact100Serial, validResponse.serialNumber());

        // 100 chars ending with 'ß'; in Locale.ROOT uppercase it expands to 101 chars ("SS") (#167).
        String expandingSerial = "A".repeat(99) + "ß";

        InvalidParameterException ex = assertThrows(
                InvalidParameterException.class,
                () -> assetService.create(new CreateAssetRequest("Scope", "DS1054Z", expandingSerial, null, 1L, 2L)));

        assertEquals("INVALID_PARAMETER", ex.getCode());
    }

    @Test
    void createDoesNotCatchAuditFailureAsDuplicateSerial() {
        Asset asset = new Asset("Rigol #1", "Rigol DS1054Z", "DS1ZA-001", null, category, labRoom, EXPECTED_NOW);
        when(assetRepository.existsBySerialNumberIgnoreCase("DS1ZA-001")).thenReturn(false);
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(category));
        when(labRoomRepository.findById(2L)).thenReturn(Optional.of(labRoom));
        when(assetRepository.saveAndFlush(any(Asset.class))).thenReturn(asset);
        when(currentUser.id()).thenReturn(3L);
        doThrow(new DataIntegrityViolationException("actor_id FK failure"))
                .when(auditService)
                .record(any(), any(), any(), any(), any());

        assertThrows(
                DataIntegrityViolationException.class,
                () -> assetService.create(
                        new CreateAssetRequest("Rigol #1", "Rigol DS1054Z", "ds1za-001", null, 1L, 2L)));
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
    void createThrowsLabRoomNotFoundWhenLabRoomMissing() {
        when(assetRepository.existsBySerialNumberIgnoreCase("DS1ZA-001")).thenReturn(false);
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(category));
        when(labRoomRepository.findById(99L)).thenReturn(Optional.empty());

        NotFoundException ex = assertThrows(
                NotFoundException.class,
                () -> assetService.create(
                        new CreateAssetRequest("Rigol #1", "Rigol DS1054Z", "ds1za-001", null, 1L, 99L)));

        assertEquals("LAB_ROOM_NOT_FOUND", ex.getCode());
    }

    @Test
    void updateModifiesFieldsRecordsAuditAndReturnsResponse() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 10, 20, 9, 0, 0);
        Asset asset = new Asset("Old Name", "Old Model", "DS1ZA-001", "Old desc", category, labRoom, createdAt);
        Category newCategory = new Category("Multimeters", "Bench meters");
        LabRoom newRoom = new LabRoom("EC202", "Power Lab", "EC");

        when(assetRepository.findWithDetailsById(7L)).thenReturn(Optional.of(asset));
        when(categoryRepository.findById(2L)).thenReturn(Optional.of(newCategory));
        when(labRoomRepository.findById(3L)).thenReturn(Optional.of(newRoom));
        when(assetRepository.saveAndFlush(asset)).thenReturn(asset);
        when(currentUser.id()).thenReturn(3L);

        AssetResponse response =
                assetService.update(7L, new UpdateAssetRequest("New Name", "New Model", "New desc", 2L, 3L));

        assertNotNull(response);
        assertEquals("New Name", response.name());
        assertEquals("New Model", response.model());
        assertEquals("New desc", response.description());
        assertEquals("Multimeters", response.categoryName());
        assertEquals("EC202", response.labRoomCode());
        assertEquals(EXPECTED_NOW, response.updatedAt());
        verify(auditService).record(3L, "ASSET_UPDATED", "ASSET", null, "Updated asset New Name");
    }

    @Test
    void updateThrowsNotFoundWhenAssetMissing() {
        when(assetRepository.findWithDetailsById(404L)).thenReturn(Optional.empty());

        NotFoundException ex = assertThrows(
                NotFoundException.class,
                () -> assetService.update(404L, new UpdateAssetRequest("Name", "Model", "Desc", 1L, 2L)));

        assertEquals("ASSET_NOT_FOUND", ex.getCode());
    }

    @Test
    void updateRejectsRetiredAssetWithoutQueryingForeignKeys() {
        Asset retired = new Asset("Old Uno", "Arduino Uno R3", "UNO-001", null, category, labRoom, EXPECTED_NOW);
        retired.retire(EXPECTED_NOW);
        when(assetRepository.findWithDetailsById(6L)).thenReturn(Optional.of(retired));

        InvalidStateTransitionException ex = assertThrows(
                InvalidStateTransitionException.class,
                () -> assetService.update(6L, new UpdateAssetRequest("Updated Uno", "Arduino Uno R3", "Desc", 1L, 2L)));

        assertEquals("ASSET_RETIRED", ex.getCode());
        // Category and labRoom must NOT be queried if the asset is already retired (#160).
        verify(categoryRepository, never()).findById(any());
        verify(labRoomRepository, never()).findById(any());
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
        verify(eventPublisher).publishEvent(new AssetRetiredEvent(null, EXPECTED_NOW, 3L));
    }

    @Test
    void retireThrowsNotFoundWhenAssetMissing() {
        when(assetRepository.findByIdForUpdate(404L)).thenReturn(Optional.empty());

        NotFoundException ex = assertThrows(NotFoundException.class, () -> assetService.retire(404L));

        assertEquals("ASSET_NOT_FOUND", ex.getCode());
        verify(eventPublisher, never()).publishEvent(any());
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
        verify(eventPublisher, never()).publishEvent(any());
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

        ArgumentCaptor<Specification<Asset>> specCaptor = ArgumentCaptor.forClass(Specification.class);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(assetRepository).findAll(specCaptor.capture(), pageableCaptor.capture());
        Sort expectedSort = Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.ASC, "id"));
        assertEquals(expectedSort, pageableCaptor.getValue().getSort());

        // Student search (isAdmin == false) must add the status != RETIRED predicate.
        Root<Asset> root = mock(Root.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Path<Object> textPath = mock(Path.class);
        Path<Object> statusPath = mock(Path.class);
        when(root.get("name")).thenReturn(textPath);
        when(root.get("model")).thenReturn(textPath);
        when(root.get("serialNumber")).thenReturn(textPath);
        when(root.get("category")).thenReturn(textPath);
        when(root.get("labRoom")).thenReturn(textPath);
        when(root.get("status")).thenReturn(statusPath);
        when(cb.and(any(Predicate[].class))).thenReturn(mock(Predicate.class));

        specCaptor.getValue().toPredicate(root, query, cb);
        verify(cb).notEqual(statusPath, AssetStatus.RETIRED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchDefaultsBlankDirectionToAscendingAndAdminIncludesRetired() {
        when(currentUser.isAdmin()).thenReturn(true);
        when(assetRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        PageResponse<AssetResponse> page = assetService.search(null, null, null, null, 0, 20, "model");

        assertEquals(0, page.totalElements());
        ArgumentCaptor<Specification<Asset>> specCaptor = ArgumentCaptor.forClass(Specification.class);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(assetRepository).findAll(specCaptor.capture(), pageableCaptor.capture());
        assertEquals(
                Sort.by(Sort.Direction.ASC, "model").and(Sort.by(Sort.Direction.ASC, "id")),
                pageableCaptor.getValue().getSort());

        // Admin search (isAdmin == true) must NOT exclude RETIRED assets.
        Root<Asset> root = mock(Root.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        when(cb.and(any(Predicate[].class))).thenReturn(mock(Predicate.class));

        specCaptor.getValue().toPredicate(root, query, cb);
        verify(cb, never()).notEqual(any(), eq(AssetStatus.RETIRED));
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchAcceptsBoundaryPageSizesAndMaxOffset() {
        when(currentUser.isAdmin()).thenReturn(true);
        when(assetRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 1), 0));

        // size == 1 (lower boundary)
        assertNotNull(assetService.search(null, null, null, null, 0, 1, "name,asc"));
        // size == 100 (MAX_PAGE_SIZE upper boundary)
        assertNotNull(assetService.search(null, null, null, null, 0, 100, "name,asc"));
        // page * size == Integer.MAX_VALUE (exact offset boundary must be allowed)
        assertNotNull(assetService.search(null, null, null, null, Integer.MAX_VALUE, 1, "name,asc"));
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

        InvalidParameterException hugeOffset = assertThrows(
                InvalidParameterException.class,
                () -> assetService.search(null, null, null, null, (Integer.MAX_VALUE / 2) + 1, 2, "name,asc"));
        assertEquals("INVALID_PARAMETER", hugeOffset.getCode());

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
