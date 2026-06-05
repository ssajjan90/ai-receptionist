package com.aireceptionist;

import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

final class TestJwtKeys {

    private static final KeyPair KEY_PAIR = generateKeyPair();

    private TestJwtKeys() {}

    static String privateKeyPem() {
        return toPem("PRIVATE KEY", KEY_PAIR.getPrivate());
    }

    static String publicKeyPem() {
        return toPem("PUBLIC KEY", KEY_PAIR.getPublic());
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to generate test JWT keys", ex);
        }
    }

    private static String toPem(String type, Key key) {
        String encoded = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(key.getEncoded());
        return "-----BEGIN " + type + "-----\n" + encoded + "\n-----END " + type + "-----";
    }
}
