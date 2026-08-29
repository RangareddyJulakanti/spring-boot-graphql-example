# Version Matrix — Spring Boot Testing APIs

Read this in step 1. Generating `@MockBean` for a Boot 4 project (removed) or `@MockitoBean` for a Boot 3.2 project (does not exist) produces code that does not compile.

## Bean replacement annotations

| Boot version | Spring Framework | Use | Notes |
|---|---|---|---|
| 2.x – 3.3 | 5.3 – 6.1 | `@MockBean`, `@SpyBean` | `org.springframework.boot.test.mock.mockito.*` |
| 3.4 – 3.5 | 6.2 | `@MockitoBean`, `@MockitoSpyBean` | `@MockBean`/`@SpyBean` deprecated for removal; new annotations live in `org.springframework.test.context.bean.override.mockito.*` (Framework, not Boot) |
| 4.x | 7.x | `@MockitoBean`, `@MockitoSpyBean` | old annotations gone |

Related bean-override annotations available from Framework 6.2: `@TestBean` (replace a bean with a real instance from a static factory method — often better than a mock) and `@MockitoBean(enforceOverride = true)`.

Caveat: version-specific details below Boot 3.5 are stable and well-attested; treat Boot 4.x / Framework 7 specifics as "verify against the project's actual dependency tree before committing". Always confirm by reading the classpath rather than assuming.

## Web-layer test clients

| API | Available from | Use for |
|---|---|---|
| `MockMvc` (`perform(...).andExpect(...)`) | forever | the default; universally understood in review |
| `MockMvcTester` (AssertJ fluent, auto-configured) | Boot 3.4 / Framework 6.2 | new code where the team is AssertJ-first; no checked `Exception` on every test method |
| `WebTestClient` | 5.x | WebFlux, or `@SpringBootTest(webEnvironment = RANDOM_PORT)` |
| `TestRestTemplate` | forever | blocking end-to-end against a real port |
| `RestTestClient` | Framework 7 / Boot 4 | blocking successor to `WebTestClient` style for MVC — verify availability first |

Pick one and use it consistently across the suite.

## Other version-sensitive points

- **JUnit**: `spring-boot-starter-test` ships JUnit 5. `@RunWith(SpringRunner.class)` is JUnit 4 and is a legacy marker — in JUnit 5 use `@ExtendWith(SpringExtension.class)`, which the Spring test annotations already include. Never add it manually alongside `@SpringBootTest` or `@WebMvcTest`.
- **Mockito 5+** (Boot 3.x default): inline mock maker is the default, so `mockito-inline` is no longer needed and static/final mocking works out of the box. Minimum Java 11.
- **Testcontainers**: `@ServiceConnection` (Boot 3.1+) removes almost all `@DynamicPropertySource` boilerplate. On Boot 3.0 or earlier, `@DynamicPropertySource` is required.
- **`@MockitoBean` and context caching**: each distinct set of bean overrides creates a *new* application context. Fifty test classes with slightly different mock sets = fifty context boots. Consolidate into shared abstract base classes.
- **Hamcrest vs AssertJ**: `MockMvcResultMatchers` (`jsonPath(...).value(...)`) is Hamcrest-based; that is fine inside `andExpect`. Everywhere else, prefer AssertJ.
- **JUnit 4 → 5 migration markers** in a legacy module: `@Before` → `@BeforeEach`, `@After` → `@AfterEach`, `@BeforeClass` → `@BeforeAll` (static), `@Ignore` → `@Disabled`, `@Test(expected=)` → `assertThatThrownBy`, `@Rule` → extensions, `ExpectedException` → AssertJ. Do not mix engines in one class.

## Minimum dependencies

Maven:

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-test</artifactId>
  <scope>test</scope>
</dependency>
<!-- only if security is on the classpath -->
<dependency>
  <groupId>org.springframework.security</groupId>
  <artifactId>spring-security-test</artifactId>
  <scope>test</scope>
</dependency>
<!-- integration tests against real infrastructure -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-testcontainers</artifactId>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>org.testcontainers</groupId>
  <artifactId>junit-jupiter</artifactId>
  <scope>test</scope>
</dependency>
```

`spring-boot-starter-test` already brings JUnit 5, Mockito, AssertJ, Hamcrest, JSONassert, JsonPath, XMLUnit and `spring-test`. Do not add them separately — version drift against the Boot BOM is a common cause of `NoSuchMethodError` at test runtime.
