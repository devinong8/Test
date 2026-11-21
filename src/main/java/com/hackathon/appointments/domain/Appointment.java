package com.hackathon.appointments.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "appointment", indexes = {
        @Index(name = "ix_appt_customer", columnList = "customer_id"),
        @Index(name = "ix_appt_slot", columnList = "slot_id")
})
public class Appointment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "slot_id", nullable = false)
    private TimeSlot slot;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AppointmentStatus status;

    @Column(length = 500)
    private String notes;

    @Column(name = "booked_at", nullable = false)
    private LocalDateTime bookedAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

    @Version
    @Column(nullable = false)
    private Long version;

    protected Appointment() {
    }

    public Appointment(Customer customer, TimeSlot slot, String notes, LocalDateTime now) {
        this.customer = customer;
        this.slot = slot;
        this.notes = notes;
        this.status = AppointmentStatus.BOOKED;
        this.bookedAt = now;
    }

    public void cancel(String reason, LocalDateTime now) {
        this.status = AppointmentStatus.CANCELLED;
        this.cancelledAt = now;
        this.cancellationReason = reason;
    }

    public boolean isOwnedBy(Long customerId) {
        return customer.getId().equals(customerId);
    }

    public Long getId() { return id; }
    public Customer getCustomer() { return customer; }
    public TimeSlot getSlot() { return slot; }
    public AppointmentStatus getStatus() { return status; }
    public String getNotes() { return notes; }
    public LocalDateTime getBookedAt() { return bookedAt; }
    public LocalDateTime getCancelledAt() { return cancelledAt; }
    public String getCancellationReason() { return cancellationReason; }
    public Long getVersion() { return version; }
}
