package com.example.order.client;

import com.example.order.dto.ReleaseRequest;
import com.example.order.dto.ReservationResponse;
import com.example.order.dto.ReserveRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "inventory-service")
public interface InventoryClient {

    @PostMapping("/api/v1/inventory/reservations")
    ReservationResponse reserve(@RequestBody ReserveRequest request);

    @PostMapping("/api/v1/inventory/releases")
    void release(@RequestBody ReleaseRequest request);
}
