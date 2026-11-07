package com.campus.user.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.campus.common.audit.AuditService;
import com.campus.common.error.BusinessRuleException;
import com.campus.common.error.InvalidParameterException;
import com.campus.common.error.NotFoundException;
import com.campus.common.paging.PageResponse;
import com.campus.common.security.CurrentUser;
import com.campus.user.api.Role;
import com.campus.user.domain.User;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {

    private static final Long ME = 1L; // the logged-in admin
    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 10, 20, 9, 0);

    @Mock
    private AdminUserRepository adminUserRepository;

    @Mock
    private CurrentUser currentUser;

    @Mock
    private AuditService auditService;

    private AdminUserService service;

    @BeforeEach
    void setUp() {
        service = new AdminUserService(adminUserRepository, currentUser, auditService);
    }

    private static User user(Long id, Role role, boolean enabled) {
        User user = new User("user" + id + "@campus.test", "hash", "User " + id, role, CREATED);
        ReflectionTestUtils.setField(user, "id", id);
        if (!enabled) {
            user.disable();
        }
        return user;
    }

    private void loggedInAs(Long id) {
        when(currentUser.id()).thenReturn(id);
    }

    private void givenLockedUser(User user) {
        when(adminUserRepository.findByIdForUpdate(user.getId())).thenReturn(Optional.of(user));
    }

    private void givenEnabledAdmins(User... admins) {
        when(adminUserRepository.lockEnabledByRole(Role.ADMIN)).thenReturn(List.of(admins));
    }

    // ---- search ------------------------------------------------------------------------------

    @Nested
    class Search {

        @Test
        void returnsAPage_newestFirst() {
            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            when(adminUserRepository.findAll(any(Specification.class), pageable.capture()))
                    .thenAnswer(invocation ->
                            new PageImpl<>(List.of(user(5L, Role.STUDENT, true)), invocation.getArgument(1), 1));

            PageResponse<AdminUserResponse> result = service.search("user5", Role.STUDENT, true, 0, 20);

            assertThat(result.content()).extracting(AdminUserResponse::id).containsExactly(5L);
            assertThat(result.content().getFirst().enabled()).isTrue();
            assertThat(result.content().getFirst().createdAt()).isEqualTo(CREATED);
            assertThat(pageable.getValue().getPageSize()).isEqualTo(20);
            assertThat(pageable.getValue().getSort())
                    .containsExactly(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        }

        @ParameterizedTest(name = "page={0}, size={1}")
        @CsvSource({
            "-1, 20, Page index must not be negative",
            "0, 0, Page size must be between 1 and 100",
            "0, 101, Page size must be between 1 and 100",
            "999999999, 100, Page offset exceeds maximum supported value"
        })
        void badPageOrSize_isA400(int page, int size, String message) {
            assertThatThrownBy(() -> service.search(null, null, null, page, size))
                    .isInstanceOf(InvalidParameterException.class)
                    .hasMessage(message)
                    .extracting("code")
                    .isEqualTo("INVALID_PARAMETER");
            verifyNoInteractions(adminUserRepository);
        }

        @Test
        void searchTextOver100Characters_isA400() {
            assertThatThrownBy(() -> service.search("x".repeat(101), null, null, 0, 20))
                    .isInstanceOf(InvalidParameterException.class)
                    .hasMessage("Search text must be at most 100 characters");
            verifyNoInteractions(adminUserRepository);
        }

        @Test
        void likeWildcardsAreEscaped() {
            assertThat(AdminUserService.escapeLike("50%_a\\b")).isEqualTo("50\\%\\_a\\\\b");
        }
    }

    // ---- get ---------------------------------------------------------------------------------

    @Nested
    class Get {

        @Test
        void returnsTheUser_withoutThePasswordHash() {
            when(adminUserRepository.findById(5L)).thenReturn(Optional.of(user(5L, Role.STUDENT, false)));

            AdminUserResponse response = service.get(5L);

            assertThat(response)
                    .isEqualTo(new AdminUserResponse(5L, "user5@campus.test", "User 5", Role.STUDENT, false, CREATED));
        }

        @Test
        void unknownId_isA404() {
            when(adminUserRepository.findById(404L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.get(404L))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessage("User 404 not found")
                    .extracting("code")
                    .isEqualTo("USER_NOT_FOUND");
        }
    }

    // ---- disable -----------------------------------------------------------------------------

    @Nested
    class Disable {

        @Test
        void disablesAStudent_andWritesTheAuditLog() {
            loggedInAs(ME);
            User student = user(5L, Role.STUDENT, true);
            givenEnabledAdmins(user(ME, Role.ADMIN, true));
            givenLockedUser(student);

            AdminUserResponse response = service.disable(5L);

            assertThat(response.enabled()).isFalse();
            assertThat(student.isEnabled()).isFalse();
            verify(auditService).record(ME, "USER_DISABLED", "USER", 5L, "user5@campus.test");
        }

        // Admins first, then the target, always: the lock order that prevents deadlocks.
        @Test
        void locksTheAdminsBeforeTheTarget() {
            loggedInAs(ME);
            givenEnabledAdmins(user(ME, Role.ADMIN, true));
            givenLockedUser(user(5L, Role.STUDENT, true));

            service.disable(5L);

            InOrder order = inOrder(adminUserRepository, auditService);
            order.verify(adminUserRepository).lockEnabledByRole(Role.ADMIN);
            order.verify(adminUserRepository).findByIdForUpdate(5L);
            order.verify(auditService).record(anyLong(), anyString(), anyString(), anyLong(), anyString());
        }

        @Test
        void disablesAnotherAdmin_whenOneAdminIsLeft() {
            loggedInAs(ME);
            User otherAdmin = user(2L, Role.ADMIN, true);
            givenEnabledAdmins(user(ME, Role.ADMIN, true), otherAdmin);
            givenLockedUser(otherAdmin);

            assertThat(service.disable(2L).enabled()).isFalse();
        }

        @Test
        void yourself_isCannotModifySelf_andLocksNothing() {
            loggedInAs(ME);

            assertThatThrownBy(() -> service.disable(ME))
                    .isInstanceOf(BusinessRuleException.class)
                    .hasMessage("You cannot disable your own account")
                    .extracting("code")
                    .isEqualTo("CANNOT_MODIFY_SELF");
            verifyNoInteractions(adminUserRepository, auditService);
        }

        // Possible only in a race: the other admin was demoted a moment ago, so this admin is the last one.
        @Test
        void theLastEnabledAdmin_isLastAdminRequired() {
            loggedInAs(ME);
            User lastAdmin = user(2L, Role.ADMIN, true);
            givenEnabledAdmins(lastAdmin);
            givenLockedUser(lastAdmin);

            assertThatThrownBy(() -> service.disable(2L))
                    .isInstanceOf(BusinessRuleException.class)
                    .extracting("code")
                    .isEqualTo("LAST_ADMIN_REQUIRED");
            assertThat(lastAdmin.isEnabled()).isTrue();
            verifyNoInteractions(auditService);
        }

        @Test
        void alreadyDisabled_returnsItUnchanged_andWritesNothing() {
            loggedInAs(ME);
            givenEnabledAdmins(user(ME, Role.ADMIN, true));
            givenLockedUser(user(5L, Role.STUDENT, false));

            assertThat(service.disable(5L).enabled()).isFalse();
            verifyNoInteractions(auditService);
        }

        @Test
        void unknownId_isA404() {
            loggedInAs(ME);
            givenEnabledAdmins(user(ME, Role.ADMIN, true));
            when(adminUserRepository.findByIdForUpdate(404L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.disable(404L))
                    .isInstanceOf(NotFoundException.class)
                    .extracting("code")
                    .isEqualTo("USER_NOT_FOUND");
            verifyNoInteractions(auditService);
        }
    }

    // ---- enable ------------------------------------------------------------------------------

    @Nested
    class Enable {

        @Test
        void enablesADisabledUser_andWritesTheAuditLog() {
            loggedInAs(ME);
            User student = user(5L, Role.STUDENT, false);
            givenLockedUser(student);

            AdminUserResponse response = service.enable(5L);

            assertThat(response.enabled()).isTrue();
            verify(auditService).record(ME, "USER_ENABLED", "USER", 5L, "user5@campus.test");
            verify(adminUserRepository, never()).lockEnabledByRole(any());
        }

        @Test
        void alreadyEnabled_returnsItUnchanged_andWritesNothing() {
            givenLockedUser(user(5L, Role.STUDENT, true));

            assertThat(service.enable(5L).enabled()).isTrue();
            verifyNoInteractions(auditService);
        }

        @Test
        void unknownId_isA404() {
            when(adminUserRepository.findByIdForUpdate(404L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.enable(404L))
                    .isInstanceOf(NotFoundException.class)
                    .extracting("code")
                    .isEqualTo("USER_NOT_FOUND");
        }
    }

    // ---- change role -------------------------------------------------------------------------

    @Nested
    class ChangeRole {

        @Test
        void promotesAStudentToAdmin_andWritesOldAndNewRole() {
            loggedInAs(ME);
            User student = user(5L, Role.STUDENT, true);
            givenEnabledAdmins(user(ME, Role.ADMIN, true));
            givenLockedUser(student);

            AdminUserResponse response = service.changeRole(5L, Role.ADMIN);

            assertThat(response.role()).isEqualTo(Role.ADMIN);
            verify(auditService).record(ME, "USER_ROLE_CHANGED", "USER", 5L, "user5@campus.test: STUDENT -> ADMIN");
        }

        @Test
        void demotesAnotherAdmin_whenAnotherAdminIsLeft() {
            loggedInAs(ME);
            User otherAdmin = user(2L, Role.ADMIN, true);
            givenEnabledAdmins(user(ME, Role.ADMIN, true), otherAdmin);
            givenLockedUser(otherAdmin);

            assertThat(service.changeRole(2L, Role.STUDENT).role()).isEqualTo(Role.STUDENT);
            verify(auditService).record(ME, "USER_ROLE_CHANGED", "USER", 2L, "user2@campus.test: ADMIN -> STUDENT");
        }

        // A disabled admin does not count as an admin, so demoting it never needs another admin.
        @Test
        void demotesADisabledAdmin_evenWhenOnlyOneEnabledAdminExists() {
            loggedInAs(ME);
            User disabledAdmin = user(2L, Role.ADMIN, false);
            givenEnabledAdmins(user(ME, Role.ADMIN, true));
            givenLockedUser(disabledAdmin);

            assertThat(service.changeRole(2L, Role.STUDENT).role()).isEqualTo(Role.STUDENT);
        }

        @Test
        void demotingYourself_isCannotModifySelf_andLocksNothing() {
            loggedInAs(ME);

            assertThatThrownBy(() -> service.changeRole(ME, Role.STUDENT))
                    .isInstanceOf(BusinessRuleException.class)
                    .hasMessage("You cannot remove your own admin role")
                    .extracting("code")
                    .isEqualTo("CANNOT_MODIFY_SELF");
            verifyNoInteractions(adminUserRepository, auditService);
        }

        // Setting your own role to ADMIN (which you already have) is harmless: unchanged, nothing written.
        @Test
        void settingYourOwnRoleToAdmin_returnsItUnchanged() {
            loggedInAs(ME);
            User me = user(ME, Role.ADMIN, true);
            givenEnabledAdmins(me);
            givenLockedUser(me);

            assertThat(service.changeRole(ME, Role.ADMIN).role()).isEqualTo(Role.ADMIN);
            verifyNoInteractions(auditService);
        }

        @Test
        void demotingTheLastEnabledAdmin_isLastAdminRequired() {
            loggedInAs(ME);
            User lastAdmin = user(2L, Role.ADMIN, true);
            givenEnabledAdmins(lastAdmin);
            givenLockedUser(lastAdmin);

            assertThatThrownBy(() -> service.changeRole(2L, Role.STUDENT))
                    .isInstanceOf(BusinessRuleException.class)
                    .hasMessage("At least one enabled admin must remain")
                    .extracting("code")
                    .isEqualTo("LAST_ADMIN_REQUIRED");
            assertThat(lastAdmin.getRole()).isEqualTo(Role.ADMIN);
            verifyNoInteractions(auditService);
        }

        @Test
        void sameRole_returnsItUnchanged_andWritesNothing() {
            loggedInAs(ME);
            givenEnabledAdmins(user(ME, Role.ADMIN, true));
            givenLockedUser(user(5L, Role.STUDENT, true));

            assertThat(service.changeRole(5L, Role.STUDENT).role()).isEqualTo(Role.STUDENT);
            verifyNoInteractions(auditService);
        }

        @Test
        void unknownId_isA404() {
            loggedInAs(ME);
            givenEnabledAdmins(user(ME, Role.ADMIN, true));
            when(adminUserRepository.findByIdForUpdate(404L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.changeRole(404L, Role.ADMIN))
                    .isInstanceOf(NotFoundException.class)
                    .extracting("code")
                    .isEqualTo("USER_NOT_FOUND");
        }
    }
}
