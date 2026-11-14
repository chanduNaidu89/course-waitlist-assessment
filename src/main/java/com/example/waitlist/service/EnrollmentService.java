package com.example.waitlist.service;

import com.example.waitlist.domain.CourseOffering;
import com.example.waitlist.domain.PrerequisitePolicy.Eligibility;
import com.example.waitlist.domain.PrerequisitePolicy;
import com.example.waitlist.dto.DropResult.Outcome;
import com.example.waitlist.dto.DropResult;
import com.example.waitlist.dto.EnrollResult;
import com.example.waitlist.dto.OfferingSnapshot;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Service;

/**
 * Enrollment, drop and FIFO waitlist promotion for a single course offering.
 *
 * <p>Invariants (hold whenever the lock is not held):
 * <ol>
 *   <li>{@code enrolled.size() <= capacity}.
 *   <li>A learner appears at most once, and never in both {@code enrolled} and {@code waitlist}.
 *   <li>If the waitlist is non-empty, the offering is full ({@code enrolled.size() == capacity}).
 *   <li>The waitlist is in arrival order; a learner's relative order never changes while waiting.
 * </ol>
 *
 * <p>Ineligible-at-the-front policy: when a seat opens, waitlisted learners are examined in FIFO
 * order and their eligibility is re-checked. A learner who is no longer eligible is <b>skipped and
 * removed</b> from the waitlist (reported in {@link DropResult#removedIneligible()}); the next
 * learner is then examined. This matches enroll-time behaviour, where an ineligible learner is
 * rejected rather than waitlisted, so the waitlist only ever holds learners believed to be eligible.
 * The same rule applies if a waitlisted learner re-requests enrollment after becoming ineligible.
 * An already-enrolled learner keeps their seat even if their records later change.
 *
 * <p>Concurrency: every read and mutation runs under a single {@link ReentrantLock}, so each
 * enroll / drop (including the promotion it triggers) is atomic with respect to the others.
 */
@Service
public class EnrollmentService {

    static final int MAX_LEARNER_ID_LENGTH = 64;

    private final CourseOffering offering;
    private final PrerequisitePolicy prerequisitePolicy;
    // Sorted once: Set.copyOf iteration order varies between JVM runs, and the offering is immutable.
    private final List<String> sortedPrerequisites;

    private final ReentrantLock lock = new ReentrantLock();
    // LinkedHashSet gives O(1) membership checks plus stable insertion (seat-grant / FIFO) order.
    private final LinkedHashSet<String> enrolled = new LinkedHashSet<>();
    private final LinkedHashSet<String> waitlist = new LinkedHashSet<>();

    public EnrollmentService(CourseOffering offering, PrerequisitePolicy prerequisitePolicy) {
        this.offering = offering;
        this.prerequisitePolicy = prerequisitePolicy;
        this.sortedPrerequisites = offering.prerequisites().stream().sorted().toList();
    }

    public CourseOffering offering() {
        return offering;
    }

    public EnrollResult enroll(String learnerId) {
        String invalid = validateLearnerId(learnerId);
        if (invalid != null) {
            return EnrollResult.of(EnrollResult.Outcome.INVALID_REQUEST, learnerId, invalid);
        }
        lock.lock();
        try {
            if (enrolled.contains(learnerId)) {
                return EnrollResult.of(EnrollResult.Outcome.ALREADY_ENROLLED, learnerId,
                        "Learner is already enrolled; no change made.");
            }
            Eligibility eligibility = prerequisitePolicy.check(offering, learnerId);
            if (waitlist.contains(learnerId)) {
                if (eligibility == Eligibility.ELIGIBLE) {
                    return EnrollResult.waitlisted(EnrollResult.Outcome.ALREADY_WAITLISTED, learnerId,
                            positionOf(learnerId), "Learner is already on the waitlist; position unchanged.");
                }
                // Same skip-and-remove policy as promotion: an ineligible learner never stays waiting.
                waitlist.remove(learnerId);
            }
            if (eligibility == Eligibility.UNKNOWN_LEARNER) {
                return EnrollResult.of(EnrollResult.Outcome.LEARNER_NOT_FOUND, learnerId,
                        "Unknown learner.");
            }
            if (eligibility == Eligibility.MISSING_PREREQUISITES) {
                return EnrollResult.of(EnrollResult.Outcome.PREREQUISITE_NOT_MET, learnerId,
                        "Learner has not completed all prerequisites " + sortedPrerequisites
                                + "; not enrolled or waitlisted.");
            }
            if (enrolled.size() < offering.capacity()) {
                enrolled.add(learnerId);
                return EnrollResult.of(EnrollResult.Outcome.ENROLLED, learnerId, "Enrolled.");
            }
            waitlist.add(learnerId);
            return EnrollResult.waitlisted(EnrollResult.Outcome.WAITLISTED, learnerId, waitlist.size(),
                    "Offering is full; learner added to the waitlist.");
        } finally {
            lock.unlock();
        }
    }

    public DropResult drop(String learnerId) {
        String invalid = validateLearnerId(learnerId);
        if (invalid != null) {
            return DropResult.of(Outcome.INVALID_REQUEST, learnerId, invalid);
        }
        lock.lock();
        try {
            if (enrolled.remove(learnerId)) {
                List<String> removedIneligible = new ArrayList<>();
                String promoted = promoteNextEligible(removedIneligible);
                String message = promoted != null
                        ? "Dropped; seat given to " + promoted + "."
                        : "Dropped; no eligible learner waiting, seat left open.";
                return new DropResult(Outcome.DROPPED, learnerId, promoted, List.copyOf(removedIneligible),
                        message);
            }
            if (waitlist.remove(learnerId)) {
                return DropResult.of(Outcome.LEFT_WAITLIST, learnerId, "Removed from the waitlist.");
            }
            return DropResult.of(Outcome.NOT_ENROLLED, learnerId,
                    "Learner is neither enrolled nor waitlisted; no change made.");
        } finally {
            lock.unlock();
        }
    }

    public OfferingSnapshot snapshot() {
        lock.lock();
        try {
            return new OfferingSnapshot(
                    offering.code(),
                    offering.capacity(),
                    offering.capacity() - enrolled.size(),
                    sortedPrerequisites,
                    List.copyOf(enrolled),
                    List.copyOf(waitlist));
        } finally {
            lock.unlock();
        }
    }

    /** Must be called with the lock held, immediately after a seat has been released. */
    private String promoteNextEligible(List<String> removedIneligible) {
        Iterator<String> it = waitlist.iterator();
        while (it.hasNext()) {
            String candidate = it.next();
            it.remove();
            if (prerequisitePolicy.isEligible(offering, candidate)) {
                enrolled.add(candidate);
                return candidate;
            }
            removedIneligible.add(candidate);
        }
        return null;
    }

    /** Must be called with the lock held. 1-based. */
    private int positionOf(String learnerId) {
        int position = 1;
        for (String waiting : waitlist) {
            if (waiting.equals(learnerId)) {
                return position;
            }
            position++;
        }
        throw new IllegalStateException("learner not on waitlist: " + learnerId);
    }

    private static String validateLearnerId(String learnerId) {
        if (learnerId == null || learnerId.isBlank()) {
            return "learnerId is required.";
        }
        if (!learnerId.equals(learnerId.strip())) {
            return "learnerId must not have leading or trailing whitespace.";
        }
        if (learnerId.length() > MAX_LEARNER_ID_LENGTH) {
            return "learnerId must be at most " + MAX_LEARNER_ID_LENGTH + " characters.";
        }
        return null;
    }
}
