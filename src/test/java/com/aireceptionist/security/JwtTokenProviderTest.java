package com.aireceptionist.security;

import com.aireceptionist.common.security.JwtTokenProvider;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jwt.JWTClaimsSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenProviderTest {

    private static RSAPrivateKey privateKey;
    private static RSAPublicKey publicKey;

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        KeyPair keyPair = keyGen.generateKeyPair();
        privateKey = (RSAPrivateKey) keyPair.getPrivate();
        publicKey = (RSAPublicKey) keyPair.getPublic();
    }

    @Test
    void generatedTokenParsesWithExpectedClaims() throws Exception {
        JwtTokenProvider provider = new JwtTokenProvider(privateKey, publicKey, 60);

        String token = provider.generateToken("tenant-1", "user-1", "OWNER", "PRO");
        JWTClaimsSet claims = provider.validateAndParseClaims(token);

        assertThat(claims.getStringClaim("tenantId")).isEqualTo("tenant-1");
        assertThat(claims.getStringClaim("userId")).isEqualTo("user-1");
        assertThat(claims.getStringClaim("role")).isEqualTo("OWNER");
        assertThat(claims.getStringClaim("tier")).isEqualTo("PRO");
    }

    @Test
    void tamperedTokenThrowsJoseException() throws Exception {
        JwtTokenProvider provider = new JwtTokenProvider(privateKey, publicKey, 60);
        String token = provider.generateToken("tenant-1", "user-1", "OWNER", "PRO");
        String tampered = token.substring(0, token.length() - 2) + "xx";

        assertThatThrownBy(() -> provider.validateAndParseClaims(tampered))
                .isInstanceOf(JOSEException.class);
    }

    @Test
    void expiredTokenThrowsJoseException() throws Exception {
        JwtTokenProvider provider = new JwtTokenProvider(privateKey, publicKey, -1);
        String token = provider.generateToken("tenant-1", "user-1", "OWNER", "PRO");

        assertThatThrownBy(() -> provider.validateAndParseClaims(token))
                .isInstanceOf(JOSEException.class);
    }
}
