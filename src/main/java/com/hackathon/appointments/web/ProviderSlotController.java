package com.hackathon.appointments.web;

import com.hackathon.appointments.domain.SlotStatus;
import com.hackathon.appointments.service.PageRequests;
import com.hackathon.appointments.service.SlotService;
import com.hackathon.appointments.web.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/providers/{providerId}/slots")
@Validated
@Tag(name = "2. Provider slot management", description = "Providers define and manage their availability")
public class ProviderSlotController {

    static final Map<String, String> SORTABLE = Map.of(
            "startTime", "startTime", "endTime", "endTime", "status", "status", "id", "id");

    private final SlotService slotService;
    private final PageRequests pageRequests;

    public ProviderSlotController(SlotService slotService, PageRequests pageRequests) {
        this.slotService = slotService;
        this.pageRequests = pageRequests;
    }

    @Operation(summary = "Create one slot (status AVAILABLE)",
            description = "400 invalid time range / past date, 404 unknown provider, 409 overlaps an existing slot")
    @PostMapping
    public ResponseEntity<SlotResponse> create(@PathVariable @Positive Long providerId,
                                               @Valid @RequestBody CreateSlotRequest req) {
        SlotResponse body = SlotResponse.from(slotService.createSlot(providerId, req));
        return ResponseEntity.created(ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(body.id()).toUri()).body(body);
    }

    @Operation(summary = "Create many slots atomically (all or nothing)")
    @PostMapping("/batch")
    @ResponseStatus(HttpStatus.CREATED)
    public List<SlotResponse> createBatch(@PathVariable @Positive Long providerId,
                                          @Valid @RequestBody CreateSlotsBatchRequest req) {
        return slotService.createSlots(providerId, req.slots()).stream().map(SlotResponse::from).toList();
    }

    @Operation(summary = "List this provider's slots in any status (provider view)")
    @GetMapping
    public PageResponse<SlotResponse> list(
            @PathVariable @Positive Long providerId,
            @RequestParam(required = false) SlotStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "e.g. startTime,asc. Allowed: startTime, endTime, status, id")
            @RequestParam(required = false) List<String> sort) {
        var pageable = pageRequests.of(page, size, sort, SORTABLE, "startTime,asc");
        return PageResponse.from(slotService.listProviderSlots(providerId, status, fromDate, toDate, pageable), SlotResponse::from);
    }

    @Operation(summary = "Get one of this provider's slots")
    @GetMapping("/{slotId}")
    public SlotResponse get(@PathVariable @Positive Long providerId, @PathVariable @Positive Long slotId) {
        return SlotResponse.from(slotService.getProviderSlot(providerId, slotId));
    }

    @Operation(summary = "Reschedule an AVAILABLE or LOCKED slot",
            description = "Same rules as creation; overlap check excludes the slot itself. 409 if BOOKED, started or overlapping")
    @PutMapping("/{slotId}")
    public SlotResponse update(@PathVariable @Positive Long providerId, @PathVariable @Positive Long slotId,
                               @Valid @RequestBody CreateSlotRequest req) {
        return SlotResponse.from(slotService.updateSlot(providerId, slotId, req));
    }

    @Operation(summary = "Delete an AVAILABLE or LOCKED slot that has never been booked",
            description = "409 if BOOKED, started, or has booking history (lock it instead)")
    @DeleteMapping("/{slotId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable @Positive Long providerId, @PathVariable @Positive Long slotId) {
        slotService.deleteSlot(providerId, slotId);
    }

    @Operation(summary = "Lock an AVAILABLE slot so it cannot be booked", description = "409 if not AVAILABLE")
    @PostMapping("/{slotId}/lock")
    public SlotResponse lock(@PathVariable @Positive Long providerId, @PathVariable @Positive Long slotId) {
        return SlotResponse.from(slotService.lock(providerId, slotId));
    }

    @Operation(summary = "Unlock a LOCKED slot back to AVAILABLE", description = "409 if not LOCKED")
    @PostMapping("/{slotId}/unlock")
    public SlotResponse unlock(@PathVariable @Positive Long providerId, @PathVariable @Positive Long slotId) {
        return SlotResponse.from(slotService.unlock(providerId, slotId));
    }
}
