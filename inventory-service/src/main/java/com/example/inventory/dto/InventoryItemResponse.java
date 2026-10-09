package com.example.inventory.dto;

import com.example.inventory.domain.InventoryItem;
import java.math.BigDecimal;

public record InventoryItemResponse(Long id, Long productId, String productName, Integer availableQuantity, BigDecimal unitPrice) {
    public static InventoryItemResponse from(InventoryItem e) {
        return new InventoryItemResponse(e.getId(), e.getProductId(), e.getProductName(), e.getAvailableQuantity(), e.getUnitPrice());
    }
}
