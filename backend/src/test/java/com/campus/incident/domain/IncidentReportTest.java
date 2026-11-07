package com.campus.incident.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campus.common.error.InvalidStateTransitionException;
import com.campus.incident.domain.IncidentReport.IncidentSeverity;
import com.campus.incident.domain.IncidentReport.IncidentStatus;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The incident state machine on its own, without Spring or a database:
 *
 * <pre>
 * OPEN ──start-repair──▶ IN_REPAIR
 *  │                       │
 *  ├──resolve / reject──┐  ├──resolve / reject──┐
 *  ▼                    ▼  ▼                    ▼
 * RESOLVED          REJECTED   (both final)
 * </pre>
 */
class IncidentReportTest {

    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 11, 3, 14, 0);
    private static final LocalDateTime CLOSED = LocalDateTime.of(2026, 11, 4, 9, 30);

    private static IncidentReport inStatus(IncidentStatus status) {
        IncidentReport report =
                new IncidentReport(10L, null, 42L, "Display cracked", IncidentSeverity.HIGH, null, CREATED);
        ReflectionTestUtils.setField(report, "status", status);
        return report;
    }

    @Test
    void aNewReportIsOpen_andNotClosedByAnyone() {
        IncidentReport report =
                new IncidentReport(10L, 7L, 42L, "Display cracked", IncidentSeverity.LOW, null, CREATED);

        assertThat(report.getStatus()).isEqualTo(IncidentStatus.OPEN);
        assertThat(report.getAssetId()).isEqualTo(10L);
        assertThat(report.getReservationId()).isEqualTo(7L);
        assertThat(report.getReportedById()).isEqualTo(42L);
        assertThat(report.getCreatedAt()).isEqualTo(CREATED);
        assertThat(report.getResolvedAt()).isNull();
        assertThat(report.getResolvedById()).isNull();
        assertThat(report.getResolutionNotes()).isNull();
    }

    // ---- start repair ------------------------------------------------------------------------

    @Test
    void startRepair_movesOpenToInRepair() {
        IncidentReport report = inStatus(IncidentStatus.OPEN);

        report.startRepair();

        assertThat(report.getStatus()).isEqualTo(IncidentStatus.IN_REPAIR);
        assertThat(report.getResolvedAt()).isNull();
    }

    // IN_REPAIR is here too: the service returns it unchanged before calling the entity.
    @ParameterizedTest(name = "from {0}")
    @CsvSource({"IN_REPAIR", "RESOLVED", "REJECTED"})
    void startRepair_onlyWorksFromOpen(IncidentStatus from) {
        IncidentReport report = inStatus(from);

        assertThatThrownBy(report::startRepair)
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessage("Repair cannot start from status: " + from)
                .extracting("code")
                .isEqualTo("INVALID_INCIDENT_STATUS_TRANSITION");
        assertThat(report.getStatus()).isEqualTo(from);
    }

    // ---- resolve -----------------------------------------------------------------------------

    @ParameterizedTest(name = "from {0}")
    @CsvSource({"OPEN", "IN_REPAIR"})
    void resolve_closesTheIncident_andRecordsWhoWhenAndWhy(IncidentStatus from) {
        IncidentReport report = inStatus(from);

        report.resolve(99L, "Replaced display panel", CLOSED);

        assertThat(report.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(report.getResolvedById()).isEqualTo(99L);
        assertThat(report.getResolvedAt()).isEqualTo(CLOSED);
        assertThat(report.getResolutionNotes()).isEqualTo("Replaced display panel");
    }

    @ParameterizedTest(name = "from {0}")
    @CsvSource({"RESOLVED", "REJECTED"})
    void resolve_failsOnAClosedIncident_andChangesNothing(IncidentStatus from) {
        IncidentReport report = inStatus(from);

        assertThatThrownBy(() -> report.resolve(99L, "Notes", CLOSED))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessage("Incident cannot be resolved from status: " + from)
                .extracting("code")
                .isEqualTo("INVALID_INCIDENT_STATUS_TRANSITION");
        assertThat(report.getStatus()).isEqualTo(from);
        assertThat(report.getResolvedAt()).isNull();
    }

    // ---- reject ------------------------------------------------------------------------------

    @ParameterizedTest(name = "from {0}")
    @CsvSource({"OPEN", "IN_REPAIR"})
    void reject_closesTheIncident_andRecordsWhoWhenAndWhy(IncidentStatus from) {
        IncidentReport report = inStatus(from);

        report.reject(99L, "Works fine, wrong cable", CLOSED);

        assertThat(report.getStatus()).isEqualTo(IncidentStatus.REJECTED);
        assertThat(report.getResolvedById()).isEqualTo(99L);
        assertThat(report.getResolvedAt()).isEqualTo(CLOSED);
        assertThat(report.getResolutionNotes()).isEqualTo("Works fine, wrong cable");
    }

    @Test
    void reject_acceptsNoNotes() {
        IncidentReport report = inStatus(IncidentStatus.OPEN);

        report.reject(99L, null, CLOSED);

        assertThat(report.getStatus()).isEqualTo(IncidentStatus.REJECTED);
        assertThat(report.getResolutionNotes()).isNull();
    }

    @ParameterizedTest(name = "from {0}")
    @CsvSource({"RESOLVED", "REJECTED"})
    void reject_failsOnAClosedIncident_andChangesNothing(IncidentStatus from) {
        IncidentReport report = inStatus(from);

        assertThatThrownBy(() -> report.reject(99L, "Notes", CLOSED))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessage("Incident cannot be rejected from status: " + from)
                .extracting("code")
                .isEqualTo("INVALID_INCIDENT_STATUS_TRANSITION");
        assertThat(report.getStatus()).isEqualTo(from);
        assertThat(report.getResolvedById()).isNull();
    }
}
