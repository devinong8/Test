package com.hackathon.appointments.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.List;

/** The single, standard error envelope returned by every endpoint for every failure. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@Schema(description = "Standard error response")
public record ApiError(
        @Schema(example = "2026-11-21T10:15:30+08:00") OffsetDateTime timestamp,
        @Schema(example = "409") int status,
        @Schema(example = "Conflict") String error,
        @Schema(example = "SLOT_ALREADY_BOOKED") String code,
        @Schema(example = "Slot 42 has already been booked by another customer") String message,
        @Schema(example = "/api/v1/appointments") String path,
        List<FieldViolation> fieldErrors) {

    public record FieldViolation(String field, Object rejectedValue, String message) {
    }
}
