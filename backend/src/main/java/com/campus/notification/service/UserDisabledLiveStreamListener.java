package com.campus.notification.service;

import com.campus.user.api.UserDisabledEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Closes a disabled user's open SSE streams right after the account-disable transaction commits
 * (#290, #302), closing the up-to-10-minute window before the next JWT check.
 *
 * <p>Follows the {@link UserDisabledEvent} listener contract: runs {@code AFTER_COMMIT}, needs no
 * {@code REQUIRES_NEW} transaction because it makes no database writes, and is not {@code @Async}
 * because {@code completeAll} is an in-memory call that returns immediately.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserDisabledLiveStreamListener {

    private final LiveStreams liveStreams;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserDisabled(UserDisabledEvent event) {
        try {
            liveStreams.completeAll(event.userId());
        } catch (RuntimeException ex) {
            log.warn("Failed to close live streams for disabled userId={}: {}", event.userId(), ex.getMessage());
        }
    }
}
