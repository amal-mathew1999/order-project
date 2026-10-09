package com.example.inventory.web;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.inventory.common.InsufficientStockException;
import com.example.inventory.common.NotFoundException;
import com.example.inventory.dto.AvailabilityResponse;
import com.example.inventory.dto.ReservationResponse;
import com.example.inventory.service.StockService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(StockController.class)
class StockControllerTest {

    private static final String RES = "/api/v1/inventory/reservations";
    private static final String REL = "/api/v1/inventory/releases";
    private static final String BODY = "{\"orderId\":7,\"productId\":1,\"quantity\":2}";
    private static final String ZERO = "{\"orderId\":7,\"productId\":1,\"quantity\":0}";

    private final MockMvc mvc;
    @MockitoBean StockService service;

    @Autowired
    StockControllerTest(MockMvc mvc) {
        this.mvc = mvc;
    }

    @Test
    void availabilityReturns200() throws Exception {
        when(service.checkAvailability(1L, 2)).thenReturn(new AvailabilityResponse(1L, 2, true, new BigDecimal("10.50")));
        mvc.perform(get("/api/v1/inventory/1/availability").param("quantity", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.requestedQuantity").value(2))
                .andExpect(jsonPath("$.unitPrice").value(10.50));
    }

    @Test
    void availabilityUnknownReturns404() throws Exception {
        when(service.checkAvailability(9L, 2)).thenThrow(new NotFoundException("Product", 9L));
        mvc.perform(get("/api/v1/inventory/9/availability").param("quantity", "2"))
                .andExpect(status().isNotFound());
    }

    @Test
    void availabilityNegativeQuantityReturns400() throws Exception {
        mvc.perform(get("/api/v1/inventory/1/availability").param("quantity", "-5"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void reserveReturns200() throws Exception {
        when(service.reserve(7L, 1L, 2)).thenReturn(new ReservationResponse(7L, 1L, 2, new BigDecimal("10.50")));
        mvc.perform(post(RES).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(7))
                .andExpect(jsonPath("$.productId").value(1))
                .andExpect(jsonPath("$.quantity").value(2))
                .andExpect(jsonPath("$.unitPrice").value(10.50));
    }

    @Test
    void reserveInsufficientReturns409ProblemJson() throws Exception {
        when(service.reserve(7L, 1L, 2)).thenThrow(new InsufficientStockException(1L, 2));
        mvc.perform(post(RES).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void reserveUnknownReturns404() throws Exception {
        when(service.reserve(7L, 1L, 2)).thenThrow(new NotFoundException("Product", 1L));
        mvc.perform(post(RES).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNotFound());
    }

    @Test
    void reserveQuantityZeroReturns400() throws Exception {
        mvc.perform(post(RES).contentType(MediaType.APPLICATION_JSON).content(ZERO))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reserveHugeQuantityReturns400() throws Exception {
        mvc.perform(post(RES).contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\":7,\"productId\":1,\"quantity\":2147483647}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reserveMissingOrderIdReturns400() throws Exception {
        mvc.perform(post(RES).contentType(MediaType.APPLICATION_JSON).content("{\"productId\":1,\"quantity\":2}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unexpectedErrorReturns500ProblemJsonWithoutLeak() throws Exception {
        when(service.reserve(7L, 1L, 2)).thenThrow(new IllegalStateException("secret internals"));
        mvc.perform(post(RES).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Unexpected error"));
    }

    @Test
    void releaseReturns204() throws Exception {
        mvc.perform(post(REL).contentType(MediaType.APPLICATION_JSON).content("{\"orderId\":7}"))
                .andExpect(status().isNoContent());
        verify(service).release(7L);
    }

    @Test
    void releaseMissingOrderIdReturns400() throws Exception {
        mvc.perform(post(REL).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }
}
