package com.example.flashsale.domain.exception;

public class ReservationNotFoundException extends BusinessException {
    public ReservationNotFoundException(String reservationId) {
        super("RESERVATION_NOT_FOUND", "Reservation not found: " + reservationId);
    }
}