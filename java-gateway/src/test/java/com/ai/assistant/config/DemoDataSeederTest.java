package com.ai.assistant.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class DemoDataSeederTest {

    @Test
    void disabledSeedNeverTouchesProductionData() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);

        new DemoDataSeeder(jdbc, false).run();

        verifyNoInteractions(jdbc);
    }
}
