package com.campus.catalog.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.campus.catalog.domain.LabRoom;
import com.campus.catalog.repository.AssetRepository;
import com.campus.catalog.repository.LabRoomRepository;
import com.campus.catalog.web.LabRoomRequest;
import com.campus.catalog.web.LabRoomResponse;
import com.campus.common.audit.AuditService;
import com.campus.common.error.ConflictException;
import com.campus.common.error.InvalidParameterException;
import com.campus.common.error.NotFoundException;
import com.campus.common.security.CurrentUser;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;

@ExtendWith(MockitoExtension.class)
class LabRoomServiceTest {

    @Mock
    private LabRoomRepository labRoomRepository;

    @Mock
    private AssetRepository assetRepository;

    @Mock
    private AuditService auditService;

    @Mock
    private CurrentUser currentUser;

    @InjectMocks
    private LabRoomService labRoomService;

    @Test
    void listAllReturnsLabRoomsSortedByCode() {
        when(labRoomRepository.findAll(Sort.by("code")))
                .thenReturn(
                        List.of(new LabRoom("B204", "Embedded Lab", "B"), new LabRoom("EC105", "Electronics", "EC")));

        List<LabRoomResponse> result = labRoomService.listAll();

        assertEquals(2, result.size());
        assertEquals("B204", result.getFirst().code());
    }

    @Test
    void createNormalizesCodeAndRecordsAudit() {
        LabRoom saved = new LabRoom("b204", "Embedded Lab", "Building B");
        when(labRoomRepository.existsByCodeIgnoreCase("B204")).thenReturn(false);
        when(labRoomRepository.saveAndFlush(any(LabRoom.class))).thenReturn(saved);
        when(currentUser.id()).thenReturn(3L);

        LabRoomResponse response = labRoomService.create(new LabRoomRequest(" b204 ", "Embedded Lab", "Building B"));

        assertEquals("B204", response.code());
        verify(auditService).record(3L, "LAB_ROOM_CREATED", "LAB_ROOM", null, "Created lab room B204");
    }

    @Test
    void createRejectsDuplicateCode() {
        when(labRoomRepository.existsByCodeIgnoreCase("B204")).thenReturn(true);

        ConflictException ex = assertThrows(
                ConflictException.class,
                () -> labRoomService.create(new LabRoomRequest("b204", "Embedded Lab", "Building B")));

        assertEquals("LAB_ROOM_CODE_TAKEN", ex.getCode());
    }

    @Test
    void createRejectsDuplicateCodeOnConcurrentRace() {
        when(labRoomRepository.existsByCodeIgnoreCase("B204")).thenReturn(false, true);
        when(labRoomRepository.saveAndFlush(any(LabRoom.class)))
                .thenThrow(new DataIntegrityViolationException("unique index constraint"));

        ConflictException ex = assertThrows(
                ConflictException.class,
                () -> labRoomService.create(new LabRoomRequest("b204", "Embedded Lab", "Building B")));

        assertEquals("LAB_ROOM_CODE_TAKEN", ex.getCode());
    }

    @Test
    void createRethrowsDataIntegrityViolationWhenCodeNotTaken() {
        when(labRoomRepository.existsByCodeIgnoreCase("B204")).thenReturn(false);
        when(labRoomRepository.saveAndFlush(any(LabRoom.class)))
                .thenThrow(new DataIntegrityViolationException("other constraint"));

        assertThrows(
                DataIntegrityViolationException.class,
                () -> labRoomService.create(new LabRoomRequest("b204", "Embedded Lab", "Building B")));
    }

    @Test
    void createRejectsCodeWhenLengthExpandsBeyondLimitAndAcceptsExact20Chars() {
        String exact20Code = "A".repeat(20);
        when(labRoomRepository.existsByCodeIgnoreCase(exact20Code)).thenReturn(false);
        when(labRoomRepository.saveAndFlush(any(LabRoom.class))).thenAnswer(inv -> inv.getArgument(0));
        when(currentUser.id()).thenReturn(3L);

        LabRoomResponse validResponse = labRoomService.create(new LabRoomRequest(exact20Code, "Lab", "Building"));
        assertEquals(exact20Code, validResponse.code());

        // 20 chars ending with 'ß'; in Locale.ROOT uppercase it expands to 21 chars ("SS") (#167).
        String expandingCode = "A".repeat(19) + "ß";

        InvalidParameterException ex = assertThrows(
                InvalidParameterException.class,
                () -> labRoomService.create(new LabRoomRequest(expandingCode, "Lab", "Building")));

        assertEquals("INVALID_PARAMETER", ex.getCode());
    }

    @Test
    void createDoesNotCatchAuditFailureAsDuplicateCode() {
        LabRoom saved = new LabRoom("B204", "Embedded Lab", "Building B");
        when(labRoomRepository.existsByCodeIgnoreCase("B204")).thenReturn(false);
        when(labRoomRepository.saveAndFlush(any(LabRoom.class))).thenReturn(saved);
        when(currentUser.id()).thenReturn(3L);
        doThrow(new DataIntegrityViolationException("actor_id FK failure"))
                .when(auditService)
                .record(any(), any(), any(), any(), any());

        assertThrows(
                DataIntegrityViolationException.class,
                () -> labRoomService.create(new LabRoomRequest("b204", "Embedded Lab", "Building B")));
    }

    @Test
    void updateModifiesLabRoomRecordsAuditAndReturnsResponse() {
        LabRoom existing = new LabRoom("OLD1", "Old Lab", "Building A");
        when(labRoomRepository.findById(2L)).thenReturn(Optional.of(existing));
        when(labRoomRepository.existsByCodeIgnoreCaseAndIdNot("EC105", 2L)).thenReturn(false);
        when(labRoomRepository.saveAndFlush(existing)).thenReturn(existing);
        when(currentUser.id()).thenReturn(3L);

        LabRoomResponse response = labRoomService.update(2L, new LabRoomRequest(" ec105 ", "Electronics Lab", "EC"));

        assertNotNull(response);
        assertEquals("EC105", response.code());
        assertEquals("Electronics Lab", response.name());
        assertEquals("EC", response.building());
        verify(auditService).record(3L, "LAB_ROOM_UPDATED", "LAB_ROOM", null, "Updated lab room EC105");
    }

    @Test
    void updateRejectsDuplicateCodeFromAnotherRoom() {
        LabRoom existing = new LabRoom("OLD1", "Old Lab", "Building A");
        when(labRoomRepository.findById(2L)).thenReturn(Optional.of(existing));
        when(labRoomRepository.existsByCodeIgnoreCaseAndIdNot("EC105", 2L)).thenReturn(true);

        ConflictException ex = assertThrows(
                ConflictException.class,
                () -> labRoomService.update(2L, new LabRoomRequest("ec105", "Electronics Lab", "EC")));

        assertEquals("LAB_ROOM_CODE_TAKEN", ex.getCode());
        verify(labRoomRepository, never()).saveAndFlush(any());
        verify(auditService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void updateValidatesMaxCodeLengthAndHandlesConcurrentRace() {
        LabRoom existing = new LabRoom("OLD1", "Old Lab", "Building A");
        String exact20Code = "B".repeat(20);
        when(labRoomRepository.findById(2L)).thenReturn(Optional.of(existing));
        when(labRoomRepository.existsByCodeIgnoreCaseAndIdNot(exact20Code, 2L))
                .thenReturn(false, false, true, false, false);
        when(labRoomRepository.saveAndFlush(existing))
                .thenReturn(existing)
                .thenThrow(new DataIntegrityViolationException("unique constraint"));
        when(currentUser.id()).thenReturn(3L);

        LabRoomResponse ok = labRoomService.update(2L, new LabRoomRequest(exact20Code, "Electronics Lab", "EC"));
        assertEquals(exact20Code, ok.code());

        String expandingCode = "A".repeat(19) + "ß";
        InvalidParameterException lenEx = assertThrows(
                InvalidParameterException.class,
                () -> labRoomService.update(2L, new LabRoomRequest(expandingCode, "Lab", "EC")));
        assertEquals("INVALID_PARAMETER", lenEx.getCode());

        ConflictException raceEx = assertThrows(
                ConflictException.class, () -> labRoomService.update(2L, new LabRoomRequest(exact20Code, "Lab", "EC")));
        assertEquals("LAB_ROOM_CODE_TAKEN", raceEx.getCode());

        assertThrows(
                DataIntegrityViolationException.class,
                () -> labRoomService.update(2L, new LabRoomRequest(exact20Code, "Lab", "EC")));
    }

    @Test
    void updateThrowsNotFoundWhenMissing() {
        when(labRoomRepository.findById(77L)).thenReturn(Optional.empty());

        NotFoundException ex = assertThrows(
                NotFoundException.class, () -> labRoomService.update(77L, new LabRoomRequest("B204", "Lab", "Corp B")));

        assertEquals("LAB_ROOM_NOT_FOUND", ex.getCode());
    }

    @Test
    void deleteRemovesUnusedLabRoomAndRecordsAudit() {
        LabRoom existing = new LabRoom("B204", "Embedded Lab", "Building B");
        when(labRoomRepository.findById(2L)).thenReturn(Optional.of(existing));
        when(assetRepository.existsByLabRoomId(2L)).thenReturn(false);
        when(currentUser.id()).thenReturn(3L);

        labRoomService.delete(2L);

        verify(labRoomRepository).delete(existing);
        verify(labRoomRepository).flush();
        verify(auditService).record(3L, "LAB_ROOM_DELETED", "LAB_ROOM", 2L, "Deleted lab room B204");
    }

    @Test
    void deleteTranslatesFlushConstraintViolationIntoLabRoomInUse() {
        LabRoom existing = new LabRoom("B204", "Embedded Lab", "Building B");
        when(labRoomRepository.findById(2L)).thenReturn(Optional.of(existing));
        when(assetRepository.existsByLabRoomId(2L)).thenReturn(false);
        doThrow(new DataIntegrityViolationException("fk_assets_lab_room"))
                .when(labRoomRepository)
                .flush();

        ConflictException ex = assertThrows(ConflictException.class, () -> labRoomService.delete(2L));

        assertEquals("LAB_ROOM_IN_USE", ex.getCode());
        verify(auditService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void deleteRejectsRoomInUse() {
        LabRoom existing = new LabRoom("B204", "Embedded Lab", "Building B");
        when(labRoomRepository.findById(2L)).thenReturn(Optional.of(existing));
        when(assetRepository.existsByLabRoomId(2L)).thenReturn(true);

        ConflictException ex = assertThrows(ConflictException.class, () -> labRoomService.delete(2L));

        assertEquals("LAB_ROOM_IN_USE", ex.getCode());
        verify(labRoomRepository, never()).delete(any());
    }
}
