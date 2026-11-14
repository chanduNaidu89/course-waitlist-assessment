# Implementation Steps — Course Waitlist Assessment

This file lists, in order, the steps used to build this solution. Each step names the files it
produces and how to check it before moving on. Design reasoning lives in [`DESIGN.md`](DESIGN.md);
the AI-use record is [`AI_USAGE.md`](AI_USAGE.md).

---

## Step 0 — Understand the brief and the starting point

1. Read the assessment and pull out the acceptance criteria (AC 1–7), the scope limits and the
   deliverables.
2. Look for a starter project. **None was supplied**, so use the brief's fallback: a minimal service
   with an in-memory store. This means no database, no frontend and no auth.
3. Check the toolchain: `java -version` (JDK 17+) and `mvn -v` (Maven 3.9+).

**Output:** a short list of what must be built and what must *not* be built.

---

## Step 1 — Decide the rules before writing code

Write these down first, because every test depends on them. See DESIGN §4.

| Question | Decision |
|---|---|
| Prerequisite rule | The learner is known **and** has completed **every** prerequisite course of the offering |
| Ineligible learner requests a seat | Rejected (`PREREQUISITE_NOT_MET`): not enrolled, not waitlisted |
| Ineligible learner reaches the front of the waitlist | **Skipped and removed**; the next learner in FIFO order is checked |
| Waitlisted learner re-requests after becoming ineligible | Same rule: removed and rejected |
| Enrolled learner's records change later | Keeps the seat |
| Duplicate enroll | No-op: `ALREADY_ENROLLED` / `ALREADY_WAITLISTED` (position unchanged) |
| Drop of an enrolled learner | Seat freed, then promote the earliest eligible waiter |
| Drop of a waitlisted learner | Removed from the waitlist; no seat change |
| Repeated or unknown drop | No-op: `NOT_ENROLLED` |
| Concurrency | One lock around every operation, including the promotion it triggers |

Also write the **invariants** that tests will check after every operation:
1. Seats used ≤ capacity.
2. No learner appears twice, or in both lists.
3. If the waitlist is non-empty, the offering is full.
4. The waitlist keeps arrival order.

---

## Step 2 — Create the project skeleton

1. Create `pom.xml`:
   - Parent: Spring Boot **3.5.6**.
   - `java.version` **17**.
   - Dependencies: `spring-boot-starter-web`, `spring-boot-starter-validation`,
     `spring-boot-starter-test` (test scope).
   - Surefire `argLine`: `@{argLine} -javaagent:<mockito-core jar>`. Without it, Mockito fails to
     self-attach on recent JDKs. The empty `<argLine></argLine>` property lets other plugins
     (e.g. JaCoCo) still add their own agents.
2. Create the package layout under `src/main/java/com/example/waitlist/`:

   ```
   config/      config binding (OfferingProperties) + Spring bean wiring
   controller/  HTTP layer
   exception/   exceptions + global exception handler
   service/     business rules + state
   dto/         request/response records
   domain/      offering, learner records, prerequisite rule
   ```
3. Add `WaitlistApplication.java` with `@SpringBootApplication` and `@ConfigurationPropertiesScan`.
4. Add `.gitignore` (`target/`, IDE files).

**Check:** `mvn -o compile` succeeds. Drop `-o` if you have network access.

---

## Step 3 — Domain model (`domain/`)

| File | What it does |
|---|---|
| `CourseOffering` (record) | `code`, `capacity`, `prerequisites`. The constructor rejects a blank code, capacity < 1 and blank prerequisite codes |
| `LearnerRecords` | In-memory `ConcurrentHashMap<learnerId, Set<completedCourse>>`, with `completedCourses`, `put`, `remove` |
| `PrerequisitePolicy` (`@Component`) | `check()` returns `ELIGIBLE` / `UNKNOWN_LEARNER` / `MISSING_PREREQUISITES` from **one** read of the record |

**Check:** the code compiles; the unit tests come in Step 7.

---

## Step 4 — Request/response DTOs (`dto/`)

| File | Contents |
|---|---|
| `EnrollRequest` | `learnerId` |
| `EnrollResult` | `outcome` enum (`ENROLLED`, `WAITLISTED`, `ALREADY_ENROLLED`, `ALREADY_WAITLISTED`, `PREREQUISITE_NOT_MET`, `LEARNER_NOT_FOUND`, `INVALID_REQUEST`), `waitlistPosition`, `message` |
| `DropResult` | `outcome` enum (`DROPPED`, `LEFT_WAITLIST`, `NOT_ENROLLED`, `INVALID_REQUEST`), `promotedLearnerId`, `removedIneligible[]`, `message` |
| `OfferingSnapshot` | `capacity`, `seatsAvailable`, `prerequisites`, `enrolled[]`, `waitlist[]` |

Expected business results are **return values, not exceptions**. That makes "no state change on
rejection" easy to guarantee and to test.

---

## Step 5 — The service (`service/EnrollmentService`, `@Service`)

1. **State:** `LinkedHashSet<String> enrolled` and `LinkedHashSet<String> waitlist`. Each gives O(1)
   duplicate checks plus FIFO order.
2. **Lock:** one `ReentrantLock`. Every public method runs `lock(); try { ... } finally { unlock(); }`.
3. **`enroll(learnerId)`**, checked in this order:
   1. Validate the id: not blank, no surrounding whitespace, ≤ 64 characters. Invalid →
      `INVALID_REQUEST`.
   2. Already enrolled → `ALREADY_ENROLLED`.
   3. Evaluate eligibility once.
   4. Already waitlisted → `ALREADY_WAITLISTED` if still eligible; otherwise remove from the waitlist
      and continue to the rejection below.
   5. Unknown learner → `LEARNER_NOT_FOUND`.
   6. Prerequisites missing → `PREREQUISITE_NOT_MET`.
   7. Seat free → `ENROLLED`.
   8. Otherwise append to the waitlist → `WAITLISTED` with its position.
4. **`drop(learnerId)`:**
   - If enrolled: remove, then `promoteNextEligible()`. That pops the waitlist head in a loop: if the
     head is eligible, it is enrolled and the loop stops; if not, it is added to `removedIneligible`
     and the loop continues.
   - Else if waitlisted: remove → `LEFT_WAITLIST`.
   - Else `NOT_ENROLLED`.
5. **`snapshot()`:** copies of both lists under the lock. The prerequisites are sorted once, in the
   constructor.

**Check:** write the Step 7 service tests alongside this step and run them.

---

## Step 6 — Wiring and HTTP layer

1. **`config/OfferingProperties`** (record): binds `waitlist.*`. A null learner value becomes "no
   completed courses".
2. **`config/WaitlistConfig`:** `@Bean`s for `LearnerRecords` (seeded from properties) and
   `CourseOffering`. `EnrollmentService` and `PrerequisitePolicy` are component-scanned and get these
   by constructor injection.
3. **`src/main/resources/application.properties`:** offering `CS201`, capacity 2, prerequisite `CS101`,
   and seed learners. Use the **bracket** form, `waitlist.learners[alice]=CS101`, so characters such
   as `@` in learner ids are not stripped.
4. **`controller/EnrollmentController`** at `/api/offerings/{offeringCode}`. It only maps outcomes to
   HTTP statuses:

   | Endpoint | Status mapping |
   |---|---|
   | `POST /enrollments` | 201 `ENROLLED` · 202 `WAITLISTED` · 200 `ALREADY_*` · 422 `PREREQUISITE_NOT_MET` · 404 `LEARNER_NOT_FOUND` · 400 `INVALID_REQUEST` |
   | `DELETE /enrollments/{learnerId}` | 200 `DROPPED` / `LEFT_WAITLIST` · 404 `NOT_ENROLLED` · 400 `INVALID_REQUEST` |
   | `GET /` | 200 snapshot |

   An unknown `offeringCode` throws `OfferingNotFoundException`, which becomes a 404.
5. **`exception/ApiExceptionHandler`** (`@RestControllerAdvice`) keeps every error in the
   `{outcome, message}` shape:
   - Bad JSON → 400.
   - Spring MVC's own errors (405, 415, unknown path) keep their status **and headers** (`Allow`,
     `Accept`).
   - 406 returns status only, with no body.
   - Anything else → 500 `INTERNAL_ERROR`.

6. **API documentation (Swagger UI):**
   - Add `org.springdoc:springdoc-openapi-starter-webmvc-ui` (2.8.x, for Spring Boot 3.5).
   - Add `config/OpenApiConfig` for the title, version and description.
   - On the controller: `@Tag`, plus `@Operation` and one `@ApiResponse` per status code and outcome.
   - Add `@Schema` examples on the DTOs.
   - Return the `ApiError` DTO from the exception handler, so error responses have a documented
     schema.

**Check:** `./run.sh` starts the app, and http://localhost:8080/swagger-ui.html shows the API.
`./run.sh test` runs the curl scenario suites (Step 7b).

---

## Step 7 — Tests (AC 6)

| Test class | Covers |
|---|---|
| `service/EnrollmentServiceTest` | Nested groups: `AvailableCapacity`, `FullCapacity`, `DuplicateRequests`, `FifoPromotion`, `PrerequisiteFailure` (incl. the skip policy), `CapacityEdges`, `InvalidAndRepeatedDrops`. An `@AfterEach` **asserts the invariants after every test**, and no-op cases assert the snapshot is unchanged |
| `service/EnrollmentServiceConcurrencyTest` | Latch-released 32-thread pool, each test repeated ×20: concurrent enrolls with racing duplicates; concurrent drops with racing repeated drops; skip policy under contention; mixed enroll/drop |
| `controller/EnrollmentControllerTest` | MockMvc end-to-end through `@SpringBootTest` with the real properties: full workflow, 400/404/405/406/415/422, headers |
| `exception/ApiExceptionHandlerTest` | 500 shape; exception text not leaked |
| `controller/LearnerBindingTest` | Bracketed config keys keep learner ids verbatim |
| `controller/OpenApiDocsTest` | The spec lists every endpoint and status code; Swagger UI is served |
| `config/OfferingPropertiesTest` | Null learner value becomes no completed courses |
| `domain/CourseOfferingTest` | Blank prerequisite code rejected |

**Check:** `mvn test` → `Tests run: 133, Failures: 0, Errors: 0`, `BUILD SUCCESS`.

### Step 7b — curl scenario tests against the running app

1. **`run.sh`:** a single entry point with the commands `run`, `test`, `unit`, `all`. For `test` it
   builds the jar and starts a fresh app per suite. It waits until a GET returns 200, runs the suite,
   then stops the app.
2. **`scripts/curl-tests.sh`:** needs only bash 3.2 and curl. Every scenario checks the HTTP status,
   the `outcome`, and the resulting `enrolled` / `waitlist` / `seatsAvailable`.
   - `functional`: 20 scenarios in sequence.
   - `concurrency`: parallel enrolls and drops, with racing duplicates.

**Check:** `./run.sh test` → `functional suite: 134 passed, 0 failed` and
`concurrency suite: 17 passed, 0 failed`.

---

## Step 8 — Prove the tests can catch bugs

Inject one bug at a time, run `mvn test`, and confirm it fails. Then restore the code.

| Inject | Expect |
|---|---|
| Capacity check `<` → `<=` | Many failures |
| Remove the lock in `enroll` | Concurrency tests fail |
| Promotion skips the waitlist head | FIFO tests fail |
| Drop the header copy in `ApiExceptionHandler` | 405/415 header tests fail |

The results are recorded in `AI_USAGE.md` §4b.

---

## Step 9 — Independent review

1. Have the code reviewed (this project used an AI reviewer and `/code-review`).
2. For **each** finding, decide accept, edit or reject, and write down why.
3. **Verify each claim before acting on it.** For example, one reviewer said `_` is stripped from
   config keys. A test proved that wrong, but showed that `@` and `/` are stripped.
4. After each change, re-run `mvn test`.

---

## Step 10 — Write the deliverables

| Deliverable | File |
|---|---|
| Design spec: boundary, data/API, invariants, promotion rule, concurrency, assumptions, tradeoffs | `DESIGN.md` |
| AI prompt and use record, with accept/edit/reject decisions and verification evidence | `AI_USAGE.md` |
| Setup, run and test commands | `README.md` |
| Limitations and follow-up risks | `DESIGN.md` §8 |
| These steps | `IMPLEMENTATION_STEPS.md` |

---

## Step 11 — Final checks before submission (candidate)

- [ ] `mvn test` passes on your machine.
- [ ] `./run.sh test` passes both curl suites on your machine.
- [ ] You have reviewed the DESIGN §4 policy decisions and agree with them, or changed them.
- [ ] You have filled in **AI_USAGE.md §5 (Candidate's own review)**. This is required evidence of
      independent review.
- [ ] There are no credentials or personal data in any file or prompt record.
