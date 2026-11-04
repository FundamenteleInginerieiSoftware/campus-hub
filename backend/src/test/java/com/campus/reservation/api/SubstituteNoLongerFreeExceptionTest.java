package com.campus.reservation.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SubstituteNoLongerFreeExceptionTest {

    @Test
    @DisplayName("Constructor with message initializes exception correctly")
    void constructorWithMessage() {
        SubstituteNoLongerFreeException ex =
                new SubstituteNoLongerFreeException("Substitute asset 10 is no longer free");

        assertThat(ex.getMessage()).isEqualTo("Substitute asset 10 is no longer free");
        assertThat(ex.getCause()).isNull();
    }

    @Test
    @DisplayName("Constructor with message and cause initializes exception correctly")
    void constructorWithMessageAndCause() {
        IllegalStateException cause = new IllegalStateException("Slot conflict");
        SubstituteNoLongerFreeException ex =
                new SubstituteNoLongerFreeException("Substitute asset 10 is no longer free", cause);

        assertThat(ex.getMessage()).isEqualTo("Substitute asset 10 is no longer free");
        assertThat(ex.getCause()).isSameAs(cause);
    }
}
