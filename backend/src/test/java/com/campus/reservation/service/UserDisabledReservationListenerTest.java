package com.campus.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.campus.reservation.api.ReservationApi;
import com.campus.reservation.repository.ReservationRepository;
import com.campus.user.api.Role;
import com.campus.user.api.UserApi;
import com.campus.user.api.UserDisabledEvent;
import java.lang.reflect.Method;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Async;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class UserDisabledReservationListenerTest {

    @Mock
    private ReservationApi reservationApi;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private UserApi userApi;

    @Mock
    private WaitlistService waitlistService;

    @Mock
    private PlatformTransactionManager transactionManager;

    private UserDisabledReservationListener listener;

    @BeforeEach
    void setUp() {
        TransactionStatus txStatus = mock(TransactionStatus.class);
        lenient().when(transactionManager.getTransaction(any())).thenReturn(txStatus);
        listener = new UserDisabledReservationListener(
                reservationApi, reservationRepository, userApi, waitlistService, transactionManager);
    }

    @Test
    @DisplayName("UserDisabledReservationListener (#229): uses AFTER_COMMIT, REQUIRES_NEW, and is not @Async")
    void listenerContractUsesAfterCommitRequiresNewAndNoAsync() throws NoSuchMethodException {
        Method method = UserDisabledReservationListener.class.getMethod("onUserDisabled", UserDisabledEvent.class);

        TransactionalEventListener listenerAnnotation = method.getAnnotation(TransactionalEventListener.class);
        assertThat(listenerAnnotation)
                .as("listener must be annotated with @TransactionalEventListener(AFTER_COMMIT); "
                        + "see UserDisabledReservationListener.onUserDisabled")
                .isNotNull();
        assertThat(listenerAnnotation.phase())
                .as("listener must run AFTER_COMMIT so disabling the account commits first; "
                        + "see UserDisabledReservationListener.onUserDisabled")
                .isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(method.getAnnotation(Async.class))
                .as("listener must not be @Async so CurrentUser stays available on the request thread for audit; "
                        + "see UserDisabledEvent Javadoc")
                .isNull();

        TransactionTemplate tt = (TransactionTemplate) ReflectionTestUtils.getField(listener, "requiresNewTx");
        assertThat(tt)
                .as("listener must use a TransactionTemplate for REQUIRES_NEW")
                .isNotNull();
        assertThat(tt.getPropagationBehavior())
                .as("listener must run inside PROPAGATION_REQUIRES_NEW after the disable tx commits; "
                        + "see UserDisabledReservationListener")
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Test
    @DisplayName(
            "UserDisabledReservationListener (#229, #362): locks quota, confirms user is still disabled, and cancels upcoming reservations")
    void onUserDisabledCancelsUpcomingReservations() {
        when(userApi.findActiveRole(42L)).thenReturn(Optional.empty());
        when(reservationApi.cancelUpcomingReservationsForUser(42L, UserDisabledReservationListener.DEFAULT_REASON))
                .thenReturn(2);

        listener.onUserDisabled(new UserDisabledEvent(42L, 3L));

        var order = inOrder(reservationRepository, userApi, reservationApi, waitlistService);
        order.verify(reservationRepository).lockUserQuota(42L);
        order.verify(userApi).findActiveRole(42L);
        order.verify(reservationApi)
                .cancelUpcomingReservationsForUser(42L, UserDisabledReservationListener.DEFAULT_REASON);
        order.verify(waitlistService).cancelWaitingEntriesForUser(42L);
    }

    @Test
    @DisplayName(
            "UserDisabledReservationListener (#362 §3): skips cancellation when user was re-enabled before listener ran")
    void onUserDisabledSkipsWhenUserAlreadyReEnabled() {
        when(userApi.findActiveRole(42L)).thenReturn(Optional.of(Role.STUDENT));

        listener.onUserDisabled(new UserDisabledEvent(42L, 3L));

        var order = inOrder(reservationRepository, userApi);
        order.verify(reservationRepository).lockUserQuota(42L);
        order.verify(userApi).findActiveRole(42L);
        verify(reservationApi, never()).cancelUpcomingReservationsForUser(any(), any());
        verify(waitlistService, never()).cancelWaitingEntriesForUser(any());
    }

    @Test
    @DisplayName("UserDisabledReservationListener (#229): catches and logs exceptions so disable never fails")
    void onUserDisabledSwallowsRuntimeException() {
        when(userApi.findActiveRole(42L)).thenReturn(Optional.empty());
        when(reservationApi.cancelUpcomingReservationsForUser(42L, UserDisabledReservationListener.DEFAULT_REASON))
                .thenThrow(new IllegalStateException("DB timeout"));

        assertThatCode(() -> listener.onUserDisabled(new UserDisabledEvent(42L, 3L)))
                .as("listener must catch and log errors so disabling an account never fails; "
                        + "see UserDisabledReservationListener.onUserDisabled")
                .doesNotThrowAnyException();
        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
    }
}
