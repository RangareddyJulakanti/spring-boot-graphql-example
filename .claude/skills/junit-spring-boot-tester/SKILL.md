---
name: junit-spring-boot-tester
description: Write, review, and repair JUnit 5 tests for Spring Boot applications — unit tests, slice tests (@WebMvcTest, @DataJpaTest, @JsonTest, @RestClientTest), and Testcontainers-backed integration tests. Use this skill whenever the user asks for tests, test cases, unit tests, coverage, mocks, MockMvc, Mockito, Testcontainers, "test this class/service/controller/repository", "why is this test flaky", "my coverage is low", or when the user shares Spring Boot production code and asks what to do with it — even if they never say the word "JUnit". Also use when reviewing an existing test suite for anti-patterns or migrating tests across Spring Boot versions.
---

# JUnit Test Writer for Spring Boot

Produce tests a senior reviewer would approve: behaviour-focused, deterministic, fast, and using the narrowest Spring context that can prove the behaviour.

The failure mode to avoid is **coverage theatre** — tests that execute lines, assert `assertNotNull`, mock the thing under test, and pass no matter what the production code does. Every test written here must be able to fail for exactly one reason.

## Workflow

Follow these steps in order. Do not skip step 1 or step 2 — they determine everything downstream.

### Step 1 — Detect the stack before writing a line

Read `pom.xml` / `build.gradle` and one existing test if any exists. Establish:

| Question | Why it changes the output |
|---|---|
| Spring Boot version | Decides `@MockBean` vs `@MockitoBean`, `MockMvc` vs `MockMvcTester` |
| JUnit 4 or 5 on the classpath | `spring-boot-starter-test` is JUnit 5; legacy modules may still be on 4 (vintage engine) |
| Mockito version | Mockito 5+ = inline mock maker by default, strict stubs |
| Testcontainers present? | Decides real-DB integration tests vs H2 |
| Existing test conventions | Match them; a correct test in a foreign style gets rejected in review |
| Build/verify command | `mvn -q test`, `./gradlew test`, or module-scoped variants |

See `references/version-matrix.md` for the API differences per version. Getting this wrong produces code that does not compile, which is the fastest way to lose the user's trust.

### Step 2 — Enumerate behaviours before writing tests

Read the class under test and write a **test plan table** first — output it to the user before generating code:

| # | Behaviour | Input / state | Expected outcome | Test type |
|---|---|---|---|---|
| 1 | Rejects transfer when balance insufficient | balance=100, amount=500 | throws `InsufficientFundsException`, no repo save | unit |
| 2 | Debits and credits atomically | valid transfer | both accounts saved, event published once | unit |
| 3 | Returns 409 for domain conflict | service throws | HTTP 409 + RFC 7807 body | `@WebMvcTest` |

Derive rows from the source, not from imagination: every `if`/`else`, every `catch`, every `@Valid` constraint, every early return, every boundary (`0`, `null`, empty collection, max), and every branch of a `switch`. Loops need zero / one / many. This table is the coverage contract — line coverage is a by-product of it, never the goal.

If the class has more than ~8 behaviours, say so and propose splitting the tests by `@Nested` context rather than producing one 900-line file.

### Step 3 — Pick the narrowest test type that proves the behaviour

```
Pure logic, mappers, validators, domain objects
  → plain JUnit 5. No Spring. No Mockito unless there is a real collaborator.

Service with mockable collaborators
  → @ExtendWith(MockitoExtension.class) + @Mock/@InjectMocks. No Spring context.

Controller: routing, status codes, serialization, validation, security, error handling
  → @WebMvcTest(XController.class) + @MockitoBean on services

Repository: custom @Query, derived queries, JPA mappings, constraints
  → @DataJpaTest (+ Testcontainers if the query uses vendor SQL)

Serialization contract only
  → @JsonTest

Outbound HTTP client
  → @RestClientTest / MockRestServiceServer, or WireMock for RestClient/WebClient

Wiring, transactions, config, end-to-end flow through real infrastructure
  → @SpringBootTest + Testcontainers @ServiceConnection
```

Rule of thumb: `@SpringBootTest` is the *last* resort, not the default. A service test that boots the whole context to test an `if` statement is a defect, not thoroughness. Then open the matching playbook in `references/layer-playbooks.md`.

### Step 4 — Write the tests

Non-negotiables:

1. **Arrange-Act-Assert**, visually separated by blank lines. One act per test.
2. **One reason to fail.** If a test can fail for two unrelated reasons, split it.
3. **No logic in tests** — no `if`, no loops, no computed expected values. If the expected value needs an algorithm to derive, you are re-implementing production code in the test and it will be wrong in the same way.
4. **AssertJ** (`assertThat`) over JUnit assertions over Hamcrest. Assert on values and exception *types plus messages/fields*, never `assertNotNull` as the only assertion.
5. **Never mock the class under test.** Never mock value objects, DTOs, or types you do not own (see `references/mockito-and-test-doubles.md`).
6. **Determinism**: inject `Clock`, seed randomness, never `Thread.sleep` (use Awaitility), never depend on test execution order, never share mutable state between tests.
7. **Parameterize** branch families with `@ParameterizedTest` + `@CsvSource`/`@MethodSource` instead of copy-pasting six near-identical tests.
8. **Test behaviour through the public API.** No `ReflectionTestUtils` to poke private state — if you need it, that is a design signal, and say so.
9. **Name tests as specifications**: `methodUnderTest_condition_expectedOutcome`, e.g. `transfer_whenBalanceInsufficient_throwsAndDoesNotPersist`. Add `@DisplayName` for human-readable reports; use `@Nested` classes to group by condition.
10. **Verify interactions sparingly** — assert on observable outcomes; use `verify` only when the interaction *is* the behaviour (an event published, a payment gateway called exactly once, `verify(repo, never()).save(any())` on the rejection path).

### Step 5 — Run them, then report honestly

Compile and run the suite if a build tool is available. Never present tests as working when they have not been executed. If they cannot be run in this environment, say so explicitly.

Then report, in this order:

1. **Files written** — paths.
2. **Behaviour coverage** — which rows of the step-2 table are covered.
3. **Gaps** — what is deliberately untested and why (e.g. "framework-generated getters", "the `@Retryable` backoff needs an integration test, not a unit test").
4. **Testability findings** — production-code smells found while writing: `new` inside methods, static clocks, private methods that need testing, constructor doing work, God services. Propose the seam. Do not silently refactor production code; propose it, and only change it if asked.

Do not claim a coverage percentage that was not measured. If the user cares about the number, run JaCoCo; if they care whether the tests are *worth* anything, propose mutation testing (PIT) — a suite at 90% line coverage and 30% mutation score is a suite that asserts almost nothing.

## Reference files

Read the ones relevant to the current task; do not read all of them.

| File | Read when |
|---|---|
| `references/version-matrix.md` | Always, in step 1 — API differences across Boot 2.7 → 4.x |
| `references/layer-playbooks.md` | Writing any test — per-layer recipes with full working examples |
| `references/mockito-and-test-doubles.md` | Stubbing, strict stubs, captors, spies, static/final mocking, what not to mock |
| `references/integration-and-testcontainers.md` | `@SpringBootTest`, Testcontainers, context caching, DB state management, Kafka |
| `references/data-and-assertions.md` | Test data builders, AssertJ recipes, JSON assertions, parameterized sources, time/randomness seams |
| `references/legacy-and-hard-to-test-code.md` | Untested brownfield code, statics, `new` in methods, characterization tests |
| `references/review-checklist.md` | Reviewing an existing suite, diagnosing flakiness, PR checklist |

## Output contract

When asked to test a class, produce in this order:

1. The **test plan table** (step 2).
2. The **test file(s)** — complete and compilable, with imports, matching the project's existing style and package layout (`src/test/java/<same package>`).
3. Any **new test fixtures** (builders, `@TestConfiguration`, container config) as separate files rather than inlined duplication.
4. The **run command** and the actual result.
5. The **gaps and testability findings** section.

Keep the prose short. The tests are the deliverable; the commentary exists to make review fast.

## When the request is underspecified

Do not stall on a question you can answer from the code. Ask only when the answer changes the design of the tests and cannot be inferred — for example, whether the user wants Testcontainers-backed repository tests or is stuck on H2 for CI reasons, or whether an external HTTP dependency may be hit in CI. Otherwise, pick the sound default, write the tests, and state the assumption in one line.
