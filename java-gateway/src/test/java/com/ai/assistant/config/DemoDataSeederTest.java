package com.ai.assistant.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DemoDataSeederTest {

    @Test
    void weakDemoPasswordsFailBeforeWritingAnySeedData() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        assertThrows(IllegalArgumentException.class,
            () -> new DemoDataSeeder(jdbc, true, "123456", "strong-owner-password").run());
        verifyNoInteractions(jdbc);
    }

    @Test
    void disabledSeedNeverTouchesProductionData() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);

        new DemoDataSeeder(jdbc, false, "", "").run();

        verifyNoInteractions(jdbc);
    }
}
