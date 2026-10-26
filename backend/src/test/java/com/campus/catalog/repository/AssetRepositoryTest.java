package com.campus.catalog.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.campus.catalog.domain.Asset;
import com.campus.catalog.domain.Category;
import com.campus.catalog.domain.LabRoom;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

@DataJpaTest
class AssetRepositoryTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 26, 12, 0);

    @Autowired
    private AssetRepository assetRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private LabRoomRepository labRoomRepository;

    @Test
    void findByIdForUpdateLocksAndLoadsAsset() {
        Category category = categoryRepository.save(new Category("Osciloscoape", "Masura"));
        LabRoom labRoom = labRoomRepository.save(new LabRoom("b204", "Lab Robotica", "Corp B"));
        Asset saved = assetRepository.saveAndFlush(
                new Asset("Osciloscop #1", "DS1054Z", "osc-101", "Descriere", category, labRoom, NOW));

        Optional<Asset> locked = assetRepository.findByIdForUpdate(saved.getId());

        assertTrue(locked.isPresent());
        assertEquals("OSC-101", locked.get().getSerialNumber());
        assertEquals("B204", locked.get().getLabRoom().getCode());
    }

    @Test
    void rejectsDuplicateSerialNumberIgnoringCase() {
        Category category = categoryRepository.save(new Category("Osciloscoape", "Masura"));
        LabRoom labRoom = labRoomRepository.save(new LabRoom("B204", "Lab Robotica", "Corp B"));
        assetRepository.saveAndFlush(
                new Asset("Osciloscop #1", "DS1054Z", "osc-101", "Descriere", category, labRoom, NOW));

        Asset duplicate = new Asset("Osciloscop #2", "DS1054Z", "OSC-101", "Descriere", category, labRoom, NOW);

        assertThrows(DataIntegrityViolationException.class, () -> assetRepository.saveAndFlush(duplicate));
    }
}
