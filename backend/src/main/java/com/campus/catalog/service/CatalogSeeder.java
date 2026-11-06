package com.campus.catalog.service;

import com.campus.catalog.domain.Asset;
import com.campus.catalog.domain.Category;
import com.campus.catalog.domain.LabRoom;
import com.campus.catalog.repository.AssetRepository;
import com.campus.catalog.repository.CategoryRepository;
import com.campus.catalog.repository.LabRoomRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dev seeder for lab equipment, categories, and rooms.
 * <p>
 * Runs on the {@code dev} profile only, after {@code UserSeeder} ({@code @Order(1)})
 * and before {@code ReservationSeeder} ({@code @Order(3)}).
 * Provides multi-unit models in the same lab room so Calin's {@code SameModelStrategy}
 * can find available substitute units during demo reassignments.
 */
@Component
@Profile("dev")
@Order(2)
@RequiredArgsConstructor
@Slf4j
public class CatalogSeeder implements CommandLineRunner {

    private final CategoryRepository categoryRepository;
    private final LabRoomRepository labRoomRepository;
    private final AssetRepository assetRepository;
    private final Clock clock;

    @Override
    @Transactional
    public void run(String... args) {
        // Idempotency check: only populate on empty catalog to avoid duplicate serial or code errors on restarts.
        if (categoryRepository.count() > 0 || labRoomRepository.count() > 0 || assetRepository.count() > 0) {
            log.info("Catalog already contains data; skipping dev catalog seeding");
            return;
        }

        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);

        // 1. Categories
        Category catOscilloscopes = categoryRepository.save(
                new Category("Oscilloscopes", "Digital storage oscilloscopes, waveform capture and signal analysis"));
        Category catMultimeters = categoryRepository.save(new Category(
                "Multimeters", "Digital multimeters for voltage, current, resistance and continuity testing"));
        Category catMicrocontrollers = categoryRepository.save(new Category(
                "Microcontrollers", "Embedded development kits and prototyping boards (STM32, ESP32, Arduino)"));
        Category cat3dPrinters = categoryRepository.save(
                new Category("3D Printers", "Additive manufacturing, FDM and resin 3D printers for rapid prototyping"));

        // 2. Lab Rooms
        LabRoom roomA101 = labRoomRepository.save(
                new LabRoom("A101", "Electronics & Instrumentation Lab", "Building A, 1st Floor"));
        LabRoom roomB204 = labRoomRepository.save(new LabRoom("B204", "Embedded Systems Lab", "Building B, 2nd Floor"));
        LabRoom roomC305 =
                labRoomRepository.save(new LabRoom("C305", "Prototyping & Fabrication Lab", "Building C, 3rd Floor"));

        // 3. Assets: multiple units per model for reassignment engine demonstrations.
        // Asset 1 is picked by ReservationSeeder as the first AVAILABLE asset (id = 1).
        Asset osc1 = new Asset(
                "Rigol DS1054Z Digital Oscilloscope 1",
                "DS1054Z",
                "RIGOL-DS1054Z-001",
                "50 MHz, 4-channel digital oscilloscope with 1 GSa/s real-time sample rate",
                catOscilloscopes,
                roomA101,
                now);

        // Substitute 1: same model, same room, operational (AVAILABLE).
        Asset osc2 = new Asset(
                "Rigol DS1054Z Digital Oscilloscope 2",
                "DS1054Z",
                "RIGOL-DS1054Z-002",
                "50 MHz, 4-channel digital oscilloscope with 1 GSa/s real-time sample rate",
                catOscilloscopes,
                roomA101,
                now);

        // Substitute 2: same model, different room (B204).
        Asset osc3 = new Asset(
                "Rigol DS1054Z Digital Oscilloscope 3",
                "DS1054Z",
                "RIGOL-DS1054Z-003",
                "50 MHz, 4-channel digital oscilloscope with 1 GSa/s real-time sample rate",
                catOscilloscopes,
                roomB204,
                now);

        Asset osc4 = new Asset(
                "Keysight DSOX1204G Oscilloscope 1",
                "DSOX1204G",
                "KEYSIGHT-1204G-001",
                "70 MHz, 4-channel oscilloscope with built-in 20 MHz function generator",
                catOscilloscopes,
                roomA101,
                now);

        Asset osc5 = new Asset(
                "Keysight DSOX1204G Oscilloscope 2",
                "DSOX1204G",
                "KEYSIGHT-1204G-002",
                "70 MHz, 4-channel oscilloscope with built-in 20 MHz function generator",
                catOscilloscopes,
                roomA101,
                now);

        Asset multi1 = new Asset(
                "Fluke 115 Digital Multimeter 1",
                "Fluke 115",
                "FLUKE-115-001",
                "True-RMS digital multimeter for electrical field and bench testing",
                catMultimeters,
                roomA101,
                now);

        Asset multi2 = new Asset(
                "Fluke 115 Digital Multimeter 2",
                "Fluke 115",
                "FLUKE-115-002",
                "True-RMS digital multimeter for electrical field and bench testing",
                catMultimeters,
                roomA101,
                now);

        Asset multi3 = new Asset(
                "Fluke 115 Digital Multimeter 3",
                "Fluke 115",
                "FLUKE-115-003",
                "True-RMS digital multimeter for electrical field and bench testing",
                catMultimeters,
                roomB204,
                now);

        Asset multi4 = new Asset(
                "Rigol DM3058E Bench Multimeter",
                "DM3058E",
                "RIGOL-DM3058E-001",
                "5.5 digit dual-display precision bench digital multimeter",
                catMultimeters,
                roomA101,
                now);

        Asset mcu1 = new Asset(
                "STM32F4-Discovery Kit 1",
                "STM32F407G-DISC1",
                "STM32-DISC-001",
                "ARM Cortex-M4 development board with on-board ST-LINK/V2-A debugger",
                catMicrocontrollers,
                roomB204,
                now);

        Asset mcu2 = new Asset(
                "STM32F4-Discovery Kit 2",
                "STM32F407G-DISC1",
                "STM32-DISC-002",
                "ARM Cortex-M4 development board with on-board ST-LINK/V2-A debugger",
                catMicrocontrollers,
                roomB204,
                now);

        Asset mcu3 = new Asset(
                "STM32F4-Discovery Kit 3",
                "STM32F407G-DISC1",
                "STM32-DISC-003",
                "ARM Cortex-M4 development board with on-board ST-LINK/V2-A debugger",
                catMicrocontrollers,
                roomB204,
                now);

        Asset mcu4 = new Asset(
                "ESP32-S3 DevKit 1",
                "ESP32-S3-DevKitC-1",
                "ESP32-S3-001",
                "Wi-Fi + BLE 5.0 development board with Xtensa 32-bit LX7 dual core",
                catMicrocontrollers,
                roomB204,
                now);

        // Pre-configured UNDER_MAINTENANCE unit for catalog filtering demos.
        Asset mcu5 = new Asset(
                "ESP32-S3 DevKit 2 (Under Maintenance)",
                "ESP32-S3-DevKitC-1",
                "ESP32-S3-002",
                "Wi-Fi + BLE 5.0 development board - undergoing USB-C connector repair",
                catMicrocontrollers,
                roomB204,
                now);
        mcu5.markUnderMaintenance(now);

        Asset printer1 = new Asset(
                "Prusa i3 MK3S+ 3D Printer 1",
                "Prusa i3 MK3S+",
                "PRUSA-MK3S-001",
                "FDM 3D printer with automatic mesh bed leveling and magnetic heatbed",
                cat3dPrinters,
                roomC305,
                now);

        Asset printer2 = new Asset(
                "Prusa i3 MK3S+ 3D Printer 2",
                "Prusa i3 MK3S+",
                "PRUSA-MK3S-002",
                "FDM 3D printer with automatic mesh bed leveling and magnetic heatbed",
                cat3dPrinters,
                roomC305,
                now);

        Asset printer3 = new Asset(
                "Bambu Lab X1-Carbon 3D Printer",
                "X1-Carbon",
                "BAMBU-X1C-001",
                "High-speed multi-material 3D printer with dual auto-leveling and AI lidar",
                cat3dPrinters,
                roomC305,
                now);

        // Pre-configured RETIRED asset to verify filtering and retention rules.
        Asset printer4 = new Asset(
                "Anycubic Photon Mono (Retired)",
                "Photon Mono",
                "ANYCUBIC-MONO-001",
                "Monochrome LCD resin 3D printer - retired from service due to screen wear",
                cat3dPrinters,
                roomC305,
                now);
        printer4.retire(now);

        assetRepository.saveAll(List.of(
                osc1, osc2, osc3, osc4, osc5, multi1, multi2, multi3, multi4, mcu1, mcu2, mcu3, mcu4, mcu5, printer1,
                printer2, printer3, printer4));

        log.info(
                "Seeded dev catalog: 4 categories, 3 lab rooms, 18 assets (including multi-unit models and status variants)");
    }
}
