---
name: add-resource
description: Generate a complete, tested CRUD REST slice (JPA entity, request/response DTO records, repository, transactional service, versioned controller, WebMvc + Mockito + persistence tests) for an entity in an existing service. Use for any new entity/resource/endpoint set.
---

Run ONE command from the project root:

```
python .claude/skills/add-resource/scripts/gen_resource.py <service-dir> <Entity> "field:Type,field:Type" [--table name] [--plural word]
```

Types: `String Long Integer Double BigDecimal Boolean LocalDate Instant`. `id` is added automatically, so don't list it.

Example: `python .claude/skills/add-resource/scripts/gen_resource.py order-service Order "customerId:Long,quantity:Integer,totalAmount:BigDecimal,status:String"`

The generator rejects these with a clear error: malformed specs, `id`, duplicate fields, Java keywords, and entity names that clash with imported classes (`Service`, `List`, ...).

Output (existing files are never overwritten):
- `domain/<E>`: entity. The table is the snake_case plural (`Category` → `categories`). SQL reserved words used as table or column names (`order`, `value`, `year`, `user`, ...) are quoted.
- `dto/<E>Request` (Bean Validation), `dto/<E>Response` (`from(entity)`).
- `repository/<E>Repository`, `service/<E>Service`.
- `web/<E>Controller` at `/api/v1/<plural>` (POST 201+Location, GET, paged list, PUT, DELETE 204).
- Tests: `web/<E>ControllerTest` (status codes plus ProblemDetail), `service/<E>ServiceTest` (Mockito), `repository/<E>RepositoryTest` (real schema round-trip).

After generating, edit ONLY what is domain-specific: enums, extra validation (`@Positive`, `@Size`), custom queries, business rules in the service, plus a test for each rule you add. Leave the generated structure as it is.
