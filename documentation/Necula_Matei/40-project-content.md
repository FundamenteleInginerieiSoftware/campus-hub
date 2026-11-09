# Project assistant content: seven more project guides in Romanian and English (Necula Matei)

**Role:** Member 1, Cloud, Security & Auth (owner of the `project` module)
**Date:** 2026-11-09
**Branch / PR:** `feat/project-content` (issue #140)
**See also:** `39-project-engine.md` (the engine that loads and searches these files), spec §5.3–§5.4 in `docs/superpowers/specs/2026-11-09-project-assistant-design.md`

---

## Part 1: In simple terms

1. **What was added.** The project library had 3 guides (thermometer, PWM with the oscilloscope, multimeter basics). This PR adds the other 7 from the spec, so a student now has 10 to choose from:

   | Guide | Level | Hours | Lab equipment it books |
   |---|---|---|---|
   | Line follower robot | Intermediate | 5 | robot chassis + L298N kit, Arduino Uno, breadboard kit |
   | Parking sensor | Beginner | 2 | Arduino Uno, HC-SR04 kit, breadboard kit |
   | ESP32 weather station | Intermediate | 4 | ESP32-S3 board, DS18B20 kit, 16x2 LCD kit, breadboard kit |
   | Digital clock with 16x2 LCD | Beginner | 3 | Arduino Uno, 16x2 LCD kit, breadboard kit |
   | 3D-printed enclosure for an Arduino | Intermediate | 6 | Prusa i3 MK3S+, Arduino Uno |
   | STM32 blinky with the debugger | Beginner | 2 | STM32F4-Discovery |
   | Bench power supply motor test | Beginner | 2 | Rigol DP832, robot chassis + L298N kit, Fluke 115 |

2. **What each guide contains**, in both languages: a short summary, the lab equipment and *why* each piece is needed, what to bring from home (resistors, LEDs, batteries, cables…), 5–6 steps and 4 common mistakes.
3. **The electronics are checked**, because a wrong pin can burn a board. Examples: the ESP32 works at 3.3 V, so the guides warn that the 5 V LCD module and the HC-SR04's 5 V echo pin need a level shifter or a divider; the DS18B20 needs a 4.7 kΩ pull-up; the L298N loses about 2 V; the LCD answers at 0x27 or 0x3F.
4. **Only equipment the lab has (or will have) is listed.** Every model comes from the agreed list (spec §5.3). Models Liviu has not added to the dev catalog yet show "0 available"; that is expected and does not stop the server.
5. **Search was checked.** Typical searches such as "robot urmaritor de linie", "senzor parcare", "statie meteo", "ceas lcd" and their English versions find the right guide first, and the 3 old guides still win their own searches ("termometre", "osciloscop pwm", "multimetru"). A new test keeps it that way.

**Result:** 10 guides load at startup, search ranks them correctly in both languages, and a test fails with the exact ranking if someone's later keyword change breaks a search.

---

## Part 2: Technical details

### Files

| File | Change |
|---|---|
| `backend/src/main/resources/projects/line-follower-robot.json` | New project, `INTERMEDIATE`, 5 h, 3 components (3 units) |
| `backend/src/main/resources/projects/parking-sensor.json` | New project, `BEGINNER`, 2 h, 3 components (3 units) |
| `backend/src/main/resources/projects/esp32-weather-station.json` | New project, `INTERMEDIATE`, 4 h, 4 components (4 units) |
| `backend/src/main/resources/projects/lcd-digital-clock.json` | New project, `BEGINNER`, 3 h, 3 components (3 units) |
| `backend/src/main/resources/projects/printed-enclosure.json` | New project, `INTERMEDIATE`, 6 h, 2 components (2 units) |
| `backend/src/main/resources/projects/stm32-blinky-debugger.json` | New project, `BEGINNER`, 2 h, 1 component (1 unit) |
| `backend/src/main/resources/projects/bench-motor-test.json` | New project, `BEGINNER`, 2 h, 3 components (3 units) |
| `backend/src/test/java/com/campus/project/service/ShippedProjectsTest.java` | New: 18 search checks on the real files + agreed-models check |
| `backend/src/test/java/com/campus/project/service/ProjectLibraryTest.java` | `loadsShippedProjectsFromClasspath` now expects all 10 slugs in order |
| `documentation/Necula_Matei/{02-files-explained,README}.md` | Rows for the new files and this doc |

No Java code in `main` changed: same schema, same `ProjectLibrary` validation, same `ProjectSearch` scoring. `docs/api-contract.md` only uses `thermometer` as an example and does not list the shipped projects, so it is unchanged.

### Content rules followed

| Rule | How |
|---|---|
| Only spec §5.3 models, exact spelling | Seeded names copied from Liviu's `CatalogSeeder` (`STM32F407G-DISC1`, `ESP32-S3-DevKitC-1`, `Prusa i3 MK3S+`, `Fluke 115`); the rest from §5.3. `ShippedProjectsTest.componentsUseOnlyAgreedModels` enforces it |
| ≤ 5 units, one row per model | Max is 4 (weather station); `ProjectLibrary.validate` enforces it at startup |
| Everything else → `bringYourself` | TCRT5000 sensors, batteries, DS3231 RTC, level shifter, LEDs, buzzer, resistors, cables, filament, SD card, caliper |
| Romanian diacritics with comma below | `ș ț` everywhere; `grep "[şţŞŢ]"` on the folder finds nothing |
| Keywords for real student queries | Inflected RO forms the 5-letter prefix rule can't reach are listed explicitly (`ceas`/`ceasul`/`ceasuri` because `ceas` is shorter than 5; `linie`/`linii`; `roboți`/`robotul`) |
| No keyword stealing | No new project uses the old guides' distinctive keywords (`termometru`, `osciloscop`, `pwm`, `multimetru`, `tensiune`, `curent`), so their searches don't get ambiguous |

### Electronics facts in the guides (the review checklist)

| Guide | Facts to check |
|---|---|
| Line follower | ENA/ENB jumpers off for PWM on D5/D6; common GND battery ↔ Arduino; L298N drops ~2 V; TCRT5000 DO is HIGH over black; motors never from the Arduino 5V pin |
| Parking sensor | HC-SR04 needs 5 V; 10 µs trigger; `pulseIn(…, 30000)`; cm = µs / 58; ≥ 60 ms between pings; on an ESP32 the 5 V Echo needs a 1 kΩ / 2 kΩ divider |
| Weather station | ESP32-S3 is 3.3 V only; DS18B20 pull-up to 3V3 on GPIO4; LCD backpack pull-ups at 5 V → level shifter on SDA GPIO8 / SCL GPIO9; 2.4 GHz only, eduroam hard → phone hotspot; avoid strapping pins 0/45/46; `millis()` not `delay()` with `handleClient()` |
| LCD clock | LCD 0x27/0x3F + DS3231 0x68 share A4/A5; `rtc.adjust` only when `lostPower()`; `%02d`; no `lcd.clear()` every second; ZS-042 charging circuit vs CR2032 |
| 3D enclosure | Uno 68.6 × 53.4 mm; 2 mm walls, 0.3–0.5 mm clearance; M3 holes drawn 3.2–3.4 mm; PrusaSlicer 0.2 mm / 15–20 %; overhangs > 45°; clean PEI with IPA |
| STM32 blinky | LEDs PD12–PD15 (LD4 green = PD12), button B1 = PA0 (reads 1 when pressed); ST-LINK on the top Mini-USB CN1; code only inside `USER CODE` blocks; debug at `-O0`/`-Og` |
| Bench motor | DP832 CH1/CH2 30 V / 3 A; set the limit before connecting; CC mode when the limit is hit; TT motor 3–6 V; L298N at 8 V gives ~6 V on OUT1–OUT2; red lead out of the A jack before measuring volts |

### Search check (`ShippedProjectsTest`)

Each row must rank its slug first. Scores from the real files (`EXACT = 3`, `PREFIX = 2`, `TITLE = 1`):

| Query | First hit (score) | Runner-up |
|---|---|---|
| `robot urmaritor de linie` / `line follower robot` | `line-follower-robot` (12) | – |
| `senzor parcare` / `parking sensor` | `parking-sensor` (8) | `thermometer` (3, keyword `senzor`/`sensor`) |
| `statie meteo` / `stația meteo` / `weather station` | `esp32-weather-station` (8 / 7 / 8) | – |
| `ceas lcd` / `lcd clock` | `lcd-digital-clock` (8) | – |
| `carcasa printata 3d` / `3d printed enclosure` | `printed-enclosure` (12) | – |
| `stm32 led` / `stm32 led blink` | `stm32-blinky-debugger` (8 / 11) | – |
| `motor test sursa` / `bench power supply motor test` | `bench-motor-test` (12 / 20) | – |
| `termometre`, `osciloscop pwm`, `multimetru` | `thermometer` (2), `oscilloscope-pwm` (7), `multimeter-basics` (3) | – |

The failure message prints the whole ranking as `slug=score`, so whoever edits a file sees which keyword to change. The rule from the task: fix keywords, never the search code.

### How it was tested

| Command | Result |
|---|---|
| `./gradlew test --tests 'com.campus.project.*'` | 72 tests in the 5 `project` test classes, all green (`ShippedProjectsTest` 19, `ProjectLibraryTest` 17) |
| Break check: renamed `Arduino Uno R3` → `Arduino Uno` and removed the `parcare` keywords in `parking-sensor.json` | 2 failures: `componentsUseOnlyAgreedModels` and `"senzor parcare"` (ranking `[thermometer=3, parking-sensor=2]`); file restored |
| `SPRING_DATASOURCE_URL=…/campus_p3 ./gradlew --no-daemon spotlessApply build` | BUILD SUCCESSFUL, 961 tests, 0 failures (incl. ArchUnit, `DemoFlowIT`, `OpenApiDocsIT`) |
| Real app (`bootRun`, dev profile, port 8093) | `GET /api/v1/projects` → 10 items; the 10 RO queries above (typed with diacritics) each return their slug first, `ambiguous: false`; `GET /projects/stm32-blinky-debugger` → `STM32F407G-DISC1` 3 available, `reservable: true`; `esp32-weather-station` → ESP32 1 available (the 2nd unit is under maintenance in the seed), the 3 unseeded kits 0, `reservable: false`. No startup error |

### Problems hit and fixes

| Problem | Fix |
|---|---|
| `ceas` is 4 letters, so the prefix rule (≥ 5) never links it to `ceasul`/`ceasuri` | Listed all three forms as keywords |
| First `pulseIn` mistake text claimed a 1 s block when no echo comes; the HC-SR04 drops Echo after ~38 ms anyway | Replaced with the real beginner bug: treating the timeout result `0` as "0 cm" so the buzzer never stops |
| The 5 V LCD backpack on a 3.3 V ESP32 is an easy way to damage the board | Level shifter in `bringYourself`, its own step and its own mistake |

### Waiting on teammates

- **Liviu:** these §5.3 models are not in `CatalogSeeder` yet, so they show `available = 0`: `Arduino Uno R3`, `DS18B20 temperature sensor kit`, `HC-SR04 ultrasonic sensor kit`, `16x2 I2C LCD module kit`, `2WD robot chassis + L298N kit`, `Breadboard + jumper wire kit`, `DP832`, `FX-888D` (asked on the PR).
- **Whole team:** review the content (electronics facts table above, Romanian wording).
