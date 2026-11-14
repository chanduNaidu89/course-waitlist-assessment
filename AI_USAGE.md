# AI Prompt and Use Record

**Tool:** Claude Code (Anthropic CLI agent), model Claude Opus 5.5, running locally in this
project directory. **Date:** 2026-11-14.
**Sensitive data:** None was sent. The prompts contain only the assessment brief and project code.
There are no credentials, and the learner ids are made up (`alice`, `L0`…).

## Candid summary of what AI contributed

The AI did a lot here, so this record does not understate it:

- **Implementation, tests and docs:** Claude Code generated all of the code, tests and documents in
  this repository from a single prompt (Activity 1).
- **Review:** a second, independent Claude agent then reviewed the result (Activity 2). Its findings
  were triaged one by one: some accepted, some edited, some rejected (table below).
- **Verification:** all changes were verified by commands whose output is recorded in §4.
- **Human review:** the section at the end is for the candidate's own review. It is intentionally
  left for the candidate to complete; the AI must not fill it in for them.

---

## Activity 1 — Implementation (task decomposition, design, code, tests, docs)

**Prompt (verbatim):**

> `/Users/.../CHANDRASEKHAR RAO NAIDU_AI_Assessment.pdf  implement this assessment`

**What the AI did, in order:**

1. **Read the brief and inspected the workspace.** The project directory was empty, with no starter
   project. Following the brief, it chose the "minimal service with in-memory store" fallback.
   - It picked Spring Boot 3.5.6 / Java 21 because those artifacts were already in the local Maven
     cache and Maven Central was not reachable from the sandbox.
2. **Decomposed the task** into the domain (offering, learner records, prerequisite rule) →
   `EnrollmentService` (state + rules + lock) → a thin HTTP adapter → tests per acceptance criterion
   → docs.
3. **Made the key design decisions.** The AI chose each of these; the reasoning is in `DESIGN.md`:
   - Prerequisite = completed **all** listed courses.
   - Ineligible on request → rejected, not waitlisted.
   - Ineligible at the queue head → **skip and remove** (retaining them was considered and
     rejected; see DESIGN §4.3).
   - One `ReentrantLock` around every operation.
   - Outcomes as enum return values rather than exceptions.
   - `LinkedHashSet` for both the seat list and the waitlist.
4. **Wrote the code and tests:**
   - `EnrollmentServiceTest`, grouped into nested classes matching acceptance criterion 6. An
     `@AfterEach` checks the invariants after every test.
   - `EnrollmentServiceConcurrencyTest`: a latch-released thread pool, repeated 20×.
   - `EnrollmentControllerTest`: MockMvc.

**Problems found while running the tests (AI-detected, AI-fixed):**

| # | Problem | Resolution |
|---|---|---|
| 1a | Every service test errored in `assertInvariants`: AssertJ's `doesNotContainAnyElementsOf(emptyList)` throws `IllegalArgumentException`. The defect was in the test helper, not the service. | **Edited:** replaced with `Collections.disjoint(...)`. |
| 1b | All 5 web tests errored: `Could not self-attach to current VM`. Spring Boot's test listeners load Mockito, which tries to attach a byte-buddy agent dynamically; that is blocked on JDK 25 / this sandbox. | **Edited:** the first fix (`maven-dependency-plugin:properties`) was **rejected** because the plugin was not in the offline cache. Final fix: Surefire `argLine -javaagent:` pointing at the Mockito jar in the local repo (`pom.xml`). |
| 1c | Manual smoke test of the running app (`mvn spring-boot:run` + `curl`) was not possible. Tomcat could not bind a port (`SocketException: Operation not permitted`), and the sandbox security policy blocks bypassing the HTTP proxy. | **Not worked around** (the policy was respected). The HTTP layer is verified by `@SpringBootTest` + MockMvc, which boots the full context with the real `application.properties`. The candidate should run the `curl` script in the README on their own machine. |

**Verification after Activity 1:** `mvn -o test` gave `Tests run: 92, Failures: 0, Errors: 0`,
`BUILD SUCCESS`.

---

## Activity 2 — Independent adversarial code review

**Prompt (sent to a separate Claude agent with no write access; abridged only by removing the file
list):**

> You are reviewing a small Spring Boot service … It implements enrollment, drop, and FIFO waitlist
> promotion for ONE course offering with an in-memory store. Do NOT modify any files … Requirements it
> must meet: [acceptance criteria 1–6 and the documented skip-and-remove policy] … Please adversarially
> look for: (a) correctness bugs or invariant violations … (b) HTTP-layer issues … (c) concurrency
> holes; (d) important missing test cases …; (e) anything over-engineered … Try to actually confirm
> suspected bugs … Report a concise numbered list … severity, file:line, concrete failure scenario,
> suggested fix. Explicitly say "no issue found" for any of (a)-(e) where you found nothing.

**Response summary:**

- **No correctness bugs and no concurrency holes found.** The reviewer traced every path and confirmed
  the invariants hold and the skip policy is applied consistently. It ran the tests (92/92) plus its
  own throwaway MockMvc probe harness to confirm its HTTP findings.
- It made the 10 findings below.

**Triage of each suggestion:**

| # | Reviewer finding (severity) | Decision | What changed / why |
|---|---|---|---|
| 1 | *Risk:* a waitlisted learner whose prerequisite is revoked, re-requesting enrollment, gets `ALREADY_WAITLISTED` + a position although they can never be promoted. | **Accepted (edited)** | Rather than only changing the message, the AI applied the *existing* skip-and-remove policy at this second detection point. The learner is removed from the waitlist and gets `PREREQUISITE_NOT_MET` / `LEARNER_NOT_FOUND`. This keeps the policy consistent (AC 4). `EnrollmentService.enroll`; 2 new tests. |
| 2 | *Nit:* `exists()` and `isEligible()` are two separate reads of mutable learner records, so a concurrent delete between them yields the wrong outcome. | **Accepted** | `PrerequisitePolicy.check()` now returns `ELIGIBLE / UNKNOWN_LEARNER / MISSING_PREREQUISITES` from **one** read. `LearnerRecords.exists()` was removed, and the service no longer depends on `LearnerRecords` directly. |
| 3 | *Risk:* 415 / 405 / unknown-path responses don't use the `{outcome,message}` body. | **Accepted** | Added handlers to `ApiExceptionHandler`; 2 new HTTP tests. |
| 4 | *Nit:* the body is parsed before the offering is checked, so a bad body sent to an unknown offering gives 400, not 404. | **Rejected** | The fix would need manual body parsing for no state-safety benefit. Documented in DESIGN §8.9. |
| 5 | *Nit:* Jackson coerces `{"learnerId":123}` to `"123"`. | **Rejected** | Harmless: it results in `LEARNER_NOT_FOUND` and no state change. Documented in DESIGN §8.9. |
| 6 | *Nit:* a repeated DELETE → 404 is debatable; a 200 no-op is more common. | **Rejected** | Kept deliberately; the rationale is already in DESIGN §7. The `NOT_ENROLLED` body makes the no-op explicit. |
| 7 | *Test gap (HTTP):* `LEFT_WAITLIST`, encoded whitespace id (the reviewer warned that the MockMvc string template double-encodes `%20` → `%2520`), 415, unknown offering on GET/DELETE, skip policy over HTTP. | **Accepted** | 6 new tests in `EnrollmentControllerTest`. The whitespace test uses `URI.create(...)`, per the reviewer's warning. |
| 8 | *Test gap (service):* re-request by a newly ineligible waitlister; whether an enrolled learner keeps their seat after a revocation; capacity 1; `CourseOffering` validation. | **Accepted** | 5 new tests. "Enrolled learner keeps seat" was also added as an explicit assumption (DESIGN §4.3). |
| 9 | *Test gap (concurrency):* no concurrent test exercises the skip policy. | **Accepted** | New `concurrentDropsApplySkipPolicyToIneligibleWaitersInFifoOrder` (×20). It asserts the exact promoted set and the exact removed set. |
| 10 | *Nit:* capacity is validated twice (`@Min(1)` and the `CourseOffering` constructor); the offering-code path is unnecessary. | **Rejected** | `@Min` gives a clear startup config error. The constructor guard protects the plain-Java service, which is used without Spring in tests. The offering code in the path makes the single-offering scope explicit in the API. |

---

## Activity 3 — `/code-review` (two runs, user-requested)

**Prompt:** the user ran `/code-review` twice; it reviewed the whole `src/` tree, since there is no git
diff. Each run returned about 10 unverified findings, 5 of which overlapped between the two runs. The
AI merged them and recommended a subset. **The user approved** with "yes".

| # | Finding | Decision | Change / verification |
|---|---|---|---|
| R1 | Custom 405/415 handlers drop the `Allow` / `Accept` headers (RFC 9110 requires `Allow` on 405). | **Accepted** | `ApiExceptionHandler` now handles Spring's `ErrorResponse` exceptions generically and copies their status **and headers**. Tests assert both headers. Mutation check: removing the header copy fails both tests. |
| R2 | No catch-all handler: 406 / 500 escape the `{outcome,message}` shape. | **Accepted (edited)** | A catch-all gives 500 `INTERNAL_ERROR` without leaking the exception text. The AI changed one part of the suggestion: **406 returns status only**, because a JSON body cannot be written to a client that refuses JSON. Unit test + MockMvc test. |
| R3 | `Map.copyOf` NPEs on a null learner value, so the null guard in `WaitlistConfig` is dead code. | **Accepted** | `OfferingProperties` maps a null value to "no completed courses"; the dead guard was removed. Unit test. |
| R4 | Blank prerequisite codes (`CS101,`) are accepted and silently block everyone. | **Accepted** | `CourseOffering` rejects blank codes at startup. Unit test. |
| R5 | Unbracketed map keys mangle learner ids, e.g. `john_doe` → `johndoe`. | **Accepted, but the reviewer's example was wrong** | The AI's first test, built on the reviewer's `_` example, **failed**. An empirical probe showed `_`, `.` and case are kept, while `@` and `/` are stripped (`x@y` → `xy`). Config now uses `waitlist.learners[id]`. The test, comment and README were corrected to use `@`. This shows why unverified review output has to be checked. |
| R6 | The `PREREQUISITE_NOT_MET` message prints a `Set.copyOf` set, so its order varies between JVM runs. | **Accepted** | The sorted list is computed once in the `EnrollmentService` constructor and used for both the message and the snapshot. This also resolves "re-sorts on every GET under the lock". |
| R7 | The hard-coded surefire `argLine` would discard other plugins' agents (e.g. JaCoCo). | **Accepted** | Added an empty `<argLine>` property and `@{argLine} -javaagent:...`. |
| R8 | Redundant defensive copies in `WaitlistConfig`. | **Accepted** | `LearnerRecords.put` takes a `Collection`; the config passes values straight through. |
| R9 | An empty-id `DELETE .../enrollments/` returns 404, not 400. | **Rejected** | The URL matches no route, so 404 is correct. Documented in DESIGN §8.9. |
| R10 | Offering and course codes are case-sensitive. | **Rejected** | A policy choice, now stated as an assumption in DESIGN §2. |
| R11 | Learner-id validation counts UTF-16 units and allows control characters. | **Rejected** | Ids are opaque in this scope. Documented in DESIGN §2. |

**Also noticed during this pass:** `pom.xml` had `java.version` changed from 21 to 17 outside the AI's
edits. The AI kept 17 and confirmed the code uses no post-17 language features. The full suite passes.

---

## Activity 4 — Wiring convention (user-raised)

**Prompt:** "in EnrollmentService.java not declared as component or service"

**What the AI said and did:**

- **Explanation:** the class *was* a Spring bean, registered through a `@Bean` method in
  `WaitlistConfig`. Leaving off the annotation was a deliberate choice, to keep the service free of
  Spring.
- **The AI agreed the user's point was fair:** `@Service` is the convention reviewers expect.
- **Accepted:**
  - `EnrollmentService` is now `@Service` and `PrerequisitePolicy` is `@Component`, both using
    constructor injection.
  - `WaitlistConfig` now builds only the beans that come from config: `CourseOffering` and
    `LearnerRecords`.
- **Docs:** DESIGN §1 and the §7 tradeoff row were updated.
- **Verification:** `mvn -o test` gave `Tests run: 131, Failures: 0, Errors: 0`, `BUILD SUCCESS`. The
  `@SpringBootTest` classes confirm the context starts and injects the beans.

---

## Activity 5 — Package layout (user-raised)

**Prompt:** "records in dto package and service class in service package, controller in controller package"

**What the AI did:**

- **New packages:**
  - Moved `EnrollResult`, `DropResult` and `OfferingSnapshot` from `service` to a new `dto` package.
  - Extracted `EnrollRequest`, which had been nested in the controller, into `dto`.
  - Renamed the `web` package to `controller`, in both main and test.
  - `EnrollmentService` was already in `service` and stayed there.
- **Code changes forced by the move:**
  - The DTO factory methods became `public`, because they are now called from another package.
  - Imports were updated and sorted.
- **Judgement call:**
  - `CourseOffering` (domain model) and `OfferingProperties` (config binding) are also records but are
    not DTOs, so they stayed in `domain`. The AI flagged this to the user.
- **Not rewritten:** the test logs recorded in earlier activities show the old `web.` package names,
  because they were captured before this move.
- **Follow-up prompt:** "WaitlistConfig.java in config package". `WaitlistConfig` moved to
  `com.example.waitlist.config`; component scanning still finds it under the application package.
- **Follow-up prompt:** "move OfferingProperties to config package". Moved it to `config`. Its test was
  split so each test follows its class: `config/OfferingPropertiesTest` and `domain/CourseOfferingTest`
  (formerly the combined `domain/OfferingConfigTest`). `@ConfigurationPropertiesScan` on the application
  class still picks it up.
- **Follow-up prompt:** "exceptions and ApiExceptionHandler move to excepiton package". The AI read
  "excepiton" as a typo for `exception`. It moved `ApiExceptionHandler`, `OfferingNotFoundException`
  and `ApiExceptionHandlerTest` to `com.example.waitlist.exception`, and added an import in
  `EnrollmentController`. The `@RestControllerAdvice` is still found by component scanning.
- **Verification:** `mvn -o test` gave `Tests run: 131, Failures: 0, Errors: 0`, `BUILD SUCCESS`.

---

## Activity 6 — Run script and curl scenario tests (user-raised)

**Prompt:** "create one file to run application and add curl test scripts to test all the senarios"

**What the AI produced:**

- `run.sh`, with the commands `run`, `test`, `unit` and `all`.
- `scripts/curl-tests.sh`:
  - `functional` suite: 20 scenarios, 134 checks.
  - `concurrency` suite: 17 checks.
- Both are written for macOS's default bash 3.2 and need no `jq`.

**Constraint, stated honestly:** the sandbox blocks opening any listening socket, for Tomcat and even
a Python `socket.bind`. So **the scripts were not run against a live server here.** The candidate must
run `./run.sh test` locally. Verification used the following instead:

1. **Record and replay against the real app.** A fake `curl` placed on `PATH` recorded the 50 requests
   the functional suite sends. A throwaway `@SpringBootTest` sent those same requests through MockMvc
   to the real application, and saved each status, header set and body. The test was then deleted.
   A replaying fake `curl` fed those real responses back to the script:
   - result: `functional suite: 134 passed, 0 failed`, exit 0, all 50 responses used;
   - with one saved response corrupted (promoted `carol` changed to `dave`): `133 passed, 1 failed`,
     exit 1.
2. **Concurrency suite logic.** A recording cannot reproduce parallel requests, so a stateful fake
   server was used instead: a fake `curl` holding state in files behind a lock. This **found a real
   bug in the AI's script**: list sizes were undercounted by one, because the extracted list had no
   trailing newline for `wc -l`. After the fix: `concurrency suite: 17 passed, 0 failed` on 3
   consecutive runs.
   - This validates the script's parsing and counting only. The service's real concurrency behaviour
     is verified by `EnrollmentServiceConcurrencyTest`.
3. **Other checks:** `bash -n` passes on both scripts. `./run.sh help` prints the usage. The build step
   produces `target/course-waitlist-0.1.0.jar`, the path `run.sh` launches.

**Remaining risk:** the script runs against MockMvc-generated responses, not a real Tomcat. Small
differences in formatting, such as header casing or the order of `Accept` values, are possible. The
header checks use case-insensitive names and substring matches to tolerate them.

---

## Activity 7 — Swagger UI (user-raised)

**Prompt:** "add swagger ui for api documentation"

**What the AI did:**

- **Dependency:** added `springdoc-openapi-starter-webmvc-ui` **2.8.13**. That version was chosen
  because it is the springdoc line for Spring Boot 3.5 and was already in the offline Maven cache.
- **Docs content:**
  - `config/OpenApiConfig` holds the API title and description. The description includes the
    configured offering and states the skip-and-remove policy.
  - The controller has `@Tag` and `@Operation`, plus one `@ApiResponse` per status code, each naming
    the `outcome` values it carries.
  - The DTOs have `@Schema` examples.
- **Edited during implementation:**
  - The exception handler now returns a new `ApiError` record instead of an untyped `Map`, so error
    bodies have a documented schema. The JSON shape is unchanged; the existing HTTP tests and the
    updated handler unit test pass.
  - A custom `@OfferingCode` meta-annotation, used to avoid repeating the `@Parameter` docs, was
    **rejected by the AI**. It wasn't certain springdoc honours meta-annotated `@Parameter`, so it used
    inline `@Parameter` with a shared description constant.
  - Fixed stray indentation on the `ApiExceptionHandler` class declaration.
- **Verification:**
  - New `OpenApiDocsTest`: the spec has the title, every endpoint, the key status codes and the
    `PREREQUISITE_NOT_MET` enum value; `/swagger-ui.html` redirects to `/swagger-ui/index.html`,
    which serves the Swagger UI page.
  - A throwaway test dumped `/v3/api-docs`; the AI inspected it with `jq` and then deleted the test.
    It confirmed each operation lists exactly the intended codes:
    - POST: 200, 201, 202, 400, 404, 415, 422
    - GET: 200, 404
    - DELETE: 200, 400, 404

    It also confirmed there are no extra responses inferred from the `@RestControllerAdvice`, the
    examples are present, `learnerId` is required, and only the 5 project DTOs appear as schemas.
  - `mvn -o test` gave `Tests run: 133, Failures: 0, Errors: 0`, `BUILD SUCCESS`.
- **Not verified here:** viewing the UI in a real browser, because the sandbox cannot open a port. The
  candidate should open http://localhost:8080/swagger-ui.html after `./run.sh`.

---

## 4. Verification evidence

All commands were run in this repository.

**a) Full suite after both review rounds:** `mvn -o test`

```
Tests run: 13 ... web.EnrollmentControllerTest | 3 ... web.LearnerBindingTest | 1 ... web.ApiExceptionHandlerTest
Tests run: 80 ... service.EnrollmentServiceConcurrencyTest
Tests run: 32 across EnrollmentServiceTest nested groups | 2 ... domain.OfferingConfigTest
Tests run: 131, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

(After Activity 2 the suite was `Tests run: 123, Failures: 0`; after Activity 1 it was `92`.)

**b) Mutation check: do the tests actually catch bugs?** Three deliberate defects were injected into
`EnrollmentService` one at a time, the suite was run, and the file was restored. A `diff` confirmed the
restore.

| Injected defect | Result |
|---|---|
| M1: capacity check `<` → `<=` (overfill by one) | `Failures: 96` — BUILD FAILURE |
| M2: lock removed from `enroll` | `Failures: 6, Errors: 27` — BUILD FAILURE (concurrency tests) |
| M3: promotion skips the FIFO head when >1 waiting | `Failures: 49` — BUILD FAILURE |
| Original restored | `Tests run: 123, Failures: 0, Errors: 0` — BUILD SUCCESS (at that point) |
| M4 (Activity 3): header copy removed from `ApiExceptionHandler` | 405 and 415 header tests fail (`Failures: 2`) |

**c) Acceptance-criteria → test traceability**

| AC | Covered by |
|---|---|
| 1 Enroll with capacity + prerequisite | `AvailableCapacity.*`, `fullWorkflowEnrollWaitlistDropPromote` |
| 2 Waitlist once; duplicates create no records | `FullCapacity.*`, `DuplicateRequests.*`, concurrency test with racing duplicates |
| 3 Drop promotes earliest eligible | `FifoPromotion.*`, `concurrentDropsPromoteEachWaitingLearnerAtMostOnce` |
| 4 Ineligible never enrolled/promoted; policy consistent | `PrerequisiteFailure.*` (10 tests), `skipPolicyIsVisibleOverHttp`, concurrent skip-policy test |
| 5 Invalid requests / duplicate drops don't corrupt state | `InvalidAndRepeatedDrops.*` (snapshot equality before/after), HTTP 400/404/405/406/415/500 tests |
| 6 Concurrency | `EnrollmentServiceConcurrencyTest` (4 tests × 20 repetitions) |
| — Invariants | `assertInvariants` runs after **every** service and concurrency test |

---

## 5. Candidate's own review (to be completed by the candidate)

> The AI generated this repository. Before you submit, record your **own** review here. For example:
> which design choices you agree with or changed; anything you rewrote by hand; whether you ran
> `mvn test` and the README `curl` script yourself, and what you saw. The assessment asks for evidence
> of *independent* human review, and the AI must not write this section on your behalf.

- [ ] Ran `mvn test` locally — result:
- [ ] Ran the README `curl` walkthrough against `mvn spring-boot:run` — result:
- [ ] Reviewed the policy decisions in DESIGN §4 — agree / changed:
- [ ] Other edits made by hand:
