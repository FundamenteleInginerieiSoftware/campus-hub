package com.campus.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.campus.common.error.NotFoundException;
import com.campus.user.api.Role;
import com.campus.user.api.UserSummary;
import com.campus.user.domain.User;
import com.campus.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

// Plain Mockito test: no Spring context and no database, so it runs in milliseconds.
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserService userService;

    @Test
    void getByIdReturnsSummaryWithoutPassword() {
        User user = new User(
                "Student1@Campus.test",
                "{bcrypt}hash",
                "Student One",
                Role.STUDENT,
                LocalDateTime.of(2026, 11, 2, 9, 0));
        // The database sets the id on insert; here there is no database, so we set it by hand.
        ReflectionTestUtils.setField(user, "id", 5L);
        when(userRepository.findById(5L)).thenReturn(Optional.of(user));

        UserSummary summary = userService.getById(5L);

        assertThat(summary).isEqualTo(new UserSummary(5L, "student1@campus.test", "Student One", Role.STUDENT));
    }

    @Test
    void getByIdThrowsUserNotFoundForUnknownId() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getById(99L))
                .isInstanceOf(NotFoundException.class)
                .extracting("code")
                .isEqualTo("USER_NOT_FOUND");
    }

    @Test
    void existsAsksTheRepository() {
        when(userRepository.existsById(5L)).thenReturn(true);
        when(userRepository.existsById(99L)).thenReturn(false);

        assertThat(userService.exists(5L)).isTrue();
        assertThat(userService.exists(99L)).isFalse();
    }
}
