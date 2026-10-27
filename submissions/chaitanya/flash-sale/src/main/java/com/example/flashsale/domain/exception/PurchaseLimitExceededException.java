package com.example.flashsale.domain.exception;

public class PurchaseLimitExceededException extends BusinessException {
    public PurchaseLimitExceededException(String userId, String sku, int limit, int current, int requested) {
        super("PURCHASE_LIMIT_EXCEEDED",
                "Purchase limit exceeded for user " + userId + " on SKU " + sku
                        + ": limit " + limit + ", already reserved/confirmed " + current
                        + ", requested " + requested);
    }
}