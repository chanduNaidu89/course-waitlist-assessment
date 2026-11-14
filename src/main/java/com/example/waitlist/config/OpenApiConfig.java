package com.example.waitlist.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** API metadata shown in Swagger UI ({@code /swagger-ui.html}) and the spec ({@code /v3/api-docs}). */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI waitlistOpenApi(OfferingProperties properties) {
        return new OpenAPI().info(new Info()
                .title("Course Waitlist API")
                .version("0.1.0")
                .description("""
                        Enrollment, drop and FIFO waitlist promotion for a single course offering \
                        (configured offering: **%s**, capacity %d, prerequisites %s).

                        * Every response has a machine-readable `outcome` and a human-readable `message`.
                        * Duplicate enrolls and repeated drops are safe no-ops.
                        * A learner who is ineligible when they reach the front of the waitlist is \
                        **skipped and removed**; the next learner in line is promoted.
                        * State is in memory and resets on restart.
                        """.formatted(properties.offeringCode(), properties.capacity(),
                        properties.prerequisites())));
    }
}
