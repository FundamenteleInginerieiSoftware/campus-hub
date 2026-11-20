package com.campus.user.repository;

import com.campus.user.api.Role;
import com.campus.user.domain.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// Spring Data writes the SQL from the method names. IgnoreCase means a login typed as
// "Student1@Campus.test" still finds the stored "student1@campus.test".
public interface UserRepository extends JpaRepository<User, Long> {

    // Login: find the account to check its password.
    Optional<User> findByEmailIgnoreCase(String email);

    // Register: refuse an email that is already taken (409 EMAIL_TAKEN).
    boolean existsByEmailIgnoreCase(String email);

    // Role of an enabled user, empty if disabled or missing. A scalar query always goes to the
    // database (scalar results never come from the persistence context), unlike
    // findById, which returns the copy the caller's transaction already loaded.
    @Query("select u.role from User u where u.id = :id and u.enabled = true")
    Optional<Role> findActiveRoleById(@Param("id") Long id);
}
