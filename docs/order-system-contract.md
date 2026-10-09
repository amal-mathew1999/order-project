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
2. It calls inventory-service `POST /api/v1/inventory/reservations`.
   - 409 → order `REJECTED` (out of stock). Stop.
   - 404 → order `REJECTED` (unknown product). Stop.
3. total = `unitPrice × quantity` (the unit price comes from the reservation response).
4. It calls payment-service `POST /api/v1/payments/charges`.
   - `SUCCESS` → order `CONFIRMED`, `paymentId` set.
   - `FAILED` → it calls inventory `POST /api/v1/inventory/releases`, and the order becomes `PAYMENT_FAILED`.
5. If a downstream service is unreachable or times out (circuit open), any reservation already made is released, the order becomes `FAILED`, and the API returns 503 ProblemDetail.

The endpoint returns `201` with the final order for CONFIRMED / REJECTED / PAYMENT_FAILED. Remote calls are never made inside a DB transaction.

## inventory-service API
Generated CRUD: `InventoryItem "productId:Long,productName:String,availableQuantity:Integer,unitPrice:BigDecimal"` at `/api/v1/inventory-items` (admin/seed). `productId` is unique.

| Method & path | Request | Response |
|---|---|---|
| `GET /api/v1/inventory/{productId}/availability?quantity=2` | | `200 {"productId":1,"requestedQuantity":2,"available":true,"unitPrice":10.50}`; 404 if unknown |
| `POST /api/v1/inventory/reservations` | `{"productId":1,"quantity":2}` | `200 {"productId":1,"quantity":2,"unitPrice":10.50}`; 409 if insufficient stock; 404 if unknown |
| `POST /api/v1/inventory/releases` | `{"productId":1,"quantity":2}` | `204`; 404 if unknown |

Reservations must be atomic under concurrency: a conditional update `... SET available_quantity = available_quantity - :q WHERE product_id = :p AND available_quantity >= :q`, never read-then-write. `quantity` must be ≥ 1.

## payment-service API
Generated CRUD: `Payment "orderId:Long,amount:BigDecimal,status:String"` at `/api/v1/payments` (GET is used for lookups).

| Method & path | Request | Response |
|---|---|---|
| `POST /api/v1/payments/charges` | `{"orderId":1,"amount":21.00}` | `201 {"id":5,"orderId":1,"amount":21.00,"status":"SUCCESS"}` |

- Simulated processor: `amount <= 10000.00` → `SUCCESS`, otherwise `FAILED`. Both return 201 with a persisted payment.
- Idempotent per `orderId`: if a SUCCESS payment already exists for the order, return it (201) without charging again.
- `amount` must be > 0.

## order-service API
Generated with `Order "customerId:Long,productId:Long,quantity:Integer"`, then hand-edited:
- Entity/response gain `unitPrice` (BigDecimal), `totalAmount` (BigDecimal), `status` (`OrderStatus` enum: PENDING, CONFIRMED, REJECTED, PAYMENT_FAILED, FAILED), `paymentId` (Long) and `failureReason` (String). All are nullable and server-set; none of them is in the request.
- Request: `{"customerId":1,"productId":1,"quantity":2}` with `quantity` ≥ 1.
- `POST /api/v1/orders` places the order (flow above). `GET /api/v1/orders/{id}` and `GET /api/v1/orders` stay. **Remove PUT and DELETE** (placed orders are immutable).
- Clients: OpenFeign interfaces `InventoryClient` (`@FeignClient("inventory-service")`) and `PaymentClient` (`@FeignClient("payment-service")`), resolved through Eureka. Feign timeouts: connect 2s, read 5s. Resilience4j circuit breakers wrap the calls.

## gateway-service routes
`/api/v1/orders/**` → `lb://order-service`; `/api/v1/inventory/**` and `/api/v1/inventory-items/**` → `lb://inventory-service`; `/api/v1/payments/**` → `lb://payment-service`.
