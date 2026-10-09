package com.example.order.service;

import com.example.order.client.InventoryClient;
import com.example.order.client.PaymentClient;
import com.example.order.common.NotFoundException;
import com.example.order.common.ServiceUnavailableException;
import com.example.order.domain.Order;
import com.example.order.dto.ChargeRequest;
import com.example.order.dto.ChargeResponse;
import com.example.order.dto.OrderRequest;
import com.example.order.dto.OrderResponse;
import com.example.order.dto.ReservationResponse;
import com.example.order.dto.StockRequest;
import com.example.order.repository.OrderRepository;
import feign.FeignException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.math.BigDecimal;
import java.util.List;
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
        StockRequest stock = new StockRequest(r.productId(), r.quantity());

        ReservationResponse reservation;
        try {
            reservation = inventoryBreaker.executeSupplier(() -> inventoryClient.reserve(stock));
        } catch (FeignException.Conflict e) {
            return OrderResponse.from(updater.markRejected(id, "Insufficient stock"));
        } catch (FeignException.NotFound e) {
            return OrderResponse.from(updater.markRejected(id, "Unknown product"));
        } catch (FeignException | CallNotPermittedException e) {
            throw unavailable(id, "Inventory service unavailable", e);
        }

        BigDecimal unitPrice = reservation.unitPrice();
        BigDecimal total = unitPrice.multiply(BigDecimal.valueOf(r.quantity()));

        ChargeResponse charge;
        try {
            charge = paymentBreaker.executeSupplier(() -> paymentClient.charge(new ChargeRequest(id, total)));
        } catch (FeignException | CallNotPermittedException e) {
            release(stock);
            throw unavailable(id, "Payment service unavailable", e);
        }

        if (PAYMENT_SUCCESS.equals(charge.status())) {
            return OrderResponse.from(updater.markConfirmed(id, unitPrice, total, charge.id()));
        }
        release(stock);
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

    private ServiceUnavailableException unavailable(Long id, String reason, Exception cause) {
        updater.markFailed(id, reason);
        return new ServiceUnavailableException(reason, cause);
    }

    /** Best effort: a failed release is logged and must not mask the original outcome. */
    private void release(StockRequest stock) {
        try {
            inventoryBreaker.executeRunnable(() -> inventoryClient.release(stock));
        } catch (FeignException | CallNotPermittedException e) {
            log.error("Failed to release reservation for product {} quantity {}", stock.productId(), stock.quantity(), e);
        }
    }

    private Order find(Long id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("Order", id));
    }
}
