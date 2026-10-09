# Order system: service contract

This is the single source of truth for the parallel builds. Each service is built independently, and the APIs below must match exactly. All error responses are RFC 7807 ProblemDetail (`application/problem+json`).

| Service | Port | Package | Deps (`--deps`) | DB |
|---|---|---|---|---|
| discovery-service | 8761 | com.example.discovery | `cloud-eureka-server,actuator` | none |
| gateway-service | 8080 | com.example.gateway | `cloud-gateway,cloud-eureka,actuator` | none |
| order-service | 8081 | com.example.order | `web,data-jpa,validation,mysql,actuator,cloud-eureka,cloud-feign,cloud-resilience4j` | order_db |
| inventory-service | 8082 | com.example.inventory | default | inventory_db |
| payment-service | 8083 | com.example.payment | default | payment_db |

Always pass `--port` explicitly, because worktrees cannot see each other's ports. MySQL credentials come from `DB_USER`/`DB_PASSWORD` (generated yml); tests run on H2.

## Flow: place an order
`POST /api/v1/orders` (through the gateway at :8080 or directly at :8081)
1. order-service saves the order as `PENDING`.
2. It calls inventory-service `POST /api/v1/inventory/reservations` with the order id.
   - 409 → order `REJECTED` (out of stock). Stop.
   - 404 → order `REJECTED` (unknown product). Stop.
3. total = `unitPrice × quantity` (the unit price comes from the reservation response).
4. It calls payment-service `POST /api/v1/payments/charges`.
   - `SUCCESS` → order `CONFIRMED`, `paymentId` set.
   - `FAILED`, or a charge whose `orderId`/`amount` does not match the order → it releases the reservation, and the order becomes `PAYMENT_FAILED`.
   - payment-service answers 4xx (other than the above) → release, order `FAILED`, 502 ProblemDetail.
5. Inventory unreachable, timing out or returning 5xx on reserve → release (idempotent, a safe no-op if nothing was reserved), order `FAILED`, 503 ProblemDetail.
6. Payment unreachable or timing out → order-service looks up `GET /api/v1/payments?orderId=`:
   - a SUCCESS payment exists → `CONFIRMED` (the charge went through and only the response was lost);
   - the lookup works and finds no SUCCESS → release, `FAILED`, 503;
   - the lookup also fails → `PAYMENT_UNKNOWN`, the reservation is kept, 503. A scheduled reconciler re-checks it later.
7. Releases that fail (inventory down, circuit open) are not dropped: the order keeps `releasePending=true`, and a scheduled job retries until inventory accepts the release.

The endpoint returns `201` with the final order for CONFIRMED / REJECTED / PAYMENT_FAILED. Remote calls are never made inside a DB transaction.

## inventory-service API
Generated CRUD: `InventoryItem "productId:Long,productName:String,availableQuantity:Integer,unitPrice:BigDecimal"` at `/api/v1/inventory-items` (admin/seed). `productId` is unique.

| Method & path | Request | Response |
|---|---|---|
| `GET /api/v1/inventory/{productId}/availability?quantity=2` | | `200 {"productId":1,"requestedQuantity":2,"available":true,"unitPrice":10.50}`; 404 if unknown |
| `POST /api/v1/inventory/reservations` | `{"orderId":7,"productId":1,"quantity":2}` | `200 {"orderId":7,"productId":1,"quantity":2,"unitPrice":10.50}`; 409 if insufficient stock; 404 if unknown product |
| `POST /api/v1/inventory/releases` | `{"orderId":7}` | `204` always (idempotent; a no-op if there is no active reservation for that order) |

- Reservations are stored per `orderId` (unique). Repeating a reserve for the same order returns the existing reservation without decrementing again. A release only gives back what that order reserved, once.
- The stock decrement stays an atomic conditional update (`... WHERE available_quantity >= :q`), never read-then-write.
- `quantity`: 1–10000. `availability?quantity=` must be ≥ 1. Prices have at most 2 decimals (`@Digits`; column precision 19, scale 2).
- `/reservations` and `/releases` are **internal**: the gateway does not route them.

## payment-service API
Generated with `Payment "orderId:Long,amount:BigDecimal,status:String"`, then made **read-only**: `GET /api/v1/payments/{id}` and `GET /api/v1/payments?orderId=` (a list filter). The generated POST/PUT/DELETE are removed.

| Method & path | Request | Response |
|---|---|---|
| `POST /api/v1/payments/charges` | `{"orderId":1,"amount":21.00}` | `201 {"id":5,"orderId":1,"amount":21.00,"status":"SUCCESS"}` |

- Simulated processor: `amount <= 10000.00` → `SUCCESS`, otherwise `FAILED`. Both return 201 with a persisted payment.
- Idempotent per `orderId`, **including under concurrency**: at most one SUCCESS payment per order, enforced by a DB unique constraint (e.g. a nullable unique `success_order_id` column). On a constraint violation, re-read and return the existing SUCCESS payment.
- `amount` must be > 0 with at most 2 decimals.

## order-service API
Generated with `Order "customerId:Long,productId:Long,quantity:Integer"`, then hand-edited:
- Entity/response gain `unitPrice` (BigDecimal), `totalAmount` (BigDecimal), `status` (`OrderStatus` enum: PENDING, CONFIRMED, REJECTED, PAYMENT_FAILED, FAILED, PAYMENT_UNKNOWN), `paymentId` (Long) and `failureReason` (String). The entity also has `releasePending` (boolean, not exposed). All are server-set; none of them is in the request.
- Request: `{"customerId":1,"productId":1,"quantity":2}` with `quantity` ≥ 1.
- `POST /api/v1/orders` places the order (flow above). `GET /api/v1/orders/{id}` and `GET /api/v1/orders` stay. **Remove PUT and DELETE** (placed orders are immutable).
- Clients: OpenFeign interfaces `InventoryClient` (`@FeignClient("inventory-service")`) and `PaymentClient` (`@FeignClient("payment-service")`), resolved through Eureka. Feign timeouts: connect 2s, read 5s. Resilience4j circuit breakers wrap the calls.

## gateway-service routes
`/api/v1/orders/**` → `lb://order-service`; `/api/v1/inventory/*/availability` and `/api/v1/inventory-items/**` → `lb://inventory-service`; `/api/v1/payments/**` → `lb://payment-service` (read-only). Reservation and release endpoints are internal and are not routed.

## All services
Every error is ProblemDetail, including unexpected ones: `DataIntegrityViolationException` → 409, and any other exception → 500 with a generic detail (no stack trace or message leak).
