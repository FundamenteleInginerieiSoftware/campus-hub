package com.campus.catalog.service;

import com.campus.catalog.domain.LabRoom;
import com.campus.catalog.repository.AssetRepository;
import com.campus.catalog.repository.LabRoomRepository;
import com.campus.catalog.web.LabRoomRequest;
import com.campus.catalog.web.LabRoomResponse;
import com.campus.common.audit.AuditService;
import com.campus.common.error.ConflictException;
import com.campus.common.error.NotFoundException;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LabRoomService {

    private final LabRoomRepository labRoomRepository;
    private final AssetRepository assetRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<LabRoomResponse> listAll() {
        return labRoomRepository.findAll(Sort.by("code")).stream()
                .map(LabRoomResponse::from)
                .toList();
    }

    @Transactional
    public LabRoomResponse create(LabRoomRequest request, Long actorId) {
        String normalizedCode = request.code().trim().toUpperCase(Locale.ROOT);
        if (labRoomRepository.existsByCodeIgnoreCase(normalizedCode)) {
            throw codeTaken(normalizedCode);
        }

        LabRoom labRoom = new LabRoom(normalizedCode, request.name(), request.building());
        try {
            LabRoom saved = labRoomRepository.saveAndFlush(labRoom);
            auditService.record(
                    actorId, "LAB_ROOM_CREATED", "LAB_ROOM", saved.getId(), "Created lab room " + saved.getCode());
            return LabRoomResponse.from(saved);
        } catch (DataIntegrityViolationException ex) {
            // Concurrent creates can both pass existsByCodeIgnoreCase; the DB unique index stops the second with 409.
            throw codeTaken(normalizedCode);
        }
    }

    @Transactional
    public LabRoomResponse update(Long id, LabRoomRequest request, Long actorId) {
        LabRoom labRoom = findOrThrow(id);
        String normalizedCode = request.code().trim().toUpperCase(Locale.ROOT);
        if (labRoomRepository.existsByCodeIgnoreCaseAndIdNot(normalizedCode, id)) {
            throw codeTaken(normalizedCode);
        }

        labRoom.update(normalizedCode, request.name(), request.building());
        try {
            LabRoom saved = labRoomRepository.saveAndFlush(labRoom);
            auditService.record(
                    actorId, "LAB_ROOM_UPDATED", "LAB_ROOM", saved.getId(), "Updated lab room " + saved.getCode());
            return LabRoomResponse.from(saved);
        } catch (DataIntegrityViolationException ex) {
            // A concurrent create or rename can pass the check above; the DB unique index stops it with 409.
            throw codeTaken(normalizedCode);
        }
    }

    @Transactional
    public void delete(Long id, Long actorId) {
        LabRoom labRoom = findOrThrow(id);
        if (assetRepository.existsByLabRoomId(id)) {
            throw roomInUse(labRoom.getCode());
        }

        try {
            labRoomRepository.delete(labRoom);
            labRoomRepository.flush();
            auditService.record(actorId, "LAB_ROOM_DELETED", "LAB_ROOM", id, "Deleted lab room " + labRoom.getCode());
        } catch (DataIntegrityViolationException ex) {
            throw roomInUse(labRoom.getCode());
        }
    }

    private LabRoom findOrThrow(Long id) {
        return labRoomRepository
                .findById(id)
                .orElseThrow(() -> new NotFoundException("LAB_ROOM_NOT_FOUND", "Lab room " + id + " was not found"));
    }

    private static ConflictException codeTaken(String code) {
        return new ConflictException("LAB_ROOM_CODE_TAKEN", "Lab room code '" + code + "' already exists");
    }

    private static ConflictException roomInUse(String code) {
        return new ConflictException(
                "LAB_ROOM_IN_USE", "Lab room '" + code + "' cannot be deleted while assets still reference it");
    }
}
