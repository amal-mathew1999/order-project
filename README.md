# Order system: Spring Boot microservices

Customers place an order for a product through the API gateway. **order-service** reserves stock in **inventory-service**, charges **payment-service**, and compensates on failure. Services find each other through Eureka (**discovery-service**). Each business service owns its own MySQL database.

```
client ──► gateway-service :8080 ──► order-service :8081 ──► inventory-service :8082
                │                         │          └─────► payment-service   :8083
                └──── all register with ──┴──► discovery-service :8761 (Eureka)
```

| Service | Port | Database | Role |
|---|---|---|---|
| discovery-service | 8761 | none | Eureka server; dashboard at http://localhost:8761 |
| gateway-service | 8080 | none | Single public entry point; internal endpoints are not routed |
| order-service | 8081 | `order_db` | Places orders, compensates, reconciles stuck orders every 30 s |
| inventory-service | 8082 | `inventory_db` | Stock and per-order reservations |
| payment-service | 8083 | `payment_db` | Idempotent charges, read-only payment lookup |

API details and failure rules: [docs/order-system-contract.md](docs/order-system-contract.md).

---

## 1. Prerequisites

| Tool | Version | Check |
|---|---|---|
| Java (JDK) | 17+ | `java -version` |
| MySQL Server | 8.x, on `localhost:3306` | `mysql --version`, or check that the MySQL80 Windows service is running |
| Maven | **not needed**: each service ships its own Maven wrapper (`mvnw` / `mvnw.cmd`) | |
| Internet | on the first build only (Maven downloads dependencies) | |

Free ports needed: **8761, 8080, 8081, 8082, 8083**.

## 2. Database

Nothing to create by hand. Each service connects with `createDatabaseIfNotExist=true`, so `order_db`, `inventory_db` and `payment_db` are created on first start, and tables are created or updated automatically (`ddl-auto: update`).

The MySQL user needs permission to create databases and tables. To use a dedicated account instead of `root`:

```sql
CREATE USER 'orders'@'localhost' IDENTIFIED BY 'choose-a-password';
GRANT ALL PRIVILEGES ON order_db.*     TO 'orders'@'localhost';
GRANT ALL PRIVILEGES ON inventory_db.* TO 'orders'@'localhost';
GRANT ALL PRIVILEGES ON payment_db.*   TO 'orders'@'localhost';
```

## 3. Configuration (environment variables)

Credentials are read from the environment only. **Never put them in `application.yml`.**

| Variable | Default | Used by |
|---|---|---|
| `DB_USER` | `root` | order, inventory, payment |
| `DB_PASSWORD` | *(empty)* | order, inventory, payment |
| `DB_HOST` | `localhost` | order, inventory, payment |
| `DB_PORT` | `3306` | order, inventory, payment |
| `EUREKA_URL` | `http://localhost:8761/eureka` | all except discovery |

Set them in **every terminal that starts order, inventory or payment**:

```powershell
# Windows PowerShell
$env:DB_USER = "root"
$env:DB_PASSWORD = "your-mysql-password"
```

```bash
# Git Bash / Linux / macOS
export DB_USER=root
export DB_PASSWORD='your-mysql-password'
```

## 4. Start the services

Use **one terminal per service**, started **in this order**. Wait for each one to log `Started ...Application in N seconds` before starting the next. The first start is slower because Maven downloads dependencies.

| # | Directory | Needs DB env vars? | Ready when |
|---|---|---|---|
| 1 | `discovery-service` | no | http://localhost:8761 shows the Eureka dashboard |
| 2 | `inventory-service` | **yes** | logs `Started InventoryServiceApplication` |
| 3 | `payment-service` | **yes** | logs `Started PaymentServiceApplication` |
| 4 | `order-service` | **yes** | logs `Started OrderServiceApplication` |
| 5 | `gateway-service` | no | logs `Started GatewayServiceApplication` |

Command in each terminal, run from the repository root:

```powershell
# Windows PowerShell
cd discovery-service      # then inventory-service, payment-service, order-service, gateway-service
.\mvnw.cmd spring-boot:run
```

```bash
# Git Bash / Linux / macOS
cd discovery-service      # then inventory-service, payment-service, order-service, gateway-service
./mvnw spring-boot:run
```

<details>
<summary>Alternative: start everything from one Git Bash terminal</summary>

```bash
export DB_USER=root DB_PASSWORD='your-mysql-password'
mkdir -p logs
(cd discovery-service && ./mvnw -q spring-boot:run > ../logs/discovery.log 2>&1) &
until curl -s localhost:8761 > /dev/null; do sleep 2; done            # wait for Eureka
for s in inventory-service payment-service order-service gateway-service; do
  (cd $s && ./mvnw -q spring-boot:run > ../logs/${s%-service}.log 2>&1) &
done
tail -f logs/*.log        # Ctrl+C stops tailing; the services keep running
```
</details>

## 5. Verify everything is up

```bash
# each should return {"status":"UP"}
for p in 8761 8080 8081 8082 8083; do echo -n "$p "; curl -s localhost:$p/actuator/health; echo; done
```

Open http://localhost:8761. Under **Instances currently registered with Eureka** it should list **GATEWAY-SERVICE, ORDER-SERVICE, INVENTORY-SERVICE, PAYMENT-SERVICE**. **Wait about 60 s after the last service starts before sending requests.** Registration takes up to 30 s, and the gateway's load-balancer cache refreshes on its own schedule. Requests sent too early fail with `500` (see Troubleshooting).

## 6. Try it (through the gateway on :8080)

```bash
# 1. Add a product with stock (admin endpoint)
curl -X POST localhost:8080/api/v1/inventory-items -H 'Content-Type: application/json' \
  -d '{"productId":1,"productName":"Phone","availableQuantity":5,"unitPrice":499.50}'

# 2. Place an order -> 201 {"status":"CONFIRMED","totalAmount":999.00,"paymentId":...}
curl -X POST localhost:8080/api/v1/orders -H 'Content-Type: application/json' \
  -d '{"customerId":1,"productId":1,"quantity":2}'

# 3. Look things up
curl localhost:8080/api/v1/orders/1
curl "localhost:8080/api/v1/inventory/1/availability?quantity=3"
curl "localhost:8080/api/v1/payments?orderId=1"
```

PowerShell users: use `curl.exe` instead of `curl`, and escape the inner quotes, or use `Invoke-RestMethod`:

```powershell
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/orders -ContentType 'application/json' `
  -Body '{"customerId":1,"productId":1,"quantity":2}'
```

What other requests return:

| Request | Result |
|---|---|
| Order more than is in stock | `201` with `"status":"REJECTED"`, `"failureReason":"Insufficient stock"` |
| Order an unknown product | `201` with `"status":"REJECTED"`, `"failureReason":"Unknown product"` |
| Order totalling more than 10000.00 | `201` with `"status":"PAYMENT_FAILED"`; the stock is restored |
| `quantity: 0` | `400` ProblemDetail with field errors |
| payment-service stopped | `503`; the order is `PAYMENT_UNKNOWN` and is resolved automatically within about 30 s after payment-service is back |

All errors use the RFC 7807 `application/problem+json` format.

## 7. Stop

Press **Ctrl+C** in each service terminal. If you used the one-terminal script, stop the processes listening on the ports:

```powershell
# Windows PowerShell
foreach ($p in 8761,8080,8081,8082,8083) {
  Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue |
    ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }
}
```

To reset all data, drop the databases (they are recreated on the next start):

```sql
DROP DATABASE order_db; DROP DATABASE inventory_db; DROP DATABASE payment_db;
```

## 8. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `Access denied for user 'root'@'localhost'` | `DB_USER`/`DB_PASSWORD` are not set in **that** terminal; set them, then restart the service |
| `Communications link failure` | MySQL is not running, or is not on `DB_HOST:DB_PORT` |
| `Port 808x was already in use` | A previous run is still alive; stop it (section 7) |
| Gateway returns `500` right after startup; `gateway.log` shows `Unable to find instance for <service>` | Warm-up: the gateway hasn't refreshed its registry and load-balancer cache yet. Wait up to about 60 s and retry. Check http://localhost:8761 to confirm the service is listed |
| Gateway returns `404` for `/api/v1/inventory/reservations` | Expected: reservation and release endpoints are internal and are not routed |
| Order returns `503` | inventory-service or payment-service is down; start it again. Stuck orders are reconciled automatically |
| `Data truncated for column 'status'` | Database created by an older version with a native ENUM column: `ALTER TABLE order_db.orders MODIFY status VARCHAR(32);` and `ALTER TABLE inventory_db.reservations MODIFY status VARCHAR(32) NOT NULL;` |
| `./mvnw: Permission denied` (Linux/macOS) | `chmod +x */mvnw` |

## 9. Tests

```bash
cd order-service && ./mvnw test          # same for every service; Windows: .\mvnw.cmd test
```

Service tests use an in-memory H2 database (MySQL mode) with Eureka disabled, so **MySQL does not need to be running**.

Harness tests (generators and hooks): `python -m pytest` from the repository root; add `-m slow` for the end-to-end Maven build.

## Development harness

`CLAUDE.md` and `.claude/` contain the Claude Code setup used to build these services:
- skills `new-service` and `add-resource` generate services and CRUD slices;
- agents `service-architect`, `service-builder` and `code-reviewer`;
- hooks: a Java lint after each edit, and a `mvnw test` build gate on stop.

See `CLAUDE.md` for the conventions.
