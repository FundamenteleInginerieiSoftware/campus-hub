package com.campus.catalog.service;

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
    private final CurrentUser currentUser;

    @Transactional(readOnly = true)
    public List<LabRoomResponse> listAll() {
        return labRoomRepository.findAll(Sort.by("code")).stream()
                .map(LabRoomResponse::from)
                .toList();
    }

    @Transactional
    public LabRoomResponse create(LabRoomRequest request) {
        String normalizedCode = request.code().trim().toUpperCase(Locale.ROOT);
        // Guard against characters whose uppercase expands (e.g. 'ß' -> 'SS') exceeding VARCHAR(20) (#167).
        if (normalizedCode.length() > 20) {
            throw new InvalidParameterException("Lab room code must not exceed 20 characters");
        }
        if (labRoomRepository.existsByCodeIgnoreCase(normalizedCode)) {
            throw codeTaken(normalizedCode);
        }

        LabRoom labRoom = new LabRoom(normalizedCode, request.name(), request.building());
        LabRoom saved;
        try {
            saved = labRoomRepository.saveAndFlush(labRoom);
        } catch (DataIntegrityViolationException ex) {
            // Check if the code was actually taken by a concurrent insert; otherwise rethrow (#167).
            if (labRoomRepository.existsByCodeIgnoreCase(normalizedCode)) {
                throw codeTaken(normalizedCode);
            }
            throw ex;
        }

        // Recorded outside the try-catch so an audit failure is not falsely reported as a duplicate code (#120).
        auditService.record(
                currentUser.id(), "LAB_ROOM_CREATED", "LAB_ROOM", saved.getId(), "Created lab room " + saved.getCode());
        return LabRoomResponse.from(saved);
    }

    @Transactional
    public LabRoomResponse update(Long id, LabRoomRequest request) {
        LabRoom labRoom = findOrThrow(id);
        String normalizedCode = request.code().trim().toUpperCase(Locale.ROOT);
        // Guard against characters whose uppercase expands (e.g. 'ß' -> 'SS') exceeding VARCHAR(20) (#167).
        if (normalizedCode.length() > 20) {
            throw new InvalidParameterException("Lab room code must not exceed 20 characters");
        }
        if (labRoomRepository.existsByCodeIgnoreCaseAndIdNot(normalizedCode, id)) {
            throw codeTaken(normalizedCode);
        }

        labRoom.update(normalizedCode, request.name(), request.building());
        LabRoom saved;
        try {
            saved = labRoomRepository.saveAndFlush(labRoom);
        } catch (DataIntegrityViolationException ex) {
            // Check if the code was actually taken by a concurrent insert/update; otherwise rethrow (#167).
            if (labRoomRepository.existsByCodeIgnoreCaseAndIdNot(normalizedCode, id)) {
                throw codeTaken(normalizedCode);
            }
            throw ex;
        }

        // Recorded outside the try-catch so an audit failure is not falsely reported as a duplicate code (#120).
        auditService.record(
                currentUser.id(), "LAB_ROOM_UPDATED", "LAB_ROOM", saved.getId(), "Updated lab room " + saved.getCode());
        return LabRoomResponse.from(saved);
    }

    @Transactional
    public void delete(Long id) {
        LabRoom labRoom = findOrThrow(id);
        if (assetRepository.existsByLabRoomId(id)) {
            throw roomInUse(labRoom.getCode());
        }

        try {
            labRoomRepository.delete(labRoom);
            labRoomRepository.flush();
        } catch (DataIntegrityViolationException ex) {
            throw roomInUse(labRoom.getCode());
        }

        // Recorded outside the try-catch so an audit failure is not falsely reported as a lab room in use (#120).
        auditService.record(
                currentUser.id(), "LAB_ROOM_DELETED", "LAB_ROOM", id, "Deleted lab room " + labRoom.getCode());
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
