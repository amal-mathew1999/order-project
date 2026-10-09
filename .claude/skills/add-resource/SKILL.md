---
name: add-resource
description: Generate a complete, tested CRUD REST slice (JPA entity, request/response DTO records, repository, transactional service, versioned controller, WebMvc + Mockito tests) for an entity in an existing service. Use for any new entity/resource/endpoint set.
---

Run ONE command from the project root:

```
python .claude/skills/add-resource/scripts/gen_resource.py <service-dir> <Entity> "field:Type,field:Type" [--table name]
```

Types: `String Long Integer Double BigDecimal Boolean LocalDate Instant`. `id` is added automatically.

Example: `python .claude/skills/add-resource/scripts/gen_resource.py order-service Order "customerId:Long,quantity:Integer,totalAmount:BigDecimal,status:String"`

Output (existing files are never overwritten):
- `domain/<E>`: entity; table = snake_case plural, so reserved words like `order` are safe.
- `dto/<E>Request` (Bean Validation) and `dto/<E>Response` (`from(entity)`).
- `repository/<E>Repository`, `service/<E>Service`.
- `web/<E>Controller` at `/api/v1/<plural>` (POST 201+Location, GET, paged list, PUT, DELETE 204).
- `test/.../web/<E>ControllerTest`, `test/.../service/<E>ServiceTest`.

After generating, edit ONLY what is domain-specific: enums, extra validation (`@Positive`, `@Size`), custom queries, business rules in the service, plus a test for each rule you add. Leave the generated structure as it is.
