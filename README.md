# Order system: Spring Boot microservices

Place an order for a product. inventory-service reserves stock, payment-service charges, and order-service orchestrates the flow and compensates on failure. Clients go through the API gateway, and services find each other via Eureka. API details and failure rules are in [docs/order-system-contract.md](docs/order-system-contract.md).

| Service | Port | Role |
|---|---|---|
| discovery-service | 8761 | Eureka server (dashboard: http://localhost:8761) |
| gateway-service | 8080 | Single public entry point; internal endpoints are not routed |
| order-service | 8081 | Order placement, compensation, scheduled reconciliation (MySQL `order_db`) |
| inventory-service | 8082 | Stock + per-order reservations (MySQL `inventory_db`) |
| payment-service | 8083 | Idempotent charges, read-only payment lookup (MySQL `payment_db`) |

## Run locally
Requires Java 17 and MySQL 8 on localhost:3306. Databases are created automatically. Pass credentials through the environment only:

```bash
export DB_USER=root DB_PASSWORD=...          # PowerShell: $env:DB_USER='root'; $env:DB_PASSWORD='...'
(cd discovery-service && ./mvnw spring-boot:run) &   # start this first
for s in inventory-service payment-service order-service gateway-service; do (cd $s && ./mvnw spring-boot:run) & done
```

```bash
curl -X POST localhost:8080/api/v1/inventory-items -H 'Content-Type: application/json' \
  -d '{"productId":1,"productName":"Phone","availableQuantity":5,"unitPrice":499.50}'
curl -X POST localhost:8080/api/v1/orders -H 'Content-Type: application/json' \
  -d '{"customerId":1,"productId":1,"quantity":2}'      # -> 201 {"status":"CONFIRMED",...}
```

## Tests
Each service: `cd <service> && ./mvnw test`. Tests use H2 in MySQL mode with Eureka disabled, so MySQL is not needed.
Harness (generators, hooks): `python -m pytest` (add `-m slow` for the end-to-end Maven build).

## Development harness
`CLAUDE.md` + `.claude/` (skills `new-service` / `add-resource`, agents `service-architect` / `service-builder` / `code-reviewer`, and lint and build-gate hooks) generate new services and keep them consistent. See `CLAUDE.md`.
