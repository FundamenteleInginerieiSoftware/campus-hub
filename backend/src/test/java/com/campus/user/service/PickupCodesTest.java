package com.campus.user.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PickupCodesTest {

    private static final String SECRET = "test-secret-test-secret-test-secret-0123";
    private final PickupCodes codes = new PickupCodes(SECRET);

    @Test
    void sameInputGivesSameCode() {
        assertThat(codes.code(12, 100))
                .as("same user and step must give the same code; see PickupCodes.code")
                .isEqualTo(codes.code(12, 100));
    }

    @Test
    void codeChangesWithTheStep() {
        Set<String> seen = new HashSet<>();
        for (long step = 100; step < 120; step++) {
            seen.add(codes.code(12, step));
        }
        assertThat(seen)
                .as("the code must rotate with the time step; see PickupCodes.code")
                .hasSizeGreaterThanOrEqualTo(19);
    }

    @Test
    void codeDiffersPerUser() {
        Set<String> seen = new HashSet<>();
        for (long user = 1; user <= 20; user++) {
            seen.add(codes.code(user, 100));
        }
        assertThat(seen)
                .as("each user needs their own code; see PickupCodes.code")
                .hasSizeGreaterThanOrEqualTo(19);
    }

    @Test
    void codeDiffersPerSecret() {
        PickupCodes other = new PickupCodes("another-secret-another-secret-0123456");
        assertThat(other.code(12, 100))
                .as("the secret is part of the key; see PickupCodes constructor")
                .isNotEqualTo(codes.code(12, 100));
    }

    @Test
    void alwaysSixDigits() {
        boolean leadingZero = false;
        for (long step = 0; step < 2000; step++) {
            String code = codes.code(7, step);
            assertThat(code).as("code is always 6 digits; see PickupCodes.code").matches("\\d{6}");
            leadingZero |= code.startsWith("0");
        }
        assertThat(leadingZero)
                .as("padding with zeros must be exercised; see PickupCodes.code")
                .isTrue();
    }

    @Test
    void timeStepIsEpochSecondDividedByThirty() {
        assertThat(PickupCodes.timeStep(0))
                .as("second 0; see PickupCodes.timeStep")
                .isZero();
        assertThat(PickupCodes.timeStep(29))
                .as("second 29; see PickupCodes.timeStep")
                .isZero();
        assertThat(PickupCodes.timeStep(30))
                .as("second 30; see PickupCodes.timeStep")
                .isEqualTo(1);
    }

    @Test
    void validForSecondsCountsDownToOne() {
        assertThat(PickupCodes.validForSeconds(60))
                .as("start of a step; see PickupCodes.validForSeconds")
                .isEqualTo(30);
        assertThat(PickupCodes.validForSeconds(70))
                .as("10 s into a step; see PickupCodes.validForSeconds")
                .isEqualTo(20);
        assertThat(PickupCodes.validForSeconds(89))
                .as("last second of a step; see PickupCodes.validForSeconds")
                .isEqualTo(1);
    }

    @Test
    void qrTextFormat() {
        assertThat(PickupCodes.qrText(12, "004211"))
                .as("QR text format; see PickupCodes.qrText")
                .isEqualTo("campus-pickup:1:12:004211");
    }

    @Test
    void pinnedValue() {
        // Guards the formula: if it changes, every pass printed or screenshotted in the docs is wrong too.
        assertThat(codes.code(12, 100)).as("pinned value; see PickupCodes.code").isEqualTo("248331");
    }
}
