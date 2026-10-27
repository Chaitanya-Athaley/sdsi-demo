package com.example.flashsale.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

@TestConfiguration
public class TestConfig {

    /**
     * Provides a fixed clock for deterministic time-based tests.
     * Individual tests can override by injecting a custom Clock bean.
     */
    @Bean
    @Primary
    public Clock testClock() {
        return Clock.fixed(Instant.parse("2026-10-27T10:00:00Z"), ZoneId.of("UTC"));
    }
}