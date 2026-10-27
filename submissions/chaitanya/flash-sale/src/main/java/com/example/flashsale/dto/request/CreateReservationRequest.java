package com.example.flashsale.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public class CreateReservationRequest {

    @NotBlank(message = "userId must not be blank")
    private String userId;

    @NotBlank(message = "sku must not be blank")
    private String sku;

    @Positive(message = "quantity must be positive")
    private int quantity;

    public CreateReservationRequest() {
    }

    public CreateReservationRequest(String userId, String sku, int quantity) {
        this.userId = userId;
        this.sku = sku;
        this.quantity = quantity;
    }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
}