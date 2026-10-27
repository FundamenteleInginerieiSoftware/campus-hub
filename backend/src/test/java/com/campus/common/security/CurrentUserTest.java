package com.campus.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campus.common.error.UnauthorizedException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class CurrentUserTest {

    private final CurrentUser currentUser = new CurrentUser();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void loginAs(String userId, String authority) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject(userId)
                .build();
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority(authority))));
    }

    @Test
    void readsIdAndAdminRoleFromJwt() {
        loginAs("7", "ROLE_ADMIN");

        assertThat(currentUser.id()).isEqualTo(7L);
        assertThat(currentUser.isAdmin()).isTrue();
    }

    @Test
    void studentIsNotAdmin() {
        loginAs("3", "ROLE_STUDENT");

        assertThat(currentUser.id()).isEqualTo(3L);
        assertThat(currentUser.isAdmin()).isFalse();
    }

    @Test
    void noTokenGives401() {
        assertThatThrownBy(currentUser::id)
                .isInstanceOf(UnauthorizedException.class)
                .extracting("code")
                .isEqualTo("UNAUTHORIZED");
    }
}
