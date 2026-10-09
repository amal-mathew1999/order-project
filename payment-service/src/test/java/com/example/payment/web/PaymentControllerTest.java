package com.example.payment.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.payment.common.NotFoundException;
import com.example.payment.dto.ChargeRequest;
import com.example.payment.dto.PaymentRequest;
import com.example.payment.dto.PaymentResponse;
import com.example.payment.service.PaymentService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PaymentController.class)
class PaymentControllerTest {

    private final MockMvc mvc;

    @MockitoBean PaymentService service;

    PaymentControllerTest(@Autowired MockMvc mvc) {
        this.mvc = mvc;
    }

    @Test
    void createReturns201() throws Exception {
        when(service.create(any(PaymentRequest.class))).thenReturn(new PaymentResponse(1L, 1L, new BigDecimal("10.50"), "sample"));
        mvc.perform(post("/api/v1/payments").contentType(MediaType.APPLICATION_JSON).content("{\"orderId\":1,\"amount\":10.50,\"status\":\"sample\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/payments/1"))
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void createWithEmptyBodyReturns400() throws Exception {
        mvc.perform(post("/api/v1/payments").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors").exists());
    }

    @Test
    void chargeReturns201WithBody() throws Exception {
        when(service.charge(any(ChargeRequest.class))).thenReturn(new PaymentResponse(5L, 1L, new BigDecimal("21.00"), "SUCCESS"));
        mvc.perform(post("/api/v1/payments/charges").contentType(MediaType.APPLICATION_JSON).content("{\"orderId\":1,\"amount\":21.00}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.orderId").value(1))
                .andExpect(jsonPath("$.amount").value(21.00))
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    void chargeWithZeroAmountReturns400() throws Exception {
        mvc.perform(post("/api/v1/payments/charges").contentType(MediaType.APPLICATION_JSON).content("{\"orderId\":1,\"amount\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void chargeWithoutOrderIdReturns400() throws Exception {
        mvc.perform(post("/api/v1/payments/charges").contentType(MediaType.APPLICATION_JSON).content("{\"amount\":21.00}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void getMissingReturns404() throws Exception {
        when(service.get(99L)).thenThrow(new NotFoundException("Payment", 99L));
        mvc.perform(get("/api/v1/payments/99"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void badRequestsReturnProblemDetail() throws Exception {
        mvc.perform(get("/api/v1/payments/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(get("/api/v1/payments").param("size", "1000"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(post("/api/v1/payments").contentType(MediaType.APPLICATION_JSON).content("{bad"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }
}
