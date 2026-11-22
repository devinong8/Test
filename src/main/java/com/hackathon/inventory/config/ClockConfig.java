package com.hackathon.inventory.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/** Injectable clock: every date calculation goes through it so tests can pin "today". */
@Configuration
public class ClockConfig {
    @Bean
    public Clock clock(@Value("${app.zone:Asia/Singapore}") String zone) {
        return Clock.system(ZoneId.of(zone));
    }
}
