package com.example.waitlist.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OfferingPropertiesTest {

    @Test
    void learnerKeyWithNoValueMeansNoCompletedCourses() {
        Map<String, List<String>> learners = new HashMap<>();
        learners.put("frank", null);

        OfferingProperties properties = new OfferingProperties("CS201", 2, List.of("CS101"), learners);

        assertThat(properties.learners()).containsEntry("frank", List.of());
    }
}
