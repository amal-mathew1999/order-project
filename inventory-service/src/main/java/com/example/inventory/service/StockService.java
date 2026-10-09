package com.example.inventory.service;

import com.example.inventory.common.InsufficientStockException;
import com.example.inventory.common.NotFoundException;
import com.example.inventory.domain.InventoryItem;
import com.example.inventory.domain.Reservation;
import com.example.inventory.dto.AvailabilityResponse;
import com.example.inventory.dto.ReservationResponse;
import com.example.inventory.repository.InventoryItemRepository;
import com.example.inventory.repository.ReservationRepository;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

@Service
@Transactional(readOnly = true)
public class StockService {

    private final InventoryItemRepository repository;
    private final ReservationRepository reservations;
    private final TransactionOperations tx;

    public StockService(InventoryItemRepository repository, ReservationRepository reservations,
            TransactionOperations tx) {
        this.repository = repository;
        this.reservations = reservations;
        this.tx = tx;
    }

    public AvailabilityResponse checkAvailability(Long productId, int quantity) {
        InventoryItem item = find(productId);
        return new AvailabilityResponse(productId, quantity, item.getAvailableQuantity() >= quantity, item.getUnitPrice());
    }

    /**
     * Idempotent per order. The transaction is programmatic so that a duplicate-order constraint violation
     * rolls back the whole attempt (including the stock decrement) before the winner is re-read.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ReservationResponse reserve(Long orderId, Long productId, int quantity) {
        try {
            return tx.execute(status -> doReserve(orderId, productId, quantity));
        } catch (DataIntegrityViolationException e) {
            Optional<ReservationResponse> existing = tx.execute(status -> existing(orderId));
            if (existing != null && existing.isPresent()) {
                return existing.get();
            }
            throw e;
        }
    }

    private ReservationResponse doReserve(Long orderId, Long productId, int quantity) {
        Optional<ReservationResponse> existing = existing(orderId);
        if (existing.isPresent()) {
            return existing.get();
        }
        InventoryItem item = find(productId);
        reservations.saveAndFlush(new Reservation(orderId, productId, quantity));
        if (repository.reserve(productId, quantity) == 0) {
            throw new InsufficientStockException(productId, quantity);
        }
        return new ReservationResponse(orderId, productId, quantity, item.getUnitPrice());
    }

    private Optional<ReservationResponse> existing(Long orderId) {
        return reservations.findByOrderId(orderId).map(r -> new ReservationResponse(
                r.getOrderId(), r.getProductId(), r.getQuantity(), find(r.getProductId()).getUnitPrice()));
    }

    /** Idempotent: restores stock only if this call performs the RESERVED to RELEASED transition. */
    @Transactional
    public void release(Long orderId) {
        reservations.findByOrderId(orderId).ifPresent(r -> {
            if (reservations.markReleased(orderId) == 1) {
                repository.release(r.getProductId(), r.getQuantity());
            }
        });
    }

    private InventoryItem find(Long productId) {
        return repository.findByProductId(productId).orElseThrow(() -> new NotFoundException("Product", productId));
    }
}
