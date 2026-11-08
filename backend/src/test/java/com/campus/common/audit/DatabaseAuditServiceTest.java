package com.campus.common.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.campus.common.audit.web.AuditLogResponse;
import com.campus.common.error.InvalidParameterException;
import com.campus.common.paging.PageResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

@ExtendWith(MockitoExtension.class)
class DatabaseAuditServiceTest {

    private static final ZoneId CAMPUS_ZONE = ZoneId.of("Europe/Bucharest");
    // Sub-second nanos verify that DatabaseAuditService truncates timestamps to whole seconds (#147).
    private static final Instant FIXED_INSTANT = Instant.parse("2026-10-29T10:00:00.987654321Z");
    private static final LocalDateTime EXPECTED_NOW =
            LocalDateTime.ofInstant(FIXED_INSTANT, CAMPUS_ZONE).truncatedTo(ChronoUnit.SECONDS);

    @Mock
    private AuditLogRepository auditLogRepository;

    private DatabaseAuditService auditService;

    @BeforeEach
    void setUp() {
        auditService = new DatabaseAuditService(auditLogRepository, Clock.fixed(FIXED_INSTANT, CAMPUS_ZONE));
    }

    @Test
    void recordsAuditEntryWithCampusClockTimestamp() {
        auditService.record(3L, "ASSET_RETIRED", "ASSET", 12L, "Retired damaged oscilloscope");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());

        AuditLog saved = captor.getValue();
        assertEquals(3L, saved.getActorId());
        assertEquals("ASSET_RETIRED", saved.getAction());
        assertEquals("ASSET", saved.getEntityType());
        assertEquals(12L, saved.getEntityId());
        assertEquals("Retired damaged oscilloscope", saved.getDetails());
        assertEquals(EXPECTED_NOW, saved.getCreatedAt());
    }

    @Test
    void recordsSystemAuditEntryWithNullActor() {
        auditService.record(null, "RESERVATION_NO_SHOW", "RESERVATION", 42L, "Marked as no-show by scheduled job");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());

        AuditLog saved = captor.getValue();
        assertNull(saved.getActorId());
        assertEquals("RESERVATION_NO_SHOW", saved.getAction());
        assertEquals("RESERVATION", saved.getEntityType());
        assertEquals(42L, saved.getEntityId());
        assertEquals(EXPECTED_NOW, saved.getCreatedAt());
    }

    @Test
    @DisplayName("search delegates to repository and maps entities to AuditLogResponse")
    void searchDelegatesToRepositoryAndMapsResponse() {
        AuditLog entry = new AuditLog(2L, "USER_ROLE_CHANGED", "USER", 5L, "Role updated", EXPECTED_NOW);
        Page<AuditLog> page = new PageImpl<>(List.of(entry));

        when(auditLogRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(page);

        PageResponse<AuditLogResponse> response =
                auditService.search(2L, "USER_ROLE_CHANGED", "USER", 5L, null, null, 0, 20, "createdAt,desc");

        assertThat(response.content()).hasSize(1);
        AuditLogResponse dto = response.content().getFirst();
        assertThat(dto.actorId()).isEqualTo(2L);
        assertThat(dto.action()).isEqualTo("USER_ROLE_CHANGED");
        assertThat(dto.entityType()).isEqualTo("USER");
        assertThat(dto.entityId()).isEqualTo(5L);
        assertThat(dto.details()).isEqualTo("Role updated");
        assertThat(dto.createdAt()).isEqualTo(EXPECTED_NOW);
    }

    @Test
    @DisplayName("search validates custom sorting with secondary sort on id")
    void searchValidatesCustomSortWithSecondarySort() {
        Page<AuditLog> emptyPage = new PageImpl<>(List.of());
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);

        when(auditLogRepository.findAll(any(Specification.class), pageableCaptor.capture()))
                .thenReturn(emptyPage);

        auditService.search(null, null, null, null, null, null, 1, 10, "action,asc");

        Pageable captured = pageableCaptor.getValue();
        assertThat(captured.getPageNumber()).isEqualTo(1);
        assertThat(captured.getPageSize()).isEqualTo(10);
        Sort.Order primary = captured.getSort().getOrderFor("action");
        Sort.Order secondary = captured.getSort().getOrderFor("id");
        assertThat(primary).isNotNull();
        assertThat(primary.getDirection()).isEqualTo(Sort.Direction.ASC);
        assertThat(secondary).isNotNull();
        assertThat(secondary.getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    @Test
    @DisplayName("search rejects negative page index with INVALID_PARAMETER")
    void searchRejectsNegativePage() {
        assertThatThrownBy(() -> auditService.search(null, null, null, null, null, null, -1, 20, null))
                .isInstanceOf(InvalidParameterException.class)
                .hasMessageContaining("Page index must not be negative");
    }

    @Test
    @DisplayName("search rejects page size out of bounds (1 to 100)")
    void searchRejectsInvalidPageSize() {
        assertThatThrownBy(() -> auditService.search(null, null, null, null, null, null, 0, 0, null))
                .isInstanceOf(InvalidParameterException.class)
                .hasMessageContaining("Page size must be between 1 and 100");

        assertThatThrownBy(() -> auditService.search(null, null, null, null, null, null, 0, 101, null))
                .isInstanceOf(InvalidParameterException.class)
                .hasMessageContaining("Page size must be between 1 and 100");
    }

    @Test
    @DisplayName("search rejects integer overflow on page * size offset")
    void searchRejectsPageOffsetOverflow() {
        assertThatThrownBy(() -> auditService.search(null, null, null, null, null, null, Integer.MAX_VALUE, 100, null))
                .isInstanceOf(InvalidParameterException.class)
                .hasMessageContaining("Page offset exceeds maximum supported value");
    }

    @Test
    @DisplayName("search rejects 'from' timestamp after 'to' timestamp")
    void searchRejectsFromAfterToTimestamp() {
        LocalDateTime from = LocalDateTime.of(2026, 11, 5, 12, 0);
        LocalDateTime to = LocalDateTime.of(2026, 11, 4, 12, 0);

        assertThatThrownBy(() -> auditService.search(null, null, null, null, from, to, 0, 20, null))
                .isInstanceOf(InvalidParameterException.class)
                .hasMessageContaining("'from' timestamp must not be after 'to' timestamp");
    }

    @Test
    @DisplayName("search rejects action filter exceeding 64 characters")
    void searchRejectsOversizedActionFilter() {
        String longAction = "A".repeat(65);
        assertThatThrownBy(() -> auditService.search(null, longAction, null, null, null, null, 0, 20, null))
                .isInstanceOf(InvalidParameterException.class)
                .hasMessageContaining("Action filter must not exceed 64 characters");
    }

    @Test
    @DisplayName("search rejects entityType filter exceeding 64 characters")
    void searchRejectsOversizedEntityTypeFilter() {
        String longEntityType = "E".repeat(65);
        assertThatThrownBy(() -> auditService.search(null, null, longEntityType, null, null, null, 0, 20, null))
                .isInstanceOf(InvalidParameterException.class)
                .hasMessageContaining("EntityType filter must not exceed 64 characters");
    }

    @Test
    @DisplayName("search rejects unsupported sort property")
    void searchRejectsUnsupportedSortField() {
        assertThatThrownBy(() -> auditService.search(null, null, null, null, null, null, 0, 20, "details,desc"))
                .isInstanceOf(InvalidParameterException.class)
                .hasMessageContaining("Unsupported sort property 'details'");
    }

    @Test
    @DisplayName("search rejects invalid sort direction")
    void searchRejectsInvalidSortDirection() {
        assertThatThrownBy(() -> auditService.search(null, null, null, null, null, null, 0, 20, "createdAt,sideways"))
                .isInstanceOf(InvalidParameterException.class)
                .hasMessageContaining("Invalid sort direction 'sideways'");
    }

    @Test
    @DisplayName("search rejects malformed sort expression with too many parts")
    void searchRejectsMalformedSortExpression() {
        assertThatThrownBy(() -> auditService.search(null, null, null, null, null, null, 0, 20, "createdAt,desc,extra"))
                .isInstanceOf(InvalidParameterException.class)
                .hasMessageContaining("Sort parameter must have format 'field,asc' or 'field,desc'");
    }
}
