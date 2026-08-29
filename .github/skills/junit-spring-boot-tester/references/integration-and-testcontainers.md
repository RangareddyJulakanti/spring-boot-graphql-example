# Integration Tests and Testcontainers

Integration tests are expensive. Write few, make each prove something no cheaper test can: real SQL, real transactions, real serialization across a wire, real wiring.

## Shared configuration (Boot 3.1+)

```java
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>("postgres:16-alpine");
    }

    @Bean
    @ServiceConnection
    KafkaContainer kafka() {
        return new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));
    }
}
```

`@ServiceConnection` wires the datasource/broker properties automatically — no `@DynamicPropertySource`. For services without a `ServiceConnection` implementation, fall back to:

```java
@DynamicPropertySource
static void props(DynamicPropertyRegistry registry) {
    registry.add("app.vault.url", vault::getHttpHostAddress);
}
```

Base class:

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
abstract class AbstractIntegrationTest {
    @Autowired protected TestRestTemplate restTemplate;
}
```

Every integration test extends this one base class. That is not just tidiness: **it is the single biggest lever on suite runtime**, because Spring caches contexts by configuration key and one shared key means one context boot for the whole suite.

## Container lifecycle

- Containers declared as `@Bean` in a `@TestConfiguration` are singletons for the cached context — started once, reused across all test classes sharing that context. This is what you want.
- `@Testcontainers` + `@Container` (non-static) restarts a container **per test method**. Almost never what you want. Static `@Container` is per-class.
- Enable reuse locally (`testcontainers.reuse.enable=true` in `~/.testcontainers.properties` + `.withReuse(true)`) to skip startup between runs on a developer machine. Do not rely on it in CI.
- Pin image tags. `postgres:latest` makes the build non-reproducible and eventually breaks on a Tuesday for no reason anyone can explain.

## Context caching — the thing that makes suites slow

Spring caches the `ApplicationContext` keyed by the full test configuration: annotations, active profiles, property sources, bean overrides, `@Import`s. Any difference creates a new context and another boot cycle.

Rules:
- Do not sprinkle `@DirtiesContext`. It evicts the cache and forces a full reboot. Use it only when a test genuinely corrupts global state, and fix the corruption instead if you can.
- Do not vary `@TestPropertySource` values per class if you can pass configuration at runtime instead.
- Keep `@MockitoBean` sets identical across classes that could share a context.
- Diagnose with `logging.level.org.springframework.test.context.cache=DEBUG` — it logs cache hits, misses and the current cache size (default max 32 contexts, LRU-evicted).

## Database state between tests

Pick one strategy and apply it suite-wide:

1. **`@Transactional` rollback** — fastest, but the test runs inside the same transaction as the code, so it cannot test commit/rollback semantics, `@Transactional(propagation = REQUIRES_NEW)`, or post-commit hooks. Fine for read-oriented tests.
2. **Truncate after each test** — a `@BeforeEach` that truncates all tables (or `@Sql(scripts = "/cleanup.sql", executionPhase = AFTER_TEST_METHOD)`). Slower, but the code runs with real transaction boundaries. This is the right default for end-to-end flow tests.
3. **Unique data per test** — no cleanup, every test creates its own identifiers. Scales well, but leaks state and eventually produces order-dependent failures.

Never rely on tests running in a particular order. If test B only passes after test A has run, the suite is already broken; it just has not failed yet.

## Testing transactional behaviour honestly

To prove a rollback actually happens, the test must be **non-transactional**:

```java
@Test   // note: no @Transactional here
void transfer_whenLedgerWriteFails_rollsBackDebit() {
    accountRepository.save(account("ACC-1", "1000.00"));
    doThrow(new DataIntegrityViolationException("boom")).when(ledgerRepository).save(any());

    assertThatThrownBy(() -> transferService.transfer(...))
            .isInstanceOf(DataIntegrityViolationException.class);

    assertThat(accountRepository.findByCode("ACC-1").balance())
            .isEqualByComparingTo("1000.00");   // debit was rolled back
}
```

Also worth an explicit test: self-invocation. A `@Transactional` method called from another method of the same bean bypasses the proxy and runs without a transaction. This is one of the most common production bugs in Spring codebases and it is invisible to unit tests.

## Async and eventual consistency

```java
await().atMost(Duration.ofSeconds(10))
       .pollInterval(Duration.ofMillis(200))
       .untilAsserted(() -> assertThat(outboxRepository.findByStatus(SENT)).hasSize(1));
```

Awaitility with a bounded timeout, never `Thread.sleep`. Sleeps are either too short (flaky) or too long (slow suite) and usually both across different machines.

## Kafka integration shape

```java
@Test
void transferCompleted_publishesLedgerEvent() {
    restTemplate.postForEntity("/api/v1/transfers", validRequest(), Void.class);

    await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
        ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
        assertThat(records).extracting(ConsumerRecord::value)
                .anySatisfy(v -> assertThatJson(v).node("eventType").isEqualTo("LEDGER_POSTED"));
    });
}
```

Cover: DLT routing on a poison message, idempotency on duplicate delivery, and consumer group rebalancing only if the application logic actually depends on it.

## WireMock for external HTTP dependencies

For integration tests that must not reach real third-party systems:

```java
@AutoConfigureWireMock(port = 0)   // spring-cloud-contract-wiremock
```

or a `WireMockExtension` registered per class. Stub the upstream, then test the paths that matter: success, 4xx, 5xx, timeout, and slow response (`withFixedDelay`) against the configured client timeout. A client whose timeout has never been tested does not have a timeout.

## CI considerations

- Testcontainers requires a Docker daemon in CI. If the pipeline cannot provide one, say so and fall back to embedded alternatives with the limitation stated plainly — do not silently substitute H2 and imply equivalent coverage.
- Separate fast tests from integration tests (`maven-surefire` vs `maven-failsafe`, or JUnit tags `@Tag("integration")`) so the inner-loop feedback stays under a minute.
- Parallel execution (`junit.jupiter.execution.parallel.enabled=true`) requires tests to be independent of shared DB state and free of static mocking. Enable it deliberately, not by default.
