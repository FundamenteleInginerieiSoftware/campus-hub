package com.campus.user.repository;

import com.campus.user.domain.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

// Spring Data writes the SQL from the method names. IgnoreCase means a login typed as
// "Student1@Campus.test" still finds the stored "student1@campus.test".
public interface UserRepository extends JpaRepository<User, Long> {

    // Login: find the account to check its password.
    Optional<User> findByEmailIgnoreCase(String email);

    // Register: refuse an email that is already taken (409 EMAIL_TAKEN).
    boolean existsByEmailIgnoreCase(String email);
}
