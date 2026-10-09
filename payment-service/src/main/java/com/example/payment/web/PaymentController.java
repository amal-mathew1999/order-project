package com.example.payment.web;

import com.example.payment.dto.ChargeRequest;
import com.example.payment.dto.PaymentResponse;
import com.example.payment.service.PaymentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentService service;

    public PaymentController(PaymentService service) {
        this.service = service;
    }

    @PostMapping("/charges")
    public ResponseEntity<PaymentResponse> charge(@Valid @RequestBody ChargeRequest request) {
        PaymentResponse charged = service.charge(request);
        return ResponseEntity.created(URI.create("/api/v1/payments/" + charged.id())).body(charged);
    }

    @GetMapping("/{id}")
    public PaymentResponse get(@PathVariable Long id) {
        return service.get(id);
    }

    @GetMapping
    public List<PaymentResponse> list(@RequestParam(required = false) Long orderId,
                                      @RequestParam(defaultValue = "0") @Min(0) int page,
                                      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(orderId, page, size);
    }
}
