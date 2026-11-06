package com.campus.catalog.repository;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.campus.catalog.api.AssetStatus;
import com.campus.catalog.domain.Asset;
import com.campus.catalog.domain.Category;
import com.campus.catalog.domain.LabRoom;
import com.campus.catalog.web.AssetResponse;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

@DataJpaTest
class AssetRepositoryTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 26, 12, 0);

    @Autowired
    private AssetRepository assetRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private LabRoomRepository labRoomRepository;

    @Autowired
    private EntityManager entityManager;

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

    @Test
    void searchSpecificationCombinesFiltersPaginationAndEagerEntityGraph() {
        Sort sortByName = Sort.by(Sort.Direction.ASC, "name").and(Sort.by(Sort.Direction.ASC, "id"));
        // Other @SpringBootTest classes in the suite commit rows into campus_test, so compare against the baseline.
        long baselineStudent = assetRepository
                .findAll(
                        AssetSpecifications.withFilters(null, null, null, null, true), PageRequest.of(0, 1, sortByName))
                .getTotalElements();
        long baselineAdmin = assetRepository.count();

        Category scopes = categoryRepository.save(new Category("SearchTest Oscilloscopes", "Signal lab"));
        Category boards = categoryRepository.save(new Category("SearchTest Dev Kits", "Embedded lab"));
        LabRoom ec105 = labRoomRepository.save(new LabRoom("SRCH105", "Electronics Lab", "EC"));
        LabRoom ed210 = labRoomRepository.save(new LabRoom("SRCH210", "Embedded Lab", "ED"));

        Asset rigol1 = assetRepository.save(
                new Asset("Rigol #1", "Rigol DS1054Z-ST", "ST-DS1ZA-001", "4-ch scope", scopes, ec105, NOW));
        Asset rigolMaint = assetRepository.save(new Asset(
                "Rigol #2", "Rigol DS1054Z-ST", "ST-DS1ZA-002", "Needs probe", scopes, ec105, NOW.plusMinutes(5)));
        rigolMaint.markUnderMaintenance(NOW.plusMinutes(10));
        assetRepository.save(rigolMaint);

        Asset retiredScope = assetRepository.save(
                new Asset("Old Tektronix", "Tektronix TBS1052C-ST", "ST-TEK-900", "Retired unit", scopes, ed210, NOW));
        retiredScope.retire(NOW.plusMinutes(15));
        assetRepository.save(retiredScope);

        Asset arduino = assetRepository.save(
                new Asset("Arduino R4_kit A", "Arduino Uno R4-ST", "ST-ABX-50%-01", "WiFi board", boards, ed210, NOW));
        assetRepository.save(
                new Asset("Arduino R4Xkit B", "Arduino Uno R4-ST", "ST-ABX-500-02", "WiFi board", boards, ed210, NOW));

        entityManager.flush();
        entityManager.clear();

        // 1. Student view (excludeRetired = true) adds only the 4 active/maintenance items, while admin sees all 5.
        Page<Asset> studentAll = assetRepository.findAll(
                AssetSpecifications.withFilters(null, null, null, null, true), PageRequest.of(0, 20, sortByName));
        assertEquals(baselineStudent + 4, studentAll.getTotalElements());

        Page<Asset> adminAll = assetRepository.findAll(
                AssetSpecifications.withFilters(null, null, null, null, false), PageRequest.of(0, 20, sortByName));
        assertEquals(baselineAdmin + 5, adminAll.getTotalElements());

        // 2. Combined filters: search (case-insensitive on model/serial) + category + room + status.
        Page<Asset> availableRigolsInEc105 = assetRepository.findAll(
                AssetSpecifications.withFilters(
                        "ds1054z-st", scopes.getId(), ec105.getId(), AssetStatus.AVAILABLE, true),
                PageRequest.of(0, 10, sortByName));
        assertEquals(
                List.of(rigol1.getId()),
                availableRigolsInEc105.map(Asset::getId).getContent());

        // 3. SQL LIKE wildcards (% and _) in user search input match literally instead of acting as wildcards.
        Page<Asset> literalPercent = assetRepository.findAll(
                AssetSpecifications.withFilters("st-abx-50%", null, null, null, false),
                PageRequest.of(0, 10, sortByName));
        assertEquals(List.of(arduino.getId()), literalPercent.map(Asset::getId).getContent());

        Page<Asset> literalUnderscore = assetRepository.findAll(
                AssetSpecifications.withFilters("R4_kit", boards.getId(), null, null, false),
                PageRequest.of(0, 10, sortByName));
        assertEquals(
                List.of(arduino.getId()), literalUnderscore.map(Asset::getId).getContent());

        // 4. Pagination + EntityGraph: detaching loaded entities proves category and labRoom were fetched in the
        // initial SELECT (no LazyInitializationException when open-in-view is false).
        entityManager.clear();
        Page<Asset> firstPage = assetRepository.findAll(
                AssetSpecifications.withFilters(null, scopes.getId(), null, null, false),
                PageRequest.of(0, 2, sortByName));
        assertEquals(3, firstPage.getTotalElements());
        assertEquals(2, firstPage.getTotalPages());
        assertEquals(2, firstPage.getContent().size());

        entityManager.clear();
        AssetResponse mappedDetached = assertDoesNotThrow(
                () -> AssetResponse.from(firstPage.getContent().getFirst()));
        assertEquals("SearchTest Oscilloscopes", mappedDetached.categoryName());
        assertTrue(List.of("SRCH105", "SRCH210").contains(mappedDetached.labRoomCode()));
    }
}
