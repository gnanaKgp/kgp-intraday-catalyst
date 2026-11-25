package com.catalyst.terminal.service.dhan;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Locale;

/**
 * Pure-Java RFC 6238 TOTP (HMAC-SHA1, 30s window, 6 digits) generator for DhanHQ auth.
 */
public final class TotpUtil {

    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private TotpUtil() {}

    public static String generateCode(String base32Secret) {
        return generateCodeAt(base32Secret, Instant.now().getEpochSecond());
    }

    public static String generateCodeAt(String base32Secret, long epochSeconds) {
        if (base32Secret == null || base32Secret.isBlank()) {
            throw new IllegalArgumentException("TOTP secret is blank");
        }
        byte[] key = decodeBase32(base32Secret);
        long timeStep = epochSeconds / 30L;
        byte[] counter = ByteBuffer.allocate(8).putLong(timeStep).array();
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(counter);
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);
            int otp = binary % 1_000_000;
            return String.format("%06d", otp);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to compute TOTP", e);
        }
    }

    static byte[] decodeBase32(String input) {
        String clean = input.trim().replace("=", "").replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        byte[] out = new byte[(clean.length() * 5) / 8];
        int buffer = 0;
        int bitsLeft = 0;
        int idx = 0;
        for (int i = 0; i < clean.length(); i++) {
            int val = BASE32_ALPHABET.indexOf(clean.charAt(i));
            if (val < 0) {
                throw new IllegalArgumentException("Invalid Base32 character in TOTP secret");
            }
            buffer = (buffer << 5) | val;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out[idx++] = (byte) ((buffer >> (bitsLeft - 8)) & 0xFF);
                bitsLeft -= 8;
            }
        }
        return out;
    }
}

