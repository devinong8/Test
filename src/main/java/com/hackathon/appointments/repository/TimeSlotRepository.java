package com.hackathon.appointments.repository;

import com.hackathon.appointments.domain.SlotStatus;
import com.hackathon.appointments.domain.TimeSlot;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TimeSlotRepository extends JpaRepository<TimeSlot, Long>, JpaSpecificationExecutor<TimeSlot> {

    /** Provider is always part of the response, so fetch it in the same query (no N+1, no lazy errors). */
    @Override
    @EntityGraph(attributePaths = "provider")
    Optional<TimeSlot> findById(Long id);

    @Override
    @EntityGraph(attributePaths = "provider")
    Page<TimeSlot> findAll(Specification<TimeSlot> spec, Pageable pageable);

    /**
     * Two intervals [s1,e1) and [s2,e2) overlap iff s1 < e2 AND s2 < e1.
     * Back-to-back slots (10:00-11:00 and 11:00-12:00) do NOT overlap.
     * EXPIRED slots are ignored because they can never be booked again.
     */
    @Query("""
            select s from TimeSlot s
            where s.provider.id = :providerId
              and s.status <> com.hackathon.appointments.domain.SlotStatus.EXPIRED
              and s.startTime < :endTime
              and s.endTime > :startTime
            """)
    @EntityGraph(attributePaths = "provider")
    List<TimeSlot> findOverlapping(@Param("providerId") Long providerId,
                                   @Param("startTime") LocalDateTime startTime,
                                   @Param("endTime") LocalDateTime endTime);

    @EntityGraph(attributePaths = "provider")
    Optional<TimeSlot> findByProviderIdAndStartTimeAndEndTime(Long providerId, LocalDateTime startTime, LocalDateTime endTime);

    /**
     * Atomic compare-and-set: flips the slot only if it is still in {@code expected} state.
     * Returns the number of rows changed (0 = somebody else got there first). This is the core
     * double-booking guard and works identically on SQLite, PostgreSQL and MySQL.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update TimeSlot s
               set s.status = :newStatus, s.updatedAt = :now, s.version = s.version + 1
             where s.id = :id and s.status = :expected
            """)
    int compareAndSetStatus(@Param("id") Long id,
                            @Param("expected") SlotStatus expected,
                            @Param("newStatus") SlotStatus newStatus,
                            @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update TimeSlot s
               set s.status = com.hackathon.appointments.domain.SlotStatus.EXPIRED,
                   s.updatedAt = :now, s.version = s.version + 1
             where s.status in :statuses and s.startTime <= :now
            """)
    int expireStartedSlots(@Param("statuses") Collection<SlotStatus> statuses, @Param("now") LocalDateTime now);
}
