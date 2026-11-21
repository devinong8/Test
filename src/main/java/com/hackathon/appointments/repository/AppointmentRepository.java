package com.hackathon.appointments.repository;

import com.hackathon.appointments.domain.Appointment;
import com.hackathon.appointments.domain.AppointmentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface AppointmentRepository extends JpaRepository<Appointment, Long> {

    @EntityGraph(attributePaths = {"customer", "slot", "slot.provider"})
    @Query("select a from Appointment a where a.id = :id")
    Optional<Appointment> findDetailedById(@Param("id") Long id);

    @EntityGraph(attributePaths = {"customer", "slot", "slot.provider"})
    Page<Appointment> findByCustomerId(Long customerId, Pageable pageable);

    @EntityGraph(attributePaths = {"customer", "slot", "slot.provider"})
    Page<Appointment> findByCustomerIdAndStatus(Long customerId, AppointmentStatus status, Pageable pageable);

    /** A customer cannot be in two places at once: detect an existing BOOKED appointment that overlaps. */
    @Query("""
            select count(a) > 0 from Appointment a
            where a.customer.id = :customerId
              and a.status = com.hackathon.appointments.domain.AppointmentStatus.BOOKED
              and a.slot.startTime < :endTime
              and a.slot.endTime > :startTime
            """)
    boolean customerHasOverlappingBooking(@Param("customerId") Long customerId,
                                          @Param("startTime") LocalDateTime startTime,
                                          @Param("endTime") LocalDateTime endTime);
}
