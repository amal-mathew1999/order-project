---
name: new-service
description: Scaffold a new Spring Boot microservice (Maven wrapper, Java 17, Boot 4.1.1, MySQL + Eureka client by default, H2 for tests, ProblemDetail errors, auto port). Also discovery server and API gateway. Use whenever a new service/microservice is requested.
---

Run ONE command from the project root. Do not hand-write pom.xml, the Application class, application.yml or exception handling.

```
python .claude/skills/new-service/scripts/new_service.py <name>-service [--port N] [--deps ...] [--boot 4.1.1]
```

| Kind | `--deps` |
|---|---|
| Business service (default) | `web,data-jpa,validation,mysql,actuator,cloud-eureka` |
| + calls other services | add `cloud-feign,cloud-resilience4j` |
| Discovery (Eureka server, port 8761) | `cloud-eureka-server,actuator` |
| API gateway (port 8080) | `cloud-gateway,cloud-eureka,actuator` |

- `<name>` is kebab-case, for example `order-service` → package `com.example.order`. Java keywords are rejected.
- Ports: business services get the next free port in 8081–8699; the gateway gets 8080 and discovery gets 8761.
- MySQL: `jdbc:mysql://${DB_HOST:localhost}:${DB_PORT:3306}/<name>_db` with `DB_USER`/`DB_PASSWORD` from the environment, and the database is created if missing. Tests run on H2 in MySQL mode, with Eureka disabled.
- Web services get `common/NotFoundException` and `common/GlobalExceptionHandler`. Every error, including bad JSON, type mismatches and parameter validation, comes back as RFC 7807 ProblemDetail.
- The service is marked for the Stop build gate automatically.

Next: add entities with the `add-resource` skill. Don't read the generated files unless you need to change them.
