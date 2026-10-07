package com.ai.assistant.security;

import static org.junit.jupiter.api.Assertions.*;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class JwtUtilTest {
  final String secret = "s".repeat(32);
  final JwtUtil jwt = new JwtUtil();

  @Test
  void requiresExpirationIssuerAudienceAndMandatoryTimes() throws Exception {
    var claims =
        new JWTClaimsSet.Builder()
            .issuer("ai-order-assistant")
            .audience("customer")
            .jwtID("a")
            .issueTime(new Date())
            .notBeforeTime(new Date())
            .claim("userId", 1)
            .build();
    var signed = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
    signed.sign(new MACSigner(secret));
    assertThrows(AuthException.class, () -> jwt.parseJWT(secret, signed.serialize()));
  }

  @Test
  void signatureAndExpiryAreEnforced() throws Exception {
    String token =
        jwt.createJWT(secret, 60000, Map.of("userId", 1, "tokenVersion", 1, "role", "CUSTOMER"));
    assertEquals("CUSTOMER", jwt.parseJWT(secret, token).get("role"));
    assertThrows(AuthException.class, () -> jwt.parseJWT("a".repeat(32), token));
    var expired =
        new SignedJWT(
            new JWSHeader(JWSAlgorithm.HS256),
            new JWTClaimsSet.Builder()
                .issuer("ai-order-assistant")
                .audience("customer")
                .jwtID("expired")
                .issueTime(new Date(System.currentTimeMillis() - 20000))
                .notBeforeTime(new Date(System.currentTimeMillis() - 20000))
                .expirationTime(new Date(System.currentTimeMillis() - 1000))
                .claim("userId", 1)
                .build());
    expired.sign(new MACSigner(secret));
    assertThrows(AuthException.class, () -> jwt.parseJWT(secret, expired.serialize()));
  }
}
