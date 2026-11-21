package com.hackathon.appointments.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateProviderRequest(
        @Schema(example = "Tan & Partners LLP") @NotBlank @Size(max = 120) String name,
        @Schema(example = "LEGAL", description = "Letters, digits and underscores; stored upper-case")
        @NotBlank @Size(max = 60) @Pattern(regexp = "[A-Za-z0-9_]+", message = "must contain only letters, digits or underscores")
        String serviceCategory) {
}
