package com.hackathon.appointments.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CancelAppointmentRequest(
        @Schema(example = "1", description = "Must be the customer who owns the appointment")
        @NotNull @Positive Long customerId,
        @Schema(example = "Schedule conflict") @NotBlank @Size(max = 500) String reason) {
}
