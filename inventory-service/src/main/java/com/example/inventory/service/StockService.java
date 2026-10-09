package com.example.inventory.service;

import com.example.inventory.common.InsufficientStockException;
import com.example.inventory.common.NotFoundException;
import com.example.inventory.domain.InventoryItem;
import com.example.inventory.dto.AvailabilityResponse;
import com.example.inventory.dto.ReservationResponse;
import com.example.inventory.repository.InventoryItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class StockService {

    private final InventoryItemRepository repository;

    public StockService(InventoryItemRepository repository) {
        this.repository = repository;
    }

    public AvailabilityResponse checkAvailability(Long productId, int quantity) {
        InventoryItem item = find(productId);
        return new AvailabilityResponse(productId, quantity, item.getAvailableQuantity() >= quantity, item.getUnitPrice());
    }

    @Transactional
    public ReservationResponse reserve(Long productId, int quantity) {
        InventoryItem item = find(productId);
        if (repository.reserve(productId, quantity) == 0) {
            throw new InsufficientStockException(productId, quantity);
        }
        return new ReservationResponse(productId, quantity, item.getUnitPrice());
    }

    @Transactional
    public void release(Long productId, int quantity) {
        if (repository.release(productId, quantity) == 0) {
            throw new NotFoundException("Product", productId);
        }
    }

    private InventoryItem find(Long productId) {
        return repository.findByProductId(productId).orElseThrow(() -> new NotFoundException("Product", productId));
    }
}
