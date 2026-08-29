# Review Checklist, Anti-Patterns and Flakiness

## Anti-patterns to flag

| Anti-pattern | Why it is a defect | Fix |
|---|---|---|
| `assertNotNull` as the only assertion | passes for any non-null garbage | assert the value |
| No assertion at all (only `verify` of trivia) | tests that the code ran, not that it works | assert the outcome |
| Mocking the class under test | the test tests the mock | construct the real object |
| `@SpringBootTest` for a pure-logic class | seconds instead of milliseconds, no extra confidence | plain JUnit |
| Bare `@WebMvcTest` (unscoped) | loads every controller, forces app-wide mocking | `@WebMvcTest(X.class)` |
| Logic (`if`, loops) in the test | test can be wrong in the same way as production | table-driven `@ParameterizedTest` |
| Expected value computed by the same algorithm | tautology — always passes | hard-code the expected value |
| `Thread.sleep` | flaky and slow | Awaitility with a bounded timeout |
| Shared mutable static state between tests | order dependence | per-test state, `@BeforeEach` |
| `@DirtiesContext` used casually | evicts the context cache, multiplies suite time | remove; fix the state leak |
| Random data in assertion-relevant fields | non-reproducible failures | builders with explicit values |
| Catch-and-ignore in a test | hides the failure | `assertThatThrownBy` |
| `@Disabled` with no reason | dead test nobody will revive | fix, or delete with a ticket reference |
| One test asserting eight unrelated things | failure does not localise the bug | split |
| `verifyNoMoreInteractions` everywhere | change-detector; breaks on harmless refactors | verify only contractual calls |
| Test names like `test1`, `testTransfer` | failure report communicates nothing | `method_condition_outcome` |
| Security disabled to make the test pass | deletes the test's purpose | `@WithMockUser` and test the real chain |
| Asserting log output as the behaviour | logs are not a contract | assert state or interactions |

## Definition of done for a test file

- [ ] Every behaviour from the plan table has a test, and every test maps to a row
- [ ] Boundaries covered: `null`, empty, zero, negative, max, off-by-one on each threshold
- [ ] Both the happy path and each failure mode, including "what was *not* done" on the failure path
- [ ] Compiles and passes; passes again when run twice in a row and in isolation
- [ ] Fails for the right reason — mutate one production line mentally (or with PIT) and confirm the test would catch it
- [ ] No `sleep`, no order dependence, no shared static state, no real network calls
- [ ] Runs in the narrowest context that proves the behaviour
- [ ] Names read as specifications; failure messages localise the bug
- [ ] Matches the project's existing conventions and package layout

## Coverage: measuring the right thing

Line coverage measures execution, not verification. A suite can reach 90% line coverage with zero assertions. When coverage is the topic:

- Report what was actually measured; never estimate a percentage.
- JaCoCo: `mvn verify` with the plugin bound to `prepare-agent`; enforce with `jacoco:check` rules per package rather than one global number.
- Prefer **branch** coverage over line coverage as a target, and treat both as diagnostics for finding untested paths rather than as goals. Any coverage number becomes a bad metric the moment it becomes a target.
- **Mutation testing (PIT)** is the honest measure: it changes the production bytecode and checks whether tests fail. `mvn org.pitest:pitest-maven:mutationCoverage`. A 90% line / 35% mutation score means the suite executes code without checking it. Propose PIT on the critical modules (money movement, pricing, authorization), not the whole codebase — it is slow.
- If asked for a coverage target: 80% overall is a common convention, but push the conversation toward "which behaviours are untested" instead, and be honest that the number itself is not evidence of a good suite.

## Flaky test triage

| Symptom | Likely cause |
|---|---|
| Passes alone, fails in the suite | shared state, static mock leak, context pollution, `@DirtiesContext` ordering |
| Fails only in CI | timezone/locale/encoding defaults, slower machine vs a fixed sleep, missing Docker, parallelism |
| Fails intermittently on timing | `Thread.sleep`, async assertion without Awaitility, hardcoded timeout too tight |
| Fails around month/year end | `LocalDate.now()` in production code — needs a `Clock` seam |
| Fails after adding an unrelated test | context cache eviction (max 32) forcing reboots, or execution-order dependence |
| Fails on a different machine | reliance on default charset, file separator, or hostname resolution |

Diagnose by running with `-Djunit.jupiter.execution.order.random.seed`, or run the single test in isolation and then with the suite. A quarantined flaky test is technical debt with interest: fix or delete it, do not `@Disabled` it indefinitely.

## Suite performance

In rough order of impact:

1. One shared `@SpringBootTest` base class → one cached context instead of many
2. Push tests down the pyramid — most logic belongs in plain unit tests
3. Remove `@DirtiesContext`
4. Singleton Testcontainers instead of per-class containers
5. Split fast/slow with tags and Failsafe so the inner loop stays fast
6. Parallel execution last, only once tests are provably independent

If the suite takes longer than a coffee break, developers stop running it locally, and the suite stops preventing bugs. Runtime is a correctness concern, not a nicety.
