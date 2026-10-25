package com.campus.common.time;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ClockConfigTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(ClockConfig.class);

    @Test
    void clockUsesConfiguredZone() {
        contextRunner.run(context -> {
            Clock clock = context.getBean(Clock.class);
            assertEquals(ZoneId.of("Europe/Bucharest"), clock.getZone());
        });
    }

    @Test
    void clockDirectInvocationUsesProvidedZone() {
        ClockConfig config = new ClockConfig();
        Clock clock = config.clock("Europe/Bucharest");
        assertEquals(ZoneId.of("Europe/Bucharest"), clock.getZone());
    }
}
