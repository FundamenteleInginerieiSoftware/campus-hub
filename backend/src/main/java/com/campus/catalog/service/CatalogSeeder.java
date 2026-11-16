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
        Category catSensors = categoryRepository.save(new Category(
                "Sensors & Modules", "Sensor breakout boards, displays, robotics modules and actuator kits"));
        Category catPowerProto = categoryRepository.save(new Category(
                "Power & Prototyping", "Benchtop DC power supplies, soldering stations and prototyping accessories"));

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

        Asset mcu5 = new Asset(
                "ESP32-S3 DevKit 2",
                "ESP32-S3-DevKitC-1",
                "ESP32-S3-002",
                "Wi-Fi + BLE 5.0 development board with Xtensa 32-bit LX7 dual core",
                catMicrocontrollers,
                roomB204,
                now);

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

        // 4. Student project hardware kits (#140) for Projects Assistant & atomic "Reserve all" bundle bookings.
        // Microcontrollers: Arduino Uno R3 boards
        Asset arduino1 = new Asset(
                "Arduino Uno R3 Dev Board 1",
                "Arduino Uno R3",
                "ARDUINO-UNO-001",
                "ATmega328P microcontroller board with 14 digital I/O pins and 6 analog inputs",
                catMicrocontrollers,
                roomB204,
                now);
        Asset arduino2 = new Asset(
                "Arduino Uno R3 Dev Board 2",
                "Arduino Uno R3",
                "ARDUINO-UNO-002",
                "ATmega328P microcontroller board with 14 digital I/O pins and 6 analog inputs",
                catMicrocontrollers,
                roomB204,
                now);
        Asset arduino3 = new Asset(
                "Arduino Uno R3 Dev Board 3",
                "Arduino Uno R3",
                "ARDUINO-UNO-003",
                "ATmega328P microcontroller board with 14 digital I/O pins and 6 analog inputs",
                catMicrocontrollers,
                roomB204,
                now);

        // Sensors & Modules
        Asset ds18b20_1 = new Asset(
                "DS18B20 Temperature Sensor Kit 1",
                "DS18B20 temperature sensor kit",
                "SENSOR-DS18B20-001",
                "1-Wire digital temperature sensor probe with 4.7k pull-up breakout resistor",
                catSensors,
                roomB204,
                now);
        Asset ds18b20_2 = new Asset(
                "DS18B20 Temperature Sensor Kit 2",
                "DS18B20 temperature sensor kit",
                "SENSOR-DS18B20-002",
                "1-Wire digital temperature sensor probe with 4.7k pull-up breakout resistor",
                catSensors,
                roomB204,
                now);

        Asset hcsr04_1 = new Asset(
                "HC-SR04 Ultrasonic Distance Sensor Kit 1",
                "HC-SR04 ultrasonic sensor kit",
                "SENSOR-HCSR04-001",
                "Ultrasonic sonar distance ranging sensor module (2cm - 400cm range)",
                catSensors,
                roomB204,
                now);
        Asset hcsr04_2 = new Asset(
                "HC-SR04 Ultrasonic Distance Sensor Kit 2",
                "HC-SR04 ultrasonic sensor kit",
                "SENSOR-HCSR04-002",
                "Ultrasonic sonar distance ranging sensor module (2cm - 400cm range)",
                catSensors,
                roomB204,
                now);

        Asset lcd1 = new Asset(
                "16x2 I2C LCD Character Display Kit 1",
                "16x2 I2C LCD module kit",
                "DISPLAY-LCD1602-001",
                "HD44780 1602 LCD with PCF8574 I2C adapter backpack (address 0x27)",
                catSensors,
                roomB204,
                now);
        Asset lcd2 = new Asset(
                "16x2 I2C LCD Character Display Kit 2",
                "16x2 I2C LCD module kit",
                "DISPLAY-LCD1602-002",
                "HD44780 1602 LCD with PCF8574 I2C adapter backpack (address 0x27)",
                catSensors,
                roomB204,
                now);

        Asset robot1 = new Asset(
                "2WD Smart Robot Car Chassis + L298N Kit 1",
                "2WD robot chassis + L298N kit",
                "ROBOT-2WD-001",
                "2-wheel drive robot chassis kit with dual DC gear motors, optical encoders, and L298N driver",
                catSensors,
                roomC305,
                now);
        Asset robot2 = new Asset(
                "2WD Smart Robot Car Chassis + L298N Kit 2",
                "2WD robot chassis + L298N kit",
                "ROBOT-2WD-002",
                "2-wheel drive robot chassis kit with dual DC gear motors, optical encoders, and L298N driver",
                catSensors,
                roomC305,
                now);

        // Power & Prototyping
        Asset bb1 = new Asset(
                "Solderless Breadboard + Jumper Wire Kit 1",
                "Breadboard + jumper wire kit",
                "PROTO-BB-001",
                "830-tie point full-size breadboard with 65-piece male-to-male and male-to-female flexible jumper wires",
                catPowerProto,
                roomA101,
                now);
        Asset bb2 = new Asset(
                "Solderless Breadboard + Jumper Wire Kit 2",
                "Breadboard + jumper wire kit",
                "PROTO-BB-002",
                "830-tie point full-size breadboard with 65-piece male-to-male and male-to-female flexible jumper wires",
                catPowerProto,
                roomB204,
                now);
        Asset bb3 = new Asset(
                "Solderless Breadboard + Jumper Wire Kit 3",
                "Breadboard + jumper wire kit",
                "PROTO-BB-003",
                "830-tie point full-size breadboard with 65-piece male-to-male and male-to-female flexible jumper wires",
                catPowerProto,
                roomC305,
                now);

        Asset dp832_1 = new Asset(
                "Rigol DP832 Triple-Output Power Supply 1",
                "DP832",
                "RIGOL-DP832-001",
                "3-channel programmable DC bench power supply (30V/3A, 30V/3A, 5V/3A; 195W total)",
                catPowerProto,
                roomA101,
                now);
        Asset dp832_2 = new Asset(
                "Rigol DP832 Triple-Output Power Supply 2",
                "DP832",
                "RIGOL-DP832-002",
                "3-channel programmable DC bench power supply (30V/3A, 30V/3A, 5V/3A; 195W total)",
                catPowerProto,
                roomA101,
                now);

        Asset hakko1 = new Asset(
                "Hakko FX-888D Digital Soldering Station 1",
                "FX-888D",
                "HAKKO-FX888D-001",
                "70W ESD-safe digital temperature adjustable soldering station (50°C - 480°C)",
                catPowerProto,
                roomA101,
                now);
        Asset hakko2 = new Asset(
                "Hakko FX-888D Digital Soldering Station 2",
                "FX-888D",
                "HAKKO-FX888D-002",
                "70W ESD-safe digital temperature adjustable soldering station (50°C - 480°C)",
                catPowerProto,
                roomC305,
                now);

        assetRepository.saveAll(List.of(
                osc1, osc2, osc3, osc4, osc5, multi1, multi2, multi3, multi4, mcu1, mcu2, mcu3, mcu4, mcu5, printer1,
                printer2, printer3, printer4, arduino1, arduino2, arduino3, ds18b20_1, ds18b20_2, hcsr04_1, hcsr04_2,
                lcd1, lcd2, robot1, robot2, bb1, bb2, bb3, dp832_1, dp832_2, hakko1, hakko2));

        log.info(
                "Seeded dev catalog: 6 categories, 3 lab rooms, 36 assets (including project kits and multi-unit models)");
    }
}
