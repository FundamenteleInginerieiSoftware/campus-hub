package com.campus.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campus.user.api.Role;
import com.campus.user.domain.User;
import com.campus.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Starts the whole app and checks which paths are public, which need a token and which need ADMIN.
 * The paths don't have to exist: if security lets a request through, an unknown path ends in 404.
 */
@SpringBootTest
@AutoConfigureMockMvc
// Rolled back after each test: the users saved by bearer() don't stay in campus_test.
@Transactional
class SecurityConfigTest {

    // Bug fixed (#67): the probes used /api/v1/assets and /api/v1/admin/assets. Once Liviu mapped
    // real endpoints there, a request that passed security got 405 or 200 instead of the expected 404.
    // Fix: probe paths that no controller will ever map, so 404 always means "security let it through".
    private static final String PROBE = "/api/v1/_security-probe";
    private static final String ADMIN_PROBE = "/api/v1/admin/_security-probe";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtEncoder jwtEncoder;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    UserRepository userRepository;

    @Test
    void healthCheckIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void swaggerIsPublic() throws Exception {
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }

    @Test
    void swaggerOffersBearerLogin() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme")
                        .value("bearer"));
    }

    @Test
    void otherEndpointsNeedAToken() throws Exception {
        mockMvc.perform(get(PROBE))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.instance").value(PROBE));
    }

    // #118: the firewall blocks ";" in a URL before any other filter; it must still answer with our JSON.
    @Test
    void firewallRejectionIsProblemJson() throws Exception {
        mockMvc.perform(get("/api/v1/auth/ping;x=1"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("REQUEST_REJECTED"));
    }

    @Test
    void invalidTokenIsRejected() throws Exception {
        mockMvc.perform(get(PROBE).header("Authorization", "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    // #116: an old or broken token must not block the public auth endpoints.
    @Test
    void authEndpointsIgnoreAnInvalidToken() throws Exception {
        mockMvc.perform(get("/api/v1/auth/ping").header("Authorization", "Bearer expired-or-broken"))
                .andExpect(status().isOk());
    }

    @Test
    void studentPassesSecurityOnNormalPaths() throws Exception {
        mockMvc.perform(get(PROBE).header("Authorization", bearer("STUDENT"))).andExpect(status().isNotFound());
    }

    @Test
    void studentIsForbiddenOnAdminPaths() throws Exception {
        mockMvc.perform(get(ADMIN_PROBE).header("Authorization", bearer("STUDENT")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void adminPassesSecurityOnAdminPaths() throws Exception {
        mockMvc.perform(get(ADMIN_PROBE).header("Authorization", bearer("ADMIN")))
                .andExpect(status().isNotFound());
    }

    // Since the Dockerfile PR, a GET outside /api is open (the React app, see SpaFrontendTest).
    // Everything else outside /api/v1 must still be closed, even for an admin.
    @Test
    void otherApiPathsAreDeniedEvenWithAToken() throws Exception {
        mockMvc.perform(get("/api/v2/something").header("Authorization", bearer("ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void writesOutsideTheApiAreDeniedEvenWithAToken() throws Exception {
        mockMvc.perform(post("/something-else").header("Authorization", bearer("ADMIN")))
                .andExpect(status().isForbidden());
    }

    // #117: the account is checked on every request, not only at login.
    @Test
    void disabledUserIsRejectedAlthoughTheTokenIsValid() throws Exception {
        User user = newUser(Role.STUDENT);
        ReflectionTestUtils.setField(user, "enabled", false);
        Long id = userRepository.save(user).getId();

        mockMvc.perform(get(PROBE).header("Authorization", bearer(id, "STUDENT")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void deletedUserIsRejectedAlthoughTheTokenIsValid() throws Exception {
        mockMvc.perform(get(PROBE).header("Authorization", bearer(999_999L, "STUDENT")))
                .andExpect(status().isUnauthorized());
    }

    // The token says ADMIN (e.g. issued before a demotion), the database says STUDENT: the database wins.
    @Test
    void roleComesFromTheDatabaseNotFromTheToken() throws Exception {
        Long studentId = userRepository.save(newUser(Role.STUDENT)).getId();

        mockMvc.perform(get(ADMIN_PROBE).header("Authorization", bearer(studentId, "ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void passwordsAreStoredAsBcryptHashes() {
        String hash = passwordEncoder.encode("password");

        assertThat(hash).startsWith("{bcrypt}");
        assertThat(passwordEncoder.matches("password", hash)).isTrue();
        assertThat(passwordEncoder.matches("wrong", hash)).isFalse();
    }

    // A real user with this role in the database, and a token for it. Since #117 the token alone
    // isn't enough: SecurityConfig looks the user up on every request.
    private String bearer(String role) {
        Long id = userRepository.save(newUser(Role.valueOf(role))).getId();
        return bearer(id, role);
    }

    private static User newUser(Role role) {
        return new User(
                role + "-" + UUID.randomUUID() + "@campus.test",
                "{bcrypt}unused",
                "Security Test",
                role,
                LocalDateTime.of(2026, 11, 4, 12, 0));
    }

    // Same claims login will put in real tokens (docs/api-contract.md section 3). No "exp" here,
    // so the test doesn't need the clock; the decoder only checks expiry when the claim is present.
    private String bearer(Long userId, String role) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(String.valueOf(userId))
                .claim("roles", List.of(role))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return "Bearer "
                + jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
