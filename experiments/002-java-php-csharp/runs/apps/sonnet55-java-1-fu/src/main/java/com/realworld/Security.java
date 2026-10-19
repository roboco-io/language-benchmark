package com.realworld;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** HS256 JWT issuing/verification, PBKDF2 password hashing, and request authentication. */
@Component
public class Security {

    private static final long TOKEN_TTL_SECONDS = 30L * 24 * 3600;
    private static final int PBKDF2_ITERATIONS = 60_000;
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private final byte[] secret;
    private final ObjectMapper mapper;
    private final UserRepository users;
    private final SecureRandom random = new SecureRandom();

    public Security(@Value("${app.jwt.secret}") String secret, ObjectMapper mapper, UserRepository users) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.mapper = mapper;
        this.users = users;
    }

    public String issueToken(User user) {
        try {
            long now = Instant.now().getEpochSecond();
            String header = B64.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
            String payload = B64.encodeToString(mapper.writeValueAsBytes(
                    Map.of("sub", user.getId(), "iat", now, "exp", now + TOKEN_TTL_SECONDS)));
            String unsigned = header + "." + payload;
            return unsigned + "." + B64.encodeToString(hmac(unsigned));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Returns the user id in the token, or null if the token is invalid or expired. */
    private Long verify(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return null;
            }
            byte[] expected = hmac(parts[0] + "." + parts[1]);
            byte[] actual = Base64.getUrlDecoder().decode(parts[2]);
            if (!MessageDigest.isEqual(expected, actual)) {
                return null;
            }
            var claims = mapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
            if (claims.path("exp").asLong(0) < Instant.now().getEpochSecond()) {
                return null;
            }
            return claims.path("sub").isNumber() ? claims.path("sub").asLong() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] hmac(String data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    public String hashPassword(String password) {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt) + "$" + Base64.getEncoder().encodeToString(pbkdf2(password, salt));
    }

    public boolean matches(String password, String stored) {
        String[] parts = stored.split("\\$");
        if (parts.length != 2) {
            return false;
        }
        byte[] expected = Base64.getDecoder().decode(parts[1]);
        return MessageDigest.isEqual(expected, pbkdf2(password, Base64.getDecoder().decode(parts[0])));
    }

    private byte[] pbkdf2(String password, byte[] salt) {
        try {
            var spec = new PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Returns the authenticated user, or null when the request has no Authorization header. */
    public User optionalUser(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || header.isBlank()) {
            return null;
        }
        String token = header.startsWith("Token ") ? header.substring(6)
                : header.startsWith("Bearer ") ? header.substring(7) : header;
        Long id = verify(token.trim());
        if (id == null) {
            throw ApiException.unauthorized("is invalid");
        }
        return users.findById(id).orElseThrow(() -> ApiException.unauthorized("is invalid"));
    }

    public User requiredUser(HttpServletRequest request) {
        User user = optionalUser(request);
        if (user == null) {
            throw ApiException.unauthorized("is missing");
        }
        return user;
    }
}
