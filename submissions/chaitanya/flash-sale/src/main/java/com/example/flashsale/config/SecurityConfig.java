package com.example.flashsale.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Minimal security configuration for a development/demo application.
     *
     * IMPORTANT: This application does NOT authenticate users. The userId field
     * in API requests is caller-controlled and represents a simple identifier,
     * not verified identity. In production, replace this with OAuth2/OIDC and
     * derive the user ID from the authenticated principal.
     *
     * CSRF is disabled because this is a REST API with no browser sessions.
     * H2 console is enabled when the dev profile is active; it is disabled
     * in production via profile-specific configuration.
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/**", "/actuator/health", "/actuator/info").permitAll()
                .anyRequest().denyAll()
            )
            .headers(headers -> headers
                .frameOptions(frame -> frame.sameOrigin()) // needed for H2 console
            );
        return http.build();
    }
}