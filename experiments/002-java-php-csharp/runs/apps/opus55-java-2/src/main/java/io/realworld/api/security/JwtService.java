package io.realworld.api.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Issues and verifies HS256-signed JWTs whose subject is the user id. */
@Service
public class JwtService {
    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();
    private static final String HEADER = ENC.encodeToString(
            "{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));

    private final byte[] secret;
    private final long ttlSeconds;
    private final ObjectMapper mapper;

    public JwtService(@Value("${jwt.secret}") String secret,
                      @Value("${jwt.ttl-seconds}") long ttlSeconds,
                      ObjectMapper mapper) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.ttlSeconds = ttlSeconds;
        this.mapper = mapper;
    }

    public String issue(long userId) {
        long now = Instant.now().getEpochSecond();
        try {
            byte[] payload = mapper.writeValueAsBytes(
                    Map.of("sub", String.valueOf(userId), "iat", now, "exp", now + ttlSeconds));
            String signingInput = HEADER + "." + ENC.encodeToString(payload);
            return signingInput + "." + ENC.encodeToString(sign(signingInput));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot issue token", e);
        }
    }

    /** Returns the user id for a valid, unexpired token; empty otherwise. */
    public Optional<Long> verify(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3 || !HEADER.equals(parts[0])) return Optional.empty();
            byte[] expected = sign(parts[0] + "." + parts[1]);
            if (!MessageDigest.isEqual(expected, DEC.decode(parts[2]))) return Optional.empty();
            JsonNode claims = mapper.readTree(DEC.decode(parts[1]));
            if (claims.path("exp").asLong(0) < Instant.now().getEpochSecond()) return Optional.empty();
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
}
