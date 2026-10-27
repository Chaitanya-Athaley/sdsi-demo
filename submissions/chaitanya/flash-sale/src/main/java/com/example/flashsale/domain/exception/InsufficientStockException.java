package com.example.flashsale.domain.exception;

public class InsufficientStockException extends BusinessException {
    public InsufficientStockException(String sku, int requested, int available) {
        super("INSUFFICIENT_STOCK",
                "Insufficient stock for SKU " + sku + ": requested " + requested + ", available " + available);
    }
}