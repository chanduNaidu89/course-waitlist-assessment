package com.example.waitlist.service;

import com.example.waitlist.dto.DropResult;
import com.example.waitlist.dto.EnrollResult;
import com.example.waitlist.dto.OfferingSnapshot;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.waitlist.domain.CourseOffering;
import com.example.waitlist.domain.LearnerRecords;
import com.example.waitlist.domain.PrerequisitePolicy;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class EnrollmentServiceTest {

    private static final String PREREQ = "CS101";

    private LearnerRecords records;
    private EnrollmentService service;

    @BeforeEach
    void setUp() {
        records = new LearnerRecords();
        for (String eligible : List.of("a", "b", "c", "d", "e")) {
            records.put(eligible, Set.of(PREREQ));
        }
        records.put("noPrereq", Set.of("MATH100"));
        service = newService(2);
    }

    /** Every test ends by checking the structural invariants documented on EnrollmentService. */
    @AfterEach
    void invariantsHold() {
        assertInvariants(service);
    }

    private EnrollmentService newService(int capacity) {
        return new EnrollmentService(new CourseOffering("CS201", capacity, Set.of(PREREQ)), new PrerequisitePolicy(records));
    }

    static void assertInvariants(EnrollmentService service) {
        OfferingSnapshot s = service.snapshot();
        assertThat(s.enrolled()).hasSizeLessThanOrEqualTo(s.capacity());
        assertThat(s.enrolled()).doesNotHaveDuplicates();
        assertThat(s.waitlist()).doesNotHaveDuplicates();
        assertThat(java.util.Collections.disjoint(s.enrolled(), s.waitlist())).as("enrolled and waitlist disjoint").isTrue();
        assertThat(s.seatsAvailable()).isEqualTo(s.capacity() - s.enrolled().size());
        if (!s.waitlist().isEmpty()) {
            assertThat(s.seatsAvailable()).as("waitlist non-empty implies full").isZero();
        }
    }

    @Nested
    class AvailableCapacity {

        @Test
        void eligibleLearnerIsEnrolledWhileSeatsRemain() {
            assertThat(service.enroll("a").outcome()).isEqualTo(EnrollResult.Outcome.ENROLLED);
            assertThat(service.enroll("b").outcome()).isEqualTo(EnrollResult.Outcome.ENROLLED);

            OfferingSnapshot s = service.snapshot();
            assertThat(s.enrolled()).containsExactly("a", "b");
            assertThat(s.waitlist()).isEmpty();
            assertThat(s.seatsAvailable()).isZero();
        }

        @Test
        void offeringWithoutPrerequisitesAdmitsAnyKnownLearner() {
            service = new EnrollmentService(new CourseOffering("OPEN1", 1, Set.of()), new PrerequisitePolicy(records));

            assertThat(service.enroll("noPrereq").outcome()).isEqualTo(EnrollResult.Outcome.ENROLLED);
        }
    }

    @Nested
    class FullCapacity {

        @Test
        void eligibleLearnerJoinsWaitlistInArrivalOrderWhenFull() {
            service.enroll("a");
            service.enroll("b");

            EnrollResult c = service.enroll("c");
            EnrollResult d = service.enroll("d");

            assertThat(c.outcome()).isEqualTo(EnrollResult.Outcome.WAITLISTED);
            assertThat(c.waitlistPosition()).isEqualTo(1);
            assertThat(d.outcome()).isEqualTo(EnrollResult.Outcome.WAITLISTED);
            assertThat(d.waitlistPosition()).isEqualTo(2);
            assertThat(service.snapshot().enrolled()).containsExactly("a", "b");
            assertThat(service.snapshot().waitlist()).containsExactly("c", "d");
        }
    }

    @Nested
    class DuplicateRequests {

        @Test
        void repeatedEnrollOfEnrolledLearnerIsANoOp() {
            service.enroll("a");
            OfferingSnapshot before = service.snapshot();

            EnrollResult again = service.enroll("a");

            assertThat(again.outcome()).isEqualTo(EnrollResult.Outcome.ALREADY_ENROLLED);
            assertThat(service.snapshot()).isEqualTo(before);
        }

        @Test
        void repeatedEnrollOfWaitlistedLearnerKeepsOriginalPosition() {
            service.enroll("a");
            service.enroll("b");
            service.enroll("c");
            service.enroll("d");
            OfferingSnapshot before = service.snapshot();

            EnrollResult again = service.enroll("c");

            assertThat(again.outcome()).isEqualTo(EnrollResult.Outcome.ALREADY_WAITLISTED);
            assertThat(again.waitlistPosition()).isEqualTo(1);
            assertThat(service.snapshot()).isEqualTo(before);
        }

        @Test
        void learnerWhoDroppedCanReEnrollAsANewRequest() {
            service.enroll("a");
            service.drop("a");

            assertThat(service.enroll("a").outcome()).isEqualTo(EnrollResult.Outcome.ENROLLED);
            assertThat(service.snapshot().enrolled()).containsExactly("a");
        }
    }

    @Nested
    class FifoPromotion {

        @Test
        void droppingEnrolledLearnerPromotesEarliestWaitlistedLearner() {
            service.enroll("a");
            service.enroll("b");
            service.enroll("c");
            service.enroll("d");

            DropResult result = service.drop("a");

            assertThat(result.outcome()).isEqualTo(DropResult.Outcome.DROPPED);
            assertThat(result.promotedLearnerId()).isEqualTo("c");
            assertThat(result.removedIneligible()).isEmpty();
            assertThat(service.snapshot().enrolled()).containsExactly("b", "c");
            assertThat(service.snapshot().waitlist()).containsExactly("d");
        }

        @Test
        void successiveDropsPromoteInFifoOrder() {
            for (String id : List.of("a", "b", "c", "d", "e")) {
                service.enroll(id);
            }

            assertThat(service.drop("a").promotedLearnerId()).isEqualTo("c");
            assertThat(service.drop("b").promotedLearnerId()).isEqualTo("d");
            assertThat(service.drop("c").promotedLearnerId()).isEqualTo("e");
            assertThat(service.snapshot().enrolled()).containsExactly("d", "e");
            assertThat(service.snapshot().waitlist()).isEmpty();
        }

        @Test
        void dropWithEmptyWaitlistLeavesSeatOpen() {
            service.enroll("a");
            service.enroll("b");

            DropResult result = service.drop("a");

            assertThat(result.outcome()).isEqualTo(DropResult.Outcome.DROPPED);
            assertThat(result.promotedLearnerId()).isNull();
            assertThat(service.snapshot().seatsAvailable()).isEqualTo(1);
        }

        @Test
        void waitlistedLearnerLeavingDoesNotChangeSeatsAndPreservesOthersOrder() {
            for (String id : List.of("a", "b", "c", "d", "e")) {
                service.enroll(id);
            }

            DropResult result = service.drop("d");

            assertThat(result.outcome()).isEqualTo(DropResult.Outcome.LEFT_WAITLIST);
            assertThat(service.snapshot().enrolled()).containsExactly("a", "b");
            assertThat(service.snapshot().waitlist()).containsExactly("c", "e");
            assertThat(service.drop("a").promotedLearnerId()).isEqualTo("c");
        }
    }

    @Nested
    class PrerequisiteFailure {

        @Test
        void ineligibleLearnerIsRejectedWhenSeatsAreAvailable() {
            EnrollResult result = service.enroll("noPrereq");

            assertThat(result.outcome()).isEqualTo(EnrollResult.Outcome.PREREQUISITE_NOT_MET);
            assertThat(service.snapshot().enrolled()).isEmpty();
            assertThat(service.snapshot().waitlist()).isEmpty();
        }

        @Test
        void ineligibleLearnerIsNotWaitlistedWhenFull() {
            service.enroll("a");
            service.enroll("b");

            EnrollResult result = service.enroll("noPrereq");

            assertThat(result.outcome()).isEqualTo(EnrollResult.Outcome.PREREQUISITE_NOT_MET);
            assertThat(service.snapshot().waitlist()).isEmpty();
        }

        @Test
        void partialPrerequisitesAreNotEnough() {
            records.put("partial", Set.of("CS101"));
            service = new EnrollmentService(new CourseOffering("CS301", 2, Set.of("CS101", "CS201")), new PrerequisitePolicy(records));

            assertThat(service.enroll("partial").outcome()).isEqualTo(EnrollResult.Outcome.PREREQUISITE_NOT_MET);
        }

        @Test
        void learnerWhoBecameIneligibleAtFrontIsSkippedAndRemoved() {
            for (String id : List.of("a", "b", "c", "d")) {
                service.enroll(id);
            }
            records.put("c", new HashSet<>()); // c's prerequisite completion revoked while waiting

            DropResult result = service.drop("a");

            assertThat(result.promotedLearnerId()).isEqualTo("d");
            assertThat(result.removedIneligible()).containsExactly("c");
            assertThat(service.snapshot().enrolled()).containsExactly("b", "d");
            assertThat(service.snapshot().waitlist()).doesNotContain("c").isEmpty();
        }

        @Test
        void skipPolicyIsAppliedConsistentlyAcrossMultipleIneligibleLearners() {
            records.put("f", Set.of(PREREQ));
            for (String id : List.of("a", "b", "c", "d", "e", "f")) {
                service.enroll(id);
            }
            records.put("c", Set.of());
            records.remove("d"); // learner record deleted entirely: also ineligible

            DropResult result = service.drop("a");

            assertThat(result.promotedLearnerId()).isEqualTo("e");
            assertThat(result.removedIneligible()).containsExactly("c", "d");
            assertThat(service.snapshot().waitlist()).containsExactly("f");
        }

        @Test
        void whenEveryWaitingLearnerIsIneligibleTheSeatStaysOpenAndWaitlistEmpties() {
            for (String id : List.of("a", "b", "c", "d")) {
                service.enroll(id);
            }
            records.put("c", Set.of());
            records.put("d", Set.of());

            DropResult result = service.drop("a");

            assertThat(result.promotedLearnerId()).isNull();
            assertThat(result.removedIneligible()).containsExactly("c", "d");
            assertThat(service.snapshot().seatsAvailable()).isEqualTo(1);
            assertThat(service.snapshot().waitlist()).isEmpty();
            // the open seat is immediately available to the next eligible request
            assertThat(service.enroll("e").outcome()).isEqualTo(EnrollResult.Outcome.ENROLLED);
        }

        @Test
        void waitlistedLearnerWhoBecameIneligibleIsRemovedWhenTheyReRequest() {
            for (String id : List.of("a", "b", "c", "d")) {
                service.enroll(id);
            }
            records.put("c", Set.of());

            EnrollResult result = service.enroll("c");

            assertThat(result.outcome()).isEqualTo(EnrollResult.Outcome.PREREQUISITE_NOT_MET);
            assertThat(service.snapshot().waitlist()).containsExactly("d");
        }

        @Test
        void waitlistedLearnerWhoseRecordWasDeletedIsRemovedWhenTheyReRequest() {
            for (String id : List.of("a", "b", "c")) {
                service.enroll(id);
            }
            records.remove("c");

            assertThat(service.enroll("c").outcome()).isEqualTo(EnrollResult.Outcome.LEARNER_NOT_FOUND);
            assertThat(service.snapshot().waitlist()).isEmpty();
        }

        @Test
        void enrolledLearnerKeepsSeatIfRecordsChangeAfterEnrollment() {
            service.enroll("a");
            records.put("a", Set.of());

            assertThat(service.enroll("a").outcome()).isEqualTo(EnrollResult.Outcome.ALREADY_ENROLLED);
            assertThat(service.snapshot().enrolled()).containsExactly("a");
        }

        @Test
        void skippedLearnerWhoRegainsEligibilityRejoinsAtTheBack() {
            for (String id : List.of("a", "b", "c", "d")) {
                service.enroll(id);
            }
            records.put("c", Set.of());
            service.drop("a"); // c removed, d promoted
            records.put("c", Set.of(PREREQ));
            service.enroll("e");

            EnrollResult result = service.enroll("c");

            assertThat(result.outcome()).isEqualTo(EnrollResult.Outcome.WAITLISTED);
            assertThat(service.snapshot().waitlist()).containsExactly("e", "c");
        }
    }

    @Nested
    class CapacityEdges {

        @Test
        void capacityOneCyclesSeatThroughWaitlist() {
            service = newService(1);
            service.enroll("a");
            service.enroll("b");

            assertThat(service.drop("a").promotedLearnerId()).isEqualTo("b");
            assertThat(service.snapshot().enrolled()).containsExactly("b");
            assertThat(service.snapshot().waitlist()).isEmpty();
        }

        @Test
        void offeringRejectsInvalidDefinition() {
            assertThatThrownBy(() -> new CourseOffering("CS201", 0, Set.of()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new CourseOffering(" ", 1, Set.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class InvalidAndRepeatedDrops {

        @Test
        void repeatedDropIsANoOpAndDoesNotPromoteAgain() {
            for (String id : List.of("a", "b", "c", "d")) {
                service.enroll(id);
            }
            service.drop("a"); // c promoted
            OfferingSnapshot afterFirstDrop = service.snapshot();

            DropResult second = service.drop("a");

            assertThat(second.outcome()).isEqualTo(DropResult.Outcome.NOT_ENROLLED);
            assertThat(service.snapshot()).isEqualTo(afterFirstDrop);
            assertThat(service.snapshot().waitlist()).containsExactly("d");
        }

        @Test
        void dropOfLearnerNeverInOfferingIsANoOp() {
            service.enroll("a");
            OfferingSnapshot before = service.snapshot();

            assertThat(service.drop("zed").outcome()).isEqualTo(DropResult.Outcome.NOT_ENROLLED);
            assertThat(service.snapshot()).isEqualTo(before);
        }

        @Test
        void dropOnEmptyOfferingDoesNotPushCapacityAboveConfigured() {
            service.drop("a");
            service.drop("a");

            assertThat(service.snapshot().seatsAvailable()).isEqualTo(2);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", " a", "a "})
        void invalidLearnerIdIsRejectedWithoutStateChange(String learnerId) {
            service.enroll("a");
            OfferingSnapshot before = service.snapshot();

            assertThat(service.enroll(learnerId).outcome()).isEqualTo(EnrollResult.Outcome.INVALID_REQUEST);
            assertThat(service.drop(learnerId).outcome()).isEqualTo(DropResult.Outcome.INVALID_REQUEST);
            assertThat(service.snapshot()).isEqualTo(before);
        }

        @Test
        void overlongLearnerIdIsRejected() {
            String tooLong = "x".repeat(EnrollmentService.MAX_LEARNER_ID_LENGTH + 1);

            assertThat(service.enroll(tooLong).outcome()).isEqualTo(EnrollResult.Outcome.INVALID_REQUEST);
        }

        @Test
        void unknownLearnerIsRejectedWithoutStateChange() {
            EnrollResult result = service.enroll("ghost");

            assertThat(result.outcome()).isEqualTo(EnrollResult.Outcome.LEARNER_NOT_FOUND);
            assertThat(service.snapshot().enrolled()).isEmpty();
            assertThat(service.snapshot().waitlist()).isEmpty();
        }
    }
}
