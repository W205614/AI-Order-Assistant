package com.ai.assistant.config;

import com.ai.assistant.properties.AiProperties;
import com.ai.assistant.security.AuthProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/** 启动时拒绝缺失或弱密钥，避免以公开默认值签发 JWT 或暴露 Agent。 */
@Component
public class SecurityConfigValidator {
    private static final int MIN_SECRET_LENGTH = 32;

    private final AuthProperties auth;
    private final AiProperties ai;

    public SecurityConfigValidator(AuthProperties auth, AiProperties ai) {
        this.auth = auth;
        this.ai = ai;
    }

    @PostConstruct
    public void validate() {
        requireStrong("JWT_USER_SECRET", auth.getUserSecretKey());
        requireStrong("JWT_ADMIN_SECRET", auth.getAdminSecretKey());
        requireStrong("AI_INTERNAL_API_KEY", ai.getInternalApiKey());
        if (auth.getUserSecretKey().equals(auth.getAdminSecretKey())) {
            throw new IllegalStateException("JWT_USER_SECRET 与 JWT_ADMIN_SECRET 必须使用不同的值");
        }
    }

    private void requireStrong(String name, String value) {
        if (value == null || value.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(name + " 必须配置为至少 " + MIN_SECRET_LENGTH + " 位的随机字符串");
        }
    }
}
