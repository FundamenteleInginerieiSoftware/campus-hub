package com.campus.project.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.campus.catalog.api.AssetStatus;
import com.campus.catalog.api.AssetSummary;
import com.campus.catalog.api.CatalogApi;
import com.campus.common.error.BadRequestException;
import com.campus.common.error.NotFoundException;
import com.campus.project.domain.Difficulty;
import com.campus.project.domain.Keywords;
import com.campus.project.domain.Lang;
import com.campus.project.domain.LocalizedText;
import com.campus.project.domain.Project;
import com.campus.project.domain.ProjectComponent;
import com.campus.project.domain.ProjectStep;
import com.campus.project.web.ComponentView;
import com.campus.project.web.ProjectDetailsResponse;
import com.campus.project.web.ProjectSearchResponse;
import com.campus.project.web.StepView;
import com.campus.reservation.api.ReservationApi;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// Plain Mockito test with a real ProjectSearch so search + localization + availability are tested in milliseconds.
@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 16, 10, 0);
    private static final LocalDateTime END = START.plusHours(2);

    @Mock
    private ProjectLibrary projectLibrary;

    @Mock
    private CatalogApi catalogApi;

    @Mock
    private ReservationApi reservationApi;

    private ProjectService projectService;

    @BeforeEach
    void setUp() {
        projectService = new ProjectService(projectLibrary, new ProjectSearch(), catalogApi, reservationApi);
    }

    @Test
    void searchReturnsChosenLanguage() {
        when(projectLibrary.all()).thenReturn(List.of(thermometerProject()));

        ProjectSearchResponse roResponse = projectService.search("termometru", Lang.RO);
        ProjectSearchResponse enResponse = projectService.search("termometru", Lang.EN);

        assertThat(roResponse.items())
                .as("search with Lang.RO must return Romanian title and summary; see ProjectService.search")
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.title())
                            .as("RO title must be selected; see ProjectService.search")
                            .isEqualTo("Termometru digital");
                    assertThat(item.summary())
                            .as("RO summary must be selected; see ProjectService.search")
                            .isEqualTo("Masoara temperatura cu Arduino.");
                    assertThat(item.componentCount())
                            .as("componentCount must match component list size; see ProjectService.search")
                            .isEqualTo(1);
                });
        assertThat(enResponse.items())
                .as("search with Lang.EN must return English title and summary; see ProjectService.search")
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.title())
                            .as("EN title must be selected; see ProjectService.search")
                            .isEqualTo("Digital thermometer");
                    assertThat(item.summary())
                            .as("EN summary must be selected; see ProjectService.search")
                            .isEqualTo("Measure temperature with Arduino.");
                });
    }

    @Test
    void searchPassesAmbiguityThrough() {
        Project first = projectWithKeywords(
                "sensor-pro",
                new LocalizedText("Senzor rapid", "Fast sensor"),
                new Keywords(List.of("senzor"), List.of("sensor")));
        Project second = projectWithKeywords(
                "parking-kit",
                new LocalizedText("Kit parcare", "Parking kit"),
                new Keywords(List.of("senzor"), List.of("sensor")));
        when(projectLibrary.all()).thenReturn(List.of(first, second));

        ProjectSearchResponse response = projectService.search("senzor", Lang.EN);

        assertThat(response.ambiguous())
                .as("top two hits within 1 point (4 vs 3) must set ambiguous=true; see ProjectService.search")
                .isTrue();
        assertThat(response.items())
                .as("ambiguous search must return both matching items in rank order; see ProjectService.search")
                .extracting(item -> item.slug())
                .containsExactly("sensor-pro", "parking-kit");
    }

    @Test
    void detailsWithoutSlotCountsAvailableUnits() {
        when(projectLibrary.find("thermometer")).thenReturn(Optional.of(thermometerProject()));
        when(catalogApi.findAvailableByModel("Arduino Uno R3", null))
                .thenReturn(
                        List.of(asset(1L, "Arduino Uno R3"), asset(2L, "Arduino Uno R3"), asset(3L, "Arduino Uno R3")));

        ProjectDetailsResponse details = projectService.details("thermometer", Lang.EN, null, null);

        assertThat(details.slotChecked())
                .as("slotChecked must be false when start/end are omitted; see ProjectService.details")
                .isFalse();
        assertThat(details.components())
                .as("available without slot must equal AVAILABLE catalog count; see ProjectService.details")
                .containsExactly(new ComponentView("Arduino Uno R3", 1, "Reads the sensor", 3));
        verifyNoInteractions(reservationApi);
    }

    @Test
    void countsOnlyAvailableUnits() {
        // Liviu's CatalogApi.findAvailableByModel already filters out UNDER_MAINTENANCE and RETIRED assets;
        // ProjectService relies on that contract and counts only the returned AVAILABLE summaries.
        when(projectLibrary.find("thermometer")).thenReturn(Optional.of(thermometerProject()));
        when(catalogApi.findAvailableByModel("Arduino Uno R3", null))
                .thenReturn(List.of(asset(1L, "Arduino Uno R3"), asset(2L, "Arduino Uno R3")));

        ProjectDetailsResponse details = projectService.details("thermometer", Lang.EN, null, null);

        assertThat(details.components())
                .singleElement()
                .extracting(ComponentView::available)
                .as("only AVAILABLE units returned by CatalogApi must be counted; see ProjectService.details")
                .isEqualTo(2);
    }

    @Test
    void detailsWithSlotCountsFreeUnits() {
        when(projectLibrary.find("thermometer")).thenReturn(Optional.of(thermometerProject()));
        when(catalogApi.findAvailableByModel("Arduino Uno R3", null))
                .thenReturn(
                        List.of(asset(1L, "Arduino Uno R3"), asset(2L, "Arduino Uno R3"), asset(3L, "Arduino Uno R3")));
        when(reservationApi.isAssetFree(1L, START, END, null)).thenReturn(true);
        when(reservationApi.isAssetFree(2L, START, END, null)).thenReturn(false);
        when(reservationApi.isAssetFree(3L, START, END, null)).thenReturn(true);

        ProjectDetailsResponse details = projectService.details("thermometer", Lang.EN, START, END);

        assertThat(details.slotChecked())
                .as("slotChecked must be true when start and end are given; see ProjectService.details")
                .isTrue();
        assertThat(details.components())
                .as("only units free in the requested slot must be counted; see ProjectService.details")
                .containsExactly(new ComponentView("Arduino Uno R3", 1, "Reads the sensor", 2));
    }

    @Test
    void missingModelIsZeroAndNotReservable() {
        when(projectLibrary.find("thermometer")).thenReturn(Optional.of(thermometerProject()));
        when(catalogApi.findAvailableByModel("Arduino Uno R3", null)).thenReturn(List.of());

        ProjectDetailsResponse details = projectService.details("thermometer", Lang.EN, null, null);

        assertThat(details.components())
                .singleElement()
                .extracting(ComponentView::available)
                .as("unseeded model must report 0 available units; see ProjectService.details")
                .isEqualTo(0);
        assertThat(details.reservable())
                .as(
                        "project with 0 available units for a required component must not be reservable; see ProjectService.details")
                .isFalse();
    }

    @Test
    void reservableWhenEveryComponentHasEnough() {
        Project twoComponentProject = projectWithComponents(List.of(
                new ProjectComponent("Arduino Uno R3", 1, new LocalizedText("Citeste senzorul", "Reads the sensor")),
                new ProjectComponent("Fluke 115", 2, new LocalizedText("Verifica tensiunea", "Checks the voltage"))));
        when(projectLibrary.find("thermometer")).thenReturn(Optional.of(twoComponentProject));
        when(catalogApi.findAvailableByModel("Arduino Uno R3", null)).thenReturn(List.of(asset(1L, "Arduino Uno R3")));
        when(catalogApi.findAvailableByModel("Fluke 115", null))
                .thenReturn(List.of(asset(10L, "Fluke 115"), asset(11L, "Fluke 115")));

        ProjectDetailsResponse details = projectService.details("thermometer", Lang.RO, null, null);

        assertThat(details.reservable())
                .as(
                        "reservable must be true when every component has available >= quantity; see ProjectService.details")
                .isTrue();
        assertThat(details.bringYourself())
                .as("bringYourself must be localized into the requested language; see ProjectService.details")
                .containsExactly("Cablu USB-B");
        assertThat(details.steps())
                .as("steps must be localized into the requested language; see ProjectService.details")
                .containsExactly(new StepView("Pasul 1", "Conecteaza cablul"));
        assertThat(details.mistakes())
                .as("mistakes must be localized into the requested language; see ProjectService.details")
                .containsExactly("Fara rezistor pull-up");
    }

    @Test
    void notReservableWhenQuantityExceedsAvailable() {
        Project twoUnitProject = projectWithComponents(List.of(
                new ProjectComponent("Fluke 115", 2, new LocalizedText("Verifica tensiunea", "Checks the voltage"))));
        when(projectLibrary.find("thermometer")).thenReturn(Optional.of(twoUnitProject));
        when(catalogApi.findAvailableByModel("Fluke 115", null)).thenReturn(List.of(asset(10L, "Fluke 115")));

        ProjectDetailsResponse details = projectService.details("thermometer", Lang.EN, null, null);

        assertThat(details.components())
                .as("component view must show available=1 and quantity=2; see ProjectService.details")
                .containsExactly(new ComponentView("Fluke 115", 2, "Checks the voltage", 1));
        assertThat(details.reservable())
                .as("reservable must be false when available (1) < quantity (2); see ProjectService.details")
                .isFalse();
    }

    @Test
    void unknownSlugIs404() {
        when(projectLibrary.find("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> projectService.details("nope", Lang.EN, null, null))
                .as("unknown project slug must throw NotFoundException(PROJECT_NOT_FOUND); see ProjectService.details")
                .isInstanceOf(NotFoundException.class)
                .extracting("code")
                .as("error code must be PROJECT_NOT_FOUND; see ProjectService.details")
                .isEqualTo("PROJECT_NOT_FOUND");
    }

    @Test
    void rejectsHalfSlot() {
        when(projectLibrary.find("thermometer")).thenReturn(Optional.of(thermometerProject()));

        assertThatThrownBy(() -> projectService.details("thermometer", Lang.EN, START, null))
                .as("providing only start without end must fail with VALIDATION_FAILED; see ProjectService.details")
                .isInstanceOf(BadRequestException.class)
                .extracting("code")
                .as("error code for missing end must be VALIDATION_FAILED; see ProjectService.details")
                .isEqualTo("VALIDATION_FAILED");

        assertThatThrownBy(() -> projectService.details("thermometer", Lang.EN, null, END))
                .as("providing only end without start must fail with VALIDATION_FAILED; see ProjectService.details")
                .isInstanceOf(BadRequestException.class)
                .extracting("code")
                .as("error code for missing start must be VALIDATION_FAILED; see ProjectService.details")
                .isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void rejectsEndNotAfterStart() {
        when(projectLibrary.find("thermometer")).thenReturn(Optional.of(thermometerProject()));

        assertThatThrownBy(() -> projectService.details("thermometer", Lang.EN, START, START))
                .as("end equal to start must fail with VALIDATION_FAILED; see ProjectService.details")
                .isInstanceOf(BadRequestException.class)
                .extracting("code")
                .as("error code for zero-length slot must be VALIDATION_FAILED; see ProjectService.details")
                .isEqualTo("VALIDATION_FAILED");

        assertThatThrownBy(() -> projectService.details("thermometer", Lang.EN, START, START.minusMinutes(30)))
                .as("end before start must fail with VALIDATION_FAILED; see ProjectService.details")
                .isInstanceOf(BadRequestException.class)
                .extracting("code")
                .as("error code for inverted slot must be VALIDATION_FAILED; see ProjectService.details")
                .isEqualTo("VALIDATION_FAILED");
    }

    private static AssetSummary asset(Long id, String model) {
        return new AssetSummary(id, model + " #" + id, model, 1L, 1L, AssetStatus.AVAILABLE);
    }

    private static Project thermometerProject() {
        return projectWithComponents(List.of(
                new ProjectComponent("Arduino Uno R3", 1, new LocalizedText("Citeste senzorul", "Reads the sensor"))));
    }

    private static Project projectWithComponents(List<ProjectComponent> components) {
        return new Project(
                "thermometer",
                Difficulty.BEGINNER,
                3,
                new LocalizedText("Termometru digital", "Digital thermometer"),
                new LocalizedText("Masoara temperatura cu Arduino.", "Measure temperature with Arduino."),
                new Keywords(List.of("termometru", "temperatura"), List.of("thermometer", "temperature")),
                components,
                List.of(new LocalizedText("Cablu USB-B", "USB-B cable")),
                List.of(new ProjectStep(
                        new LocalizedText("Pasul 1", "Step 1"),
                        new LocalizedText("Conecteaza cablul", "Connect the cable"))),
                List.of(new LocalizedText("Fara rezistor pull-up", "No pull-up resistor")));
    }

    private static Project projectWithKeywords(String slug, LocalizedText title, Keywords keywords) {
        return new Project(
                slug,
                Difficulty.BEGINNER,
                2,
                title,
                new LocalizedText("Descriere", "Summary"),
                keywords,
                List.of(new ProjectComponent("Fluke 115", 1, new LocalizedText("De ce", "Why"))),
                List.of(),
                List.of(new ProjectStep(new LocalizedText("P1", "S1"), new LocalizedText("T1", "T1"))),
                List.of());
    }
}
