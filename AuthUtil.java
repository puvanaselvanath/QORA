package qora;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

/**
 * Password hashing (salted SHA-256) and opaque bearer-token generation.
 * Note: SHA-256+salt is used instead of bcrypt/argon2 because this project
 * has no external dependencies available. For a production system, swap
 * hash() to use BCrypt or Argon2 (e.g. via Spring Security) instead.
 */
public class AuthUtil {

    private static final SecureRandom RNG = new SecureRandom();

    public static String newSalt() {
        byte[] salt = new byte[16];
        RNG.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    public static String hash(String password, String salt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(Base64.getDecoder().decode(salt));
            byte[] digest = md.digest(password.getBytes("UTF-8"));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static boolean matches(String password, String salt, String expectedHash) {
        return hash(password, salt).equals(expectedHash);
    }

    public static String newToken() {
        return UUID.randomUUID().toString() + UUID.randomUUID().toString();
    }
}
