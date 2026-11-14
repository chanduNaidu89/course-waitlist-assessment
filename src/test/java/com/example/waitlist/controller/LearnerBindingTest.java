package com.example.waitlist.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.waitlist.domain.LearnerRecords;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Bracketed map keys keep learner ids verbatim. Unbracketed keys lose characters outside
 * letters, digits, '-', '_' and '.', e.g. the '@' in an email-style id.
 */
@SpringBootTest(properties = {
        "waitlist.learners[jo@example.edu]=CS101",
        "waitlist.learners.al@example.edu=CS101"})
class LearnerBindingTest {

    @Autowired
    private LearnerRecords learnerRecords;

    @Test
    void bracketedKeyKeepsIdVerbatim() {
        assertThat(learnerRecords.completedCourses("jo@example.edu")).isPresent();
    }

    @Test
    void unbracketedKeyIsMangledWhichIsWhyConfigUsesBrackets() {
        assertThat(learnerRecords.completedCourses("al@example.edu")).isEmpty();
        assertThat(learnerRecords.completedCourses("alexample.edu")).isPresent();
    }

    @Test
    void seededLearnersFromApplicationPropertiesAreBound() {
        assertThat(learnerRecords.completedCourses("alice")).hasValueSatisfying(c -> assertThat(c).contains("CS101"));
    }
}
