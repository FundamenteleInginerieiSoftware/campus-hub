package com.campus.user.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.campus.user.api.Role;
import com.campus.user.api.UserApi;
import com.campus.user.domain.User;
import com.campus.user.repository.UserRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Calin's booking code loads the user, waits on the per-user advisory lock, then asks
 * {@code findActiveRole} again to catch an account disabled meanwhile. All of it runs in one
 * transaction, so findActiveRole must read the row again instead of returning the cached User.
 */
@SpringBootTest
class FindActiveRoleFreshReadIT {

    @Autowired
    UserApi userApi;

    @Autowired
    UserRepository userRepository;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void findActiveRoleSeesAnAccountDisabledAfterTheCallerLoadedIt() {
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        TransactionTemplate other = new TransactionTemplate(transactionManager);
        other.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        Long id = userRepository
                .save(new User(
                        "fresh-read@findactiverole.test",
                        "{noop}x",
                        "Fresh Read",
                        Role.STUDENT,
                        LocalDateTime.of(2026, 11, 19, 9, 0)))
                .getId();
        try {
            outer.executeWithoutResult(status -> {
                // Puts the User into this transaction's persistence context.
                userRepository.findById(id).orElseThrow();
                // Another transaction disables the account and commits, like an admin would.
                other.executeWithoutResult(
                        s -> userRepository.findById(id).orElseThrow().disable());

                assertThat(userApi.findActiveRole(id))
                        .as("disabled while the caller's transaction was open: findActiveRole must read the row"
                                + " again, not the cached User; see UserService.findActiveRole")
                        .isEmpty();
            });
        } finally {
            userRepository.deleteById(id);
        }
    }
}
