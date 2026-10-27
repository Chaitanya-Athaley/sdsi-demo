package com.example.flashsale.domain.exception;

public class ReservationExpiredException extends BusinessException {
    public ReservationExpiredException(String reservationId) {
        super("RESERVATION_EXPIRED", "Reservation " + reservationId + " has expired");
    }
}