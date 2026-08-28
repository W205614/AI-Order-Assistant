package com.ai.assistant.service;

import com.ai.assistant.model.UserFoodPreference;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class UserPreferenceServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final UserPreferenceService service = new UserPreferenceService(jdbc);

    @Test
    void rejectsNonPositiveBudgetBeforeWriting() {
        UserFoodPreference preference = new UserFoodPreference();
        preference.setBudget(BigDecimal.ZERO);

        assertThrows(IllegalArgumentException.class, () -> service.save(1L, preference));
        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsOversizedPreferenceTextBeforeWriting() {
        UserFoodPreference preference = new UserFoodPreference();
        preference.setAllergens("花".repeat(256));

        assertThrows(IllegalArgumentException.class, () -> service.save(1L, preference));
        verifyNoInteractions(jdbc);
    }
}
