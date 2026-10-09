package com.example.order.client;

import com.example.order.dto.ChargeRequest;
import com.example.order.dto.ChargeResponse;
import java.util.List;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "payment-service")
public interface PaymentClient {

    @PostMapping("/api/v1/payments/charges")
    ChargeResponse charge(@RequestBody ChargeRequest request);

    @GetMapping("/api/v1/payments")
    List<ChargeResponse> findByOrderId(@RequestParam("orderId") Long orderId);
}
