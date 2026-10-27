package com.example.flashsale.dto.response;

import com.example.flashsale.domain.entity.Reservation;

import java.time.Instant;

public class ReservationResponse {

    private String id;
    private String userId;
    private String sku;
    private int quantity;
    private String status;
    private Instant createdAt;
    private Instant expiresAt;
    private Instant confirmedAt;
    private Instant cancelledAt;

    public ReservationResponse() {
    }

    public static ReservationResponse from(Reservation r) {
        ReservationResponse resp = new ReservationResponse();
        resp.id = r.getId();
        resp.userId = r.getUserId();
        resp.sku = r.getSku();
        resp.quantity = r.getQuantity();
        resp.status = r.getStatus().name();
        resp.createdAt = r.getCreatedAt();
        resp.expiresAt = r.getExpiresAt();
        resp.confirmedAt = r.getConfirmedAt();
        resp.cancelledAt = r.getCancelledAt();
        return resp;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(Instant confirmedAt) { this.confirmedAt = confirmedAt; }
    public Instant getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(Instant cancelledAt) { this.cancelledAt = cancelledAt; }
}