package com.campus.common.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

@DataJpaTest
class AuditLogSpecificationTest {

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private TestEntityManager entityManager;

    private Long createTestUser(String email) {
        Number id = (Number) entityManager
                .getEntityManager()
                .createNativeQuery("INSERT INTO users (email, password_hash, full_name, role, enabled, created_at) "
                        + "VALUES (:email, 'hash', 'Test User', 'ADMIN', true, NOW()) RETURNING id")
                .setParameter("email", email)
                .getSingleResult();
        return id.longValue();
    }

    @Test
    @DisplayName("filters audit logs by actorId")
    void filtersByActorId() {
        Long user1 = createTestUser("admin1@campus.test");
        Long user2 = createTestUser("admin2@campus.test");

        auditLogRepository.save(new AuditLog(user1, "USER_DISABLED", "USER", 10L, "Disabled", LocalDateTime.now()));
        auditLogRepository.save(new AuditLog(user2, "USER_ENABLED", "USER", 10L, "Enabled", LocalDateTime.now()));
        auditLogRepository.save(new AuditLog(null, "NO_SHOW", "RESERVATION", 20L, "System", LocalDateTime.now()));

        Page<AuditLog> page = auditLogRepository.findAll(
                AuditLogSpecification.withFilters(user1, null, null, null, null, null), PageRequest.of(0, 10));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().getFirst().getActorId()).isEqualTo(user1);
        assertThat(page.getContent().getFirst().getAction()).isEqualTo("USER_DISABLED");
    }

    @Test
    @DisplayName("filters audit logs by action case-insensitively")
    void filtersByActionCaseInsensitive() {
        auditLogRepository.save(new AuditLog(null, "ASSET_RETIRED", "ASSET", 1L, "Broken", LocalDateTime.now()));
        auditLogRepository.save(new AuditLog(null, "ASSET_CREATED", "ASSET", 2L, "New", LocalDateTime.now()));

        Page<AuditLog> page = auditLogRepository.findAll(
                AuditLogSpecification.withFilters(null, "asset_retired", null, null, null, null),
                PageRequest.of(0, 10));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().getFirst().getAction()).isEqualTo("ASSET_RETIRED");
    }

    @Test
    @DisplayName("filters audit logs by entityType case-insensitively")
    void filtersByEntityTypeCaseInsensitive() {
        auditLogRepository.save(new AuditLog(null, "ASSET_RETIRED", "ASSET", 1L, "Broken", LocalDateTime.now()));
        auditLogRepository.save(new AuditLog(null, "INCIDENT_REPORTED", "INCIDENT", 5L, "Defect", LocalDateTime.now()));

        Page<AuditLog> page = auditLogRepository.findAll(
                AuditLogSpecification.withFilters(null, null, "incident", null, null, null), PageRequest.of(0, 10));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().getFirst().getEntityType()).isEqualTo("INCIDENT");
    }

    @Test
    @DisplayName("filters audit logs by entityId")
    void filtersByEntityId() {
        auditLogRepository.save(new AuditLog(null, "ASSET_RETIRED", "ASSET", 100L, "Broken", LocalDateTime.now()));
        auditLogRepository.save(new AuditLog(null, "ASSET_RETIRED", "ASSET", 200L, "Old", LocalDateTime.now()));

        Page<AuditLog> page = auditLogRepository.findAll(
                AuditLogSpecification.withFilters(null, null, null, 100L, null, null), PageRequest.of(0, 10));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().getFirst().getEntityId()).isEqualTo(100L);
    }

    @Test
    @DisplayName("filters audit logs by date range [from, to]")
    void filtersByDateRange() {
        LocalDateTime t1 = LocalDateTime.of(2026, 11, 1, 10, 0);
        LocalDateTime t2 = LocalDateTime.of(2026, 11, 3, 10, 0);
        LocalDateTime t3 = LocalDateTime.of(2026, 11, 5, 10, 0);

        auditLogRepository.save(new AuditLog(null, "ACTION_1", "TEST", 1L, "Entry 1", t1));
        auditLogRepository.save(new AuditLog(null, "ACTION_2", "TEST", 2L, "Entry 2", t2));
        auditLogRepository.save(new AuditLog(null, "ACTION_3", "TEST", 3L, "Entry 3", t3));

        LocalDateTime from = LocalDateTime.of(2026, 11, 2, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 11, 4, 0, 0);

        Page<AuditLog> page = auditLogRepository.findAll(
                AuditLogSpecification.withFilters(null, null, null, null, from, to),
                PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "createdAt")));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().getFirst().getAction()).isEqualTo("ACTION_2");
    }

    @Test
    @DisplayName("combines multiple filters conjunctively")
    void combinesMultipleFilters() {
        Long user = createTestUser("admin3@campus.test");
        LocalDateTime t1 = LocalDateTime.of(2026, 11, 2, 12, 0);
        LocalDateTime t2 = LocalDateTime.of(2026, 11, 3, 12, 0);

        auditLogRepository.save(new AuditLog(user, "ASSET_RETIRED", "ASSET", 10L, "First", t1));
        auditLogRepository.save(new AuditLog(user, "ASSET_RETIRED", "ASSET", 10L, "Second", t2));
        auditLogRepository.save(new AuditLog(user, "USER_DISABLED", "USER", 10L, "Other action", t2));

        Page<AuditLog> page = auditLogRepository.findAll(
                AuditLogSpecification.withFilters(user, "ASSET_RETIRED", "ASSET", 10L, t2, null),
                PageRequest.of(0, 10));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().getFirst().getDetails()).isEqualTo("Second");
    }

    @Test
    @DisplayName("returns all entries when all filters are null or blank")
    void returnsAllWhenFiltersNullOrBlank() {
        auditLogRepository.save(new AuditLog(null, "ACTION_A", "ENTITY_A", 1L, "Details", LocalDateTime.now()));
        auditLogRepository.save(new AuditLog(null, "ACTION_B", "ENTITY_B", 2L, "Details", LocalDateTime.now()));

        Page<AuditLog> page = auditLogRepository.findAll(
                AuditLogSpecification.withFilters(null, "   ", "", null, null, null), PageRequest.of(0, 10));

        assertThat(page.getContent().size()).isGreaterThanOrEqualTo(2);
    }
}
