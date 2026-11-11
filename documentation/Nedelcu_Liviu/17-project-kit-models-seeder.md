# 17 — Seeding Project Hardware Kits for the Projects Assistant

**Issue / Spec:** [#140](https://github.com/FundamenteleInginerieiSoftware/campus-hub/issues/140)  
**Date:** 10 November 2026  
**Author:** Liviu Nedelcu (`@NliviuN`)

---

## Part 1: In Simple Terms

The Campus Hub now features a **Projects Assistant** where students can browse practical hardware project guides (such as building a digital thermometer, parking sensor, or line-follower robot) and reserve all required components in a single click using "Reserve all".

For this feature to function seamlessly, the physical equipment referenced by each project template must exist in our equipment catalog with exact model identifiers and sufficient units available for multi-component reservations.

In this update, the development catalog seeder (`CatalogSeeder`) has been extended to provision 18 additional hardware units across two new laboratory equipment categories:
- **Sensors & Modules:** Digital temperature probes, ultrasonic sonar sensors, I2C LCD character displays, and dual-motor robot car chassis kits.
- **Power & Prototyping:** Programmable triple-output DC bench power supplies, digital soldering stations, and full-size solderless breadboard with jumper wire bundles.

All units are provisioned as operational (`AVAILABLE`) across Embedded Systems Lab (B204), Electronics Lab (A101), and Prototyping Lab (C305), allowing students to immediately test and execute atomic bundle reservations.

---

## Part 2: Technical Details

### Seeded Hardware Catalog Additions

Spring Boot seeds the following categories, models, and serial numbers during initial empty-database startup (`dev` profile):

#### 1. New Categories
- **`Sensors & Modules`**: Sensor breakout boards, displays, robotics modules and actuator kits.
- **`Power & Prototyping`**: Benchtop DC power supplies, soldering stations and prototyping accessories.

#### 2. Provisioned Equipment Units

| Model (exact project key) | Category | Units Count | Room | Serial Numbers | Status |
|---|---|---|---|---|---|
| `Arduino Uno R3` | Microcontrollers | 3 | B204 | `ARDUINO-UNO-001` .. `003` | `AVAILABLE` |
| `DS18B20 temperature sensor kit` | Sensors & Modules | 2 | B204 | `SENSOR-DS18B20-001` .. `002` | `AVAILABLE` |
| `HC-SR04 ultrasonic sensor kit` | Sensors & Modules | 2 | B204 | `SENSOR-HCSR04-001` .. `002` | `AVAILABLE` |
| `16x2 I2C LCD module kit` | Sensors & Modules | 2 | B204 | `DISPLAY-LCD1602-001` .. `002` | `AVAILABLE` |
| `2WD robot chassis + L298N kit` | Sensors & Modules | 2 | C305 | `ROBOT-2WD-001` .. `002` | `AVAILABLE` |
| `Breadboard + jumper wire kit` | Power & Prototyping | 3 | A101, B204, C305 | `PROTO-BB-001` .. `003` | `AVAILABLE` |
| `DP832` | Power & Prototyping | 2 | A101 | `RIGOL-DP832-001` .. `002` | `AVAILABLE` |
| `FX-888D` | Power & Prototyping | 2 | A101, C305 | `HAKKO-FX888D-001` .. `002` | `AVAILABLE` |

Total catalog totals after seeding:
- **Categories:** 6 (Oscilloscopes, Multimeters, Microcontrollers, 3D Printers, Sensors & Modules, Power & Prototyping)
- **Lab Rooms:** 3 (A101, B204, C305)
- **Assets:** 36 units total (35 `AVAILABLE`, 0 `UNDER_MAINTENANCE`, 1 `RETIRED`)

### Test Verification

Updated `CatalogSeederTest.java` to verify:
1. Category repository saves exactly 6 categories.
2. Lab room repository saves 3 rooms.
3. Asset repository saves all 36 units in a single batch.
4. Each of the 8 required project model keys exists in the saved assets.
5. Status distribution invariants: 35 available, 0 under maintenance, 1 retired.
