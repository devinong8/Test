package com.hackathon.inventory.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegionRequest(@NotBlank @Size(max = 40) String code, @NotBlank @Size(max = 255) String name) {}
