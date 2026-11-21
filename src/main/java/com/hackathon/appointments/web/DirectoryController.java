package com.hackathon.appointments.web;

import com.hackathon.appointments.service.DirectoryService;
import com.hackathon.appointments.web.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1")
@Validated
@Tag(name = "1. Providers & Customers", description = "Register and look up providers and customers")
public class DirectoryController {

    private final DirectoryService directory;

    public DirectoryController(DirectoryService directory) {
        this.directory = directory;
    }

    @Operation(summary = "Register a service provider")
    @PostMapping("/providers")
    public ResponseEntity<ProviderResponse> createProvider(@Valid @RequestBody CreateProviderRequest req) {
        ProviderResponse body = ProviderResponse.from(directory.createProvider(req));
        return ResponseEntity.created(ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(body.id()).toUri()).body(body);
    }

    @Operation(summary = "Get a provider")
    @GetMapping("/providers/{providerId}")
    public ProviderResponse getProvider(@PathVariable @Positive Long providerId) {
        return ProviderResponse.from(directory.getProvider(providerId));
    }

    @Operation(summary = "Register a customer", description = "409 if the email is already registered")
    @PostMapping("/customers")
    public ResponseEntity<CustomerResponse> createCustomer(@Valid @RequestBody CreateCustomerRequest req) {
        CustomerResponse body = CustomerResponse.from(directory.createCustomer(req));
        return ResponseEntity.created(ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(body.id()).toUri()).body(body);
    }

    @Operation(summary = "Get a customer")
    @GetMapping("/customers/{customerId}")
    public CustomerResponse getCustomer(@PathVariable @Positive Long customerId) {
        return CustomerResponse.from(directory.getCustomer(customerId));
    }
}
