package com.ai.assistant.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 鉴权配置（JWT 密钥与过期时间）
 */
@Component
@ConfigurationProperties(prefix = "auth")
@Data
public class AuthProperties {

    /** 用户端 JWT 密钥 */
    private String userSecretKey = "ai-order-user-secret-2026";

    /** 用户端 JWT 过期时间(毫秒)，默认 24h */
    private long userTtl = 86400000;

    /** 管理端 JWT 密钥 */
    private String adminSecretKey = "ai-order-admin-secret-2026";

    /** 管理端 JWT 过期时间(毫秒)，默认 12h */
    private long adminTtl = 43200000;
}
