package com.example.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.example.inventory.common.InsufficientStockException;
import com.example.inventory.common.NotFoundException;
import com.example.inventory.domain.InventoryItem;
import com.example.inventory.dto.AvailabilityResponse;
import com.example.inventory.dto.ReservationResponse;
import com.example.inventory.repository.InventoryItemRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class StockServiceTest {

    private final InventoryItemRepository repo = mock(InventoryItemRepository.class);
    private final StockService service = new StockService(repo);

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
        ReservationResponse r = service.reserve(1L, 2);
        assertThat(r.quantity()).isEqualTo(2);
        assertThat(r.unitPrice()).isEqualByComparingTo("10.50");
    }

    @Test
    void reserveInsufficientThrowsConflict() {
        when(repo.findByProductId(1L)).thenReturn(Optional.of(item(1)));
        when(repo.reserve(1L, 2)).thenReturn(0);
        assertThatThrownBy(() -> service.reserve(1L, 2)).isInstanceOf(InsufficientStockException.class);
    }

    @Test
    void reserveUnknownProductThrowsNotFoundWithoutUpdate() {
        when(repo.findByProductId(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.reserve(9L, 1)).isInstanceOf(NotFoundException.class);
        verify(repo, never()).reserve(anyLong(), anyInt());
    }

    @Test
    void releaseRestoresStock() {
        when(repo.release(1L, 2)).thenReturn(1);
        service.release(1L, 2);
        verify(repo).release(1L, 2);
    }

    @Test
    void releaseUnknownProductThrowsNotFound() {
        when(repo.release(9L, 2)).thenReturn(0);
        assertThatThrownBy(() -> service.release(9L, 2)).isInstanceOf(NotFoundException.class);
    }
}
