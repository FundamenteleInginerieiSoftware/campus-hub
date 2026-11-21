package com.campus.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.campus.user.api.UserDisabledEvent;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

class UserDisabledLiveStreamListenerTest {

    @Test
    void closesAllOpenStreamsForDisabledUser() {
        LiveStreams liveStreams = mock(LiveStreams.class);
        UserDisabledLiveStreamListener listener = new UserDisabledLiveStreamListener(liveStreams);

        listener.onUserDisabled(new UserDisabledEvent(42L, 1L));

        verify(liveStreams).completeAll(42L);
    }

    @Test
    void listensAfterCommit() throws Exception {
        Method method = UserDisabledLiveStreamListener.class.getMethod("onUserDisabled", UserDisabledEvent.class);
        TransactionalEventListener annotation = method.getAnnotation(TransactionalEventListener.class);

        assertThat(annotation)
                .as("listener must be annotated with @TransactionalEventListener(AFTER_COMMIT); see UserDisabledEvent")
                .isNotNull();
        assertThat(annotation.phase())
                .as("must close streams only after the disable transaction commits; see UserDisabledLiveStreamListener")
                .isEqualTo(TransactionPhase.AFTER_COMMIT);
    }
}
