package com.campus.config;

import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Who may call what. Every HTTP request passes through one of the filter chains below before it
 * reaches any controller, so teammates never check "is the user logged in?" in their own code.
 *
 * <p>Week 1 version: a few public paths, everything else needs a valid JWT. In week 3 this gets login
 * (the JWT encoder), roles, and the rule that protects {@code /api/v1/admin/**}. Liviu, Calin and
 * Stefania then only have to put admin endpoints under that prefix; they don't edit this file.
 */
@Configuration
public class SecurityConfig {

    /**
     * Dev only. The H2 console is a small web app that runs inside an iframe and posts forms, which
     * the API rules below would block. Spring checks chains by {@code @Order} and uses the first one
     * whose matcher fits, so this one catches /h2-console before the API chain sees it.
     */
    @Bean
    @Order(0)
    @Profile("dev")
    SecurityFilterChain h2ConsoleChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/h2-console/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable)
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()));
        return http.build();
    }

    @Bean
    @Order(1)
    SecurityFilterChain apiChain(HttpSecurity http) throws Exception {
        // The API keeps no session and uses no cookies: every request carries its own token.
        // CSRF attacks abuse cookies the browser sends automatically, so the protection isn't needed.
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Rules are checked top to bottom and the first match wins: specific paths go first.
                .authorizeHttpRequests(auth -> auth.requestMatchers("/api/v1/auth/**")
                        .permitAll() // ping now; register and login in week 3
                        .requestMatchers("/actuator/health/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                        .permitAll() // AWS load balancer health check + Swagger UI
                        .requestMatchers("/error")
                        .permitAll() // Spring forwards failures here; if it were protected, a 404 would show as 401
                        .anyRequest()
                        .authenticated())
                // Reads "Authorization: Bearer <token>" and validates it with jwtDecoder() below.
                // Missing or invalid token -> 401 before the request reaches any controller.
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
        return http.build();
    }

    /**
     * Verifies the token's signature with our secret (dev: application-dev.yml, prod: JWT_SECRET from
     * AWS). Defining this bean also stops Spring Boot from creating its default "user" with a random
     * password.
     */
    @Bean
    JwtDecoder jwtDecoder(@Value("${app.jwt.secret}") String secret) {
        SecretKey key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }
}
