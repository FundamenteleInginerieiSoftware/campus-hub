package com.campus.reservation.repository;

import com.campus.reservation.domain.CalendarFeed;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence access for {@link CalendarFeed} token hashes.
 */
public interface CalendarFeedRepository extends JpaRepository<CalendarFeed, Long> {

    Optional<CalendarFeed> findByUserId(Long userId);

    Optional<CalendarFeed> findByTokenHash(String tokenHash);

    void deleteByUserId(Long userId);
}
