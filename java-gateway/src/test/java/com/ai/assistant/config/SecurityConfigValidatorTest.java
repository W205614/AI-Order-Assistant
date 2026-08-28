package com.ai.assistant.config;

import com.ai.assistant.properties.AiProperties;
import com.ai.assistant.security.AuthProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecurityConfigValidatorTest {

    @Test
    void acceptsIndependentStrongSecrets() {
        assertDoesNotThrow(() -> validator("u".repeat(32), "a".repeat(32), "i".repeat(32)).validate());
    }

    @Test
    void rejectsMissingOrWeakSecrets() {
        assertThrows(IllegalStateException.class, () -> validator("short", "a".repeat(32), "i".repeat(32)).validate());
        assertThrows(IllegalStateException.class, () -> validator("u".repeat(32), "a".repeat(32), "").validate());
    }

    @Test
    void rejectsSharedUserAndAdminSigningKey() {
        String shared = "s".repeat(32);
        assertThrows(IllegalStateException.class, () -> validator(shared, shared, "i".repeat(32)).validate());
    }

    private SecurityConfigValidator validator(String user, String admin, String internal) {
        AuthProperties auth = new AuthProperties();
        auth.setUserSecretKey(user); auth.setAdminSecretKey(admin);
        AiProperties ai = new AiProperties(); ai.setInternalApiKey(internal);
        return new SecurityConfigValidator(auth, ai);
    }
}
