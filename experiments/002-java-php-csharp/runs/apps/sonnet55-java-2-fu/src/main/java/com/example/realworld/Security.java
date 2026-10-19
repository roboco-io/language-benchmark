package com.example.realworld;

import com.example.realworld.Entities.User;
import com.example.realworld.Repositories.UserRepo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** HS256 JWT issuing/verification and PBKDF2 password hashing, using only JDK crypto. */
@Component
public class Security {
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();
    private static final long TOKEN_TTL_SECONDS = 7L * 24 * 3600;
    private static final int PBKDF2_ITERATIONS = 20_000;

    private final byte[] secret;
    private final ObjectMapper mapper;
    private final UserRepo users;
    private final SecureRandom random = new SecureRandom();

    public Security(@Value("${app.jwt-secret}") String secret, ObjectMapper mapper, UserRepo users) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.mapper = mapper;
        this.users = users;
    }

    public String issueToken(User user) {
        try {
            String header = B64.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
            long now = Instant.now().getEpochSecond();
            String payload = B64.encodeToString(mapper.writeValueAsBytes(
                    Map.of("sub", user.id, "iat", now, "exp", now + TOKEN_TTL_SECONDS)));
            return header + "." + payload + "." + sign(header + "." + payload);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Returns the user id for a valid token, or empty when the token is malformed, forged or expired. */
    private Optional<Long> verify(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) return Optional.empty();
            byte[] expected = sign(parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(expected, parts[2].getBytes(StandardCharsets.UTF_8))) return Optional.empty();
            JsonNode claims = mapper.readTree(B64D.decode(parts[1]));
            if (claims.path("exp").asLong(0) < Instant.now().getEpochSecond()) return Optional.empty();
            return claims.hasNonNull("sub") ? Optional.of(claims.get("sub").asLong()) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private String sign(String data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return B64.encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    /** Resolves the Authorization header; empty when absent. A present but invalid token is a 401. */
    public Optional<User> optionalUser(String header) {
        if (header == null || header.isBlank()) return Optional.empty();
        int space = header.indexOf(' ');
        String token = space < 0 ? header : header.substring(space + 1).trim();
        Long id = verify(token).orElseThrow(() -> ApiException.unauthorized("is invalid"));
        return Optional.of(users.findById(id).orElseThrow(() -> ApiException.unauthorized("is invalid")));
    }

    public User requireUser(String header) {
        if (header == null || header.isBlank()) throw ApiException.unauthorized("is missing");
        return optionalUser(header).orElseThrow(() -> ApiException.unauthorized("is missing"));
    }

    public String hashPassword(String password) {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        return B64.encodeToString(salt) + "$" + B64.encodeToString(pbkdf2(password, salt));
    }

    public boolean checkPassword(String password, String stored) {
        String[] parts = stored.split("\\$");
        if (parts.length != 2) return false;
        return MessageDigest.isEqual(pbkdf2(password, B64D.decode(parts[0])), B64D.decode(parts[1]));
    }

    private byte[] pbkdf2(String password, byte[] salt) {
        try {
            var spec = new PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
