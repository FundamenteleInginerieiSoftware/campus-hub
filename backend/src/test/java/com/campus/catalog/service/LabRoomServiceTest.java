package com.campus.catalog.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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
import com.campus.common.error.NotFoundException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LabRoomServiceTest {

    @Mock
    private LabRoomRepository labRoomRepository;

    @Mock
    private AssetRepository assetRepository;

    @Mock
    private AuditService auditService;

    @InjectMocks
    private LabRoomService labRoomService;

    @Test
    void createNormalizesCodeAndRecordsAudit() {
        LabRoom saved = new LabRoom("b204", "Embedded Lab", "Building B");
        when(labRoomRepository.existsByCodeIgnoreCase("B204")).thenReturn(false);
        when(labRoomRepository.saveAndFlush(any(LabRoom.class))).thenReturn(saved);

        LabRoomResponse response =
                labRoomService.create(new LabRoomRequest(" b204 ", "Embedded Lab", "Building B"), 3L);

        assertEquals("B204", response.code());
        verify(auditService).record(3L, "LAB_ROOM_CREATED", "LAB_ROOM", null, "Created lab room B204");
    }

    @Test
    void createRejectsDuplicateCode() {
        when(labRoomRepository.existsByCodeIgnoreCase("B204")).thenReturn(true);

        ConflictException ex = assertThrows(
                ConflictException.class,
                () -> labRoomService.create(new LabRoomRequest("b204", "Embedded Lab", "Building B"), 3L));

        assertEquals("LAB_ROOM_CODE_TAKEN", ex.getCode());
    }

    @Test
    void updateThrowsNotFoundWhenMissing() {
        when(labRoomRepository.findById(77L)).thenReturn(Optional.empty());

        NotFoundException ex = assertThrows(
                NotFoundException.class,
                () -> labRoomService.update(77L, new LabRoomRequest("B204", "Lab", "Corp B"), 3L));

        assertEquals("LAB_ROOM_NOT_FOUND", ex.getCode());
    }

    @Test
    void deleteRejectsRoomInUse() {
        LabRoom existing = new LabRoom("B204", "Embedded Lab", "Building B");
        when(labRoomRepository.findById(2L)).thenReturn(Optional.of(existing));
        when(assetRepository.existsByLabRoomId(2L)).thenReturn(true);

        ConflictException ex = assertThrows(ConflictException.class, () -> labRoomService.delete(2L, 3L));

        assertEquals("LAB_ROOM_IN_USE", ex.getCode());
        verify(labRoomRepository, never()).delete(any());
    }
}
