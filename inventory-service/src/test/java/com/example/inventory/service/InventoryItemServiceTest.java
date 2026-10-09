package com.example.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.example.inventory.common.NotFoundException;
import com.example.inventory.domain.InventoryItem;
import com.example.inventory.dto.InventoryItemRequest;
import com.example.inventory.repository.InventoryItemRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InventoryItemServiceTest {

    @Mock InventoryItemRepository repository;
    @InjectMocks InventoryItemService service;

    @Test
    void createMapsAllFields() {
        when(repository.save(any(InventoryItem.class))).thenAnswer(i -> i.getArgument(0));
        var request = new InventoryItemRequest(1L, "sample", 1, new BigDecimal("10.50"));
        var response = service.create(request);
        assertThat(response.productId()).isEqualTo(request.productId());
        assertThat(response.productName()).isEqualTo(request.productName());
        assertThat(response.availableQuantity()).isEqualTo(request.availableQuantity());
        assertThat(response.unitPrice()).isEqualTo(request.unitPrice());
    }

    @Test
    void getMissingThrows() {
        when(repository.findById(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(1L)).isInstanceOf(NotFoundException.class);
    }
}
