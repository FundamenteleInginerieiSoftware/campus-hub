package com.campus.user.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The rotating 6-digit pickup code: HMAC-SHA256 over "pickup-v1|userId|timeStep", cut down to six
 * digits. Only the server computes it (the phone just fetches it); the same inputs always give the
 * same code, so nothing is stored.
 */
final class PickupCodes {

    static final int STEP_SECONDS = 30;
    private static final int DIGITS = 6;
    // The label keeps a pickup code apart from anything else signed with the same secret (a login
    // token), so one can never be passed off as the other.
    private static final String PURPOSE = "pickup-v1";

    private final SecretKeySpec key;

    PickupCodes(String secret) {
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    String code(long userId, long timeStep) {
        byte[] mac;
        try {
            // A new Mac per call: Mac keeps state and is not thread-safe.
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(key);
            mac = hmac.doFinal((PURPOSE + "|" + userId + "|" + timeStep).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
        int number = ByteBuffer.wrap(mac).getInt() & 0x7fffffff;
        // Bug fixed: String.format used the JVM default locale, so on an Arabic or Thai JVM the code came
        // out in non-ASCII digits and verify never matched. Fix: Locale.ROOT always gives 0-9.
        return String.format(Locale.ROOT, "%0" + DIGITS + "d", number % 1_000_000);
    }

    static long timeStep(long epochSecond) {
        return epochSecond / STEP_SECONDS;
    }

    /** Seconds left in the current step, from 30 down to 1. */
    static int validForSeconds(long epochSecond) {
        return (int) (STEP_SECONDS - epochSecond % STEP_SECONDS);
    }

    static String qrText(long userId, String code) {
        return "campus-pickup:1:" + userId + ":" + code;
    }
}
