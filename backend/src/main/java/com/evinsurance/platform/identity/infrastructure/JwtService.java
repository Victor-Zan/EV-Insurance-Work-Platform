package com.evinsurance.platform.identity.infrastructure;

import com.evinsurance.platform.identity.domain.CurrentUser;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.spec.SecretKeySpec;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final long ttlSeconds;
    public JwtService(@Value("${JWT_SECRET_BASE64}") String secret, @Value("${JWT_TTL_SECONDS}") long ttlSeconds) {
        byte[] bytes = Base64.getDecoder().decode(secret);
        if (bytes.length < 32 || ttlSeconds < 1 || ttlSeconds > 86400) {
            throw new IllegalStateException("JWT requires a >=256-bit key and TTL between 1 and 86400 seconds");
        }
        var key = new SecretKeySpec(bytes, "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        var jwtDecoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        // No expiration grace: short test tokens and frontend expiry have the same boundary.
        jwtDecoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
            new JwtTimestampValidator(java.time.Duration.ZERO), new JwtIssuerValidator("ev-insurance")));
        this.decoder = jwtDecoder;
        this.ttlSeconds = ttlSeconds;
    }
    public IssuedToken issue(CurrentUser user) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(ttlSeconds);
        var claims = JwtClaimsSet.builder().issuer("ev-insurance").subject(Long.toString(user.id()))
            .issuedAt(now).expiresAt(expiresAt).claim("av", user.authVersion()).build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new IssuedToken(token, expiresAt);
    }
    public Jwt decode(String token) {
        Jwt jwt = decoder.decode(token);
        if (jwt.getExpiresAt() == null || jwt.getIssuedAt() == null || jwt.getClaim("av") == null) {
            throw new BadJwtException("Missing required claims");
        }
        return jwt;
    }
    public record IssuedToken(String accessToken, Instant expiresAt) {}
}
