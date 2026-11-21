package com.hackathon.appointments.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
@EnableConfigurationProperties(AppProperties.class)
public class AppConfig {

    /** Injected everywhere instead of calling now() directly, so tests can control time. */
    @Bean
    public Clock clock(AppProperties props) {
        return Clock.system(ZoneId.of(props.timezone()));
    }

    @Bean
    public OpenAPI openApi() {
        return new OpenAPI().info(new Info()
                .title("Appointment Management System API")
                .version("v1")
                .description("""
                        Providers publish time slots; customers search and book them; bookings can be cancelled \
                        and the slot is released atomically. All times are local to the configured business \
                        time zone (default Asia/Singapore). Every error uses the ApiError envelope.""")
                .contact(new Contact().name("Hackathon BE1")));
    }
}
