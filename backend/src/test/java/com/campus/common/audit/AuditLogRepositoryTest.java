package com.campus.common.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

@DataJpaTest
class AuditLogRepositoryTest {

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Test
    void savesAndLoadsSystemAuditEntryWithNullActor() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 29, 12, 0);
        AuditLog saved = auditLogRepository.saveAndFlush(
                new AuditLog(null, "RESERVATION_NO_SHOW", "RESERVATION", 42L, "Marked as no-show by job", now));

        assertNotNull(saved.getId());
        assertNull(saved.getActorId());
        assertEquals("RESERVATION_NO_SHOW", saved.getAction());
        assertEquals(now, saved.getCreatedAt());
    }

    @Test
    void rejectsUnknownActorId() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 29, 12, 0);
        AuditLog invalid = new AuditLog(999_999L, "ASSET_RETIRED", "ASSET", 12L, "Unknown user", now);

        assertThrows(DataIntegrityViolationException.class, () -> auditLogRepository.saveAndFlush(invalid));
    }
}
