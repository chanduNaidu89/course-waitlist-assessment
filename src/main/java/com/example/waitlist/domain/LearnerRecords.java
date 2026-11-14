package com.example.waitlist.domain;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory stand-in for the learner academic record system: which courses each known learner has
 * completed. Records can change while a learner is waitlisted (e.g. a prerequisite completion is
 * revoked), which is why eligibility is re-checked at promotion time.
 */
public class LearnerRecords {

    private final Map<String, Set<String>> completedCourses = new ConcurrentHashMap<>();

    /** Returns the learner's completed course codes, or empty if the learner is unknown. */
    public Optional<Set<String>> completedCourses(String learnerId) {
        return Optional.ofNullable(completedCourses.get(learnerId));
    }

    public void put(String learnerId, Collection<String> completed) {
        completedCourses.put(learnerId, Set.copyOf(completed));
    }

    public void remove(String learnerId) {
        completedCourses.remove(learnerId);
    }
}
