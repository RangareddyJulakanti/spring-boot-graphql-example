---
mode: agent
description: Write JUnit 5 tests for Spring Boot code — unit, slice and integration — following the team's testing standard.
---

# JUnit Test Writer (Spring Boot)

Self-contained flattening of the `junit-spring-boot-tester` skill for GitHub Copilot in IntelliJ IDEA. Drop at `.github/prompts/junit-spring-boot-tester.prompt.md`. Copilot has no progressive disclosure, so the reference layers are condensed here; the SKILL.md bundle stays the source of truth.

## Task

Write tests for the class I have open or named. Produce compilable JUnit 5 code that matches this project's existing conventions.

## Before writing

1. Read `pom.xml`/`build.gradle`: Spring Boot version (decides `@MockBean` vs `@MockitoBean` — the switch is at 3.4), JUnit version, Mockito version, Testcontainers presence.
2. Read the class under test and list every behaviour to cover: each `if`/`else`, `catch`, early return, validation constraint, boundary (0, null, empty, max), and loop at zero/one/many. Output this list before generating code.
3. Pick the narrowest test type that proves the behaviour.

## Test type selection

- Pure logic, mappers, validators → plain JUnit 5, no Spring
- Service with collaborators → `@ExtendWith(MockitoExtension.class)`, `@Mock`, explicit constructor
- Controller (routing, status, JSON, validation, security) → `@WebMvcTest(TheController.class)` + `@MockitoBean`
- Repository (custom queries, mappings, constraints) → `@DataJpaTest`, Testcontainers if vendor SQL
- Serialization contract → `@JsonTest`
- Outbound HTTP → `@RestClientTest` + `MockRestServiceServer`, or WireMock
- Wiring / transactions / end-to-end → `@SpringBootTest` + Testcontainers `@ServiceConnection` (last resort)

## Rules

- Arrange-Act-Assert, blank-line separated. One act per test. One reason to fail.
- AssertJ `assertThat`. Never `assertNotNull` as the only assertion. `isEqualByComparingTo` for `BigDecimal`.
- Name tests `method_condition_expectedOutcome`; group with `@Nested`; label with `@DisplayName`.
- No logic in tests — no `if`, no loops, no computed expected values. Use `@ParameterizedTest` + `@CsvSource`/`@MethodSource` for branch families.
- Never mock the class under test, value objects, or types you do not own. Wrap third-party types in an adapter and mock the adapter.
- Prefer explicit constructor calls over `@InjectMocks` (silent null injection when a dependency is added).
- Assert observable outcomes; use `verify` only when the interaction is the contract — including negative-path `verify(repo, never()).save(any())`.
- Determinism: inject `Clock` and use `Clock.fixed`, seed randomness, `@TempDir` for files, Awaitility instead of `Thread.sleep`.
- Test data via builders with valid defaults; override only the field the test is about.
- Do not silence `UnnecessaryStubbingException` with lenient strictness — it means the stub is dead or the test drifted.
- Scope `@WebMvcTest` to one controller. Do not use `@DirtiesContext` casually. Do not disable security to make a test pass.
- In `@DataJpaTest`, `em.flush()` and `em.clear()` before asserting, or you assert against the persistence context rather than the database.

## Output

1. Behaviour list (step 2 above)
2. Complete test file(s) with imports, in `src/test/java/<same package>`
3. Any new fixtures/builders as separate files
4. The command to run them
5. Gaps: what is deliberately untested and why, plus testability problems found in the production code (field injection, `new` inside methods, static clocks, private logic) with a proposed seam — propose, do not silently refactor

Do not claim a coverage percentage that was not measured.
