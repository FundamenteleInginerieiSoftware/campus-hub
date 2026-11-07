package com.campus.user.admin;

import com.campus.common.audit.AuditService;
import com.campus.common.error.BusinessRuleException;
import com.campus.common.error.InvalidParameterException;
import com.campus.common.error.NotFoundException;
import com.campus.common.paging.PageResponse;
import com.campus.common.security.CurrentUser;
import com.campus.user.api.Role;
import com.campus.user.domain.User;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * User administration: search, details, enable/disable, change role. Two rules protect the admins
 * (decisions.md, "User admin"):
 *
 * <ul>
 *   <li>an admin cannot disable or demote themselves: 409 {@code CANNOT_MODIFY_SELF};
 *   <li>the last enabled admin can never be disabled or demoted: 409 {@code LAST_ADMIN_REQUIRED}.
 * </ul>
 *
 * <p>Every real change is written to the audit log. Doing the same thing twice (disable a disabled
 * user, set the role it already has) returns the user unchanged and writes nothing.
 */
@Service
public class AdminUserService {

    static final int MAX_PAGE_SIZE = 100;
    static final int MAX_QUERY_LENGTH = 100;

    private static final char LIKE_ESCAPE = '\\';
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final AdminUserRepository adminUserRepository;
    private final CurrentUser currentUser;
    private final AuditService auditService;

    public AdminUserService(
            AdminUserRepository adminUserRepository, CurrentUser currentUser, AuditService auditService) {
        this.adminUserRepository = adminUserRepository;
        this.currentUser = currentUser;
        this.auditService = auditService;
    }

    /**
     * Admin list, newest first. {@code q} matches part of the email or the name (case-insensitive;
     * {@code %} and {@code _} are matched literally). {@code role} and {@code enabled} are optional filters.
     */
    @Transactional(readOnly = true)
    public PageResponse<AdminUserResponse> search(String q, Role role, Boolean enabled, int page, int size) {
        Pageable pageable = pageRequest(page, size);
        if (q != null && q.trim().length() > MAX_QUERY_LENGTH) {
            throw new InvalidParameterException("Search text must be at most " + MAX_QUERY_LENGTH + " characters");
        }
        return PageResponse.from(
                adminUserRepository.findAll(withFilters(q, role, enabled), pageable), AdminUserResponse::from);
    }

    @Transactional(readOnly = true)
    public AdminUserResponse get(Long userId) {
        return AdminUserResponse.from(adminUserRepository.findById(userId).orElseThrow(() -> notFound(userId)));
    }

    /** The account can no longer log in, and a token it already has is refused on the next request (#117). */
    @Transactional
    public AdminUserResponse disable(Long userId) {
        Long adminId = currentUser.id();
        if (userId.equals(adminId)) {
            throw new BusinessRuleException("CANNOT_MODIFY_SELF", "You cannot disable your own account");
        }

        List<User> enabledAdmins = adminUserRepository.lockEnabledByRole(Role.ADMIN);
        User user = adminUserRepository.findByIdForUpdate(userId).orElseThrow(() -> notFound(userId));

        if (!user.isEnabled()) {
            return AdminUserResponse.from(user);
        }
        if (user.getRole() == Role.ADMIN) {
            requireAnotherEnabledAdmin(enabledAdmins);
        }

        user.disable();
        auditService.record(adminId, "USER_DISABLED", "USER", userId, user.getEmail());
        return AdminUserResponse.from(user);
    }

    @Transactional
    public AdminUserResponse enable(Long userId) {
        User user = adminUserRepository.findByIdForUpdate(userId).orElseThrow(() -> notFound(userId));

        if (user.isEnabled()) {
            return AdminUserResponse.from(user);
        }

        user.enable();
        auditService.record(currentUser.id(), "USER_ENABLED", "USER", userId, user.getEmail());
        return AdminUserResponse.from(user);
    }

    /** STUDENT ↔ ADMIN. The new role counts from the user's next request (SecurityConfig reads it from the row). */
    @Transactional
    public AdminUserResponse changeRole(Long userId, Role newRole) {
        Long adminId = currentUser.id();
        if (userId.equals(adminId) && newRole != Role.ADMIN) {
            throw new BusinessRuleException("CANNOT_MODIFY_SELF", "You cannot remove your own admin role");
        }

        List<User> enabledAdmins = adminUserRepository.lockEnabledByRole(Role.ADMIN);
        User user = adminUserRepository.findByIdForUpdate(userId).orElseThrow(() -> notFound(userId));

        if (user.getRole() == newRole) {
            return AdminUserResponse.from(user);
        }
        if (user.getRole() == Role.ADMIN && user.isEnabled()) {
            requireAnotherEnabledAdmin(enabledAdmins);
        }

        Role oldRole = user.getRole();
        user.changeRole(newRole);
        auditService.record(
                adminId, "USER_ROLE_CHANGED", "USER", userId, user.getEmail() + ": " + oldRole + " -> " + newRole);
        return AdminUserResponse.from(user);
    }

    /**
     * Called only when the target is an enabled admin, so it is in the locked list. With the self
     * check above, this can only fail when two admins act on each other at the same moment: the
     * second one waits at the lock and then finds just one admin left.
     */
    private static void requireAnotherEnabledAdmin(List<User> enabledAdmins) {
        if (enabledAdmins.size() <= 1) {
            throw new BusinessRuleException("LAST_ADMIN_REQUIRED", "At least one enabled admin must remain");
        }
    }

    // Same limits as the other lists in the app: 400 instead of a 500 from Hibernate (#146).
    static Pageable pageRequest(int page, int size) {
        if (page < 0) {
            throw new InvalidParameterException("Page index must not be negative");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidParameterException("Page size must be between 1 and " + MAX_PAGE_SIZE);
        }
        if ((long) page * size > Integer.MAX_VALUE) {
            throw new InvalidParameterException("Page offset exceeds maximum supported value");
        }
        return PageRequest.of(page, size, NEWEST_FIRST);
    }

    // Criteria predicates instead of "(:q is null or ...)" in JPQL, like Liviu's AssetSpecifications.
    static Specification<User> withFilters(String q, Role role, Boolean enabled) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (q != null && !q.isBlank()) {
                String pattern = "%" + escapeLike(q.trim().toLowerCase(Locale.ROOT)) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("email")), pattern, LIKE_ESCAPE),
                        cb.like(cb.lower(root.get("fullName")), pattern, LIKE_ESCAPE)));
            }
            if (role != null) {
                predicates.add(cb.equal(root.get("role"), role));
            }
            if (enabled != null) {
                predicates.add(cb.equal(root.get("enabled"), enabled));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    // "50%" or "a_b" must match literally, not as LIKE wildcards.
    static String escapeLike(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static NotFoundException notFound(Long userId) {
        return new NotFoundException("USER_NOT_FOUND", "User " + userId + " not found");
    }
}
