package com.hackathon.appointments.web;

import com.hackathon.appointments.service.PageRequests;
import com.hackathon.appointments.service.SlotSearchCriteria;
import com.hackathon.appointments.service.SlotService;
import com.hackathon.appointments.web.dto.PageResponse;
import com.hackathon.appointments.web.dto.SlotResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/slots")
@Validated
@Tag(name = "3. Customer slot search")
public class SlotSearchController {

    private static final Map<String, String> SORTABLE = Map.of(
            "startTime", "startTime", "endTime", "endTime", "id", "id",
            "providerName", "provider.name", "serviceCategory", "provider.serviceCategory");

    private final SlotService slotService;
    private final PageRequests pageRequests;

    public SlotSearchController(SlotService slotService, PageRequests pageRequests) {
        this.slotService = slotService;
        this.pageRequests = pageRequests;
    }

    @Operation(summary = "Search bookable slots",
            description = "Only AVAILABLE slots that have not started are returned; BOOKED, LOCKED and EXPIRED are always excluded. "
                    + "All filters are optional and combinable. Paginated and sortable.")
    @GetMapping("/available")
    public PageResponse<SlotResponse> searchAvailable(
            @RequestParam(required = false) @Positive Long providerId,
            @Parameter(example = "LEGAL") @RequestParam(required = false) String serviceCategory,
            @Parameter(example = "2026-12-01") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @Parameter(example = "2026-12-31") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "Repeatable, e.g. sort=startTime,asc&sort=providerName,desc. "
                    + "Allowed: startTime, endTime, providerName, serviceCategory, id")
            @RequestParam(required = false) List<String> sort) {
        var pageable = pageRequests.of(page, size, sort, SORTABLE, "startTime,asc");
        var criteria = new SlotSearchCriteria(providerId, serviceCategory, fromDate, toDate);
        return PageResponse.from(slotService.searchAvailable(criteria, pageable), SlotResponse::from);
    }
}
