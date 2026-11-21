package com.hackathon.appointments.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateCustomerRequest(
        @Schema(example = "Alice Lim") @NotBlank @Size(max = 120) String name,
        @Schema(example = "alice@example.com") @NotBlank @Email @Size(max = 200) String email) {
}
