package com.campus.reservation.repository;

import com.campus.reservation.domain.CalendarFeed;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence access for {@link CalendarFeed} token hashes.
 */
public interface CalendarFeedRepository extends JpaRepository<CalendarFeed, Long> {

    Optional<CalendarFeed> findByUserId(Long userId);

    Optional<CalendarFeed> findByTokenHash(String tokenHash);

    void deleteByUserId(Long userId);

    // Bug fixed (#362 §3): atomic PostgreSQL upsert avoids SQLState 23505 (500) when a user double-clicks
    // "Generate link" and two concurrent transactions both observe findByUserId() == empty.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
                INSERT INTO calendar_feeds (user_id, token_hash, created_at)
                VALUES (:userId, :tokenHash, :createdAt)
                ON CONFLICT (user_id) DO UPDATE
                SET token_hash = EXCLUDED.token_hash,
                    created_at = EXCLUDED.created_at
                """, nativeQuery = true)
    void upsertToken(
            @Param("userId") Long userId,
            @Param("tokenHash") String tokenHash,
            @Param("createdAt") LocalDateTime createdAt);
}
