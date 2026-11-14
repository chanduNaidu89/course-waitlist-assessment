package com.example.waitlist.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;

class CourseOfferingTest {

    @Test
    void blankPrerequisiteCodeIsRejected() {
        assertThatThrownBy(() -> new CourseOffering("CS201", 2, Set.of("CS101", "")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
    }
}
