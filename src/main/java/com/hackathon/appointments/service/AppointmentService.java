package com.hackathon.appointments.service;

import com.hackathon.appointments.config.AppProperties;
import com.hackathon.appointments.domain.Appointment;
import com.hackathon.appointments.domain.AppointmentStatus;
import com.hackathon.appointments.domain.Customer;
import com.hackathon.appointments.domain.SlotStatus;
import com.hackathon.appointments.domain.TimeSlot;
import com.hackathon.appointments.exception.BadRequestException;
import com.hackathon.appointments.exception.ConflictException;
import com.hackathon.appointments.exception.ErrorCode;
import com.hackathon.appointments.exception.ForbiddenException;
import com.hackathon.appointments.exception.NotFoundException;
import com.hackathon.appointments.repository.AppointmentRepository;
import com.hackathon.appointments.repository.CustomerRepository;
import com.hackathon.appointments.repository.ProviderRepository;
import com.hackathon.appointments.repository.TimeSlotRepository;
import com.hackathon.appointments.web.dto.BookAppointmentRequest;
import com.hackathon.appointments.web.dto.CancelAppointmentRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

@Service
public class AppointmentService {

    private static final Logger log = LoggerFactory.getLogger(AppointmentService.class);

    private final AppointmentRepository appointments;
    private final TimeSlotRepository slots;
    private final CustomerRepository customers;
    private final ProviderRepository providers;
    private final AppProperties props;
    private final Clock clock;

    public AppointmentService(AppointmentRepository appointments, TimeSlotRepository slots, CustomerRepository customers,
                              ProviderRepository providers, AppProperties props, Clock clock) {
        this.appointments = appointments;
        this.slots = slots;
        this.customers = customers;
        this.providers = providers;
        this.props = props;
        this.clock = clock;
    }

    /**
     * Books a slot. Validation order gives the most useful error first:
     * valid time range (400) -> customer exists (404) -> slot exists (404) -> slot not started (409) -> slot AVAILABLE (409)
     * -> customer has no overlapping booking (409) -> atomic claim of the slot (409 if lost the race).
     * <p>
     * The atomic claim is {@code UPDATE time_slot SET status='BOOKED' WHERE id=? AND status='AVAILABLE'}.
     * Under any level of concurrency exactly one request sees "1 row updated"; everybody else gets 409.
     * A partial unique index (one BOOKED appointment per slot) backs this up at the database level.
     */
    @Transactional
    public Appointment book(BookAppointmentRequest req) {
        LocalDateTime now = LocalDateTime.now(clock);
        if (req.byTimeRange() && !req.endTime().isAfter(req.startTime())) {
            throw new BadRequestException(ErrorCode.INVALID_TIME_RANGE,
                    "endTime (%s) must be after startTime (%s)".formatted(req.endTime(), req.startTime()));
        }
        // Lock the customer row: serialises this customer's concurrent bookings so the overlap check below is reliable.
        Customer customer = customers.findByIdForUpdate(req.customerId())
                .orElseThrow(() -> DirectoryService.customerNotFound(req.customerId()));

        TimeSlot slot = resolveSlot(req);

        if (slot.hasStarted(now)) {
            throw new ConflictException(ErrorCode.SLOT_EXPIRED, "Slot " + slot.getId() + " has already started or passed");
        }
        if (slot.getStatus() != SlotStatus.AVAILABLE) {
            ErrorCode code = slot.getStatus() == SlotStatus.BOOKED ? ErrorCode.SLOT_ALREADY_BOOKED : ErrorCode.SLOT_NOT_AVAILABLE;
            throw new ConflictException(code, "Slot %d is not available (status: %s)".formatted(slot.getId(), slot.getStatus()));
        }
        if (appointments.customerHasOverlappingBooking(customer.getId(), slot.getStartTime(), slot.getEndTime())) {
            throw new ConflictException(ErrorCode.CUSTOMER_DOUBLE_BOOKING,
                    "Customer %d already has a booking that overlaps %s-%s"
                            .formatted(customer.getId(), slot.getStartTime(), slot.getEndTime().toLocalTime()));
        }

        Long slotId = slot.getId();
        int claimed = slots.compareAndSetStatus(slotId, SlotStatus.AVAILABLE, SlotStatus.BOOKED, now);
        if (claimed == 0) {
            throw new ConflictException(ErrorCode.SLOT_ALREADY_BOOKED,
                    "Slot " + slotId + " was just booked by another customer");
        }

        // compareAndSetStatus cleared the persistence context; work with fresh managed references.
        Appointment appt = new Appointment(customers.getReferenceById(customer.getId()),
                slots.getReferenceById(slotId), blankToNull(req.notes()), now);
        appointments.saveAndFlush(appt);
        log.info("Customer {} booked slot {} -> appointment {}", customer.getId(), slotId, appt.getId());
        return appointments.findDetailedById(appt.getId()).orElseThrow();
    }

    private TimeSlot resolveSlot(BookAppointmentRequest req) {
        if (req.bySlotId()) {
            return slots.findById(req.slotId()).orElseThrow(() -> SlotService.slotNotFound(req.slotId()));
        }
        if (!providers.existsById(req.providerId())) {
            throw DirectoryService.providerNotFound(req.providerId());
        }
        LocalDateTime start = req.date().atTime(req.startTime());
        LocalDateTime end = req.date().atTime(req.endTime());
        return slots.findByProviderIdAndStartTimeAndEndTime(req.providerId(), start, end)
                .orElseThrow(() -> new NotFoundException(ErrorCode.SLOT_NOT_FOUND,
                        "Provider %d has no slot %s-%s".formatted(req.providerId(), start, end.toLocalTime())));
    }

    /**
     * Cancels an appointment and releases its slot in ONE transaction: either both the appointment
     * becomes CANCELLED (with timestamp + reason) and the slot becomes AVAILABLE, or nothing changes.
     */
    @Transactional
    public Appointment cancel(Long appointmentId, CancelAppointmentRequest req) {
        LocalDateTime now = LocalDateTime.now(clock);
        Appointment appt = appointments.findDetailedById(appointmentId)
                .orElseThrow(() -> appointmentNotFound(appointmentId));

        if (!customers.existsById(req.customerId())) {
            throw DirectoryService.customerNotFound(req.customerId());
        }
        if (!appt.isOwnedBy(req.customerId())) {
            throw new ForbiddenException(ErrorCode.NOT_APPOINTMENT_OWNER,
                    "Customer %d does not own appointment %d".formatted(req.customerId(), appointmentId));
        }
        if (appt.getStatus() == AppointmentStatus.CANCELLED) {
            throw new ConflictException(ErrorCode.APPOINTMENT_ALREADY_CANCELLED,
                    "Appointment %d was already cancelled at %s".formatted(appointmentId, appt.getCancelledAt()));
        }
        LocalDateTime cutoff = appt.getSlot().getStartTime().minusMinutes(props.cancellationCutoffMinutes());
        if (!now.isBefore(cutoff)) {
            throw new ConflictException(ErrorCode.CANCELLATION_WINDOW_CLOSED,
                    "Appointments must be cancelled at least %d minutes before the start time (%s)"
                            .formatted(props.cancellationCutoffMinutes(), appt.getSlot().getStartTime()));
        }

        Long slotId = appt.getSlot().getId();
        appt.cancel(req.reason().trim(), now);
        // flushes the appointment change (with @Version check), then releases the slot atomically.
        int released = slots.compareAndSetStatus(slotId, SlotStatus.BOOKED, SlotStatus.AVAILABLE, now);
        if (released == 0) {
            // Should never happen; throwing rolls back the cancellation too, keeping data consistent.
            throw new ConflictException(ErrorCode.CONCURRENT_MODIFICATION,
                    "Slot " + slotId + " was not in BOOKED state; cancellation rolled back");
        }
        log.info("Appointment {} cancelled by customer {}; slot {} released", appointmentId, req.customerId(), slotId);
        return appointments.findDetailedById(appointmentId).orElseThrow();
    }

    @Transactional(readOnly = true)
    public Appointment get(Long id) {
        return appointments.findDetailedById(id).orElseThrow(() -> appointmentNotFound(id));
    }

    @Transactional(readOnly = true)
    public Page<Appointment> listForCustomer(Long customerId, AppointmentStatus status, Pageable pageable) {
        if (!customers.existsById(customerId)) {
            throw DirectoryService.customerNotFound(customerId);
        }
        return status == null
                ? appointments.findByCustomerId(customerId, pageable)
                : appointments.findByCustomerIdAndStatus(customerId, status, pageable);
    }

    private static NotFoundException appointmentNotFound(Long id) {
        return new NotFoundException(ErrorCode.APPOINTMENT_NOT_FOUND, "Appointment " + id + " not found");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
