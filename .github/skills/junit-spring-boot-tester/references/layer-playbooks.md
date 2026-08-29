# Layer Playbooks

Recipes per layer. Read only the section for the class under test.

**Contents**
1. Service / domain logic (no Spring)
2. Controller — `@WebMvcTest`
3. Controller — validation and error contract
4. Controller — security
5. Repository — `@DataJpaTest`
6. Serialization — `@JsonTest`
7. Outbound HTTP clients
8. Configuration properties
9. Async, scheduled, retry, cache
10. Messaging (Kafka / JMS / Rabbit)

---

## 1. Service / domain logic — plain JUnit 5 + Mockito

No Spring context. This is where 70–80% of a well-tested Spring Boot suite lives, and it should run in milliseconds.

```java
@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    @Mock private AccountRepository accountRepository;
    @Mock private LedgerEventPublisher eventPublisher;

    private TransferService transferService;

    private final Clock fixedClock =
            Clock.fixed(Instant.parse("2026-03-01T10:15:30Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        transferService = new TransferService(accountRepository, eventPublisher, fixedClock);
    }

    @Nested
    class WhenBalanceIsInsufficient {

        @Test
        void throwsAndPersistsNothing() {
            Account source = AccountBuilder.anAccount().withBalance("100.00").build();
            when(accountRepository.findById(source.id())).thenReturn(Optional.of(source));

            assertThatThrownBy(() -> transferService.transfer(source.id(), TARGET_ID, money("500.00")))
                    .isInstanceOf(InsufficientFundsException.class)
                    .hasMessageContaining(source.id().toString())
                    .extracting("shortfall").isEqualTo(money("400.00"));

            verify(accountRepository, never()).save(any());
            verifyNoInteractions(eventPublisher);
        }
    }

    @Test
    void transfer_whenSufficientBalance_movesFundsAndPublishesEventOnce() {
        Account source = AccountBuilder.anAccount().withBalance("1000.00").build();
        Account target = AccountBuilder.anAccount().withBalance("0.00").build();
        when(accountRepository.findById(source.id())).thenReturn(Optional.of(source));
        when(accountRepository.findById(target.id())).thenReturn(Optional.of(target));

        transferService.transfer(source.id(), target.id(), money("250.00"));

        ArgumentCaptor<Account> saved = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues())
                .extracting(Account::id, Account::balance)
                .containsExactly(
                        tuple(source.id(), money("750.00")),
                        tuple(target.id(), money("250.00")));

        verify(eventPublisher).publish(argThat(e ->
                e.amount().equals(money("250.00")) && e.occurredAt().equals(fixedClock.instant())));
    }
}
```

Points a reviewer looks for: constructor injection (no `@InjectMocks` field magic needed — though `@InjectMocks` is acceptable when the constructor is long), a fixed `Clock`, negative-path assertion that *nothing* was persisted, and interaction verification only where the interaction is the contract.

`@InjectMocks` caveat: it fails silently. If a new constructor dependency is added, the field is left `null` and the test fails with an obscure NPE rather than a compile error. Explicit construction in `@BeforeEach` breaks the build immediately, which is better.

---

## 2. Controller — `@WebMvcTest`

Tests routing, deserialization, status codes, headers and the JSON contract. The service is mocked; business rules are *not* retested here.

```java
@WebMvcTest(TransferController.class)
class TransferControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private TransferService transferService;   // @MockBean on Boot <= 3.3

    @Test
    void createTransfer_returns201WithLocation() throws Exception {
        when(transferService.transfer(any(), any(), any()))
                .thenReturn(new TransferReceipt("TRF-1", Instant.parse("2026-03-01T10:15:30Z")));

        mockMvc.perform(post("/api/v1/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, "/api/v1/transfers/TRF-1"))
                .andExpect(jsonPath("$.transferId").value("TRF-1"))
                .andExpect(jsonPath("$.createdAt").value("2026-03-01T10:15:30Z"));
    }

    @Test
    void createTransfer_whenServiceRejects_returns409ProblemDetail() throws Exception {
        when(transferService.transfer(any(), any(), any()))
                .thenThrow(new InsufficientFundsException(ACCOUNT_ID, money("400.00")));

        mockMvc.perform(post("/api/v1/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:problem:insufficient-funds"))
                .andExpect(jsonPath("$.detail").value(containsString("400.00")));
    }
}
```

Rules:
- Always scope the slice: `@WebMvcTest(TransferController.class)`. Bare `@WebMvcTest` loads every controller and forces you to mock every service in the app.
- `@WebMvcTest` loads `@ControllerAdvice`, converters, filters and `WebMvcConfigurer` beans — so the error contract is genuinely testable here.
- It does **not** load `@Service`/`@Repository`/`@Component`. Anything the controller autowires must be `@MockitoBean`.
- Assert the *contract*: status, headers, and the specific JSON fields consumers depend on. Do not assert the whole body with a giant string literal — it breaks on every unrelated field addition.
- `MockMvc` does not start a server and does not exercise the real servlet container. Filters registered via `FilterRegistrationBean` may not apply.

AssertJ variant (Boot 3.4+):

```java
@Autowired private MockMvcTester mvc;

@Test
void createTransfer_returns201() {
    assertThat(mvc.post().uri("/api/v1/transfers")
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .hasStatus(HttpStatus.CREATED)
        .bodyJson().extractingPath("$.transferId").isEqualTo("TRF-1");
}
```

---

## 3. Validation and the error contract

Bean-validation failures are a controller-layer behaviour and belong in the `@WebMvcTest`. Parameterize them:

```java
@ParameterizedTest(name = "{0} is rejected with 400")
@MethodSource("invalidRequests")
void createTransfer_withInvalidPayload_returns400(String label, String json, String expectedField)
        throws Exception {
    mockMvc.perform(post("/api/v1/transfers").contentType(APPLICATION_JSON).content(json))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors[*].field").value(hasItem(expectedField)));
    verifyNoInteractions(transferService);   // request never reached the service
}

static Stream<Arguments> invalidRequests() {
    return Stream.of(
            arguments("negative amount", jsonWithAmount("-1.00"), "amount"),
            arguments("zero amount",     jsonWithAmount("0.00"),  "amount"),
            arguments("missing target",  jsonWithoutTarget(),     "targetAccountId"),
            arguments("amount over cap", jsonWithAmount("1000000.01"), "amount"));
}
```

Constraints on a POJO can also be tested without any Spring context at all — faster, and useful when the constraint logic is non-trivial:

```java
private static final Validator VALIDATOR =
        Validation.buildDefaultValidatorFactory().getValidator();

@Test
void amountMustBePositive() {
    assertThat(VALIDATOR.validate(new TransferRequest(SRC, TGT, new BigDecimal("-1"))))
            .extracting(v -> v.getPropertyPath().toString())
            .containsExactly("amount");
}
```

Custom `ConstraintValidator` implementations get their own plain unit test.

---

## 4. Security

With `spring-security-test` on the classpath, `@WebMvcTest` applies the real security filter chain. That makes authorization a first-class testable behaviour — and means an unauthenticated test will get 401/403, which surprises people who forget it.

```java
@Test
@WithMockUser(roles = "TELLER")
void approve_asTeller_isForbidden() throws Exception {
    mockMvc.perform(post("/api/v1/transfers/TRF-1/approve").with(csrf()))
            .andExpect(status().isForbidden());
    verifyNoInteractions(transferService);
}

@Test
@WithMockUser(roles = "SUPERVISOR")
void approve_asSupervisor_isAccepted() throws Exception { ... }

@Test
void approve_whenAnonymous_isUnauthorized() throws Exception {
    mockMvc.perform(post("/api/v1/transfers/TRF-1/approve").with(csrf()))
            .andExpect(status().isUnauthorized());
}
```

Notes:
- Include `.with(csrf())` for state-changing requests when CSRF is enabled, or the failure will look like an authorization bug.
- `@WithMockUser` for roles; `@WithUserDetails` when the principal must come from a real `UserDetailsService`; a custom `@WithSecurityContext` annotation for JWT-claim-driven authorization.
- Method security (`@PreAuthorize` on services) is *not* covered by controller tests. Test it with a small `@SpringBootTest` slice that has method security enabled, or accept the gap and say so.
- Never disable security in tests to make them pass. That deletes the test.

---

## 5. Repository — `@DataJpaTest`

Worth writing for: custom `@Query`, derived query names, specifications/criteria, projections, entity mapping, cascade and orphan removal, unique constraints, optimistic locking. Not worth writing for: Spring Data's own `save`/`findById`.

```java
@DataJpaTest
@Import(TestcontainersConfiguration.class)      // real PostgreSQL; see integration reference
class AccountRepositoryTest {

    @Autowired private AccountRepository accountRepository;
    @Autowired private TestEntityManager em;

    @Test
    void findDormantAccounts_returnsOnlyAccountsWithNoActivitySinceCutoff() {
        em.persist(account("ACC-1", lastActivity("2025-01-01")));
        em.persist(account("ACC-2", lastActivity("2026-02-27")));
        em.flush();
        em.clear();                             // force a real DB read, not the L1 cache

        List<Account> dormant = accountRepository.findDormantSince(Instant.parse("2026-01-01T00:00:00Z"));

        assertThat(dormant).extracting(Account::code).containsExactly("ACC-1");
    }

    @Test
    void save_whenDuplicateCode_violatesUniqueConstraint() {
        em.persistAndFlush(account("ACC-1"));

        assertThatThrownBy(() -> em.persistAndFlush(account("ACC-1")))
                .isInstanceOf(PersistenceException.class);
    }
}
```

Rules:
- `@DataJpaTest` is `@Transactional` and rolls back per test. That is convenient and it hides bugs: `em.flush()` + `em.clear()` before the assertion, or the test may pass against the persistence context rather than the database.
- The rollback also means `LazyInitializationException` never fires in the test but does in production. Lazy-loading behaviour needs a non-transactional integration test.
- **H2 is not your database.** `@DataJpaTest` replaces the DataSource with an embedded one by default. For anything using vendor SQL, native queries, `jsonb`, window functions, or specific constraint semantics, use Testcontainers and `@AutoConfigureTestDatabase(replace = NONE)`.
- Let Flyway/Liquibase build the schema rather than `ddl-auto: create-drop`, so the test exercises the migrations that production runs.

---

## 6. Serialization — `@JsonTest`

For API contracts where field names, formats and null handling matter (common in BFSI integrations).

```java
@JsonTest
class TransferReceiptJsonTest {

    @Autowired private JacksonTester<TransferReceipt> json;

    @Test
    void serializesAmountAsStringWithTwoDecimals() throws Exception {
        assertThat(json.write(new TransferReceipt("TRF-1", money("250.00"))))
                .extractingJsonPathStringValue("$.amount").isEqualTo("250.00");
    }

    @Test
    void ignoresUnknownFieldsWhenDeserializing() throws Exception {
        assertThat(json.parseObject("{\"transferId\":\"TRF-1\",\"legacyField\":true}").transferId())
                .isEqualTo("TRF-1");
    }
}
```

Use this to lock down date formats, `BigDecimal` rendering, `@JsonProperty` names and `@JsonInclude` behaviour — the things that silently break downstream consumers.

---

## 7. Outbound HTTP clients

| Client | Test tool |
|---|---|
| `RestTemplate` | `@RestClientTest` + `MockRestServiceServer` |
| `RestClient` | `@RestClientTest` (Boot 3.2+) or WireMock |
| `WebClient` | `MockWebServer` (OkHttp) or WireMock — `WebTestClient` does not mock outbound calls |
| Feign / declarative clients | WireMock |

```java
@RestClientTest(SanctionsScreeningClient.class)
class SanctionsScreeningClientTest {

    @Autowired private SanctionsScreeningClient client;
    @Autowired private MockRestServiceServer server;

    @Test
    void screen_whenUpstreamReturns503_throwsRetryableException() {
        server.expect(requestTo("/screen"))
              .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.screen("ACC-1"))
                .isInstanceOf(ScreeningUnavailableException.class);
        server.verify();
    }
}
```

Always test the unhappy upstream paths — timeouts, 4xx, 5xx, malformed body — because those are the paths that actually page someone at 3am, and they are the ones nobody writes tests for.

---

## 8. Configuration properties

```java
class BankingPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(BankingPropertiesConfig.class);

    @Test
    void bindsAndValidates() {
        runner.withPropertyValues("banking.transfer.daily-cap=50000")
              .run(ctx -> assertThat(ctx.getBean(BankingProperties.class).transfer().dailyCap())
                      .isEqualByComparingTo("50000"));
    }

    @Test
    void failsFastWhenCapIsNegative() {
        runner.withPropertyValues("banking.transfer.daily-cap=-1")
              .run(ctx -> assertThat(ctx).hasFailed());
    }
}
```

`ApplicationContextRunner` is also the right tool for testing auto-configuration and conditional beans (`@ConditionalOnProperty`, `@ConditionalOnMissingBean`) — far faster than `@SpringBootTest` with property permutations.

---

## 9. Async, scheduled, retry, cache

These are proxy-driven behaviours. A direct method call in a unit test bypasses the proxy entirely, so the annotation is never exercised — a very common false-pass.

- **`@Async`**: test the method's logic synchronously as a plain unit test. Test the async dispatch separately in a Spring test using Awaitility: `await().atMost(2, SECONDS).untilAsserted(() -> verify(collaborator).handle(any()))`. Never `Thread.sleep`.
- **`@Scheduled`**: extract the body into a public method and unit-test that. Verify the cron expression separately (`CronExpression.parse(...).next(...)` assertions), not by waiting for the trigger.
- **`@Retryable` / Resilience4j**: needs the proxy, so use a small `@SpringBootTest` with only the relevant config, stub the collaborator to fail N times then succeed, and assert both the outcome and the invocation count. Also test that the circuit opens and the fallback returns the documented degraded response.
- **`@Cacheable`**: `@SpringBootTest` with a real cache manager; call twice, `verify(repo, times(1))`. Assert eviction explicitly — cache-eviction bugs are silent in production.
- **`@Transactional`**: rollback semantics cannot be tested from inside a `@Transactional` test. Use a non-transactional `@SpringBootTest`, trigger the failure, then assert on the database in a fresh transaction. See the integration reference.

---

## 10. Messaging

Consumers and producers deserve tests for serialization, error handling and idempotency, not just the happy path.

- **Unit level**: the listener method is a plain method — call it directly with a constructed payload and assert the side effect. Covers deserialization of the domain event and the business rule.
- **Integration level**: Testcontainers Kafka (`@ServiceConnection`) with `Awaitility` polling a consumer or the database. See `references/integration-and-testcontainers.md`.
- **Always test**: malformed message handling (does it poison the partition or go to DLT?), duplicate delivery (is the handler idempotent?), and that offsets are not committed on failure when they should not be.
- `EmbeddedKafka` is lighter than Testcontainers but drifts from the broker version in production; acceptable for contract-shaped tests, weaker for anything touching broker semantics.
