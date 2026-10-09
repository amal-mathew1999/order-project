package com.example.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.example.inventory.common.InsufficientStockException;
import com.example.inventory.common.NotFoundException;
import com.example.inventory.domain.InventoryItem;
import com.example.inventory.domain.Reservation;
import com.example.inventory.dto.AvailabilityResponse;
import com.example.inventory.dto.ReservationResponse;
import com.example.inventory.repository.InventoryItemRepository;
import com.example.inventory.repository.ReservationRepository;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionOperations;

class StockServiceTest {

    private final InventoryItemRepository repo = mock(InventoryItemRepository.class);
    private final ReservationRepository reservations = mock(ReservationRepository.class);
    private final StockService service =
            new StockService(repo, reservations, TransactionOperations.withoutTransaction());

    private InventoryItem item(int qty) {
        InventoryItem i = new InventoryItem();
        i.setProductId(1L);
        i.setProductName("p");
        i.setAvailableQuantity(qty);
        i.setUnitPrice(new BigDecimal("10.50"));
        return i;
    }

    @Test
    void availabilityTrueWhenEnoughStock() {
        when(repo.findByProductId(1L)).thenReturn(Optional.of(item(5)));
        AvailabilityResponse r = service.checkAvailability(1L, 2);
        assertThat(r.available()).isTrue();
        assertThat(r.requestedQuantity()).isEqualTo(2);
        assertThat(r.unitPrice()).isEqualByComparingTo("10.50");
    }

    @Test
    void availabilityFalseWhenNotEnoughStock() {
        when(repo.findByProductId(1L)).thenReturn(Optional.of(item(1)));
        assertThat(service.checkAvailability(1L, 2).available()).isFalse();
    }

    @Test
    void availabilityUnknownProductThrowsNotFound() {
        when(repo.findByProductId(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.checkAvailability(9L, 1)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void reserveSucceeds() {
        when(repo.findByProductId(1L)).thenReturn(Optional.of(item(5)));
        when(repo.reserve(1L, 2)).thenReturn(1);
        ReservationResponse r = service.reserve(7L, 1L, 2);
        assertThat(r.orderId()).isEqualTo(7L);
        assertThat(r.quantity()).isEqualTo(2);
        assertThat(r.unitPrice()).isEqualByComparingTo("10.50");
        verify(reservations).saveAndFlush(any(Reservation.class));
    }

    @Test
    void reserveRepeatForSameOrderDecrementsOnce() {
        Map<Long, Reservation> store = new HashMap<>();
        when(repo.findByProductId(1L)).thenReturn(Optional.of(item(5)));
        when(repo.reserve(1L, 2)).thenReturn(1);
        when(reservations.findByOrderId(anyLong())).thenAnswer(a -> Optional.ofNullable(store.get(a.<Long>getArgument(0))));
        when(reservations.saveAndFlush(any(Reservation.class))).thenAnswer(a -> {
            Reservation r = a.getArgument(0);
            store.put(r.getOrderId(), r);
            return r;
        });
        ReservationResponse first = service.reserve(7L, 1L, 2);
        ReservationResponse second = service.reserve(7L, 1L, 2);
        assertThat(second).isEqualTo(first);
        verify(repo, times(1)).reserve(1L, 2);
    }

    @Test
    void reserveConcurrentDuplicateReReadsExisting() {
        Reservation winner = new Reservation(7L, 1L, 2);
        when(repo.findByProductId(1L)).thenReturn(Optional.of(item(5)));
        when(reservations.findByOrderId(7L)).thenReturn(Optional.empty(), Optional.of(winner));
        when(reservations.saveAndFlush(any(Reservation.class))).thenThrow(new DataIntegrityViolationException("dup"));
        ReservationResponse r = service.reserve(7L, 1L, 2);
        assertThat(r.orderId()).isEqualTo(7L);
        verify(repo, never()).reserve(anyLong(), anyInt());
    }

    @Test
    void reserveInsufficientThrowsConflict() {
        when(repo.findByProductId(1L)).thenReturn(Optional.of(item(1)));
        when(repo.reserve(1L, 2)).thenReturn(0);
        assertThatThrownBy(() -> service.reserve(7L, 1L, 2)).isInstanceOf(InsufficientStockException.class);
    }

    @Test
    void reserveUnknownProductThrowsNotFoundWithoutUpdate() {
        when(repo.findByProductId(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.reserve(7L, 9L, 1)).isInstanceOf(NotFoundException.class);
        verify(repo, never()).reserve(anyLong(), anyInt());
    }

    @Test
    void releaseRestoresStockOnce() {
        when(reservations.findByOrderId(7L)).thenReturn(Optional.of(new Reservation(7L, 1L, 2)));
        when(reservations.markReleased(7L)).thenReturn(1, 0);
        service.release(7L);
        service.release(7L);
        verify(repo, times(1)).release(1L, 2);
    }

    @Test
    void releaseUnknownOrderIsNoOp() {
        when(reservations.findByOrderId(99L)).thenReturn(Optional.empty());
        service.release(99L);
        verify(repo, never()).release(anyLong(), anyInt());
        verify(reservations, never()).markReleased(anyLong());
    }
}
