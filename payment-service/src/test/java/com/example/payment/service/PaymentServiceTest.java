package com.example.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.payment.common.NotFoundException;
import com.example.payment.domain.Payment;
import com.example.payment.dto.ChargeRequest;
import com.example.payment.dto.PaymentRequest;
import com.example.payment.repository.PaymentRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock PaymentRepository repository;
    @InjectMocks PaymentService service;

    @Test
    void createMapsAllFields() {
        when(repository.save(any(Payment.class))).thenAnswer(i -> i.getArgument(0));
        var request = new PaymentRequest(1L, new BigDecimal("10.50"), "sample");
        var response = service.create(request);
        assertThat(response.orderId()).isEqualTo(request.orderId());
        assertThat(response.amount()).isEqualTo(request.amount());
        assertThat(response.status()).isEqualTo(request.status());
    }

    private void stubNoExisting() {
        when(repository.findFirstByOrderIdAndStatus(1L, "SUCCESS")).thenReturn(Optional.empty());
        when(repository.save(any(Payment.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void chargeWithinLimitSucceeds() {
        stubNoExisting();
        var response = service.charge(new ChargeRequest(1L, new BigDecimal("21.00")));
        assertThat(response.status()).isEqualTo("SUCCESS");
        assertThat(response.orderId()).isEqualTo(1L);
    }

    @Test
    void chargeOverLimitFails() {
        stubNoExisting();
        var response = service.charge(new ChargeRequest(1L, new BigDecimal("10000.01")));
        assertThat(response.status()).isEqualTo("FAILED");
    }

    @Test
    void chargeExactlyAtLimitSucceeds() {
        stubNoExisting();
        var response = service.charge(new ChargeRequest(1L, new BigDecimal("10000.00")));
        assertThat(response.status()).isEqualTo("SUCCESS");
    }

    @Test
    void chargeIsIdempotentForSuccessfulPayment() {
        Payment existing = new Payment();
        existing.setOrderId(1L);
        existing.setAmount(new BigDecimal("21.00"));
        existing.setStatus("SUCCESS");
        when(repository.findFirstByOrderIdAndStatus(1L, "SUCCESS")).thenReturn(Optional.of(existing));
        var response = service.charge(new ChargeRequest(1L, new BigDecimal("21.00")));
        assertThat(response.status()).isEqualTo("SUCCESS");
        assertThat(response.amount()).isEqualByComparingTo("21.00");
        verify(repository, never()).save(any(Payment.class));
    }

    @Test
    void getMissingThrows() {
        when(repository.findById(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(1L)).isInstanceOf(NotFoundException.class);
    }
}
