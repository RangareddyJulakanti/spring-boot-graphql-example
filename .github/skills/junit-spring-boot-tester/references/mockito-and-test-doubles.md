# Mockito and Test Doubles

## What to mock — and what never to mock

| Mock it | Do not mock it |
|---|---|
| Repositories and outbound clients at the unit level | The class under test |
| Message publishers, external gateways | Value objects, DTOs, records, entities — construct them |
| Anything slow, networked, or non-deterministic | Collections, `Optional`, `String`, JDK types |
| Ports you own in a hexagonal design | Types you do not own (`RestTemplate`, `EntityManager`, AWS SDK clients) |

Mocking a type you do not own encodes your *assumption* about that library's behaviour into the test. When the assumption is wrong, the test still passes and production still breaks. Wrap the third-party type in a thin adapter you own, mock the adapter in unit tests, and cover the adapter itself with an integration test (`MockRestServiceServer`, WireMock, Testcontainers).

If a test needs more than 4–5 mocks, that is a design signal about the class under test, not a reason to write more `when(...)` lines. Say so in the testability findings.

## Strict stubs (Mockito 2+ default with `MockitoExtension`)

`UnnecessaryStubbingException` means a stub was never used. It is a real finding, not noise:
- the stub is dead — delete it, or
- the code path changed and the test is no longer testing what it claims.

Do not reach for `@MockitoSettings(strictness = Strictness.LENIENT)` to silence it. If exactly one stub is legitimately conditional, mark that single stub with `lenient().when(...)`.

`PotentialStubbingProblem` (a stubbed method called with different arguments) usually means the argument matchers are too loose or the production code changed its inputs. Fix the test, do not relax the strictness.

## Stubbing patterns

```java
// standard
when(repo.findById(ID)).thenReturn(Optional.of(account));

// void methods
doThrow(new DataAccessResourceFailureException("down")).when(repo).delete(any());

// spies — use doReturn to avoid invoking the real method during stubbing
doReturn(fallback).when(spyService).lookup(anyString());

// sequential returns: fails twice then succeeds (retry tests)
when(client.call()).thenThrow(TimeoutException.class, TimeoutException.class).thenReturn(ok);

// answer based on the argument
when(repo.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));
```

Matchers are all-or-nothing: if one argument uses a matcher, all must. Use `eq(ID)` alongside `any()`.

## Argument captors vs `argThat`

Use `ArgumentCaptor` when you want rich assertions on what was passed:

```java
ArgumentCaptor<LedgerEvent> captor = ArgumentCaptor.forClass(LedgerEvent.class);
verify(publisher).publish(captor.capture());
assertThat(captor.getValue())
        .satisfies(e -> {
            assertThat(e.amount()).isEqualByComparingTo("250.00");
            assertThat(e.occurredAt()).isEqualTo(FIXED_INSTANT);
        });
```

Use `argThat` when the predicate *is* the expectation and a failure message like "wanted but not invoked" is enough. Captors give better diagnostics on failure; prefer them for anything non-trivial.

## Verification

Verify only what constitutes the contract:

```java
verify(repository, never()).save(any());          // rejection path persisted nothing
verify(gateway, times(1)).charge(any());          // no double charge — this matters
verifyNoInteractions(auditLog);                   // nothing leaked to audit on validation failure
verifyNoMoreInteractions(repository);             // use sparingly; brittle under refactoring
```

Over-verification produces change-detector tests: every refactor breaks them, so the team stops trusting the suite. When an outcome is observable through state or a return value, assert on that instead.

## Spies

`@Spy` (partial mocking) is usually a smell — it means part of the class under test is being replaced, so the test no longer tests the class. Legitimate uses: wrapping a legacy class with an unreachable seam, or verifying a call on a real collaborator whose behaviour must remain real. When you use one, note why.

## Static, final, and constructor mocking

Mockito 5's inline mock maker handles these without extra dependencies:

```java
try (MockedStatic<Instant> mocked = mockStatic(Instant.class)) {
    mocked.when(Instant::now).thenReturn(FIXED_INSTANT);
    ...
}   // scoped to the try block — never leak a static mock across tests
```

Treat this as a last resort for code you cannot change. The better fix is a seam: inject `Clock`, inject a factory, inject a `Supplier`. If you use static mocking, keep it inside a try-with-resources — a leaked static mock corrupts unrelated tests in the same JVM and produces the hardest class of flaky failure to diagnose.

Note: static mocking is not thread-safe across parallel test execution. If the build runs JUnit in parallel, isolate those tests with `@Execution(SAME_THREAD)`.

## `@MockitoBean` vs `@Mock`

`@Mock` creates a bare mock — no Spring involved, fast.
`@MockitoBean` replaces a bean in the *application context*, and each distinct combination of overrides produces a new cached context. Use it only inside Spring tests, and keep the set of overrides consistent across test classes (a shared abstract base class) so contexts are reused.

`@TestBean` (Framework 6.2+) supplies a real instance from a static factory method instead of a mock — often a better choice than mocking, e.g. a fixed `Clock` or an in-memory fake repository.

## Fakes over mocks

For repositories with rich query behaviour, a hand-written in-memory fake implementing the same interface is often clearer and less brittle than twenty `when(...)` lines spread across a test class — and it forces the interface to stay small. Worth proposing when the same stubbing block is being copy-pasted between tests.
