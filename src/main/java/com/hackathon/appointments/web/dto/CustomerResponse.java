package com.hackathon.appointments.web.dto;

import com.hackathon.appointments.domain.Customer;

import java.time.LocalDateTime;

public record CustomerResponse(Long id, String name, String email, LocalDateTime createdAt) {
    public static CustomerResponse from(Customer c) {
        return new CustomerResponse(c.getId(), c.getName(), c.getEmail(), c.getCreatedAt());
    }
}
