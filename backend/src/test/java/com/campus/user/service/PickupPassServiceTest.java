package com.campus.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.campus.common.audit.AuditService;
import com.campus.common.error.BusinessRuleException;
import com.campus.common.security.CurrentUser;
import com.campus.user.api.Role;
import com.campus.user.domain.User;
import com.campus.user.repository.UserRepository;
import com.campus.user.web.PickupPassResponse;
import com.campus.user.web.PickupPassVerifiedResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

// Plain Mockito test. The clock is fixed at second 10 of time step 100_000, so 20 seconds are left.
@ExtendWith(MockitoExtension.class)
class PickupPassServiceTest {

    private static final String SECRET = "test-secret-test-secret-test-secret-123";
    private static final long STEP = 100_000;
    private static final long STUDENT_ID = 12L;
    private static final long ADMIN_ID = 3L;

    @Mock
    private UserRepository users;

    @Mock
    private CurrentUser currentUser;

    @Mock
    private AuditService audit;

    private final PickupCodes codes = new PickupCodes(SECRET);

    private PickupPassService serviceAt(long epochSecond) {
        Clock clock = Clock.fixed(Instant.ofEpochSecond(epochSecond), ZoneId.of("Europe/Bucharest"));
        return new PickupPassService(SECRET, users, currentUser, audit, clock);
    }

    private PickupPassService service() {
        return serviceAt(3_000_010);
    }

    private User student() {
        User user = new User("ana@campus.test", "hash", "Ana Pop", Role.STUDENT, LocalDateTime.of(2026, 11, 1, 9, 0));
        ReflectionTestUtils.setField(user, "id", STUDENT_ID);
        return user;
    }

    private void studentExists() {
        when(users.findById(STUDENT_ID)).thenReturn(Optional.of(student()));
    }

    @Test
    void currentPassIsForTheLoggedInUser() {
        when(currentUser.id()).thenReturn(STUDENT_ID);

        PickupPassResponse pass = service().currentPass();

        String expected = codes.code(STUDENT_ID, STEP);
        assertThat(pass.code())
                .as("pass: code of the logged-in user; see PickupPassService.currentPass")
                .isEqualTo(expected);
        assertThat(pass.qrText())
                .as("pass: QR text ends with the code; see PickupPassService.currentPass")
                .endsWith(expected);
        assertThat(pass.validForSeconds())
                .as("pass: 20 s left at second 10; see PickupPassService.currentPass")
                .isEqualTo(20);
    }

    @Test
    void secondsLeftAtStepStartIsThirty() {
        when(currentUser.id()).thenReturn(STUDENT_ID);

        assertThat(serviceAt(3_000_000).currentPass().validForSeconds())
                .as("pass: 30 s left at the start of a step; see PickupPassService.currentPass")
                .isEqualTo(30);
    }

    @Test
    void secondsLeftAtLastSecondIsOne() {
        when(currentUser.id()).thenReturn(STUDENT_ID);

        assertThat(serviceAt(3_000_029).currentPass().validForSeconds())
                .as("pass: 1 s left at the last second of a step; see PickupPassService.currentPass")
                .isEqualTo(1);
    }

    @Test
    void acceptsCurrentCode() {
        studentExists();
        when(currentUser.id()).thenReturn(ADMIN_ID);

        PickupPassVerifiedResponse result = service().verify(STUDENT_ID, codes.code(STUDENT_ID, STEP));

        assertThat(result.fullName())
                .as("verify: current code shows the student; see PickupPassService.verify")
                .isEqualTo("Ana Pop");
        assertThat(result.email())
                .as("verify: current code shows the e-mail; see PickupPassService.verify")
                .isEqualTo("ana@campus.test");
        assertThat(result.userId())
                .as("verify: current code shows the id; see PickupPassService.verify")
                .isEqualTo(STUDENT_ID);
    }

    @Test
    void acceptsPreviousStepCode() {
        studentExists();
        when(currentUser.id()).thenReturn(ADMIN_ID);

        PickupPassVerifiedResponse result = service().verify(STUDENT_ID, codes.code(STUDENT_ID, STEP - 1));

        assertThat(result.userId())
                .as("verify: previous step still accepted; see PickupPassService.verify")
                .isEqualTo(STUDENT_ID);
    }

    @Test
    void rejectsCodeTwoStepsOld() {
        studentExists();

        assertInvalid(codes.code(STUDENT_ID, STEP - 2), "verify: two steps old must be rejected");
    }

    @Test
    void rejectsNextStepCode() {
        studentExists();

        assertInvalid(codes.code(STUDENT_ID, STEP + 1), "verify: a future step must be rejected");
    }

    @Test
    void rejectsWrongCode() {
        studentExists();
        String wrong = "000000";
        assertThat(wrong)
                .as(
                        "test guard: 000000 must not be a valid code at this step; see PickupPassServiceTest.rejectsWrongCode")
                .isNotEqualTo(codes.code(STUDENT_ID, STEP))
                .isNotEqualTo(codes.code(STUDENT_ID, STEP - 1));

        assertInvalid(wrong, "verify: a wrong code must be rejected");
    }

    @Test
    void rejectsUnknownUser() {
        when(users.findById(STUDENT_ID)).thenReturn(Optional.empty());

        assertInvalid(codes.code(STUDENT_ID, STEP), "verify: unknown user answers like a wrong code");
    }

    @Test
    void rejectsDisabledUser() {
        User user = student();
        user.disable();
        when(users.findById(STUDENT_ID)).thenReturn(Optional.of(user));

        assertInvalid(codes.code(STUDENT_ID, STEP), "verify: disabled user answers like a wrong code");
    }

    @Test
    void recordsAuditOnSuccess() {
        studentExists();
        when(currentUser.id()).thenReturn(ADMIN_ID);

        service().verify(STUDENT_ID, codes.code(STUDENT_ID, STEP));

        verify(audit).record(eq(ADMIN_ID), eq("PICKUP_PASS_VERIFIED"), eq("USER"), eq(STUDENT_ID), contains("Ana Pop"));
    }

    @Test
    void noAuditOnFailure() {
        studentExists();

        assertInvalid("000000", "verify: failed verify must not be audited");

        verifyNoInteractions(audit);
    }

    private void assertInvalid(String code, String rule) {
        assertThatThrownBy(() -> service().verify(STUDENT_ID, code))
                .as(rule + "; see PickupPassService.verify")
                .isInstanceOfSatisfying(
                        BusinessRuleException.class,
                        e -> assertThat(e.getCode()).as(rule).isEqualTo("PICKUP_CODE_INVALID"));
    }
}
