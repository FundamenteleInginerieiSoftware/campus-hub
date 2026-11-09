package com.campus.project.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campus.common.error.BadRequestException;
import com.campus.project.domain.Difficulty;
import com.campus.project.domain.Keywords;
import com.campus.project.domain.Lang;
import com.campus.project.domain.LocalizedText;
import com.campus.project.domain.Project;
import com.campus.project.domain.ProjectComponent;
import com.campus.project.domain.ProjectStep;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ProjectLibraryTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void loadsShippedProjectsFromClasspath() {
        ProjectLibrary library = new ProjectLibrary(MAPPER);

        assertThat(library.all())
                .as("shipped classpath*:projects/*.json must load in ascending slug order; see ProjectLibrary.<init>")
                .extracting(Project::slug)
                .containsExactly(
                        "bench-motor-test",
                        "esp32-weather-station",
                        "lcd-digital-clock",
                        "line-follower-robot",
                        "multimeter-basics",
                        "oscilloscope-pwm",
                        "parking-sensor",
                        "printed-enclosure",
                        "stm32-blinky-debugger",
                        "thermometer");
    }

    @Test
    void findsBySlug() {
        ProjectLibrary library = new ProjectLibrary(MAPPER);

        Project thermometer = library.find("thermometer").orElseThrow();
        assertThat(thermometer.difficulty())
                .as("thermometer project is BEGINNER difficulty; see ProjectLibrary.find")
                .isEqualTo(Difficulty.BEGINNER);
        assertThat(thermometer.estimatedHours())
                .as("thermometer project takes 3 hours; see ProjectLibrary.find")
                .isEqualTo(3);
        assertThat(thermometer.steps())
                .as("each shipped project has at least 5 steps; see ProjectLibrary.find")
                .hasSizeGreaterThanOrEqualTo(5);
        assertThat(thermometer.mistakes())
                .as("each shipped project has at least 3 common mistakes; see ProjectLibrary.find")
                .hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    void unknownSlugIsEmpty() {
        ProjectLibrary library = new ProjectLibrary(MAPPER);

        assertThat(library.find("does-not-exist"))
                .as("unknown slug returns Optional.empty(); see ProjectLibrary.find")
                .isEmpty();
        assertThat(library.find(null))
                .as("null slug returns Optional.empty(); see ProjectLibrary.find")
                .isEmpty();
    }

    @Test
    void rejectsUnknownField() {
        assertThatThrownBy(() -> new ProjectLibrary(MAPPER, "classpath:projects-invalid/unknown-field/*.json"))
                .as("unknown JSON property like 'titel' must fail startup naming file and field; see ProjectLibrary")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bad.json")
                .hasMessageContaining("titel");
    }

    @Test
    void rejectsEmptyTranslation() {
        assertThatThrownBy(() -> new ProjectLibrary(MAPPER, "classpath:projects-invalid/empty-translation/*.json"))
                .as("blank ro or en translation must fail startup naming file and field; see ProjectLibrary.validate")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bad.json")
                .hasMessageContaining("title.en");
    }

    @Test
    void rejectsDuplicateSlug() {
        assertThatThrownBy(() -> new ProjectLibrary(MAPPER, "classpath:projects-invalid/duplicate-slug/*.json"))
                .as("two project files sharing a slug must fail startup; see ProjectLibrary.validate")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("second.json")
                .hasMessageContaining("slug");
    }

    @Test
    void rejectsBadSlug() {
        assertThatThrownBy(() -> new ProjectLibrary(MAPPER, "classpath:projects-invalid/bad-slug/*.json"))
                .as("slug outside [a-z0-9-]+ must fail startup; see ProjectLibrary.validate")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bad.json")
                .hasMessageContaining("slug");
    }

    @Test
    void rejectsZeroQuantity() {
        assertThatThrownBy(() -> new ProjectLibrary(MAPPER, "classpath:projects-invalid/zero-quantity/*.json"))
                .as("component quantity < 1 must fail startup; see ProjectLibrary.validateComponents")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bad.json")
                .hasMessageContaining("components[0].quantity");
    }

    @Test
    void rejectsDuplicateModel() {
        assertThatThrownBy(() -> new ProjectLibrary(MAPPER, "classpath:projects-invalid/duplicate-model/*.json"))
                .as("duplicate model in the same project must fail startup; see ProjectLibrary.validateComponents")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bad.json")
                .hasMessageContaining("components[1].model");
    }

    @Test
    void rejectsMoreThanFiveUnits() {
        assertThatThrownBy(() -> new ProjectLibrary(MAPPER, "classpath:projects-invalid/more-than-five-units/*.json"))
                .as("total component quantity > 5 exceeds bundle limit; see ProjectLibrary.validateComponents")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bad.json")
                .hasMessageContaining("components")
                .hasMessageContaining("quantity");

        LocalizedText text = new LocalizedText("Ro", "En");
        Project exactlyFive = new Project(
                "five-units",
                Difficulty.BEGINNER,
                2,
                text,
                text,
                new Keywords(List.of("ro"), List.of("en")),
                List.of(new ProjectComponent("Model A", 3, text), new ProjectComponent("Model B", 2, text)),
                List.of(),
                List.of(new ProjectStep(text, text)),
                List.of(text));
        assertThatCode(() -> ProjectLibrary.validate(exactlyFive, "five.json", new HashSet<>()))
                .as(
                        "exactly 5 total component units is at the bundle limit and must pass; see ProjectLibrary.validateComponents")
                .doesNotThrowAnyException();

        Project overflowUnits = new Project(
                "overflow-units",
                Difficulty.BEGINNER,
                2,
                text,
                text,
                new Keywords(List.of("ro"), List.of("en")),
                List.of(
                        new ProjectComponent("Model A", Integer.MAX_VALUE, text),
                        new ProjectComponent("Model B", 1, text)),
                List.of(),
                List.of(new ProjectStep(text, text)),
                List.of(text));
        assertThatThrownBy(() -> ProjectLibrary.validate(overflowUnits, "overflow.json", new HashSet<>()))
                .as(
                        "overflowing 32-bit int quantities must still fail the 5-unit limit; see ProjectLibrary.validateComponents")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("overflow.json")
                .hasMessageContaining("components");
    }

    @Test
    void validatesRequiredFieldsAndNestedTranslations() {
        LocalizedText ok = new LocalizedText("Ro", "En");
        Keywords kw = new Keywords(List.of("ro"), List.of("en"));
        List<ProjectComponent> comps = List.of(new ProjectComponent("M1", 1, ok));
        List<ProjectStep> steps = List.of(new ProjectStep(ok, ok));
        List<LocalizedText> mistakes = List.of(ok);

        assertThatThrownBy(() -> ProjectLibrary.validate(
                        new Project("bad", null, 1, ok, ok, kw, comps, List.of(), steps, mistakes),
                        "f.json",
                        new HashSet<>()))
                .as("null difficulty must fail validation; see ProjectLibrary.validate")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("difficulty");

        assertThatThrownBy(() -> ProjectLibrary.validate(
                        new Project("bad", Difficulty.BEGINNER, 0, ok, ok, kw, comps, List.of(), steps, mistakes),
                        "f.json",
                        new HashSet<>()))
                .as("estimatedHours < 1 must fail validation; see ProjectLibrary.validate")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("estimatedHours");

        assertThatThrownBy(() -> ProjectLibrary.validate(
                        new Project(
                                "bad",
                                Difficulty.BEGINNER,
                                1,
                                ok,
                                new LocalizedText("", "En"),
                                kw,
                                comps,
                                List.of(),
                                steps,
                                mistakes),
                        "f.json",
                        new HashSet<>()))
                .as("blank summary.ro must fail validation; see ProjectLibrary.validate")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("summary.ro");

        assertThatThrownBy(() -> ProjectLibrary.validate(
                        new Project(
                                "bad",
                                Difficulty.BEGINNER,
                                1,
                                ok,
                                ok,
                                new Keywords(List.of(), List.of("en")),
                                comps,
                                List.of(),
                                steps,
                                mistakes),
                        "f.json",
                        new HashSet<>()))
                .as("empty keywords.ro must fail validation; see ProjectLibrary.requireKeywords")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("keywords.ro");

        assertThatThrownBy(() -> ProjectLibrary.validate(
                        new Project(
                                "bad",
                                Difficulty.BEGINNER,
                                1,
                                ok,
                                ok,
                                new Keywords(List.of(" "), List.of("en")),
                                comps,
                                List.of(),
                                steps,
                                mistakes),
                        "f.json",
                        new HashSet<>()))
                .as("blank keyword entry must fail validation; see ProjectLibrary.requireKeywords")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("keywords.ro[0]");

        assertThatThrownBy(() -> ProjectLibrary.validate(
                        new Project(
                                "bad",
                                Difficulty.BEGINNER,
                                1,
                                ok,
                                ok,
                                kw,
                                List.of(new ProjectComponent("M1", 1, new LocalizedText("Ro", ""))),
                                List.of(),
                                steps,
                                mistakes),
                        "f.json",
                        new HashSet<>()))
                .as("blank component why.en must fail validation; see ProjectLibrary.validateComponents")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("components[0].why.en");

        assertThatThrownBy(() -> ProjectLibrary.validate(
                        new Project("bad", Difficulty.BEGINNER, 1, ok, ok, kw, comps, null, steps, mistakes),
                        "f.json",
                        new HashSet<>()))
                .as("null bringYourself list must fail validation; see ProjectLibrary.validateLocalizedList")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bringYourself");

        assertThatThrownBy(() -> ProjectLibrary.validate(
                        new Project(
                                "bad",
                                Difficulty.BEGINNER,
                                1,
                                ok,
                                ok,
                                kw,
                                comps,
                                List.of(new LocalizedText("", "En")),
                                steps,
                                mistakes),
                        "f.json",
                        new HashSet<>()))
                .as("blank bringYourself[0].ro must fail validation; see ProjectLibrary.validateLocalizedList")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bringYourself[0].ro");

        assertThatThrownBy(() -> ProjectLibrary.validate(
                        new Project(
                                "bad",
                                Difficulty.BEGINNER,
                                1,
                                ok,
                                ok,
                                kw,
                                comps,
                                List.of(),
                                List.of(new ProjectStep(new LocalizedText("", "En"), ok)),
                                mistakes),
                        "f.json",
                        new HashSet<>()))
                .as("blank steps[0].title.ro must fail validation; see ProjectLibrary.validateSteps")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("steps[0].title.ro");

        assertThatThrownBy(() -> ProjectLibrary.validate(
                        new Project(
                                "bad",
                                Difficulty.BEGINNER,
                                1,
                                ok,
                                ok,
                                kw,
                                comps,
                                List.of(),
                                List.of(new ProjectStep(ok, new LocalizedText("Ro", ""))),
                                mistakes),
                        "f.json",
                        new HashSet<>()))
                .as("blank steps[0].text.en must fail validation; see ProjectLibrary.validateSteps")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("steps[0].text.en");

        assertThatThrownBy(() -> ProjectLibrary.validate(
                        new Project("bad", Difficulty.BEGINNER, 1, ok, ok, kw, comps, List.of(), steps, List.of()),
                        "f.json",
                        new HashSet<>()))
                .as("empty mistakes list must fail validation; see ProjectLibrary.validateLocalizedList")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mistakes");
    }

    @Test
    void rejectsNoSteps() {
        assertThatThrownBy(() -> new ProjectLibrary(MAPPER, "classpath:projects-invalid/no-steps/*.json"))
                .as("project without steps must fail startup; see ProjectLibrary.validateSteps")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bad.json")
                .hasMessageContaining("steps");
    }

    @Test
    void rejectsNoKeywordsInOneLanguage() {
        assertThatThrownBy(() -> new ProjectLibrary(MAPPER, "classpath:projects-invalid/no-keywords/*.json"))
                .as("empty keywords in either language must fail startup; see ProjectLibrary.requireKeywords")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bad.json")
                .hasMessageContaining("keywords.en");
    }

    @Test
    void rejectsInvalidJson() {
        assertThatThrownBy(() -> new ProjectLibrary(MAPPER, "classpath:projects-invalid/invalid-json/*.json"))
                .as("malformed JSON must fail startup naming the broken file; see ProjectLibrary.readProject")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("broken.json");
    }

    @Test
    void rejectsMissingFiles() {
        assertThatThrownBy(() -> new ProjectLibrary(MAPPER, "classpath*:projects-invalid/no-such-folder/*.json"))
                .as("zero matching project files must fail startup; see ProjectLibrary.<init>")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No project files found");
    }

    @Test
    void parsesLangCaseInsensitivelyAndDefaultsToEnglish() {
        assertThat(Lang.parse(null))
                .as("null lang defaults to EN; see Lang.parse")
                .isEqualTo(Lang.EN);
        assertThat(Lang.parse("  "))
                .as("blank lang defaults to EN; see Lang.parse")
                .isEqualTo(Lang.EN);
        assertThat(Lang.parse("RO"))
                .as("uppercase RO parses to Lang.RO; see Lang.parse")
                .isEqualTo(Lang.RO);
        assertThat(Lang.parse("en"))
                .as("lowercase en parses to Lang.EN; see Lang.parse")
                .isEqualTo(Lang.EN);
    }

    @Test
    void rejectsUnsupportedLang() {
        assertThatThrownBy(() -> Lang.parse("fr"))
                .as("unsupported lang throws BadRequestException VALIDATION_FAILED; see Lang.parse")
                .isInstanceOf(BadRequestException.class)
                .extracting("code")
                .isEqualTo("VALIDATION_FAILED");
    }
}
