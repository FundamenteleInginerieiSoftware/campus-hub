package com.campus.config;

import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Who may call what. Every HTTP request passes through the filter chain below before it reaches
 * any controller, so teammates never check "is the user logged in?" in their own code.
 *
 * <p>Liviu, Calin and Stefania only have to put admin endpoints under {@code /api/v1/admin/**};
 * they don't edit this file. The rules match docs/api-contract.md section 2.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain apiChain(HttpSecurity http) throws Exception {
        // The API keeps no session and uses no cookies: every request carries its own token.
        // CSRF attacks abuse cookies the browser sends automatically, so the protection isn't needed.
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Rules are checked top to bottom and the first match wins: specific paths go first.
                .authorizeHttpRequests(auth -> auth.requestMatchers("/api/v1/auth/**")
                        .permitAll() // ping, register, login
                        .requestMatchers("/actuator/health/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                        .permitAll() // AWS load balancer health check + Swagger UI
                        .requestMatchers("/error")
                        .permitAll() // Spring forwards failures here; if it were protected, a 404 would show as 401
                        // Must stay above "/api/v1/**", or that rule would match admin paths first
                        // and let students in.
                        .requestMatchers("/api/v1/admin/**")
                        .hasRole("ADMIN")
                        .requestMatchers("/api/v1/**")
                        .authenticated()
                        // A path nobody listed above is closed by default, so a forgotten rule
                        // fails safe instead of leaving something open.
                        .anyRequest()
                        .denyAll())
                // Reads "Authorization: Bearer <token>" and validates it with jwtDecoder() below.
                // Missing or invalid token -> 401 before the request reaches any controller.
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(rolesFromToken())));
        return http.build();
    }

    /**
     * Hashes passwords with BCrypt. The stored hash starts with "{bcrypt}", so we could switch to a
     * stronger algorithm later and old passwords would still be checked with the old one. Used by
     * register/login and the dev seeder.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /** Signs the tokens that login hands out, with the same secret the decoder checks them with. */
    @Bean
    JwtEncoder jwtEncoder(@Value("${app.jwt.secret}") String secret) {
        return NimbusJwtEncoder.withSecretKey(secretKey(secret))
                .algorithm(MacAlgorithm.HS256)
                .build();
    }

    /**
     * Verifies the token's signature with our secret (dev: application-dev.yml, prod: JWT_SECRET from
     * AWS). Defining this bean also stops Spring Boot from creating its default "user" with a random
     * password.
     */
    @Bean
    JwtDecoder jwtDecoder(@Value("${app.jwt.secret}") String secret) {
        return NimbusJwtDecoder.withSecretKey(secretKey(secret))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    // HS256 = one shared secret both signs and verifies. Only the backend knows it, so nobody else
    // can make a token that passes.
    private static SecretKey secretKey(String secret) {
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    // By default Spring reads permissions from a "scope" claim. Our tokens carry roles: ["ADMIN"]
    // instead, so read that claim and add the ROLE_ prefix that hasRole("ADMIN") looks for.
    private static JwtAuthenticationConverter rolesFromToken() {
        JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName("roles");
        roles.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(roles);
        return converter;
    }
}
