package com.campus.user.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.campus.user.api.Role;
import com.campus.user.domain.User;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Plain unit test, no Spring: checks the validation rules of api-contract §3 on the records. */
class AuthRequestsTest {

    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsAValidRegistration() {
        RegisterRequest request = new RegisterRequest("ana@campus.test", "password1", "Ana Pop");

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void rejectsBadEmailShortPasswordAndBlankName() {
        RegisterRequest request = new RegisterRequest("not-an-email", "short", " ");

        assertThat(invalidFields(validator.validate(request)))
                .containsExactlyInAnyOrder("email", "password", "fullName");
    }

    @Test
    void rejectsPasswordLongerThan72Characters() {
        RegisterRequest request = new RegisterRequest("ana@campus.test", "a".repeat(73), "Ana Pop");

        // 73 ASCII letters are also 73 bytes, so the byte check fails too.
        assertThat(invalidFields(validator.validate(request)))
                .containsExactlyInAnyOrder("password", "passwordWithinBcryptLimit");
    }

    @Test
    void rejectsPasswordLongerThan72BytesEvenIfShortInCharacters() {
        // 40 characters pass @Size, but "ș" takes 2 bytes in UTF-8: 80 bytes, too long for BCrypt.
        RegisterRequest tooLong = new RegisterRequest("ana@campus.test", "ș".repeat(40), "Ana Pop");
        RegisterRequest exactly72Bytes = new RegisterRequest("ana@campus.test", "ș".repeat(36), "Ana Pop");

        assertThat(invalidFields(validator.validate(tooLong))).containsExactly("passwordWithinBcryptLimit");
        assertThat(validator.validate(exactly72Bytes)).isEmpty();
    }

    @Test
    void loginOnlyRequiresBothFields() {
        assertThat(validator.validate(new LoginRequest("anything", "x"))).isEmpty();
        assertThat(invalidFields(validator.validate(new LoginRequest("", ""))))
                .containsExactlyInAnyOrder("email", "password");
    }

    @Test
    void userResponseNeverContainsThePasswordHash() {
        User user = new User(
                "Ana@Campus.test", "{bcrypt}hash", "Ana Pop", Role.STUDENT, LocalDateTime.of(2026, 10, 21, 12, 0));

        UserResponse response = UserResponse.from(user);

        assertThat(response).isEqualTo(new UserResponse(null, "ana@campus.test", "Ana Pop", Role.STUDENT));
    }

    private static <T> Set<String> invalidFields(Set<ConstraintViolation<T>> violations) {
        return violations.stream().map(v -> v.getPropertyPath().toString()).collect(Collectors.toSet());
    }
}
