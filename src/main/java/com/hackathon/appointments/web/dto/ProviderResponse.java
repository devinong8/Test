package com.hackathon.appointments.web.dto;

import com.hackathon.appointments.domain.Provider;

import java.time.LocalDateTime;

public record ProviderResponse(Long id, String name, String serviceCategory, LocalDateTime createdAt) {
    public static ProviderResponse from(Provider p) {
        return new ProviderResponse(p.getId(), p.getName(), p.getServiceCategory(), p.getCreatedAt());
    }
}
