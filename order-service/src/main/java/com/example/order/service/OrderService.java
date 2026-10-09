package com.example.order.service;

import com.example.order.client.InventoryClient;
import com.example.order.client.PaymentClient;
import com.example.order.common.BadGatewayException;
import com.example.order.common.NotFoundException;
import com.example.order.common.ServiceUnavailableException;
import com.example.order.domain.Order;
import com.example.order.domain.OrderStatus;
import com.example.order.dto.ChargeRequest;
import com.example.order.dto.ChargeResponse;
import com.example.order.dto.OrderRequest;
import com.example.order.dto.OrderResponse;
import com.example.order.dto.ReleaseRequest;
import com.example.order.dto.ReservationResponse;
import com.example.order.dto.ReserveRequest;
import com.example.order.repository.OrderRepository;
import feign.FeignException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link #create} is deliberately NOT transactional because it makes remote calls; order state is persisted
 * through {@link OrderStatusUpdater} in short, separate transactions. Reads are read-only transactions.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    static final String PAYMENT_SUCCESS = "SUCCESS";

    private final OrderRepository repository;
    private final OrderStatusUpdater updater;
    private final InventoryClient inventoryClient;
    private final PaymentClient paymentClient;
    private final CircuitBreaker inventoryBreaker;
    private final CircuitBreaker paymentBreaker;

    public OrderService(OrderRepository repository, OrderStatusUpdater updater, InventoryClient inventoryClient,
            PaymentClient paymentClient, CircuitBreakerRegistry breakers) {
        this.repository = repository;
        this.updater = updater;
        this.inventoryClient = inventoryClient;
        this.paymentClient = paymentClient;
        this.inventoryBreaker = breakers.circuitBreaker("inventory-service");
        this.paymentBreaker = breakers.circuitBreaker("payment-service");
    }

    public OrderResponse create(OrderRequest r) {
        Order order = updater.createPending(r);
        Long id = order.getId();

        ReservationResponse reservation;
        try {
            ReserveRequest reserve = new ReserveRequest(id, r.productId(), r.quantity());
            reservation = inventoryBreaker.executeSupplier(() -> inventoryClient.reserve(reserve));
        } catch (FeignException.Conflict e) {
            return OrderResponse.from(updater.markRejected(id, "Insufficient stock"));
        } catch (FeignException.NotFound e) {
            return OrderResponse.from(updater.markRejected(id, "Unknown product"));
        } catch (FeignException | CallNotPermittedException e) {
            // The reserve may have been applied even though the response was lost; release is idempotent.
            releaseOrFlag(id);
            throw unavailable(id, "Inventory service unavailable", e);
        }

        BigDecimal unitPrice = reservation.unitPrice();
        BigDecimal total = unitPrice.multiply(BigDecimal.valueOf(r.quantity()));

        ChargeResponse charge;
        try {
            charge = paymentBreaker.executeSupplier(() -> paymentClient.charge(new ChargeRequest(id, total)));
        } catch (FeignException e) {
            if (isClientError(e)) {
                releaseOrFlag(id);
                updater.markFailed(id, "Payment service rejected the request");
                throw new BadGatewayException("Payment service rejected the request", e);
            }
            return resolveUnreachablePayment(id, unitPrice, total, e);
        } catch (CallNotPermittedException e) {
            return resolveUnreachablePayment(id, unitPrice, total, e);
        }

        if (matches(charge, id, total) && PAYMENT_SUCCESS.equals(charge.status())) {
            return OrderResponse.from(updater.markConfirmed(id, unitPrice, total, charge.id()));
        }
        releaseOrFlag(id);
        return OrderResponse.from(updater.markPaymentFailed(id, unitPrice, total, "Payment declined"));
    }

    @Transactional(readOnly = true)
    public OrderResponse get(Long id) {
        return OrderResponse.from(find(id));
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> list(int page, int size) {
        return repository.findAll(PageRequest.of(page, size)).map(OrderResponse::from).getContent();
    }

    /** Retries releases that previously failed; clears the flag once inventory accepts. */
    public void retryPendingReleases() {
        for (Order o : repository.findByReleasePendingTrue()) {
            try {
                if (tryRelease(o.getId())) {
                    updater.setReleasePending(o.getId(), false);
                }
            } catch (RuntimeException e) {
                log.error("Release retry failed for order {}", o.getId(), e);
            }
        }
    }

    /** Re-checks PAYMENT_UNKNOWN orders against payment-service; errors are retried on the next run. */
    public void reconcileUnknownPayments() {
        reconcile(repository.findByStatus(OrderStatus.PAYMENT_UNKNOWN));
    }

    /** Resolves orders stuck in PENDING (e.g. the process died or a status write failed mid-flow). */
    public void reconcileStalePending(java.time.Duration olderThan) {
        reconcile(repository.findStalePending(java.time.Instant.now().minus(olderThan)));
    }

    private void reconcile(List<Order> orders) {
        for (Order o : orders) {
            try {
                Long id = o.getId();
                List<ChargeResponse> payments = lookup(id);
                ChargeResponse success = findSuccess(payments, id);
                if (success != null) {
                    BigDecimal total = o.getTotalAmount() != null ? o.getTotalAmount() : success.amount();
                    BigDecimal unitPrice = o.getUnitPrice() != null ? o.getUnitPrice()
                            : total.divide(BigDecimal.valueOf(o.getQuantity()), 2, java.math.RoundingMode.HALF_UP);
                    updater.markConfirmed(id, unitPrice, total, success.id());
                } else {
                    releaseOrFlag(id);
                    updater.markFailed(id, "Payment not found");
                }
            } catch (FeignException | CallNotPermittedException e) {
                log.warn("Payment lookup still failing for order {}; will retry", o.getId(), e);
            } catch (RuntimeException e) {
                log.error("Reconciliation failed for order {}", o.getId(), e);
            }
        }
    }

    private OrderResponse resolveUnreachablePayment(Long id, BigDecimal unitPrice, BigDecimal total, Exception cause) {
        List<ChargeResponse> payments;
        try {
            payments = lookup(id);
        } catch (FeignException | CallNotPermittedException lookupFailure) {
            updater.markPaymentUnknown(id, unitPrice, total, "Payment outcome unknown");
            throw new ServiceUnavailableException("Payment outcome unknown", cause);
        }
        ChargeResponse success = findSuccess(payments, id);
        if (success != null) {
            return OrderResponse.from(updater.markConfirmed(id, unitPrice, total, success.id()));
        }
        releaseOrFlag(id);
        throw unavailable(id, "Payment service unavailable", cause);
    }

    private List<ChargeResponse> lookup(Long id) {
        return paymentBreaker.executeSupplier(() -> paymentClient.findByOrderId(id));
    }

    private static ChargeResponse findSuccess(List<ChargeResponse> payments, Long orderId) {
        if (payments == null) {
            return null;
        }
        return payments.stream()
                .filter(p -> p != null && PAYMENT_SUCCESS.equals(p.status()) && Objects.equals(orderId, p.orderId()))
                .findFirst().orElse(null);
    }

    private static boolean matches(ChargeResponse charge, Long orderId, BigDecimal total) {
        return charge != null && Objects.equals(orderId, charge.orderId()) && charge.amount() != null
                && charge.amount().compareTo(total) == 0;
    }

    private static boolean isClientError(FeignException e) {
        return e.status() >= 400 && e.status() < 500;
    }

    private ServiceUnavailableException unavailable(Long id, String reason, Exception cause) {
        updater.markFailed(id, reason);
        return new ServiceUnavailableException(reason, cause);
    }

    /** A failed release is never dropped: the order is flagged so the scheduled job retries it. */
    private void releaseOrFlag(Long id) {
        if (!tryRelease(id)) {
            updater.setReleasePending(id, true);
        }
    }

    private boolean tryRelease(Long id) {
        try {
            inventoryBreaker.executeRunnable(() -> inventoryClient.release(new ReleaseRequest(id)));
            return true;
        } catch (FeignException | CallNotPermittedException e) {
            log.error("Failed to release reservation for order {}; will retry", id, e);
            return false;
        }
    }

    private Order find(Long id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("Order", id));
    }
}
