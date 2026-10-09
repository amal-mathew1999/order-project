package com.example.order.client;

import com.example.order.dto.ChargeRequest;
import com.example.order.dto.ChargeResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "payment-service")
public interface PaymentClient {

    @PostMapping("/api/v1/payments/charges")
    ChargeResponse charge(@RequestBody ChargeRequest request);
}
