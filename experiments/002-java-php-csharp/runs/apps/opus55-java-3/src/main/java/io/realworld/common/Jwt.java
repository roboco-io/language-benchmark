package io.realworld.common;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Minimal HS256 JWT issuer and verifier. The subject claim holds the user id. */
@Component
public class Jwt {

    private static final String HEADER = b64("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));

    private final byte[] secret;
    private final long ttlSeconds;
    private final ObjectMapper mapper;

    public Jwt(@Value("${app.jwt.secret}") String secret,
               @Value("${app.jwt.ttl-seconds}") long ttlSeconds,
               ObjectMapper mapper) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.ttlSeconds = ttlSeconds;
        this.mapper = mapper;
    }

    public String issue(long userId) {
        long now = Instant.now().getEpochSecond();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", String.valueOf(userId));
        claims.put("iat", now);
        claims.put("exp", now + ttlSeconds);
        try {
            String payload = b64(mapper.writeValueAsBytes(claims));
            String signingInput = HEADER + "." + payload;
            return signingInput + "." + b64(sign(signingInput));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot issue token", e);
        }
    }

    /** Returns the user id when the token has a valid signature and is not expired. */
    public Optional<Long> verify(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3 || !parts[0].equals(HEADER)) {
            return Optional.empty();
        }
        try {
            byte[] expected = sign(parts[0] + "." + parts[1]);
            byte[] actual = Base64.getUrlDecoder().decode(parts[2]);
            if (!MessageDigest.isEqual(expected, actual)) {
                return Optional.empty();
            }
            JsonNode claims = mapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
            if (claims.path("exp").asLong(0) < Instant.now().getEpochSecond()) {
                return Optional.empty();
            }
            return Optional.of(Long.parseLong(claims.path("sub").asText()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private byte[] sign(String input) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
