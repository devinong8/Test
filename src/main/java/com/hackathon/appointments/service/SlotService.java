package com.hackathon.appointments.service;

import com.hackathon.appointments.config.AppProperties;
import com.hackathon.appointments.domain.Provider;
import com.hackathon.appointments.domain.SlotStatus;
import com.hackathon.appointments.domain.TimeSlot;
import com.hackathon.appointments.exception.BadRequestException;
import com.hackathon.appointments.exception.ConflictException;
import com.hackathon.appointments.exception.ErrorCode;
import com.hackathon.appointments.exception.NotFoundException;
import com.hackathon.appointments.repository.ProviderRepository;
import com.hackathon.appointments.repository.TimeSlotRepository;
import com.hackathon.appointments.web.dto.CreateSlotRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class SlotService {

    private static final Logger log = LoggerFactory.getLogger(SlotService.class);

    private final TimeSlotRepository slots;
    private final ProviderRepository providers;
    private final AppProperties props;
    private final Clock clock;

    public SlotService(TimeSlotRepository slots, ProviderRepository providers, AppProperties props, Clock clock) {
        this.slots = slots;
        this.providers = providers;
        this.props = props;
        this.clock = clock;
    }

    /* ------------------------------------------------------------------ provider: define slots */

    @Transactional
    public TimeSlot createSlot(Long providerId, CreateSlotRequest req) {
        return createSlots(providerId, List.of(req)).get(0);
    }

    /**
     * All-or-nothing creation. Steps:
     * <ol>
     *   <li>validate every time range (400)</li>
     *   <li>lock the provider row so concurrent creates for the same provider are serialised</li>
     *   <li>reject overlaps inside the batch and against existing non-expired slots (409)</li>
     *   <li>persist with status AVAILABLE</li>
     * </ol>
     */
    @Transactional
    public List<TimeSlot> createSlots(Long providerId, List<CreateSlotRequest> requests) {
        LocalDateTime now = LocalDateTime.now(clock);
        List<Range> ranges = requests.stream().map(r -> validateRange(r, now)).toList();

        Provider provider = providers.findByIdForUpdate(providerId)
                .orElseThrow(() -> DirectoryService.providerNotFound(providerId));

        for (int i = 0; i < ranges.size(); i++) {
            for (int j = i + 1; j < ranges.size(); j++) {
                if (ranges.get(i).overlaps(ranges.get(j))) {
                    throw new ConflictException(ErrorCode.SLOT_OVERLAP,
                            "Slots in the request overlap each other: %s and %s".formatted(ranges.get(i), ranges.get(j)));
                }
            }
        }

        List<TimeSlot> created = new ArrayList<>();
        for (Range r : ranges) {
            List<TimeSlot> clashes = slots.findOverlapping(providerId, r.start(), r.end());
            if (!clashes.isEmpty()) {
                String ids = clashes.stream()
                        .map(s -> "#%d %s-%s (%s)".formatted(s.getId(), s.getStartTime(), s.getEndTime().toLocalTime(), s.getStatus()))
                        .collect(Collectors.joining(", "));
                throw new ConflictException(ErrorCode.SLOT_OVERLAP,
                        "Slot %s overlaps existing slot(s) for provider %d: %s".formatted(r, providerId, ids));
            }
            created.add(slots.save(new TimeSlot(provider, r.start(), r.end(), now)));
        }
        return created;
    }

    private Range validateRange(CreateSlotRequest r, LocalDateTime now) {
        LocalDateTime start = r.date().atTime(r.startTime());
        LocalDateTime end = r.date().atTime(r.endTime());
        if (!end.isAfter(start)) {
            throw new BadRequestException(ErrorCode.INVALID_TIME_RANGE,
                    "endTime (%s) must be after startTime (%s)".formatted(r.endTime(), r.startTime()));
        }
        if (!start.isAfter(now)) {
            throw new BadRequestException(ErrorCode.INVALID_TIME_RANGE, "Slot start " + start + " is in the past");
        }
        long minutes = Duration.between(start, end).toMinutes();
        if (minutes < props.minSlotMinutes() || minutes > props.maxSlotMinutes()) {
            throw new BadRequestException(ErrorCode.INVALID_TIME_RANGE,
                    "Slot duration must be between %d and %d minutes (was %d)"
                            .formatted(props.minSlotMinutes(), props.maxSlotMinutes(), minutes));
        }
        if (r.date().isAfter(now.toLocalDate().plusDays(props.maxAdvanceDays()))) {
            throw new BadRequestException(ErrorCode.INVALID_TIME_RANGE,
                    "Slots can be published at most %d days ahead".formatted(props.maxAdvanceDays()));
        }
        return new Range(start, end);
    }

    /* ------------------------------------------------------------------ provider: manage slots */

    /** Provider temporarily blocks a slot (e.g. personal leave). Only AVAILABLE slots can be locked. */
    @Transactional
    public TimeSlot lock(Long providerId, Long slotId) {
        return transition(providerId, slotId, SlotStatus.AVAILABLE, SlotStatus.LOCKED);
    }

    @Transactional
    public TimeSlot unlock(Long providerId, Long slotId) {
        return transition(providerId, slotId, SlotStatus.LOCKED, SlotStatus.AVAILABLE);
    }

    private TimeSlot transition(Long providerId, Long slotId, SlotStatus from, SlotStatus to) {
        LocalDateTime now = LocalDateTime.now(clock);
        TimeSlot slot = getProviderSlot(providerId, slotId);
        if (slot.hasStarted(now)) {
            throw new ConflictException(ErrorCode.SLOT_EXPIRED, "Slot " + slotId + " has already started");
        }
        if (slots.compareAndSetStatus(slotId, from, to, now) == 0) {
            TimeSlot current = slots.findById(slotId).orElseThrow();
            throw new ConflictException(ErrorCode.SLOT_NOT_AVAILABLE,
                    "Slot %d is %s; only %s slots can be changed to %s".formatted(slotId, current.getStatus(), from, to));
        }
        return slots.findById(slotId).orElseThrow();
    }

    @Transactional(readOnly = true)
    public TimeSlot getProviderSlot(Long providerId, Long slotId) {
        if (!providers.existsById(providerId)) {
            throw DirectoryService.providerNotFound(providerId);
        }
        TimeSlot slot = getSlot(slotId);
        if (!slot.getProvider().getId().equals(providerId)) {
            throw slotNotFound(slotId); // don't leak other providers' slots
        }
        return slot;
    }

    @Transactional(readOnly = true)
    public TimeSlot getSlot(Long slotId) {
        return slots.findById(slotId).orElseThrow(() -> slotNotFound(slotId));
    }

    /** Provider's own view: every slot regardless of status, optionally filtered by status/date. */
    @Transactional(readOnly = true)
    public Page<TimeSlot> listProviderSlots(Long providerId, SlotStatus status, LocalDate from, LocalDate to, Pageable pageable) {
        if (!providers.existsById(providerId)) {
            throw DirectoryService.providerNotFound(providerId);
        }
        validateDates(from, to);
        Specification<TimeSlot> spec = Specification.where(SlotSpecifications.providerIs(providerId))
                .and(SlotSpecifications.hasStatus(status))
                .and(SlotSpecifications.onOrAfter(from))
                .and(SlotSpecifications.onOrBefore(to));
        return slots.findAll(spec, pageable);
    }

    /* ------------------------------------------------------------------ customer: search */

    /**
     * Customer search. Always restricted to AVAILABLE and not-yet-started slots, which by construction
     * excludes BOOKED, LOCKED and EXPIRED ones.
     */
    @Transactional(readOnly = true)
    public Page<TimeSlot> searchAvailable(SlotSearchCriteria c, Pageable pageable) {
        validateDates(c.fromDate(), c.toDate());
        LocalDateTime now = LocalDateTime.now(clock);
        Specification<TimeSlot> spec = Specification.where(SlotSpecifications.hasStatus(SlotStatus.AVAILABLE))
                .and(SlotSpecifications.startsAfter(now))
                .and(SlotSpecifications.providerIs(c.providerId()))
                .and(SlotSpecifications.categoryIs(c.serviceCategory()))
                .and(SlotSpecifications.onOrAfter(c.fromDate()))
                .and(SlotSpecifications.onOrBefore(c.toDate()));
        return slots.findAll(spec, pageable);
    }

    private static void validateDates(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new BadRequestException(ErrorCode.INVALID_TIME_RANGE, "fromDate must be on or before toDate");
        }
    }

    /* ------------------------------------------------------------------ housekeeping */

    /** Persist EXPIRED status for slots whose start time has passed (search already hides them regardless). */
    @Scheduled(fixedDelayString = "${app.expiry-sweep-interval-ms:60000}", initialDelay = 5000)
    @Transactional
    public int expireStartedSlots() {
        int n = slots.expireStartedSlots(List.of(SlotStatus.AVAILABLE, SlotStatus.LOCKED), LocalDateTime.now(clock));
        if (n > 0) {
            log.info("Marked {} slot(s) as EXPIRED", n);
        }
        return n;
    }

    static NotFoundException slotNotFound(Long id) {
        return new NotFoundException(ErrorCode.SLOT_NOT_FOUND, "Slot " + id + " not found");
    }

    private record Range(LocalDateTime start, LocalDateTime end) {
        boolean overlaps(Range o) {
            return start.isBefore(o.end) && o.start.isBefore(end);
        }

        @Override
        public String toString() {
            return start + "-" + end.toLocalTime();
        }
    }
}
