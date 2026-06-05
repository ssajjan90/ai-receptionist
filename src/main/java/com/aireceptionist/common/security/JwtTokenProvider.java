package com.aireceptionist.common.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import java.time.Instant;
import java.util.Date;

@Component
public class JwtTokenProvider {

    private final RSAPrivateKey privateKey;
    private final RSAPublicKey publicKey;
    private final long expiryMinutes;

    public JwtTokenProvider(@Value("${app.jwt.private-key}") RSAPrivateKey privateKey,
                            @Value("${app.jwt.public-key}") RSAPublicKey publicKey,
                            @Value("${app.jwt.expiry-minutes:60}") long expiryMinutes) {
        this.privateKey = privateKey;
        this.publicKey = publicKey;
        this.expiryMinutes = expiryMinutes;
    }

    public String generateToken(String tenantId, String userId, String role, String tier) throws JOSEException {
        if (tenantId == null || tenantId.isBlank() ||
            userId == null || userId.isBlank() ||
            role == null || role.isBlank() ||
            tier == null || tier.isBlank()) {
            throw new IllegalArgumentException("JWT claims tenantId, userId, role, and tier must not be null or blank");
        }
        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .claim("tenantId", tenantId)
                .claim("userId", userId)
                .claim("role", role)
                .claim("tier", tier)
                .issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plusSeconds(expiryMinutes * 60)))
                .build();

        SignedJWT signedJWT = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claimsSet);
        signedJWT.sign(new RSASSASigner(privateKey));
        return signedJWT.serialize();
    }

    public JWTClaimsSet validateAndParseClaims(String token) throws JOSEException, ParseException {
        SignedJWT jwt = SignedJWT.parse(token);
        JWSVerifier verifier = new RSASSAVerifier(publicKey);
        if (!jwt.verify(verifier)) {
            throw new JOSEException("Invalid JWT signature");
        }

        JWTClaimsSet claims = jwt.getJWTClaimsSet();
        Date expirationTime = claims.getExpirationTime();
        if (expirationTime == null || expirationTime.before(new Date())) {
            throw new JOSEException("JWT expired");
        }
        return claims;
    }
}
