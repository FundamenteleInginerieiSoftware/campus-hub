package com.campus.user.admin;

import com.campus.common.paging.PageResponse;
import com.campus.user.api.Role;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// Under /api/v1/admin/**, so SecurityConfig lets only ADMIN in (403 for students, 401 without a token).
@RestController
@RequestMapping("/api/v1/admin/users")
@Tag(name = "Admin Users", description = "Search users, enable or disable accounts, change roles")
public class AdminUserController {

    private final AdminUserService adminUserService;

    public AdminUserController(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    @GetMapping
    @Operation(summary = "Search users by email or name, optionally filtered by role and enabled; newest first")
    public PageResponse<AdminUserResponse> search(
            @Parameter(description = "Part of the email or full name, max 100 characters")
                    @RequestParam(value = "q", required = false)
                    String q,
            @RequestParam(value = "role", required = false) Role role,
            @RequestParam(value = "enabled", required = false) Boolean enabled,
            @Parameter(description = "Page index, from 0") @RequestParam(value = "page", defaultValue = "0") int page,
            @Parameter(description = "Page size, 1 to 100") @RequestParam(value = "size", defaultValue = "20")
                    int size) {
        return adminUserService.search(q, role, enabled, page, size);
    }

    @GetMapping("/{id}")
    @Operation(summary = "One user")
    public AdminUserResponse get(@PathVariable("id") Long id) {
        return adminUserService.get(id);
    }

    // No body. Disabling twice returns the user unchanged.
    @PostMapping("/{id}/disable")
    @Operation(summary = "Disable an account (not yourself, never the last admin); its token stops working at once")
    public AdminUserResponse disable(@PathVariable("id") Long id) {
        return adminUserService.disable(id);
    }

    @PostMapping("/{id}/enable")
    @Operation(summary = "Enable a disabled account")
    public AdminUserResponse enable(@PathVariable("id") Long id) {
        return adminUserService.enable(id);
    }

    @PutMapping("/{id}/role")
    @Operation(summary = "Change the role (STUDENT or ADMIN); you cannot demote yourself or the last admin")
    public AdminUserResponse changeRole(@PathVariable("id") Long id, @Valid @RequestBody ChangeRoleRequest request) {
        return adminUserService.changeRole(id, request.role());
    }
}
