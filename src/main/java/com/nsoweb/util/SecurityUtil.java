package com.nsoweb.util;

import at.favre.lib.crypto.bcrypt.BCrypt;
import com.nsoweb.config.AppConfig;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class SecurityUtil {
    private static final byte[] TOKEN_SECRET;

    static {
        String envSecret = AppConfig.envOrDefault("AUTH_SECRET", "");
        if (envSecret != null && !envSecret.isBlank()) {
            TOKEN_SECRET = envSecret.getBytes(StandardCharsets.UTF_8);
        } else {
            byte[] rnd = new byte[32];
            new SecureRandom().nextBytes(rnd);
            TOKEN_SECRET = rnd;
        }
    }

    private SecurityUtil() {
    }

    public static String hashPassword(String plain) {
        return BCrypt.withDefaults().hashToString(10, plain.toCharArray());
    }

    public static boolean verifyPassword(String plain, String hash) {
        if (plain == null || hash == null || hash.isEmpty()) {
            return false;
        }
        return BCrypt.verifyer().verify(plain.toCharArray(), hash).verified;
    }

    public static String issueToken(long userId, String username) {
        long exp = System.currentTimeMillis() + 7L * 24 * 3600 * 1000L;
        String payload = userId + ":" + username + ":" + exp;
        String b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String sig = hmac(b64);
        return b64 + "." + sig;
    }

    public static TokenClaims verifyToken(String token) {
        if (token == null || !token.contains(".")) {
            return null;
        }
        int dot = token.indexOf('.');
        String b64 = token.substring(0, dot);
        String sig = token.substring(dot + 1);
        String expected = hmac(b64);
        if (!MessageDigest.isEqual(sig.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(b64), StandardCharsets.UTF_8);
            String[] parts = decoded.split(":", 3);
            if (parts.length != 3) {
                return null;
            }
            long userId = Long.parseLong(parts[0]);
            String username = parts[1];
            long exp = Long.parseLong(parts[2]);
            if (System.currentTimeMillis() > exp) {
                return null;
            }
            return new TokenClaims(userId, username);
        } catch (Exception e) {
            return null;
        }
    }

    private static String hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(TOKEN_SECRET, "HmacSHA256"));
            byte[] bytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC error", e);
        }
    }

    public static class TokenClaims {
        public final long userId;
        public final String username;

        public TokenClaims(long userId, String username) {
            this.userId = userId;
            this.username = username;
        }
    }
}
