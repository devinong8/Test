package com.hackathon.appointments.domain;

/**
 * Lifecycle of a provider time slot.
 * <pre>
 *   AVAILABLE --book--> BOOKED --cancel--> AVAILABLE
 *   AVAILABLE --lock--> LOCKED --unlock--> AVAILABLE
 *   AVAILABLE/LOCKED --start time passes--> EXPIRED
 * </pre>
 * Only AVAILABLE slots are ever shown to customers or accepted for booking.
 */
public enum SlotStatus {
    AVAILABLE,
    BOOKED,
    LOCKED,
    EXPIRED
}
