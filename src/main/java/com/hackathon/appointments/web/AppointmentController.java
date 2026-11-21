package com.hackathon.appointments.web;

import com.hackathon.appointments.domain.AppointmentStatus;
import com.hackathon.appointments.service.AppointmentService;
import com.hackathon.appointments.service.PageRequests;
import com.hackathon.appointments.web.dto.AppointmentResponse;
import com.hackathon.appointments.web.dto.BookAppointmentRequest;
import com.hackathon.appointments.web.dto.CancelAppointmentRequest;
import com.hackathon.appointments.web.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@Validated
@Tag(name = "4. Appointments", description = "Book, view and cancel appointments")
public class AppointmentController {

    private static final Map<String, String> SORTABLE = Map.of(
            "bookedAt", "bookedAt", "startTime", "slot.startTime", "status", "status", "id", "id");

    private final AppointmentService service;
    private final PageRequests pageRequests;

    public AppointmentController(AppointmentService service, PageRequests pageRequests) {
        this.service = service;
        this.pageRequests = pageRequests;
    }

    @Operation(summary = "Book an appointment",
            description = """
                    Identify the slot by `slotId` OR by `providerId` + `date` + `startTime` + `endTime`.
                    - 400 invalid body / both or neither slot reference
                    - 404 customer, provider or slot not found
                    - 409 slot already booked, locked, expired, or customer already booked at an overlapping time""")
    @PostMapping("/appointments")
    public ResponseEntity<AppointmentResponse> book(@Valid @RequestBody BookAppointmentRequest req) {
        AppointmentResponse body = AppointmentResponse.from(service.book(req));
        return ResponseEntity.created(ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(body.id()).toUri()).body(body);
    }

    @Operation(summary = "Get an appointment")
    @GetMapping("/appointments/{appointmentId}")
    public AppointmentResponse get(@PathVariable @Positive Long appointmentId) {
        return AppointmentResponse.from(service.get(appointmentId));
    }

    @Operation(summary = "Cancel an appointment and release its slot (atomic)",
            description = """
                    - 403 the customer does not own the appointment
                    - 404 appointment or customer not found
                    - 409 already cancelled, or inside the cancellation cut-off window""")
    @PostMapping("/appointments/{appointmentId}/cancel")
    public AppointmentResponse cancel(@PathVariable @Positive Long appointmentId,
                                      @Valid @RequestBody CancelAppointmentRequest req) {
        return AppointmentResponse.from(service.cancel(appointmentId, req));
    }

    @Operation(summary = "List a customer's appointments")
    @GetMapping("/customers/{customerId}/appointments")
    public PageResponse<AppointmentResponse> listForCustomer(
            @PathVariable @Positive Long customerId,
            @RequestParam(required = false) AppointmentStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) List<String> sort) {
        var pageable = pageRequests.of(page, size, sort, SORTABLE, "startTime,asc");
        return PageResponse.from(service.listForCustomer(customerId, status, pageable), AppointmentResponse::from);
    }
}
