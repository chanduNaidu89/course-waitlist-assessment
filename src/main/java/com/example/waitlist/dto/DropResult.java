package com.example.waitlist.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Result of a drop request. When an enrolled learner drops, {@code promotedLearnerId} is the learner
 * who received the freed seat (null if nobody eligible was waiting) and {@code removedIneligible}
 * lists waitlisted learners who were skipped and removed because they were no longer eligible.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DropResult(
        Outcome outcome,
        String learnerId,
        String promotedLearnerId,
        List<String> removedIneligible,
        String message) {

    public enum Outcome {
        /** The learner held a seat and released it; promotion was attempted. */
        DROPPED,
        /** The learner was on the waitlist and has been removed from it. No seat changed hands. */
        LEFT_WAITLIST,
        /** No-op: the learner neither holds a seat nor is waitlisted (includes repeated drops). */
        NOT_ENROLLED,
        /** Rejected: the request itself is malformed (e.g. blank learner id). */
        INVALID_REQUEST
    }

    public static DropResult of(Outcome outcome, String learnerId, String message) {
        return new DropResult(outcome, learnerId, null, null, message);
    }
}
