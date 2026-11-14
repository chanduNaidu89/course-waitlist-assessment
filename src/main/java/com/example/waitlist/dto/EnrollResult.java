package com.example.waitlist.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Result of an enrollment request. {@code waitlistPosition} is 1-based and only set when waitlisted. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EnrollResult(Outcome outcome, String learnerId, Integer waitlistPosition, String message) {

    public enum Outcome {
        /** A seat was available and the learner now holds it. */
        ENROLLED,
        /** The offering was full; the learner was appended to the waitlist. */
        WAITLISTED,
        /** No-op: the learner already holds a seat. */
        ALREADY_ENROLLED,
        /** No-op: the learner is already on the waitlist; position is unchanged. */
        ALREADY_WAITLISTED,
        /** Rejected: the learner does not satisfy the prerequisite rule. Not enrolled, not waitlisted. */
        PREREQUISITE_NOT_MET,
        /** Rejected: the learner id is not a known learner. */
        LEARNER_NOT_FOUND,
        /** Rejected: the request itself is malformed (e.g. blank learner id). */
        INVALID_REQUEST
    }

    public static EnrollResult of(Outcome outcome, String learnerId, String message) {
        return new EnrollResult(outcome, learnerId, null, message);
    }

    public static EnrollResult waitlisted(Outcome outcome, String learnerId, int position, String message) {
        return new EnrollResult(outcome, learnerId, position, message);
    }
}
