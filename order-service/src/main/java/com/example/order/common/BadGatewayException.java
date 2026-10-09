package com.example.order.common;

public class BadGatewayException extends RuntimeException {

    public BadGatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
