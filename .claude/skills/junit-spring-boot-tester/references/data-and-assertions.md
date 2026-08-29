# Test Data, Assertions and Determinism

## Test data builders

Setup noise is the main reason tests become unreadable and unmaintained. Use an object mother / builder per aggregate, with valid defaults and only the relevant field overridden per test:

```java
public final class AccountBuilder {

    private String code = "ACC-DEFAULT";
    private BigDecimal balance = new BigDecimal("1000.00");
    private AccountStatus status = AccountStatus.ACTIVE;

    public static AccountBuilder anAccount() { return new AccountBuilder(); }

    public AccountBuilder withBalance(String balance) {
        this.balance = new BigDecimal(balance);
        return this;
    }

    public AccountBuilder frozen() {
        this.status = AccountStatus.FROZEN;
        return this;
    }

    public Account build() { return new Account(code, balance, status); }
}
```

Then `anAccount().withBalance("100.00").build()` — the test reads as the scenario, and every field mentioned in the test is a field that matters to the test. Fields not mentioned are explicitly irrelevant, which is information for the reader.

Keep builders in `src/test/java` under a `testsupport` or `fixtures` package. Avoid random data generators (EasyRandom, Instancio) for assertions-relevant fields: random values make failures non-reproducible and hide boundary bugs. They are acceptable for filling irrelevant fields.

## AssertJ recipes

```java
// collections
assertThat(results).hasSize(3)
        .extracting(Account::code, Account::status)
        .containsExactly(tuple("ACC-1", ACTIVE), tuple("ACC-2", FROZEN));

assertThat(results).allSatisfy(a -> assertThat(a.balance()).isPositive());
assertThat(results).filteredOn(Account::isDormant).hasSize(1);

// objects, ignoring generated fields
assertThat(actual).usingRecursiveComparison()
        .ignoringFields("id", "createdAt")
        .isEqualTo(expected);

// BigDecimal — isEqualTo fails on scale, isEqualByComparingTo does not
assertThat(balance).isEqualByComparingTo("750.00");

// exceptions
assertThatThrownBy(() -> service.transfer(...))
        .isInstanceOf(InsufficientFundsException.class)
        .hasMessageContaining("ACC-1")
        .hasFieldOrPropertyWithValue("shortfall", money("400.00"));

assertThatNoException().isThrownBy(() -> service.transfer(...));

// optionals
assertThat(repository.findByCode("ACC-1")).isPresent()
        .get().extracting(Account::balance).isEqualTo(money("750.00"));

// soft assertions — report every failure in one run instead of stopping at the first
SoftAssertions.assertSoftly(softly -> {
    softly.assertThat(receipt.id()).isEqualTo("TRF-1");
    softly.assertThat(receipt.status()).isEqualTo(COMPLETED);
});
```

`usingRecursiveComparison().ignoringFields(...)` beats fifteen individual `assertThat` lines when comparing whole objects, and it catches new fields that individual assertions would silently skip.

## JSON assertions

```java
// exact structural match, strict about extra fields
JSONAssert.assertEquals(expectedJson, actualJson, JSONCompareMode.STRICT);

// lenient — actual may have extra fields, useful for evolving APIs
JSONAssert.assertEquals(expectedJson, actualJson, JSONCompareMode.LENIENT);

// path-based, best for asserting only the contract fields consumers depend on
.andExpect(jsonPath("$.items", hasSize(2)))
.andExpect(jsonPath("$.items[0].amount").value("250.00"))
.andExpect(jsonPath("$.internalTraceId").doesNotExist());
```

Prefer JSONPath assertions on the fields that form the published contract. Whole-body string equality breaks on every additive change and trains the team to update expected files without reading them.

## Parameterized tests

```java
@ParameterizedTest(name = "amount {0} → fee {1}")
@CsvSource({
        "   0.01,  0.00",
        " 999.99,  0.00",
        "1000.00,  5.00",   // boundary: fee starts at 1000
        "5000.00, 25.00"
})
void feeIsChargedAboveThreshold(BigDecimal amount, BigDecimal expectedFee) {
    assertThat(feeCalculator.feeFor(amount)).isEqualByComparingTo(expectedFee);
}
```

Sources: `@ValueSource` (one primitive arg), `@CsvSource` (inline table), `@CsvFileSource` (large tables), `@EnumSource` (every enum constant — excellent for making sure a new enum value forces a test update), `@MethodSource` (objects), `@NullAndEmptySource` (the two inputs everyone forgets).

Always name the test with `name = "..."` so failures identify the failing row directly.

## Determinism seams

| Non-determinism | Seam |
|---|---|
| `Instant.now()`, `LocalDate.now()` | inject `Clock`; use `Clock.fixed(...)` in tests. `LocalDate.now(clock)` in production code |
| `UUID.randomUUID()` | inject a `Supplier<UUID>` or an `IdGenerator` port |
| `Math.random()`, `Random` | inject `Random` with a fixed seed, or a `RandomSource` port |
| Locale / timezone defaults | set them explicitly in the code path and in test config; never rely on the machine default |
| `System.getenv` | `@ConfigurationProperties` and inject |
| File system / temp files | `@TempDir` |

Injecting `Clock` is the single highest-value testability change in most Spring codebases: it turns "wait for midnight" tests into instant ones and makes date-boundary bugs (month ends, DST, financial cut-off times) directly testable.

```java
@Bean
Clock clock() { return Clock.systemUTC(); }
```

## Reading a failure

A good test failure tells you what broke without opening the test. That means: descriptive test names, AssertJ's `as("...")` descriptions on non-obvious assertions, and asserting on the *value* rather than a boolean:

```java
// bad:  expected: true, actual: false
assertThat(account.canTransfer(money("500"))).isTrue();

// better: reports both the balance and the requested amount on failure
assertThat(account.transferEligibility(money("500")))
        .as("transfer eligibility for balance %s", account.balance())
        .isEqualTo(Eligibility.ALLOWED);
```
