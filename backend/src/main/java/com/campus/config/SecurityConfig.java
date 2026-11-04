package com.campus.config;

import com.campus.user.api.Role;
import com.campus.user.api.UserApi;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.firewall.RequestRejectedHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import tools.jackson.databind.json.JsonMapper;

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
    SecurityFilterChain apiChain(HttpSecurity http, JsonMapper jsonMapper, ObjectProvider<UserApi> users)
            throws Exception {
        // Turns 401 and 403 into ProblemDetail JSON instead of an empty body.
        SecurityErrorHandler errors = new SecurityErrorHandler(jsonMapper);

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
                        // Other API paths and actuator endpoints stay closed. This must stay above
                        // the GET rule below, or that rule would open them.
                        .requestMatchers("/api/**", "/actuator/**")
                        .denyAll()
                        // The React app in prod (ADR-002, SpaConfig): index.html, /assets/*.js and
                        // pages like /reservations. They hold no data (all data comes from /api, which
                        // needs a token), so anyone may load them, like any website's login page.
                        .requestMatchers(HttpMethod.GET, "/**")
                        .permitAll()
                        // A path nobody listed above is closed by default, so a forgotten rule
                        // fails safe instead of leaving something open.
                        .anyRequest()
                        .denyAll())
                // Reads "Authorization: Bearer <token>" and validates it with jwtDecoder() below.
                // An invalid or expired token is answered here, with our 401 JSON.
                .oauth2ResourceServer(oauth2 -> oauth2.bearerTokenResolver(ignoreTokenOnAuthPaths())
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(activeUserFromToken(users)))
                        .authenticationEntryPoint(errors))
                // No token at all (401), or a role that isn't enough (403).
                .exceptionHandling(
                        handling -> handling.authenticationEntryPoint(errors).accessDeniedHandler(errors));
        return http.build();
    }

    /**
     * Answers URLs that Spring Security's firewall blocks (e.g. {@code /api/v1/..;/x}) with our
     * ProblemDetail JSON. Spring finds this bean by its type and uses it instead of its default.
     */
    @Bean
    RequestRejectedHandler requestRejectedHandler(JsonMapper jsonMapper) {
        return new SecurityErrorHandler(jsonMapper);
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

    // Bug fixed (#116): login and register answered 401 when the browser still sent an old token.
    // The token filter runs before the permitAll() rules and rejects any invalid token it finds, and
    // Stefania's API client sends the saved token on every call, so an expired one blocked the login
    // that would replace it. Fix: on /api/v1/auth/** don't read the header at all (these paths need
    // no token); everywhere else read it as before.
    private static BearerTokenResolver ignoreTokenOnAuthPaths() {
        RequestMatcher authPaths = PathPatternRequestMatcher.withDefaults().matcher("/api/v1/auth/**");
        DefaultBearerTokenResolver header = new DefaultBearerTokenResolver();
        return request -> authPaths.matches(request) ? null : header.resolve(request);
    }

    // Turns a valid token into the logged-in user. Runs after the signature and expiry checks.
    // Bug fixed (#117): the role used to come from the token's "roles" claim and nobody checked the
    // account, so a disabled, deleted or demoted user kept their old access until the token expired
    // (up to 8 h). Fix: look the user up on every request; gone or disabled = 401, and the role comes
    // from the database (ROLE_ prefix for hasRole("ADMIN")). The "roles" claim stays in the token
    // (docs/api-contract.md section 3), but no longer decides anything.
    // ObjectProvider: UserApi is fetched only when a real token arrives. Liviu's @WebMvcTest slices
    // import this class without a UserApi bean, and they log in with jwt(), which skips this step.
    private static Converter<Jwt, AbstractAuthenticationToken> activeUserFromToken(ObjectProvider<UserApi> users) {
        return jwt -> {
            Role role = users.getObject()
                    .findActiveRole(Long.valueOf(jwt.getSubject()))
                    .orElseThrow(() -> new InvalidBearerTokenException("The account is disabled or no longer exists"));
            return new JwtAuthenticationToken(
                    jwt, List.of(new SimpleGrantedAuthority("ROLE_" + role.name())), jwt.getSubject());
        };
    }
}
