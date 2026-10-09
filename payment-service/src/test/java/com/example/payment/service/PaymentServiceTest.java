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
import com.example.payment.repository.PaymentRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock PaymentRepository repository;
    @Mock PaymentWriter writer;
    @InjectMocks PaymentService service;

    private static Payment payment(long id, String status, String amount) {
        Payment p = new Payment();
        p.setOrderId(1L);
        p.setAmount(new BigDecimal(amount));
        p.setStatus(status);
        return p;
    }

    @Test
    void chargeInsertsWhenNoSuccessExists() {
        when(repository.findFirstByOrderIdAndStatus(1L, "SUCCESS")).thenReturn(Optional.empty());
        when(writer.insert(any(ChargeRequest.class))).thenReturn(payment(1, "SUCCESS", "21.00"));
        var response = service.charge(new ChargeRequest(1L, new BigDecimal("21.00")));
        assertThat(response.status()).isEqualTo("SUCCESS");
        assertThat(response.orderId()).isEqualTo(1L);
    }

    @Test
    void chargeIsIdempotentForSuccessfulPayment() {
        when(repository.findFirstByOrderIdAndStatus(1L, "SUCCESS")).thenReturn(Optional.of(payment(1, "SUCCESS", "21.00")));
        var response = service.charge(new ChargeRequest(1L, new BigDecimal("99.00")));
        assertThat(response.amount()).isEqualByComparingTo("21.00");
        verify(writer, never()).insert(any(ChargeRequest.class));
    }

    @Test
    void chargeReturnsConcurrentWinnerOnConstraintViolation() {
        when(repository.findFirstByOrderIdAndStatus(1L, "SUCCESS"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(payment(7, "SUCCESS", "21.00")));
        when(writer.insert(any(ChargeRequest.class))).thenThrow(new DataIntegrityViolationException("dup"));
        var response = service.charge(new ChargeRequest(1L, new BigDecimal("21.00")));
        assertThat(response.status()).isEqualTo("SUCCESS");
        assertThat(response.amount()).isEqualByComparingTo("21.00");
    }

    @Test
    void chargeRethrowsViolationWhenNoSuccessExists() {
        when(repository.findFirstByOrderIdAndStatus(1L, "SUCCESS")).thenReturn(Optional.empty());
        when(writer.insert(any(ChargeRequest.class))).thenThrow(new DataIntegrityViolationException("other"));
        assertThatThrownBy(() -> service.charge(new ChargeRequest(1L, new BigDecimal("21.00"))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void writerSetsStatusAndSuccessKeyByLimit() {
        when(repository.saveAndFlush(any(Payment.class))).thenAnswer(i -> i.getArgument(0));
        var writer = new PaymentWriter(repository);
        Payment ok = writer.insert(new ChargeRequest(1L, new BigDecimal("10000.00")));
        assertThat(ok.getStatus()).isEqualTo("SUCCESS");
        assertThat(ok.getSuccessOrderId()).isEqualTo(1L);
        Payment failed = writer.insert(new ChargeRequest(1L, new BigDecimal("10000.01")));
        assertThat(failed.getStatus()).isEqualTo("FAILED");
        assertThat(failed.getSuccessOrderId()).isNull();
    }

    @Test
    void getMissingThrows() {
        when(repository.findById(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(1L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void listFiltersByOrderIdWhenGiven() {
        when(repository.findByOrderId(any(Long.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(payment(1, "SUCCESS", "5.00"))));
        assertThat(service.list(1L, 0, 20)).hasSize(1);
        verify(repository, never()).findAll(any(Pageable.class));
    }

    @Test
    void listWithoutOrderIdUsesPagedFindAll() {
        when(repository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        assertThat(service.list(null, 0, 20)).isEmpty();
        verify(repository, never()).findByOrderId(any(Long.class), any(Pageable.class));
    }
}
