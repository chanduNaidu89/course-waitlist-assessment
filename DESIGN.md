# Design Specification — Course Waitlist Slice

## 1. Scope and service boundary

The scope is one course offering: enroll, drop, and FIFO waitlist promotion. Nothing else is built:
no catalog, terms, payments, identity, or frontend. No starter project was supplied, so this follows the
brief's fallback: a minimal Spring Boot service with an in-memory store.

```
HTTP (EnrollmentController)  ──►  EnrollmentService  ──►  PrerequisitePolicy ──► LearnerRecords
  thin: outcome → status code       all rules, all state,      "has completed every      in-memory stand-in
                                    one lock                    prerequisite course"     for academic records
```

- **`EnrollmentService`** is the boundary that matters. It owns the seat list and the waitlist, and all
  rules live there. It is a `@Service` with constructor injection only, so unit tests can still build it
  with `new` and need no Spring context.
- **`PrerequisitePolicy`** holds the single prerequisite rule (§4). It is deliberately not a
  configurable policy engine.
- **`LearnerRecords`** stands in for an external academic-record system. It is mutable so tests can
  simulate a learner's eligibility changing while they wait.
- **`EnrollmentController`** only maps outcomes to HTTP statuses. It holds no business logic.

Package layout (`com.example.waitlist`):

| Package | Contents |
|---|---|
| `controller` | `EnrollmentController` |
| `exception` | `ApiExceptionHandler` (`@RestControllerAdvice`), `OfferingNotFoundException` |
| `service` | `EnrollmentService` |
| `dto` | Request/response records: `EnrollRequest`, `EnrollResult`, `DropResult`, `OfferingSnapshot`, `ApiError` |
| `domain` | `CourseOffering`, `LearnerRecords`, `PrerequisitePolicy` |
| `config` | `OfferingProperties` (binds `waitlist.*`), `OpenApiConfig` (Swagger/OpenAPI metadata), `WaitlistConfig` (builds the config-derived `CourseOffering` and `LearnerRecords` beans) |
| root | `WaitlistApplication` |

## 2. Data model (in memory)

| Item | Representation | Why |
|---|---|---|
| Offering | `CourseOffering(code, capacity ≥ 1, prerequisites: Set<String>)`, immutable, from config | One offering only |
| Seats | `LinkedHashSet<String> enrolled` | O(1) membership, so no duplicates; insertion order for display |
| Waitlist | `LinkedHashSet<String> waitlist` | O(1) duplicate check **and** FIFO order in one structure |
| Learner records | `Map<learnerId, Set<completedCourseCode>>` | Enough to evaluate the prerequisite rule |

Learner ids are opaque strings. They must be non-blank, have no leading or trailing whitespace, and be at
most 64 characters (counted in UTF-16 units; control characters are not filtered).

Offering and course codes are compared **case-sensitively**: `cs101` is not `CS101`. Prerequisite codes
must not be blank; a stray empty entry such as `waitlist.prerequisites=CS101,` fails at startup rather
than silently blocking every learner.

## 3. API

Base path: `/api/offerings/{offeringCode}`. Any code other than the configured one returns
`404 OFFERING_NOT_FOUND`.

| Request | Outcome → HTTP status |
|---|---|
| `POST /enrollments` `{"learnerId": "..."}` | `ENROLLED` 201 · `WAITLISTED` 202 (+ `waitlistPosition`, 1-based) · `ALREADY_ENROLLED` 200 · `ALREADY_WAITLISTED` 200 (+ current position) · `PREREQUISITE_NOT_MET` 422 · `LEARNER_NOT_FOUND` 404 · `INVALID_REQUEST` 400 |
| `DELETE /enrollments/{learnerId}` | `DROPPED` 200 (+ `promotedLearnerId`, `removedIneligible[]`) · `LEFT_WAITLIST` 200 · `NOT_ENROLLED` 404 · `INVALID_REQUEST` 400 |
| `GET /` | Snapshot: `capacity`, `seatsAvailable`, `prerequisites`, `enrolled[]`, `waitlist[]` (FIFO order) |

Interactive documentation is served by springdoc-openapi: Swagger UI at `/swagger-ui.html`, the spec at
`/v3/api-docs`. Errors raised outside the service use the `ApiError` DTO.

Every response carries a machine-readable `outcome` and a human-readable `message`. This includes Spring
MVC's own rejections (405, 415, unknown path), which keep their status and headers (`Allow`, `Accept`).
Unexpected errors return 500 `INTERNAL_ERROR`. The one exception is 406, which has no body, since the
client accepts no type we could write it in. Expected business
results are **return values, not exceptions**. That keeps "no state change" easy to reason about: the
service returns before mutating anything.

Duplicate requests return 200 with an `ALREADY_*` or `NOT_ENROLLED` outcome rather than an error. This
makes enroll and drop safe to retry: repeating one never creates a second record or a second promotion.

## 4. Assumptions: prerequisites and waitlist

1. **Prerequisite rule.** A learner is eligible only if they are a known learner **and** their completed
   courses include **every** prerequisite of the offering (logical AND). An offering with no prerequisites
   admits any known learner.
2. **Ineligible at request time → rejected.** They are neither enrolled nor waitlisted
   (`PREREQUISITE_NOT_MET`). The waitlist only ever holds learners who were eligible when they joined.
3. **Ineligible at the front of the queue → skipped and removed.** Eligibility is re-checked at
   promotion time, because records can change while a learner waits (e.g. a completion is revoked).
   A waitlisted learner who is no longer eligible is removed from the waitlist and reported in
   `removedIneligible`. The next learner in FIFO order is then examined, and so on, until someone is
   promoted or the waitlist is empty.
   - *Why skip-and-remove rather than retain:* it matches rule 2, so the waitlist means one thing:
     "eligible and waiting". Retaining a learner who cannot be promoted would make them get re-skipped
     on every drop, while still showing a misleading position number.
   - *Consequence:* if that learner later regains eligibility, they must re-request and join at the
     **back** of the queue. A test covers this.
   - The same policy applies wherever a waitlisted learner is found to be ineligible. There are two
     such places:
     - promotion (`promoteNextEligible`, called from `drop`);
     - a waitlisted learner re-requesting enrollment. They are removed and get `PREREQUISITE_NOT_MET`
       (or `LEARNER_NOT_FOUND`) instead of a misleading `ALREADY_WAITLISTED`.
   - **An enrolled learner keeps their seat** if their records change afterwards. Revoking a held seat
     is a registrar decision and out of scope. A test pins this behaviour.
4. **Unknown learner.** Enrolling returns `LEARNER_NOT_FOUND`. A waitlisted learner whose record is
   deleted counts as ineligible at promotion and is skipped and removed (rule 3).
5. **Drop semantics.** Dropping an enrolled learner frees a seat and triggers promotion (one seat → at
   most one promotion). Dropping a waitlisted learner just removes them from the waitlist; no seat
   changes hands. Dropping someone who is neither is a no-op `NOT_ENROLLED`. That covers repeated drops.
6. **Check order on enroll:** validate input → already enrolled (no-op) → evaluate eligibility once →
   already waitlisted (no-op if still eligible, otherwise removed per rule 3) → unknown learner /
   prerequisites → capacity. Eligibility comes from a **single read** of the learner's record, so a
   record that changes concurrently cannot yield an inconsistent answer.
7. **Re-enrolling after a drop** is a fresh request with no memory of the earlier seat. If the offering is
   full, the learner goes to the back of the waitlist.

## 5. Invariants

These hold whenever the lock is not held. `EnrollmentServiceTest.assertInvariants` checks them after
**every** service test and after every concurrency test.

1. `0 ≤ enrolled.size() ≤ capacity`, and `seatsAvailable = capacity − enrolled.size()`.
2. No learner appears twice, and no learner is in both `enrolled` and `waitlist`.
3. **Waitlist non-empty ⇒ offering full.** A seat is never left open while an eligible learner waits.
   This holds because promotion keeps going until it promotes someone or empties the waitlist.
4. Waitlist order is arrival order. Removals (leave, skip, promotion) never reorder the remaining
   learners.
5. Rejected and no-op requests (`INVALID_REQUEST`, `LEARNER_NOT_FOUND`, `PREREQUISITE_NOT_MET`,
   `ALREADY_*`, `NOT_ENROLLED`) leave the snapshot exactly unchanged. Tests assert snapshot equality
   before and after.

## 6. Concurrency and persistence

- One `ReentrantLock` guards all reads and writes in `EnrollmentService`. Each `enroll` and each `drop`,
  **including the promotion it triggers**, is one critical section. So the read-check-write races
  (two learners taking the last seat, a duplicate enroll racing the original, two drops of the same
  learner promoting twice) cannot happen. Input validation runs before the lock is taken; it is pure.
- A single coarse lock is right here: there is one offering, and each critical section is O(waitlist)
  in-memory work. A real multi-offering system would lock per offering, or rely on database row locks /
  optimistic versioning (see §8).
- `LearnerRecords` uses a `ConcurrentHashMap`. Record updates are not part of the enrollment lock
  (they come from "another system"). Eligibility is read under the enrollment lock at decision time.
- `EnrollmentServiceConcurrencyTest` releases 10–120 tasks at once through a `CountDownLatch` on a
  32-thread pool, repeated 20 times. It checks:
  - concurrent enrolls plus racing duplicates → exactly `capacity` enrolled, every other learner
    waitlisted once, and waitlist positions unique and contiguous;
  - concurrent drops plus racing repeated drops → exactly one `DROPPED` per seat, and exactly the
    first `capacity` waiters promoted, each once;
  - concurrent drops with every other waiter made ineligible → promotions and skips consume the
    queue head strictly in FIFO order;
  - mixed concurrent enrolls and drops → the invariants hold.
- **Persistence:** none, by design. State is lost on restart (allowed by the brief).

## 7. Key tradeoffs

| Decision | Alternative | Reason |
|---|---|---|
| Outcomes as enum return values | Exceptions + `@ControllerAdvice` | No-op and rejection paths are explicit and testable; nothing is half-mutated by an exception |
| Duplicates → 200 with `ALREADY_*` | 409 Conflict | Retries are safe (idempotent in effect); the caller still sees exactly what happened |
| Repeated drop → 404 `NOT_ENROLLED` | 200 no-op | The thing to delete doesn't exist; the body makes the no-op explicit |
| Skip-and-remove ineligible | Retain in place | Consistent with enroll-time rejection; no permanently stuck queue heads (see §4.3) |
| Single coarse lock | Fine-grained / lock-free | Obviously correct, cheap at this scale |
| `@Service` / `@Component` with constructor injection; config-derived beans (`CourseOffering`, `LearnerRecords`) built in `WaitlistConfig` | Field injection, or a fully manual `@Bean` wiring | Conventional Spring layout, and unit tests can still use `new` with no Spring context |

## 8. Limitations and follow-up risks

1. **No durability.** A restart loses all seats and waitlists. A real deployment needs a database. The
   lock would then become a transaction with `SELECT … FOR UPDATE` on the offering row (or an optimistic
   version column), and a unique constraint on `(offering, learner)` to enforce no-duplicates at the
   storage level.
2. **Single instance only.** The JVM lock does not coordinate multiple replicas. Running more than one
   would need the database-level locking above.
3. **Eligibility drift is detected lazily.** A learner who becomes ineligible while waiting is only
   found and removed when a seat opens. Until then they show a waitlist position. A records-change
   event could prune them eagerly.
4. **Silent removal.** Skipped learners appear only in the drop response (`removedIneligible`). There is
   no notification or audit log. In production they should be told, and an audit trail kept.
5. **No authentication or authorization.** Anyone can drop anyone. Identity is out of scope by brief.
6. **No promotion confirmation.** Promotion is instant and automatic; there is no "accept the seat
   within 24h" step.
7. **Capacity is fixed at startup.** Lowering capacity below the current enrollment is not handled
   (there is no API to change it).
8. **Waitlist position lookup is O(n).** Irrelevant at course-sized waitlists.
9. **Minor HTTP edges, left as-is.** The request body is parsed before the offering code is checked,
   so a malformed body sent to an unknown offering gets 400 rather than 404. Jackson also coerces
   `{"learnerId": 123}` to the string `"123"`, which then gets `LEARNER_NOT_FOUND` instead of 400.
   Neither corrupts state. `DELETE .../enrollments/` with an empty id matches no route, so it returns 404
   (`No such endpoint`) rather than 400.
10. **Concurrency tests are probabilistic.** Passing runs show the lock prevents races under heavy
   contention; they do not prove it the way a model checker would. The real guarantee is the code:
   every state access is inside the one lock.
