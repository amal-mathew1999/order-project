# Spring Boot microservices

Each `*-service/` folder is an independent Maven project (Spring Boot 4.1.1, Spring Cloud, Java 17, Maven wrapper). There is no parent pom. Each service owns its own MySQL database; tests use H2. Services register with Eureka (`discovery-service`, port 8761) and are reached through `gateway-service` (port 8080).

## Workflow (always)
1. Design: use the `service-architect` agent → service list + generator commands.
2. Generate: skills `new-service` and `add-resource`. **Never hand-write boilerplate a generator produces.**
3. Customize: write only the business logic and its tests (`service-builder` agent).
4. Review: use the `code-reviewer` agent before you call the work done.

## Conventions
- Package by layer: `domain`, `dto`, `repository`, `service`, `web`, `common` under `com.example.<svc>`.
- Constructor injection only. DTOs are Java records. Entities never leave the service layer.
- REST paths are `/api/v1/<plural-kebab>`. Errors go through `common/GlobalExceptionHandler` (ProblemDetail).
- Business rules live in `@Service` classes (`@Transactional(readOnly = true)` at class level, `@Transactional` on writes).
- Cross-service: reference by ID only, no shared tables, set timeouts on HTTP clients.
- Tests: `@WebMvcTest` (`org.springframework.boot.webmvc.test.autoconfigure`) + `@MockitoBean` for controllers; plain Mockito for services.
- Build and test: `cd <svc> && ./mvnw -q -B test`.

## Automatic guards (hooks)
- After each Edit/Write of a `.java` file, a lint flags field or setter injection, System.out, printStackTrace, empty catch blocks, hard-coded secrets and controllers that import `@Entity` classes. The edit is already applied, so Claude must fix what it flags. Test sources may use field injection; every other rule still applies.
- On stop, `mvnw test` runs for every service that was edited or generated in that turn. A failure blocks the stop with a compact digest. After 3 fix attempts the failure is reported to the user.

## Token rules
Don't read generated files unless you are changing them. Don't paste code back in summaries; give file paths only.
