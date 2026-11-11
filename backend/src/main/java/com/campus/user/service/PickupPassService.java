package com.campus.user.service;

import com.campus.common.audit.AuditService;
import com.campus.common.error.BusinessRuleException;
import com.campus.common.security.CurrentUser;
import com.campus.user.domain.User;
import com.campus.user.repository.UserRepository;
import com.campus.user.web.PickupPassResponse;
import com.campus.user.web.PickupPassVerifiedResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The pickup pass: the student's phone shows a code that changes every 30 seconds, the admin types it
 * at the lab desk (Matei's Reservations desk page) to prove who the student is.
 */
@Service
public class PickupPassService {

    private final PickupCodes codes;
    private final UserRepository users;
    private final CurrentUser currentUser;
    private final AuditService audit;
    private final Clock clock;

    public PickupPassService(
            @Value("${app.jwt.secret}") String secret,
            UserRepository users,
            CurrentUser currentUser,
            AuditService audit,
            Clock clock) {
        this.codes = new PickupCodes(secret);
        this.users = users;
        this.currentUser = currentUser;
        this.audit = audit;
        this.clock = clock;
    }

    /** The logged-in student's pass right now. Nothing is stored, so there is no database call. */
    public PickupPassResponse currentPass() {
        long userId = currentUser.id();
        long now = clock.instant().getEpochSecond();
        String code = codes.code(userId, PickupCodes.timeStep(now));
        return new PickupPassResponse(code, PickupCodes.qrText(userId, code), PickupCodes.validForSeconds(now));
    }

    /**
     * The desk checks a code. The current step and the one before are accepted, so a code read just
     * before the change still works. The audit row names the admin (the logged-in user).
     */
    @Transactional
    public PickupPassVerifiedResponse verify(Long userId, String code) {
        // One answer for a missing account, a disabled one and a wrong code, so the desk
        // can't be used to find out which accounts exist.
        User user = users.findById(userId).filter(User::isEnabled).orElseThrow(PickupPassService::invalid);
        long step = PickupCodes.timeStep(clock.instant().getEpochSecond());
        if (!matches(code, codes.code(userId, step)) && !matches(code, codes.code(userId, step - 1))) {
            throw invalid();
        }
        audit.record(
                currentUser.id(),
                "PICKUP_PASS_VERIFIED",
                "USER",
                userId,
                "Pickup pass verified for " + user.getFullName());
        return new PickupPassVerifiedResponse(user.getId(), user.getFullName(), user.getEmail());
    }

    // MessageDigest.isEqual takes the same time for a right and a wrong code.
    private static boolean matches(String given, String expected) {
        return MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }

    private static BusinessRuleException invalid() {
        return new BusinessRuleException("PICKUP_CODE_INVALID", "The code is wrong or has expired");
    }
}
