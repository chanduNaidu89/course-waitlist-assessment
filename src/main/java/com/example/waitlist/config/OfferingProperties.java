package com.example.waitlist.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the single course offering served by this application, plus the seed data for
 * learner academic records (completed course codes per learner).
 */
@Validated
@ConfigurationProperties(prefix = "waitlist")
public record OfferingProperties(
        @NotBlank String offeringCode,
        @Min(1) int capacity,
        List<String> prerequisites,
        Map<String, List<String>> learners) {

    public OfferingProperties {
        prerequisites = prerequisites == null ? List.of() : List.copyOf(prerequisites);
        if (learners == null) {
            learners = Map.of();
        } else {
            // A key with no value (e.g. YAML `frank:`) binds to null: treat it as no completed courses.
            Map<String, List<String>> normalized = new HashMap<>();
            learners.forEach((id, completed) -> normalized.put(id, completed == null ? List.of() : completed));
            learners = Map.copyOf(normalized);
        }
    }
}
