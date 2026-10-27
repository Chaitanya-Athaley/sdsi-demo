package com.example.flashsale.domain.exception;

public class ProductNotFoundException extends BusinessException {
    public ProductNotFoundException(String sku) {
        super("PRODUCT_NOT_FOUND", "Product not found: " + sku);
    }
}