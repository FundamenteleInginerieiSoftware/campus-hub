package com.campus.user.service;

import com.campus.common.error.ConflictException;
import com.campus.common.error.UnauthorizedException;
import com.campus.user.api.Role;
import com.campus.user.domain.User;
import com.campus.user.repository.UserRepository;
import com.campus.user.web.LoginRequest;
import com.campus.user.web.LoginResponse;
import com.campus.user.web.RegisterRequest;
import com.campus.user.web.UserResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Register and login (docs/api-contract.md section 3). */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final Clock clock;
    private final Duration tokenLifetime;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtEncoder jwtEncoder,
            Clock clock,
            @Value("${app.jwt.expiration}") Duration tokenLifetime) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.clock = clock;
        this.tokenLifetime = tokenLifetime;
    }

    /** Creates a STUDENT account. Throws 409 EMAIL_TAKEN if the email is already used, whatever the letter case. */
    @Transactional
    public UserResponse register(RegisterRequest request) {
        if (userRepository.existsByEmailIgnoreCase(request.email())) {
            throw emailTaken();
        }
        // Only the BCrypt hash is stored, never the password itself.
        User user = new User(
                request.email(),
                passwordEncoder.encode(request.password()),
                request.fullName(),
                Role.STUDENT,
                LocalDateTime.now(clock));
        try {
            // saveAndFlush sends the INSERT now, so a duplicate fails inside this try.
            return UserResponse.from(userRepository.saveAndFlush(user));
        } catch (DataIntegrityViolationException e) {
            // Two registrations with the same email at the same moment both pass the check above;
            // the UNIQUE column stops the second one, and it gets the same 409 instead of a 500.
            // (The only other rules on the table, NOT NULL and lengths, are checked by validation first.)
            throw emailTaken();
        }
    }

    /**
     * Checks email + password and hands out a signed token. Unknown email, wrong password and a
     * disabled account all get the same 401, so nobody can find out which emails have an account.
     */
    // An unknown email skips the BCrypt check, so it answers a bit faster. That reveals nothing new:
    // register's 409 EMAIL_TAKEN already tells anyone whether an email has an account.
    //
    // Bug fixed: BCrypt only reads the first 72 bytes of a password, so a longer one was cut silently:
    // the real 72-byte password plus any tail logged in.
    // Fix: register never stores a password over 72 bytes, so a longer one can't be right. It gets the same
    // 401 as a wrong password, without calling BCrypt.
    //
    // No @Transactional here on purpose: it kept a database connection open during the slow BCrypt check.
    // login only reads, through one repository call (which runs in its own read-only transaction), and
    // builds the response from plain columns (no lazy fields), so it needs no transaction around it.
    public LoginResponse login(LoginRequest request) {
        User user = userRepository
                .findByEmailIgnoreCase(request.email().trim())
                .filter(User::isEnabled)
                .filter(found -> fitsBcrypt(request.password())
                        && passwordEncoder.matches(request.password(), found.getPasswordHash()))
                .orElseThrow(() -> new UnauthorizedException("INVALID_CREDENTIALS", "Wrong email or password"));
        return new LoginResponse(createToken(user), tokenLifetime.toSeconds(), UserResponse.from(user));
    }

    // Same byte rule as RegisterRequest: "ș" counts as 2 bytes.
    private static boolean fitsBcrypt(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }

    // The claims are part of the contract: Stefania's CurrentUser reads "sub", and the decoder rejects
    // the token once "exp" has passed. Since #117 SecurityConfig takes the role from the database on
    // every request, not from "roles"; the claim stays because docs/api-contract.md section 3 lists it.
    private String createToken(User user) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim("roles", List.of(user.getRole().name()))
                .issuedAt(now)
                .expiresAt(now.plus(tokenLifetime))
                .build();
        // HS256: the only algorithm the decoder in SecurityConfig accepts.
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private static ConflictException emailTaken() {
        return new ConflictException("EMAIL_TAKEN", "An account with this email already exists");
    }
}
