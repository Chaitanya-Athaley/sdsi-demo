package com.example.flashsale.domain.exception;

public class InvalidReservationStateException extends BusinessException {
    public InvalidReservationStateException(String message) {
        super("INVALID_RESERVATION_STATE", message);
    }
}