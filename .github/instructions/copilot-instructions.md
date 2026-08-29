# Copilot Instructions

## Testing standards

Tests are JUnit 5 + AssertJ + Mockito. Use the narrowest context that proves the behaviour: plain JUnit for logic, `@ExtendWith(MockitoExtension.class)` for services, `@WebMvcTest(TheController.class)` for controllers, `@DataJpaTest` for custom queries, `@SpringBootTest` only for wiring and transactions.

- Name tests `method_condition_expectedOutcome`. Group with `@Nested`.
- Arrange-Act-Assert, blank-line separated. One act per test, one reason to fail.
- Assert values with AssertJ. Never `assertNotNull` as the only assertion. `isEqualByComparingTo` for `BigDecimal`.
- No `if`, loops, or computed expected values in tests. Use `@ParameterizedTest` with `@CsvSource`/`@MethodSource` for branch families.
- Never mock the class under test, records/DTOs/entities, or types we do not own — wrap third-party types in an adapter and mock that.
- Prefer explicit constructor calls over `@InjectMocks`.
- Cover the failure path, including what was *not* done: `verify(repo, never()).save(any())`.
- No `Thread.sleep` — use Awaitility. No `ReflectionTestUtils`. No `@DirtiesContext` unless a test genuinely corrupts global state.
- Inject `Clock` rather than calling `Instant.now()`/`LocalDate.now()` directly; tests use `Clock.fixed`.
- Test data comes from builders with valid defaults; override only the field the test is about.
- In `@DataJpaTest`, `em.flush()` then `em.clear()` before asserting.
- Do not silence `UnnecessaryStubbingException` with lenient strictness.

## Spring Boot version conventions

Use `@MockitoBean`/`@MockitoSpyBean` (Boot 3.4+). `@MockBean`/`@SpyBean` are deprecated and must not appear in new code.

## Production code

Constructor injection, not field `@Autowired`. Do not suggest disabling security or validation to make a test pass. When code cannot be tested without a seam, say so and propose the change rather than working around it with reflection.
