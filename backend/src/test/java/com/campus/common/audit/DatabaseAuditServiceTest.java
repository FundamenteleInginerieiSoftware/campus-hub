package com.campus.common.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DatabaseAuditServiceTest {

    private static final ZoneId CAMPUS_ZONE = ZoneId.of("Europe/Bucharest");
    private static final Instant FIXED_INSTANT = Instant.parse("2026-10-29T10:00:00Z");
    private static final LocalDateTime EXPECTED_NOW = LocalDateTime.ofInstant(FIXED_INSTANT, CAMPUS_ZONE);

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
}
