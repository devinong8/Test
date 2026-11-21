package com.hackathon.appointments.web.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * A booking can identify the slot in one of two ways:
 * <ol>
 *   <li>by {@code slotId}, or</li>
 *   <li>by time range: {@code providerId + date + startTime + endTime}.</li>
 * </ol>
 * Exactly one form must be supplied.
 */
@Schema(description = "Supply either slotId, or providerId+date+startTime+endTime")
public record BookAppointmentRequest(
        @Schema(example = "1") @NotNull @Positive Long customerId,
        @Schema(example = "1") @Positive Long slotId,
        @Schema(example = "1") @Positive Long providerId,
        @Schema(example = "2026-12-01") LocalDate date,
        @Schema(type = "string", example = "09:00") @JsonFormat(pattern = "HH:mm") LocalTime startTime,
        @Schema(type = "string", example = "10:00") @JsonFormat(pattern = "HH:mm") LocalTime endTime,
        @Schema(example = "First consultation about property purchase") @Size(max = 500) String notes) {

    public boolean bySlotId() {
        return slotId != null;
    }

    public boolean byTimeRange() {
        return providerId != null || date != null || startTime != null || endTime != null;
    }

    @Schema(hidden = true)
    @AssertTrue(message = "provide either slotId, or all of providerId, date, startTime and endTime (not both)")
    public boolean isSlotReferenceValid() {
        if (bySlotId()) {
            return !byTimeRange();
        }
        return providerId != null && date != null && startTime != null && endTime != null;
    }
}
