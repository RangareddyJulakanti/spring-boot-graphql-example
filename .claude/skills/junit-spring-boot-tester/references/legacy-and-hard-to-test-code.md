# Legacy and Hard-to-Test Code

Brownfield services rarely have a clean seam waiting. The job is to get a safety net in place *before* refactoring, then improve the design.

## Order of operations

1. **Characterization tests first.** Do not fix behaviour you have not pinned down. Write tests that assert what the code *currently does* — including the odd bits — so a refactor that changes behaviour fails loudly. Label them clearly:

```java
/**
 * Characterization test: documents current behaviour, not necessarily correct behaviour.
 * Returns an empty string rather than null for unknown codes — several callers depend on this.
 */
@Test
void lookup_whenCodeUnknown_returnsEmptyString() { ... }
```

2. **Introduce the seam** with the smallest possible change (below).
3. **Then** refactor, with the characterization tests as the net.
4. Replace characterization tests with intention-revealing tests as the design clarifies.

## Common blockers and the minimal seam

| Blocker | Minimal seam |
|---|---|
| `new SomeCollaborator()` inside a method | promote to a constructor-injected field, or inject a factory |
| `static` utility doing I/O or time | wrap in an instance-level adapter and inject it |
| Field injection (`@Autowired` on fields) | convert to constructor injection — one mechanical change, makes the class testable without Spring |
| Constructor doing real work (DB calls, HTTP) | move the work to an `@PostConstruct` or an explicit `init()` the test can skip |
| Private method holding the logic worth testing | test through the public entry point; if it is genuinely a separate responsibility, extract it to a collaborator |
| God service with 15 dependencies | test the pieces you touch; recommend extraction, do not attempt it silently |
| Package-private access needed | tests live in the same package under `src/test/java` — no reflection required |

Do not reach for `ReflectionTestUtils` or `PowerMock` to avoid changing production code. They let untestable designs persist and they break on every JDK upgrade. Mockito 5's inline mock maker covers the legitimate static/final cases; use it as a bridge, not a destination.

## Constructor injection conversion

```java
// before — untestable without a Spring context
@Service
public class TransferService {
    @Autowired private AccountRepository repo;
    @Autowired private LedgerClient ledger;
}

// after — plain-JUnit testable, dependencies explicit, fields final
@Service
public class TransferService {
    private final AccountRepository repo;
    private final LedgerClient ledger;

    public TransferService(AccountRepository repo, LedgerClient ledger) {
        this.repo = repo;
        this.ledger = ledger;
    }
}
```

This is the highest-leverage change in most legacy Spring code and is safe to propose in almost every review. Note in the findings that a constructor with more than ~5 parameters is itself a signal the class does too much.

## When production code must change

Propose it explicitly rather than doing it quietly:

> To test the daily cut-off logic I need a `Clock` seam. Suggested change: add a `Clock` constructor parameter to `SettlementService` and replace `LocalDate.now()` with `LocalDate.now(clock)`. One `@Bean Clock clock() { return Clock.systemUTC(); }` in config, three call sites. I have not made this change — say the word and I will, along with the tests it unlocks.

Reasons: the user owns the production code and its review process; a test-driven production change that arrives unannounced in a diff is how test suites get reverted wholesale.

## Deciding what not to test

Untested is sometimes the right answer. Be explicit about it rather than padding coverage:

- Generated code, getters/setters, `equals`/`hashCode` on records
- Straight-through delegation with no logic (`return repo.findAll();`)
- Framework configuration classes with no conditional logic
- Third-party library behaviour

State these as deliberate exclusions in the report. A reviewer who sees "not tested, and here is why" trusts the suite more than one who sees 100% coverage with no reasoning.

## Prioritising a large untested codebase

When asked to "add tests" to a service with none, do not start at the top of the package tree. Rank by risk:

1. Money movement, ledger, pricing, fees, interest, rounding — anything where a bug is a financial loss
2. Authorization and data-access boundaries
3. Code that changes often (check git history if available)
4. Recently fixed bugs — write the regression test that should have existed
5. Complex conditionals and date/time boundary logic
6. Everything else

Deliver the first slice, get feedback on style and depth, then continue. A 40-file test PR gets rubber-stamped; a 4-file one gets reviewed.
