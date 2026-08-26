package com.ai.assistant.security;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;

/**
 * 轻量 JWT（HS256）签发/校验，兼容任意长度密钥。
 * header.payload  →  base64url
 * signature = base64url(HmacSHA256("header.payload", key.getBytes(UTF_8)))
 */
@Component
public class JwtUtil {

    private static final String HMAC_ALGO = "HmacSHA256";

    private static String base64Url(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private static byte[] hmac(String data, byte[] keyBytes) throws Exception {
        Mac mac = Mac.getInstance(HMAC_ALGO);
        mac.init(new SecretKeySpec(keyBytes, HMAC_ALGO));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    public String createJWT(String secretKey, long ttlMillis, Map<String, Object> claims) {
        try {
            JSONObject header = new JSONObject();
            header.put("alg", "HS256");
            long expSeconds = (System.currentTimeMillis() + ttlMillis) / 1000;
            JSONObject payload = new JSONObject();
            claims.forEach(payload::put);
            payload.put("exp", expSeconds);

            String signingInput = base64Url(header.toJSONString().getBytes(StandardCharsets.UTF_8))
                    + "." + base64Url(payload.toJSONString().getBytes(StandardCharsets.UTF_8));
            byte[] sig = hmac(signingInput, secretKey.getBytes(StandardCharsets.UTF_8));
            return signingInput + "." + base64Url(sig);
        } catch (Exception e) {
            throw new RuntimeException("生成 JWT 失败", e);
        }
    }

    public Map<String, Object> parseJWT(String secretKey, String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                throw new IllegalArgumentException("token 格式错误");
            }
            String signingInput = parts[0] + "." + parts[1];
            byte[] actual = hmac(signingInput, secretKey.getBytes(StandardCharsets.UTF_8));
            byte[] expected = Base64.getUrlDecoder().decode(parts[2]);
            if (!MessageDigest.isEqual(actual, expected)) {
                throw new IllegalArgumentException("token 签名无效");
            }
            byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
            JSONObject payload = JSON.parseObject(new String(payloadBytes, StandardCharsets.UTF_8));
            Long exp = payload.getLong("exp");
            if (exp != null && exp * 1000L < System.currentTimeMillis()) {
                throw new IllegalArgumentException("token 已过期");
            }
            return payload;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("解析 JWT 失败", e);
        }
    }
}
