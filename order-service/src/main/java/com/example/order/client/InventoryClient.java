package com.example.order.client;

import com.example.order.dto.ReservationResponse;
import com.example.order.dto.StockRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "inventory-service")
public interface InventoryClient {

    @PostMapping("/api/v1/inventory/reservations")
    ReservationResponse reserve(@RequestBody StockRequest request);

    @PostMapping("/api/v1/inventory/releases")
    void release(@RequestBody StockRequest request);
}
