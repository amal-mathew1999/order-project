# Spring Boot microservices

Each `*-service/` folder is an independent Maven project (Spring Boot 4.x, Java 17, Maven wrapper, H2 for dev). There is no parent pom, and each service owns its own database.

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
- After each edit: a Java lint rejects field injection, System.out, printStackTrace, empty catch blocks, hard-coded secrets, and controllers that import entities.
- On stop: `mvnw test` runs for every service edited in that turn. A failure blocks the stop (up to 3 retries).

## Token rules
Don't read generated files unless you are changing them. Don't paste code back in summaries; give file paths only.
