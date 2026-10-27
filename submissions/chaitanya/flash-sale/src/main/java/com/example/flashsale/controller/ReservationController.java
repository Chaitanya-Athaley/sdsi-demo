package com.example.flashsale.controller;

import com.example.flashsale.domain.entity.Reservation;
import com.example.flashsale.dto.request.CreateReservationRequest;
import com.example.flashsale.dto.response.ReservationResponse;
import com.example.flashsale.service.ReservationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/reservations")
public class ReservationController {

    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @PostMapping
    public ResponseEntity<ReservationResponse> createReservation(
            @Valid @RequestBody CreateReservationRequest request) {
        Reservation reservation = reservationService.createReservation(
                request.getUserId(), request.getSku(), request.getQuantity());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ReservationResponse.from(reservation));
    }

    @GetMapping("/{reservationId}")
    public ResponseEntity<ReservationResponse> getReservation(@PathVariable String reservationId) {
        Reservation reservation = reservationService.getReservation(reservationId);
        return ResponseEntity.ok(ReservationResponse.from(reservation));
    }

    @PostMapping("/{reservationId}/confirm")
    public ResponseEntity<ReservationResponse> confirmReservation(@PathVariable String reservationId) {
        Reservation reservation = reservationService.confirmReservation(reservationId);
        return ResponseEntity.ok(ReservationResponse.from(reservation));
    }

    @PostMapping("/{reservationId}/cancel")
    public ResponseEntity<ReservationResponse> cancelReservation(@PathVariable String reservationId) {
        Reservation reservation = reservationService.cancelReservation(reservationId);
        return ResponseEntity.ok(ReservationResponse.from(reservation));
    }
}