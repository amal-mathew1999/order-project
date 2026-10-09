package com.example.inventory.web;

import com.example.inventory.dto.AvailabilityResponse;
import com.example.inventory.dto.ReleaseRequest;
import com.example.inventory.dto.ReservationResponse;
import com.example.inventory.dto.StockRequest;
import com.example.inventory.service.StockService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/inventory")
public class StockController {

    private final StockService service;

    public StockController(StockService service) {
        this.service = service;
    }

    @GetMapping("/{productId}/availability")
    public AvailabilityResponse availability(@PathVariable Long productId, @RequestParam @Min(1) int quantity) {
        return service.checkAvailability(productId, quantity);
    }

    @PostMapping("/reservations")
    public ReservationResponse reserve(@Valid @RequestBody StockRequest request) {
        return service.reserve(request.orderId(), request.productId(), request.quantity());
    }

    @PostMapping("/releases")
    public ResponseEntity<Void> release(@Valid @RequestBody ReleaseRequest request) {
        service.release(request.orderId());
        return ResponseEntity.noContent().build();
    }
}
