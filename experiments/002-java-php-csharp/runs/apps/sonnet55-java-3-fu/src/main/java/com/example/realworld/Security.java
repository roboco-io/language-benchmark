package com.example.realworld;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Password hashing (PBKDF2) and HS256 JWT issuing/verification, using only the JDK. */
@Component
public class Security {
    private static final int ITERATIONS = 60_000;
    private static final long TOKEN_TTL_SECONDS = 30L * 24 * 3600;
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final SecureRandom random = new SecureRandom();
    private final ObjectMapper mapper = new ObjectMapper();
    private final byte[] secret;
    private final UserRepository users;

    public Security(@Value("${JWT_SECRET:realworld-dev-secret-change-me-in-production}") String secret,
                    UserRepository users) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.users = users;
    }

    public String hashPassword(String password) {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        return B64.encodeToString(salt) + "$" + B64.encodeToString(pbkdf2(password, salt));
    }

    public boolean checkPassword(String password, String stored) {
        String[] parts = stored.split("\\$");
        if (parts.length != 2) {
            return false;
        }
        byte[] expected = B64D.decode(parts[1]);
        return MessageDigest.isEqual(expected, pbkdf2(password, B64D.decode(parts[0])));
    }

    private static byte[] pbkdf2(String password, byte[] salt) {
        try {
            var spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public String issueToken(User user) {
        long exp = System.currentTimeMillis() / 1000 + TOKEN_TTL_SECONDS;
        String header = B64.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = B64.encodeToString(
                ("{\"sub\":" + user.id + ",\"exp\":" + exp + "}").getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + "." + sign(header + "." + payload);
    }

    private String sign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return B64.encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Returns the authenticated user, or null when no Authorization header is present. */
    public User optionalUser(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        return authenticate(header);
    }

    public User requiredUser(String header) {
        if (header == null || header.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "token", "is missing");
        }
        return authenticate(header);
    }

    private User authenticate(String header) {
        String token = header.startsWith("Token ") ? header.substring(6)
                : header.startsWith("Bearer ") ? header.substring(7) : header;
        String[] parts = token.trim().split("\\.");
        if (parts.length != 3
                || !MessageDigest.isEqual(sign(parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8),
                parts[2].getBytes(StandardCharsets.UTF_8))) {
            throw invalid();
        }
        try {
            JsonNode claims = mapper.readTree(B64D.decode(parts[1]));
            if (claims.path("exp").asLong() < System.currentTimeMillis() / 1000) {
                throw invalid();
            }
            return users.findById(claims.path("sub").asLong()).orElseThrow(Security::invalid);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw invalid();
        }
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "token", "is invalid");
    }
}
