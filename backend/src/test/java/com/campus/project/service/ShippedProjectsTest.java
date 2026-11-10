package com.campus.project.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.campus.project.domain.Project;
import com.campus.project.domain.ProjectComponent;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

// Checks the real project files in src/main/resources/projects, not test fixtures: when someone edits
// the content (keywords, models), these tests say which query or which model broke.
class ShippedProjectsTest {

    // Spec §5.3: the models Liviu seeds in CatalogSeeder (or is asked to seed). A component with any other
    // model would always show "0 available" and the project could never be booked.
    private static final Set<String> AGREED_MODELS = Set.of(
            "Arduino Uno R3",
            "DS18B20 temperature sensor kit",
            "HC-SR04 ultrasonic sensor kit",
            "16x2 I2C LCD module kit",
            "2WD robot chassis + L298N kit",
            "Breadboard + jumper wire kit",
            "DP832",
            "FX-888D",
            "DS1054Z",
            "DSOX1204G",
            "Fluke 115",
            "DM3058E",
            "STM32F407G-DISC1",
            "ESP32-S3-DevKitC-1",
            "Prusa i3 MK3S+");

    private static final ProjectLibrary LIBRARY =
            new ProjectLibrary(JsonMapper.builder().build());
    private final ProjectSearch search = new ProjectSearch();

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource({
        "robot urmaritor de linie, line-follower-robot",
        "line follower robot, line-follower-robot",
        "senzor parcare, parking-sensor",
        "parking sensor, parking-sensor",
        "statie meteo, esp32-weather-station",
        "stația meteo, esp32-weather-station",
        "weather station, esp32-weather-station",
        "ceas lcd, lcd-digital-clock",
        "lcd clock, lcd-digital-clock",
        "carcasa printata 3d, printed-enclosure",
        "3d printed enclosure, printed-enclosure",
        "stm32 led, stm32-blinky-debugger",
        "stm32 led blink, stm32-blinky-debugger",
        "motor test sursa, bench-motor-test",
        "bench power supply motor test, bench-motor-test",
        "termometre, thermometer",
        "osciloscop pwm, oscilloscope-pwm",
        "multimetru, multimeter-basics"
    })
    void typicalQueryFindsItsProjectFirst(String query, String expectedSlug) {
        List<String> ranking = search.search(query, LIBRARY.all()).hits().stream()
                .map(hit -> hit.project().slug() + "=" + hit.score())
                .toList();

        assertThat(ranking)
                .as("query '%s' must find %s; fix the keywords in projects/%s.json", query, expectedSlug, expectedSlug)
                .isNotEmpty();
        assertThat(ranking.getFirst())
                .as(
                        "query '%s' must rank %s first (ranking: %s); fix the keywords in projects/*.json, not ProjectSearch",
                        query, expectedSlug, ranking)
                .startsWith(expectedSlug + "=");
    }

    // Bug fixed (audit, 10 Nov): "multiplexer" found multimeter-basics only because both start with "multi".
    @Test
    void unrelatedWordWithSameStartFindsNothing() {
        List<String> ranking = search.search("multiplexer", LIBRARY.all()).hits().stream()
                .map(hit -> hit.project().slug() + "=" + hit.score())
                .toList();

        assertThat(ranking)
                .as(
                        "'multiplexer' is no keyword of any project and shares only 'multi' with 'multimeter': no hits; see ProjectSearch.sameStem")
                .isEmpty();
    }

    // Bug fixed (10 Nov): "proiect" found printed-enclosure through its keyword "proiectare".
    @Test
    void theWordProjectIsFiller() {
        List<String> slugs = search.search("vreau un proiect cu termometru", LIBRARY.all()).hits().stream()
                .map(hit -> hit.project().slug())
                .toList();

        assertThat(slugs)
                .as(
                        "'proiect' is a stop word, so only 'termometru' counts: thermometer only; see ProjectSearch.STOP_WORDS")
                .containsExactly("thermometer");
    }

    @Test
    void componentsUseOnlyAgreedModels() {
        List<String> models = LIBRARY.all().stream()
                .map(Project::components)
                .flatMap(List::stream)
                .map(ProjectComponent::model)
                .distinct()
                .toList();

        assertThat(AGREED_MODELS)
                .as(
                        "every component model must be one of spec §5.3 (exact CatalogSeeder spelling); see projects/*.json")
                .containsAll(models);
    }
}
