package com.campus.project.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.description;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.campus.catalog.api.AssetStatus;
import com.campus.catalog.api.AssetSummary;
import com.campus.catalog.api.CatalogApi;
import com.campus.common.error.BadRequestException;
import com.campus.common.error.ConflictException;
import com.campus.common.error.NotFoundException;
import com.campus.common.security.CurrentUser;
import com.campus.project.domain.Difficulty;
import com.campus.project.domain.Keywords;
import com.campus.project.domain.LocalizedText;
import com.campus.project.domain.Project;
import com.campus.project.domain.ProjectComponent;
import com.campus.project.domain.ProjectStep;
import com.campus.project.web.ReserveProjectResponse;
import com.campus.project.web.ReservedItem;
import com.campus.reservation.api.ReservationApi;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// Plain Mockito test: the catalog, reservation facade and CurrentUser are mocks, so each rule of
// "Reserve all" is checked without a database.
@ExtendWith(MockitoExtension.class)
class ProjectReservationServiceTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 16, 10, 0);
    private static final LocalDateTime END = START.plusHours(2);
    private static final Long STUDENT_ID = 7L;
    private static final UUID BUNDLE_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Mock
    private ProjectLibrary projectLibrary;

    @Mock
    private CatalogApi catalogApi;

    @Mock
    private ReservationApi reservationApi;

    @Mock
    private CurrentUser currentUser;

    private ProjectReservationService service;

    @BeforeEach
    void setUp() {
        service = new ProjectReservationService(projectLibrary, catalogApi, reservationApi, currentUser);
        lenient().when(currentUser.id()).thenReturn(STUDENT_ID);
        lenient()
                .when(reservationApi.bookBundle(anyLong(), anyList(), any(), any()))
                .thenReturn(BUNDLE_ID);
    }

    @Test
    void picksLowestFreeIdPerModel() {
        project(component("Arduino Uno R3", 1));
        // The catalog does not promise an order, so the ids arrive shuffled on purpose.
        when(catalogApi.findAvailableByModel("Arduino Uno R3", null))
                .thenReturn(List.of(
                        asset(12L, "Arduino Uno R3"), asset(5L, "Arduino Uno R3"), asset(9L, "Arduino Uno R3")));
        when(reservationApi.isAssetFree(anyLong(), eq(START), eq(END), eq(null)))
                .thenReturn(true);

        ReserveProjectResponse response = service.reserve("kit", START, END);

        verify(
                        reservationApi,
                        description(
                                "bookBundle must get only the lowest free id; see ProjectReservationService.pickUnits"))
                .bookBundle(STUDENT_ID, List.of(5L), START, END);
        assertThat(response.items())
                .as("the lowest free asset id of the model must be booked; see ProjectReservationService.pickUnits")
                .extracting(ReservedItem::assetId)
                .containsExactly(5L);
    }

    @Test
    void picksAsManyUnitsAsTheQuantity() {
        project(component("Arduino Uno R3", 2), component("Fluke 115", 1));
        when(catalogApi.findAvailableByModel("Arduino Uno R3", null))
                .thenReturn(
                        List.of(asset(3L, "Arduino Uno R3"), asset(1L, "Arduino Uno R3"), asset(2L, "Arduino Uno R3")));
        when(catalogApi.findAvailableByModel("Fluke 115", null)).thenReturn(List.of(asset(20L, "Fluke 115")));
        when(reservationApi.isAssetFree(anyLong(), eq(START), eq(END), eq(null)))
                .thenReturn(true);

        ReserveProjectResponse response = service.reserve("kit", START, END);

        verify(
                        reservationApi,
                        description(
                                "bookBundle must get every picked id in file order; see ProjectReservationService.reserve"))
                .bookBundle(STUDENT_ID, List.of(1L, 2L, 20L), START, END);
        assertThat(response.items())
                .as("quantity 2 must book the two lowest free units, then the next component in file order; "
                        + "see ProjectReservationService.reserve")
                .extracting(ReservedItem::assetId, ReservedItem::assetName, ReservedItem::model)
                .containsExactly(
                        tuple(1L, "Arduino Uno R3 #1", "Arduino Uno R3"),
                        tuple(2L, "Arduino Uno R3 #2", "Arduino Uno R3"),
                        tuple(20L, "Fluke 115 #20", "Fluke 115"));
        assertThat(response.bundleId())
                .as("the response must carry the bundle id returned by ReservationApi.bookBundle; "
                        + "see ProjectReservationService.reserve")
                .isEqualTo(BUNDLE_ID);
        assertThat(response.start())
                .as("the response must echo the requested start; see ProjectReservationService.reserve")
                .isEqualTo(START);
        assertThat(response.end())
                .as("the response must echo the requested end; see ProjectReservationService.reserve")
                .isEqualTo(END);
    }

    @Test
    void skipsUnitsBusyInTheSlot() {
        project(component("Arduino Uno R3", 1));
        when(catalogApi.findAvailableByModel("Arduino Uno R3", null))
                .thenReturn(List.of(asset(1L, "Arduino Uno R3"), asset(2L, "Arduino Uno R3")));
        when(reservationApi.isAssetFree(1L, START, END, null)).thenReturn(false);
        when(reservationApi.isAssetFree(2L, START, END, null)).thenReturn(true);

        service.reserve("kit", START, END);

        verify(
                        reservationApi,
                        description("a unit busy in the slot must be skipped for the next free one; "
                                + "see ProjectReservationService.pickUnits"))
                .bookBundle(STUDENT_ID, List.of(2L), START, END);
    }

    @Test
    void notEnoughFreeUnitsIs409NamingTheModel() {
        project(component("Arduino Uno R3", 1), component("DS1054Z", 2));
        when(catalogApi.findAvailableByModel("Arduino Uno R3", null)).thenReturn(List.of(asset(1L, "Arduino Uno R3")));
        when(catalogApi.findAvailableByModel("DS1054Z", null)).thenReturn(List.of(asset(30L, "DS1054Z")));
        when(reservationApi.isAssetFree(anyLong(), eq(START), eq(END), eq(null)))
                .thenReturn(true);

        assertThatThrownBy(() -> service.reserve("kit", START, END))
                .as("fewer free units than the quantity must be 409 PROJECT_COMPONENT_UNAVAILABLE naming the model; "
                        + "see ProjectReservationService.pickUnits")
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("DS1054Z")
                .extracting("code")
                .isEqualTo("PROJECT_COMPONENT_UNAVAILABLE");
        verify(
                        reservationApi,
                        never().description("nothing may be booked when one model is short (all or nothing); "
                                + "see ProjectReservationService.reserve"))
                .bookBundle(anyLong(), anyList(), any(), any());
    }

    @Test
    void modelMissingFromTheCatalogIs409() {
        project(component("FX-888D", 1));
        when(catalogApi.findAvailableByModel("FX-888D", null)).thenReturn(List.of());

        assertThatThrownBy(() -> service.reserve("kit", START, END))
                .as("a model with no unit in the catalog must block Reserve all with 409; "
                        + "see ProjectReservationService.pickUnits")
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("FX-888D")
                .extracting("code")
                .isEqualTo("PROJECT_COMPONENT_UNAVAILABLE");
    }

    @Test
    void bookBundleErrorPassesThroughUnchanged() {
        project(component("Arduino Uno R3", 1));
        when(catalogApi.findAvailableByModel("Arduino Uno R3", null)).thenReturn(List.of(asset(1L, "Arduino Uno R3")));
        when(reservationApi.isAssetFree(1L, START, END, null)).thenReturn(true);
        ConflictException limit = new ConflictException(
                "RESERVATION_LIMIT_REACHED", "Student cannot have more than 2 active reservations");
        when(reservationApi.bookBundle(STUDENT_ID, List.of(1L), START, END)).thenThrow(limit);

        assertThatThrownBy(() -> service.reserve("kit", START, END))
                .as("an error from ReservationApi.bookBundle must reach the client unchanged; "
                        + "see ProjectReservationService.reserve")
                .isSameAs(limit);
    }

    @Test
    void bookingUserComesFromCurrentUser() {
        when(currentUser.id()).thenReturn(42L);
        project(component("Arduino Uno R3", 1));
        when(catalogApi.findAvailableByModel("Arduino Uno R3", null)).thenReturn(List.of(asset(1L, "Arduino Uno R3")));
        when(reservationApi.isAssetFree(1L, START, END, null)).thenReturn(true);

        service.reserve("kit", START, END);

        verify(
                        reservationApi,
                        description("the booking user must be CurrentUser.id() (the JWT), never a body field; "
                                + "see ProjectReservationService.reserve"))
                .bookBundle(42L, List.of(1L), START, END);
    }

    @Test
    void unknownSlugIs404() {
        when(projectLibrary.find("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reserve("nope", START, END))
                .as("an unknown slug must be 404 PROJECT_NOT_FOUND; see ProjectService.findProject")
                .isInstanceOf(NotFoundException.class)
                .extracting("code")
                .isEqualTo("PROJECT_NOT_FOUND");
        verifyNoInteractions(catalogApi, reservationApi);
    }

    @Test
    void endNotAfterStartIs400() {
        project(component("Arduino Uno R3", 1));

        assertThatThrownBy(() -> service.reserve("kit", START, START))
                .as("end equal to start must be 400 VALIDATION_FAILED; see ProjectService.requireEndAfterStart")
                .isInstanceOf(BadRequestException.class)
                .extracting("code")
                .isEqualTo("VALIDATION_FAILED");
        verifyNoInteractions(catalogApi, reservationApi);
    }

    private void project(ProjectComponent... components) {
        when(projectLibrary.find("kit"))
                .thenReturn(Optional.of(new Project(
                        "kit",
                        Difficulty.BEGINNER,
                        2,
                        new LocalizedText("Trusa", "Kit"),
                        new LocalizedText("Descriere", "Summary"),
                        new Keywords(List.of("trusa"), List.of("kit")),
                        List.of(components),
                        List.of(),
                        List.of(new ProjectStep(new LocalizedText("P1", "S1"), new LocalizedText("T1", "T1"))),
                        List.of())));
    }

    private static ProjectComponent component(String model, int quantity) {
        return new ProjectComponent(model, quantity, new LocalizedText("De ce", "Why"));
    }

    private static AssetSummary asset(Long id, String model) {
        return new AssetSummary(id, model + " #" + id, model, 1L, 1L, AssetStatus.AVAILABLE);
    }
}
