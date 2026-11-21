package com.hackathon.appointments.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Publish many slots in one all-or-nothing transaction (e.g. a provider's whole day). */
public record CreateSlotsBatchRequest(@NotEmpty @Size(max = 100) List<@Valid CreateSlotRequest> slots) {
}
