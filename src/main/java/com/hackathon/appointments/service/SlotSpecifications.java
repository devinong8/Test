package com.hackathon.appointments.service;

import com.hackathon.appointments.domain.Provider;
import com.hackathon.appointments.domain.SlotStatus;
import com.hackathon.appointments.domain.TimeSlot;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;

/** Composable WHERE clauses for slot queries (JPA Criteria, so no string-built SQL). */
final class SlotSpecifications {

    private SlotSpecifications() {
    }

    static Specification<TimeSlot> hasStatus(SlotStatus status) {
        return (root, q, cb) -> status == null ? null : cb.equal(root.get("status"), status);
    }

    /** Excludes anything already started, even if the expiry sweeper hasn't flagged it EXPIRED yet. */
    static Specification<TimeSlot> startsAfter(LocalDateTime now) {
        return (root, q, cb) -> cb.greaterThan(root.get("startTime"), now);
    }

    static Specification<TimeSlot> providerIs(Long providerId) {
        return (root, q, cb) -> providerId == null ? null : cb.equal(root.get("provider").get("id"), providerId);
    }

    static Specification<TimeSlot> categoryIs(String category) {
        return (root, q, cb) -> {
            if (category == null || category.isBlank()) {
                return null;
            }
            Join<TimeSlot, Provider> provider = root.join("provider", JoinType.INNER);
            return cb.equal(provider.get("serviceCategory"), category.trim().toUpperCase(Locale.ROOT));
        };
    }

    /** Inclusive date range on the slot's start date. */
    static Specification<TimeSlot> onOrAfter(LocalDate from) {
        return (root, q, cb) -> from == null ? null : cb.greaterThanOrEqualTo(root.get("startTime"), from.atStartOfDay());
    }

    static Specification<TimeSlot> onOrBefore(LocalDate to) {
        return (root, q, cb) -> to == null ? null : cb.lessThan(root.get("startTime"), to.plusDays(1).atStartOfDay());
    }
}
