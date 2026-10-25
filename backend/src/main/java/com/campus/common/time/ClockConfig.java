package com.campus.common.time;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    // The JVM on AWS and in CI runs in UTC; setting Europe/Bucharest explicitly ensures
    // campus opening-hours checks and reservation slots remain synchronized with local time.
    @Bean
    Clock clock(@Value("${app.time-zone:Europe/Bucharest}") String zone) {
        return Clock.system(ZoneId.of(zone));
    }
}
