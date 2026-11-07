package com.campus.user.admin;

import com.campus.user.api.Role;
import com.campus.user.domain.User;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The users table as the admin pages need it: search with filters, and row locks for the
 * "never remove the last admin" rule. Matei's {@code UserRepository} stays for login and register.
 */
public interface AdminUserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {

    /**
     * Locks every enabled admin (SELECT ... FOR UPDATE), always in id order. Two admins demoting or
     * disabling each other at the same moment both start here: the second one waits until the first
     * commits, then sees one admin fewer. The fixed order means two such requests can never deadlock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.role = :role AND u.enabled = true ORDER BY u.id")
    List<User> lockEnabledByRole(@Param("role") Role role);

    /** The user to change, locked until the end of the transaction. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);
}
