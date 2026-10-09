package com.example.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.order.client.InventoryClient;
import com.example.order.client.PaymentClient;
import com.example.order.common.NotFoundException;
import com.example.order.common.ServiceUnavailableException;
import com.example.order.domain.Order;
import com.example.order.domain.OrderStatus;
import com.example.order.dto.ChargeRequest;
import com.example.order.dto.ChargeResponse;
import com.example.order.dto.OrderRequest;
import com.example.order.dto.OrderResponse;
import com.example.order.dto.ReservationResponse;
import com.example.order.dto.StockRequest;
import com.example.order.repository.OrderRepository;
import feign.FeignException;
import feign.Request;
import feign.RequestTemplate;
import feign.RetryableException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    private static final OrderRequest REQUEST = new OrderRequest(7L, 1L, 2);
    private static final StockRequest STOCK = new StockRequest(1L, 2);
    private static final BigDecimal UNIT_PRICE = new BigDecimal("10.50");
    private static final BigDecimal TOTAL = new BigDecimal("21.00");

    @Mock OrderRepository repository;
    @Mock InventoryClient inventory;
    @Mock PaymentClient payment;
    OrderService service;

    @BeforeEach
    void setUp() {
        service = new OrderService(repository, new OrderStatusUpdater(repository), inventory, payment,
                CircuitBreakerRegistry.ofDefaults());
    }

    private Order saved;

    /** Simulates persistence: save assigns id 1 and remembers the instance, findById returns it. */
    private void track() {
        when(repository.save(any(Order.class))).thenAnswer(i -> {
            Order o = i.getArgument(0);
            if (o.getId() == null) {
                ReflectionTestUtils.setField(o, "id", 1L);
            }
            saved = o;
            return o;
        });
        when(repository.findById(1L)).thenAnswer(i -> Optional.of(saved));
    }

    private static Request dummyRequest() {
        return Request.create(Request.HttpMethod.POST, "/x", Collections.emptyMap(), null, new RequestTemplate());
    }

    private static FeignException conflict() {
        return new FeignException.Conflict("conflict", dummyRequest(), null, Collections.emptyMap());
    }

    private static FeignException notFound() {
        return new FeignException.NotFound("not found", dummyRequest(), null, Collections.emptyMap());
    }

    private static RetryableException timeout() {
        return new RetryableException(-1, "timeout", Request.HttpMethod.POST, new IOException("timeout"), (Long) null,
                dummyRequest());
    }

    @Test
    void confirmsOrderWithPaymentIdAndTotal() {
        track();
        when(inventory.reserve(STOCK)).thenReturn(new ReservationResponse(1L, 2, UNIT_PRICE));
        when(payment.charge(new ChargeRequest(1L, TOTAL))).thenReturn(new ChargeResponse(5L, 1L, TOTAL, "SUCCESS"));

        OrderResponse response = service.create(REQUEST);

        assertThat(response.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(response.paymentId()).isEqualTo(5L);
        assertThat(response.unitPrice()).isEqualByComparingTo(UNIT_PRICE);
        assertThat(response.totalAmount()).isEqualByComparingTo(TOTAL);
        assertThat(response.customerId()).isEqualTo(7L);
        verify(inventory, never()).release(any());
    }

    @Test
    void rejectsWhenOutOfStock() {
        track();
        when(inventory.reserve(STOCK)).thenThrow(conflict());

        OrderResponse response = service.create(REQUEST);

        assertThat(response.status()).isEqualTo(OrderStatus.REJECTED);
        assertThat(response.failureReason()).isEqualTo("Insufficient stock");
        verifyNoInteractions(payment);
    }

    @Test
    void rejectsWhenProductUnknown() {
        track();
        when(inventory.reserve(STOCK)).thenThrow(notFound());

        OrderResponse response = service.create(REQUEST);

        assertThat(response.status()).isEqualTo(OrderStatus.REJECTED);
        assertThat(response.failureReason()).isEqualTo("Unknown product");
        verifyNoInteractions(payment);
    }

    @Test
    void releasesReservationWhenPaymentFails() {
        track();
        when(inventory.reserve(STOCK)).thenReturn(new ReservationResponse(1L, 2, UNIT_PRICE));
        when(payment.charge(any())).thenReturn(new ChargeResponse(6L, 1L, TOTAL, "FAILED"));

        OrderResponse response = service.create(REQUEST);

        assertThat(response.status()).isEqualTo(OrderStatus.PAYMENT_FAILED);
        assertThat(response.paymentId()).isNull();
        assertThat(response.totalAmount()).isEqualByComparingTo(TOTAL);
        verify(inventory).release(STOCK);
    }

    @Test
    void inventoryDownMarksFailedAndSkipsPayment() {
        track();
        when(inventory.reserve(STOCK)).thenThrow(timeout());

        assertThatThrownBy(() -> service.create(REQUEST)).isInstanceOf(ServiceUnavailableException.class);

        assertThat(saved.getStatus()).isEqualTo(OrderStatus.FAILED);
        verifyNoInteractions(payment);
        verify(inventory, never()).release(any());
    }

    @Test
    void paymentDownReleasesReservationAndMarksFailed() {
        track();
        when(inventory.reserve(STOCK)).thenReturn(new ReservationResponse(1L, 2, UNIT_PRICE));
        when(payment.charge(any())).thenThrow(timeout());

        assertThatThrownBy(() -> service.create(REQUEST)).isInstanceOf(ServiceUnavailableException.class);

        assertThat(saved.getStatus()).isEqualTo(OrderStatus.FAILED);
        verify(inventory).release(STOCK);
    }

    @Test
    void failedReleaseDoesNotMaskPaymentFailure() {
        track();
        when(inventory.reserve(STOCK)).thenReturn(new ReservationResponse(1L, 2, UNIT_PRICE));
        when(payment.charge(any())).thenReturn(new ChargeResponse(6L, 1L, TOTAL, "FAILED"));
        org.mockito.Mockito.doThrow(timeout()).when(inventory).release(STOCK);

        assertThat(service.create(REQUEST).status()).isEqualTo(OrderStatus.PAYMENT_FAILED);
    }

    @Test
    void getMissingThrows() {
        when(repository.findById(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(1L)).isInstanceOf(NotFoundException.class);
    }
}
