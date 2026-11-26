package com.issuermatch.team;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Invite link tokens: 32 random bytes, URL safe. The database keeps only the SHA-256 digest. A plain digest is enough
 * here, unlike for passwords, because the input has 256 bits of randomness and cannot be guessed.
 */
final class InviteTokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern SHAPE = Pattern.compile("^[A-Za-z0-9_-]{43}$");

    private InviteTokens() {}

    static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static boolean looksValid(String token) {
        return token != null && SHAPE.matcher(token).matches();
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-256 is required on every JVM
        }
    }
}
