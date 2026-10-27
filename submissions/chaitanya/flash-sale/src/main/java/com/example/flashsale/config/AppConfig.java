package com.example.flashsale.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class AppConfig {

    /**
     * Injectable Clock bean so that time-dependent logic can be tested
     * deterministically without waiting for real time to pass.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}