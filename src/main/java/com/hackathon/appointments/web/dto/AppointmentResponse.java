package com.hackathon.appointments.web.dto;

import com.hackathon.appointments.domain.Appointment;
import com.hackathon.appointments.domain.AppointmentStatus;

import java.time.LocalDateTime;

public record AppointmentResponse(
        Long id,
        Long customerId,
        AppointmentStatus status,
        SlotResponse slot,
        String notes,
        LocalDateTime bookedAt,
        LocalDateTime cancelledAt,
        String cancellationReason) {

    public static AppointmentResponse from(Appointment a) {
        return new AppointmentResponse(a.getId(), a.getCustomer().getId(), a.getStatus(), SlotResponse.from(a.getSlot()),
                a.getNotes(), a.getBookedAt(), a.getCancelledAt(), a.getCancellationReason());
    }
}
