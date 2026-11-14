package com.example.waitlist.domain;

import java.util.Set;

/** Immutable definition of the one course offering this service manages. */
public record CourseOffering(String code, int capacity, Set<String> prerequisites) {

    public CourseOffering {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("offering code is required");
        }
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1");
        }
        prerequisites = prerequisites == null ? Set.of() : Set.copyOf(prerequisites);
        if (prerequisites.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("prerequisite course codes must not be blank: " + prerequisites);
        }
    }
}
