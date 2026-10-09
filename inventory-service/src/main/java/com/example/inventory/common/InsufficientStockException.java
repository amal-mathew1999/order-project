package com.example.inventory.common;

public class InsufficientStockException extends RuntimeException {
    public InsufficientStockException(Long productId, int requested) {
        super("Insufficient stock for product " + productId + " (requested " + requested + ")");
    }
}
