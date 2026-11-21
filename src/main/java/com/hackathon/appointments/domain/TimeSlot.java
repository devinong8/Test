package com.hackathon.appointments.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "time_slot", indexes = {
        @Index(name = "ix_slot_provider_start", columnList = "provider_id, start_time"),
        @Index(name = "ix_slot_status_start", columnList = "status, start_time")
})
public class TimeSlot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "provider_id", nullable = false)
    private Provider provider;

    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalDateTime endTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SlotStatus status;

    /** Optimistic-locking guard: any concurrent write to the same row fails instead of silently overwriting. */
    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected TimeSlot() {
    }

    public TimeSlot(Provider provider, LocalDateTime startTime, LocalDateTime endTime, LocalDateTime now) {
        this.provider = provider;
        this.startTime = startTime;
        this.endTime = endTime;
        this.status = SlotStatus.AVAILABLE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public boolean hasStarted(LocalDateTime now) {
        return !startTime.isAfter(now);
    }

    public void reschedule(LocalDateTime newStart, LocalDateTime newEnd, LocalDateTime now) {
        this.startTime = newStart;
        this.endTime = newEnd;
        this.updatedAt = now;
    }

    public void changeStatus(SlotStatus newStatus, LocalDateTime now) {
        this.status = newStatus;
        this.updatedAt = now;
    }

    public Long getId() { return id; }
    public Provider getProvider() { return provider; }
    public LocalDateTime getStartTime() { return startTime; }
    public LocalDateTime getEndTime() { return endTime; }
    public SlotStatus getStatus() { return status; }
    public Long getVersion() { return version; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
