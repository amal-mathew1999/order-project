package com.example.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import feign.Request;
import feign.RequestTemplate;
import feign.RetryableException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
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
    private static final ReserveRequest RESERVE = new ReserveRequest(1L, 1L, 2);
    private static final ReleaseRequest RELEASE = new ReleaseRequest(1L);
    private static final BigDecimal UNIT_PRICE = new BigDecimal("10.50");
    private static final BigDecimal TOTAL = new BigDecimal("21.00");

    @Mock OrderRepository repository;
    @Mock InventoryClient inventory;
    @Mock PaymentClient payment;
    OrderService service;

    @BeforeEach
    void setUp() {
        // Mirrors application.yml: 4xx (FeignClientException) never counts as a breaker failure.
        service = new OrderService(repository, new OrderStatusUpdater(repository), inventory, payment,
                CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                        .ignoreExceptions(FeignException.FeignClientException.class).build()));
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

    private static FeignException badRequest() {
        return new FeignException.BadRequest("bad", dummyRequest(), null, Collections.emptyMap());
    }

    private static RetryableException timeout() {
        return new RetryableException(-1, "timeout", Request.HttpMethod.POST, new IOException("timeout"), (Long) null,
                dummyRequest());
    }

    private void reserved() {
        when(inventory.reserve(RESERVE)).thenReturn(new ReservationResponse(1L, 1L, 2, UNIT_PRICE));
    }

    private static ChargeResponse success() {
        return new ChargeResponse(5L, 1L, TOTAL, "SUCCESS");
    }

    private static Order unknownOrder() {
        Order o = new Order();
        ReflectionTestUtils.setField(o, "id", 1L);
        o.setUnitPrice(UNIT_PRICE);
        o.setTotalAmount(TOTAL);
        o.setStatus(OrderStatus.PAYMENT_UNKNOWN);
        return o;
    }

    @Test
    void confirmsOrderWithPaymentIdAndTotal() {
        track();
        reserved();
        when(payment.charge(new ChargeRequest(1L, TOTAL))).thenReturn(success());

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
        when(inventory.reserve(RESERVE)).thenThrow(conflict());

        OrderResponse response = service.create(REQUEST);

        assertThat(response.status()).isEqualTo(OrderStatus.REJECTED);
        assertThat(response.failureReason()).isEqualTo("Insufficient stock");
        verifyNoInteractions(payment);
    }

    @Test
    void rejectsWhenProductUnknown() {
        track();
        when(inventory.reserve(RESERVE)).thenThrow(notFound());

        OrderResponse response = service.create(REQUEST);

        assertThat(response.status()).isEqualTo(OrderStatus.REJECTED);
        assertThat(response.failureReason()).isEqualTo("Unknown product");
        verifyNoInteractions(payment);
    }

    @Test
    void releasesReservationWhenPaymentFails() {
        track();
        reserved();
        when(payment.charge(any())).thenReturn(new ChargeResponse(6L, 1L, TOTAL, "FAILED"));

        OrderResponse response = service.create(REQUEST);

        assertThat(response.status()).isEqualTo(OrderStatus.PAYMENT_FAILED);
        assertThat(response.paymentId()).isNull();
        assertThat(response.totalAmount()).isEqualByComparingTo(TOTAL);
        verify(inventory).release(RELEASE);
    }

    @Test
    void reserveTimeoutReleasesAndFails() {
        track();
        when(inventory.reserve(RESERVE)).thenThrow(timeout());

        assertThatThrownBy(() -> service.create(REQUEST)).isInstanceOf(ServiceUnavailableException.class);

        assertThat(saved.getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(saved.isReleasePending()).isFalse();
        verifyNoInteractions(payment);
        verify(inventory).release(RELEASE);
    }

    @Test
    void chargeOrderIdMismatchIsTreatedAsDeclined() {
        track();
        reserved();
        when(payment.charge(any())).thenReturn(new ChargeResponse(5L, 99L, TOTAL, "SUCCESS"));

        assertThat(service.create(REQUEST).status()).isEqualTo(OrderStatus.PAYMENT_FAILED);
        verify(inventory).release(RELEASE);
    }

    @Test
    void chargeAmountMismatchIsTreatedAsDeclined() {
        track();
        reserved();
        when(payment.charge(any())).thenReturn(new ChargeResponse(5L, 1L, new BigDecimal("20.00"), "SUCCESS"));

        assertThat(service.create(REQUEST).status()).isEqualTo(OrderStatus.PAYMENT_FAILED);
        verify(inventory).release(RELEASE);
    }

    @Test
    void chargeAmountComparesByValueNotScale() {
        track();
        reserved();
        when(payment.charge(any())).thenReturn(new ChargeResponse(5L, 1L, new BigDecimal("21"), "SUCCESS"));

        assertThat(service.create(REQUEST).status()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void payment4xxReleasesAndReturnsBadGateway() {
        track();
        reserved();
        when(payment.charge(any())).thenThrow(badRequest());

        assertThatThrownBy(() -> service.create(REQUEST)).isInstanceOf(BadGatewayException.class);

        assertThat(saved.getStatus()).isEqualTo(OrderStatus.FAILED);
        verify(inventory).release(RELEASE);
        verify(payment, never()).findByOrderId(any());
    }

    @Test
    void paymentTimeoutWithSuccessfulLookupConfirms() {
        track();
        reserved();
        when(payment.charge(any())).thenThrow(timeout());
        when(payment.findByOrderId(1L)).thenReturn(List.of(success()));

        OrderResponse response = service.create(REQUEST);

        assertThat(response.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(response.paymentId()).isEqualTo(5L);
        verify(inventory, never()).release(any());
    }

    @Test
    void paymentTimeoutWithEmptyLookupReleasesAndFails() {
        track();
        reserved();
        when(payment.charge(any())).thenThrow(timeout());
        when(payment.findByOrderId(1L)).thenReturn(List.of());

        assertThatThrownBy(() -> service.create(REQUEST)).isInstanceOf(ServiceUnavailableException.class);

        assertThat(saved.getStatus()).isEqualTo(OrderStatus.FAILED);
        verify(inventory).release(RELEASE);
    }

    @Test
    void paymentTimeoutWithFailedLookupKeepsReservationAndMarksUnknown() {
        track();
        reserved();
        when(payment.charge(any())).thenThrow(timeout());
        when(payment.findByOrderId(1L)).thenThrow(timeout());

        assertThatThrownBy(() -> service.create(REQUEST)).isInstanceOf(ServiceUnavailableException.class);

        assertThat(saved.getStatus()).isEqualTo(OrderStatus.PAYMENT_UNKNOWN);
        assertThat(saved.getTotalAmount()).isEqualByComparingTo(TOTAL);
        verify(inventory, never()).release(any());
    }

    @Test
    void failedReleaseSetsReleasePendingAndDoesNotMaskOutcome() {
        track();
        reserved();
        when(payment.charge(any())).thenReturn(new ChargeResponse(6L, 1L, TOTAL, "FAILED"));
        doThrow(timeout()).when(inventory).release(RELEASE);

        assertThat(service.create(REQUEST).status()).isEqualTo(OrderStatus.PAYMENT_FAILED);
        assertThat(saved.isReleasePending()).isTrue();
    }

    @Test
    void schedulerRetriesReleaseAndClearsFlag() {
        track();
        Order o = unknownOrder();
        o.setStatus(OrderStatus.FAILED);
        o.setReleasePending(true);
        saved = o;
        when(repository.findByReleasePendingTrue()).thenReturn(List.of(o));

        service.retryPendingReleases();

        verify(inventory).release(RELEASE);
        assertThat(o.isReleasePending()).isFalse();
    }

    @Test
    void schedulerKeepsFlagWhileInventoryStillDown() {
        Order o = unknownOrder();
        o.setReleasePending(true);
        when(repository.findByReleasePendingTrue()).thenReturn(List.of(o));
        doThrow(timeout()).when(inventory).release(RELEASE);

        service.retryPendingReleases();

        assertThat(o.isReleasePending()).isTrue();
    }

    @Test
    void reconcilerConfirmsUnknownOrderWhenPaymentSucceeded() {
        track();
        Order o = unknownOrder();
        saved = o;
        when(repository.findByStatus(OrderStatus.PAYMENT_UNKNOWN)).thenReturn(List.of(o));
        when(payment.findByOrderId(1L)).thenReturn(List.of(success()));

        service.reconcileUnknownPayments();

        assertThat(o.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(o.getPaymentId()).isEqualTo(5L);
        verify(inventory, never()).release(any());
    }

    @Test
    void reconcilerReleasesAndFailsWhenNoPaymentFound() {
        track();
        Order o = unknownOrder();
        saved = o;
        when(repository.findByStatus(OrderStatus.PAYMENT_UNKNOWN)).thenReturn(List.of(o));
        when(payment.findByOrderId(1L)).thenReturn(List.of());

        service.reconcileUnknownPayments();

        assertThat(o.getStatus()).isEqualTo(OrderStatus.FAILED);
        verify(inventory).release(RELEASE);
    }

    @Test
    void reconcilerLeavesOrderUnknownWhenLookupFails() {
        Order o = unknownOrder();
        when(repository.findByStatus(OrderStatus.PAYMENT_UNKNOWN)).thenReturn(List.of(o));
        when(payment.findByOrderId(1L)).thenThrow(timeout());

        service.reconcileUnknownPayments();

        assertThat(o.getStatus()).isEqualTo(OrderStatus.PAYMENT_UNKNOWN);
        verify(inventory, never()).release(any());
    }

    @Test
    void getMissingThrows() {
        when(repository.findById(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(1L)).isInstanceOf(NotFoundException.class);
    }
}
