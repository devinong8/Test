package com.hackathon.appointments.web.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.LocalTime;

public record CreateSlotRequest(
        @Schema(example = "2026-12-01", description = "Slot date (yyyy-MM-dd)") @NotNull LocalDate date,
        @Schema(type = "string", example = "09:00", description = "Start time (HH:mm)")
        @NotNull @JsonFormat(pattern = "HH:mm") LocalTime startTime,
        @Schema(type = "string", example = "10:00", description = "End time (HH:mm), must be after startTime")
        @NotNull @JsonFormat(pattern = "HH:mm") LocalTime endTime) {
}
