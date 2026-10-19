package io.realworld.auth;

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

/** Minimal HS256 JWT issuing and verification. The subject claim holds the user id. */
@Service
public class JwtService {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();
    private static final String HEADER = B64.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));

    private final byte[] secret;
    private final long ttlSeconds;
    private final ObjectMapper mapper;

    public JwtService(@Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.ttl-seconds}") long ttlSeconds,
                      ObjectMapper mapper) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.ttlSeconds = ttlSeconds;
        this.mapper = mapper;
    }

    public String issue(long userId) {
        long now = Instant.now().getEpochSecond();
        try {
            byte[] payload = mapper.writeValueAsBytes(Map.of("sub", String.valueOf(userId), "iat", now, "exp", now + ttlSeconds));
            String signingInput = HEADER + "." + B64.encodeToString(payload);
            return signingInput + "." + B64.encodeToString(sign(signingInput));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot issue token", e);
        }
    }

    /** Returns the user id if the token is well formed, correctly signed and not expired. */
    public Optional<Long> verify(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3 || !parts[0].equals(HEADER)) {
                return Optional.empty();
            }
            byte[] expected = sign(parts[0] + "." + parts[1]);
            if (!MessageDigest.isEqual(expected, B64D.decode(parts[2]))) {
                return Optional.empty();
            }
            JsonNode claims = mapper.readTree(B64D.decode(parts[1]));
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
}
