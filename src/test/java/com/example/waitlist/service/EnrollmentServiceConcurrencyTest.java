package com.example.waitlist.service;

import com.example.waitlist.dto.DropResult;
import com.example.waitlist.dto.EnrollResult;
import com.example.waitlist.dto.OfferingSnapshot;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.waitlist.domain.CourseOffering;
import com.example.waitlist.domain.LearnerRecords;
import com.example.waitlist.domain.PrerequisitePolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;

/**
 * Focused concurrency checks: many threads released at once through a latch to maximise
 * interleaving. Repeated to give races a real chance to surface.
 */
class EnrollmentServiceConcurrencyTest {

    private static final int CAPACITY = 10;
    private static final int LEARNERS = 60;

    private LearnerRecords records;
    private EnrollmentService service;

    @BeforeEach
    void setUp() {
        records = new LearnerRecords();
        for (int i = 0; i < LEARNERS; i++) {
            records.put("L" + i, Set.of("CS101"));
        }
        service = new EnrollmentService(new CourseOffering("CS201", CAPACITY, Set.of("CS101")), new PrerequisitePolicy(records));
    }

    @RepeatedTest(20)
    void concurrentEnrollsNeverOverfillAndEveryoneLandsExactlyOnce() throws Exception {
        List<Callable<EnrollResult>> tasks = new ArrayList<>();
        for (int i = 0; i < LEARNERS; i++) {
            String id = "L" + i;
            tasks.add(() -> service.enroll(id));
            tasks.add(() -> service.enroll(id)); // duplicate request racing the original
        }

        List<EnrollResult> results = runConcurrently(tasks);

        OfferingSnapshot s = service.snapshot();
        assertThat(s.enrolled()).hasSize(CAPACITY);
        assertThat(s.waitlist()).hasSize(LEARNERS - CAPACITY);
        assertThat(results).filteredOn(r -> r.outcome() == EnrollResult.Outcome.ENROLLED).hasSize(CAPACITY);
        assertThat(results).filteredOn(r -> r.outcome() == EnrollResult.Outcome.WAITLISTED)
                .hasSize(LEARNERS - CAPACITY);
        // waitlist positions handed out are unique and contiguous: 1..N
        assertThat(results).filteredOn(r -> r.outcome() == EnrollResult.Outcome.WAITLISTED)
                .extracting(EnrollResult::waitlistPosition)
                .containsExactlyInAnyOrderElementsOf(
                        java.util.stream.IntStream.rangeClosed(1, LEARNERS - CAPACITY).boxed().toList());
        EnrollmentServiceTest.assertInvariants(service);
    }

    @RepeatedTest(20)
    void concurrentDropsPromoteEachWaitingLearnerAtMostOnce() throws Exception {
        for (int i = 0; i < LEARNERS; i++) {
            service.enroll("L" + i);
        }
        List<String> initiallyEnrolled = service.snapshot().enrolled();
        List<Callable<DropResult>> tasks = new ArrayList<>();
        for (String id : initiallyEnrolled) {
            tasks.add(() -> service.drop(id));
            tasks.add(() -> service.drop(id)); // repeated drop racing the original
        }

        List<DropResult> results = runConcurrently(tasks);

        List<DropResult> dropped = results.stream().filter(r -> r.outcome() == DropResult.Outcome.DROPPED).toList();
        assertThat(dropped).hasSize(CAPACITY);
        assertThat(results).filteredOn(r -> r.outcome() == DropResult.Outcome.NOT_ENROLLED).hasSize(CAPACITY);
        // exactly the first CAPACITY waitlisted learners (L10..L19) were promoted, each once
        assertThat(dropped).extracting(DropResult::promotedLearnerId)
                .doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(
                        java.util.stream.IntStream.range(CAPACITY, 2 * CAPACITY).mapToObj(i -> "L" + i).toList());
        OfferingSnapshot s = service.snapshot();
        assertThat(s.enrolled()).hasSize(CAPACITY).doesNotContainAnyElementsOf(initiallyEnrolled);
        assertThat(s.waitlist()).hasSize(LEARNERS - 2 * CAPACITY);
        EnrollmentServiceTest.assertInvariants(service);
    }

    @RepeatedTest(20)
    void concurrentDropsApplySkipPolicyToIneligibleWaitersInFifoOrder() throws Exception {
        for (int i = 0; i < LEARNERS; i++) {
            service.enroll("L" + i);
        }
        // waitlist is L10..L59; revoke every odd-numbered waiter's prerequisite
        for (int i = CAPACITY + 1; i < LEARNERS; i += 2) {
            records.put("L" + i, Set.of());
        }
        List<Callable<DropResult>> tasks = new ArrayList<>();
        for (int i = 0; i < CAPACITY; i++) {
            String id = "L" + i;
            tasks.add(() -> service.drop(id));
        }

        List<DropResult> results = runConcurrently(tasks);

        // each drop is atomic, so the 10 promotions consume the waitlist head strictly in order:
        // L10 promoted, L11 skipped, L12 promoted, ... L27 skipped, L28 promoted
        assertThat(results).extracting(DropResult::promotedLearnerId)
                .containsExactlyInAnyOrder("L10", "L12", "L14", "L16", "L18", "L20", "L22", "L24", "L26", "L28");
        assertThat(results).flatExtracting(DropResult::removedIneligible)
                .containsExactlyInAnyOrder("L11", "L13", "L15", "L17", "L19", "L21", "L23", "L25", "L27");
        assertThat(service.snapshot().waitlist()).first().isEqualTo("L29");
        EnrollmentServiceTest.assertInvariants(service);
    }

    @RepeatedTest(20)
    void mixedConcurrentEnrollsAndDropsPreserveInvariants() throws Exception {
        for (int i = 0; i < CAPACITY; i++) {
            service.enroll("L" + i);
        }
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < CAPACITY; i++) {
            String id = "L" + i;
            tasks.add(() -> service.drop(id));
        }
        for (int i = CAPACITY; i < LEARNERS; i++) {
            String id = "L" + i;
            tasks.add(() -> service.enroll(id));
        }

        runConcurrently(tasks);

        OfferingSnapshot s = service.snapshot();
        // all 10 originals dropped, 50 new eligible requests: seats refilled, remainder waiting
        assertThat(s.enrolled()).hasSize(CAPACITY);
        assertThat(s.waitlist()).hasSize(LEARNERS - 2 * CAPACITY);
        EnrollmentServiceTest.assertInvariants(service);
    }

    private static <T> List<T> runConcurrently(List<? extends Callable<? extends T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<? extends T>> futures = new ArrayList<>();
            for (Callable<? extends T> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<? extends T> f : futures) {
                results.add(f.get(10, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
