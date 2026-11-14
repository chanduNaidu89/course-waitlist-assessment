package com.example.waitlist.domain;

import org.springframework.stereotype.Component;

/**
 * Prerequisite rule: a learner is eligible for the offering if and only if they are a known learner
 * and their completed courses include <em>every</em> prerequisite course code of the offering. An
 * offering with no prerequisites admits every known learner.
 */
@Component
public class PrerequisitePolicy {

    public enum Eligibility { ELIGIBLE, UNKNOWN_LEARNER, MISSING_PREREQUISITES }

    private final LearnerRecords learnerRecords;

    public PrerequisitePolicy(LearnerRecords learnerRecords) {
        this.learnerRecords = learnerRecords;
    }

    /** Evaluated from a single read of the learner's record, so the answer is internally consistent. */
    public Eligibility check(CourseOffering offering, String learnerId) {
        return learnerRecords.completedCourses(learnerId)
                .map(completed -> completed.containsAll(offering.prerequisites())
                        ? Eligibility.ELIGIBLE
                        : Eligibility.MISSING_PREREQUISITES)
                .orElse(Eligibility.UNKNOWN_LEARNER);
    }

    public boolean isEligible(CourseOffering offering, String learnerId) {
        return check(offering, learnerId) == Eligibility.ELIGIBLE;
    }
}
