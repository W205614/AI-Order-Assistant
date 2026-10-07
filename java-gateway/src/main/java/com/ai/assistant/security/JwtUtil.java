package com.ai.assistant.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;

@Component
public class JwtUtil {
  public String createJWT(String secret, long ttl, Map<String, Object> claims) {
    Instant now = Instant.now();
    var builder =
        JwtClaimsSet.builder()
            .issuer("ai-order-assistant")
            .issuedAt(now)
            .notBefore(now)
            .expiresAt(now.plusMillis(ttl))
            .id(UUID.randomUUID().toString())
            .audience(List.of(claims.containsKey("adminId") ? "management" : "customer"));
    claims.forEach(builder::claim);
    var encoder =
        new NimbusJwtEncoder(new ImmutableSecret<>(secret.getBytes(StandardCharsets.UTF_8)));
    return encoder
        .encode(
            JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), builder.build()))
        .getTokenValue();
  }

  public Map<String, Object> parseJWT(String secret, String token) {
    try {
      if (token == null || token.length() > 8192) throw new IllegalArgumentException("凭证无效");
      var decoder =
          NimbusJwtDecoder.withSecretKey(
                  new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
              .macAlgorithm(MacAlgorithm.HS256)
              .build();
      decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("ai-order-assistant"));
      Jwt jwt = decoder.decode(token);
      if (jwt.getExpiresAt() == null
          || !jwt.getExpiresAt().isAfter(Instant.now())
          || jwt.getId() == null
          || jwt.getIssuedAt() == null
          || jwt.getNotBefore() == null
          || jwt.getAudience().size() != 1) throw new IllegalArgumentException("凭证无效");
      if (!jwt.getAudience().contains(jwt.hasClaim("adminId") ? "management" : "customer"))
        throw new IllegalArgumentException("凭证无效");
      return new HashMap<>(jwt.getClaims());
    } catch (Exception e) {
      throw new AuthException("登录已失效，请重新登录");
    }
  }
}
