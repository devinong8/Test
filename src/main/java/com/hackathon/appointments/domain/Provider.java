package com.hackathon.appointments.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "provider", indexes = @Index(name = "ix_provider_category", columnList = "service_category"))
public class Provider {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String name;

    /** e.g. LEGAL, TAX, FINANCIAL_ADVISORY. Stored upper-case for case-insensitive filtering. */
    @Column(name = "service_category", nullable = false, length = 60)
    private String serviceCategory;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected Provider() {
    }

    public Provider(String name, String serviceCategory, LocalDateTime createdAt) {
        this.name = name;
        this.serviceCategory = serviceCategory;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getServiceCategory() { return serviceCategory; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
