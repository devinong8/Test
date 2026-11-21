package com.hackathon.appointments.service;

import com.hackathon.appointments.domain.Customer;
import com.hackathon.appointments.domain.Provider;
import com.hackathon.appointments.exception.ConflictException;
import com.hackathon.appointments.exception.ErrorCode;
import com.hackathon.appointments.exception.NotFoundException;
import com.hackathon.appointments.repository.CustomerRepository;
import com.hackathon.appointments.repository.ProviderRepository;
import com.hackathon.appointments.web.dto.CreateCustomerRequest;
import com.hackathon.appointments.web.dto.CreateProviderRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Locale;

/** Registration and lookup of providers and customers. */
@Service
public class DirectoryService {

    private final ProviderRepository providers;
    private final CustomerRepository customers;
    private final Clock clock;

    public DirectoryService(ProviderRepository providers, CustomerRepository customers, Clock clock) {
        this.providers = providers;
        this.customers = customers;
        this.clock = clock;
    }

    @Transactional
    public Provider createProvider(CreateProviderRequest req) {
        return providers.save(new Provider(req.name().trim(),
                req.serviceCategory().trim().toUpperCase(Locale.ROOT), LocalDateTime.now(clock)));
    }

    @Transactional
    public Customer createCustomer(CreateCustomerRequest req) {
        String email = req.email().trim().toLowerCase(Locale.ROOT);
        if (customers.existsByEmailIgnoreCase(email)) {
            throw new ConflictException(ErrorCode.DUPLICATE_RESOURCE, "A customer with email " + email + " already exists");
        }
        return customers.save(new Customer(req.name().trim(), email, LocalDateTime.now(clock)));
    }

    @Transactional(readOnly = true)
    public Provider getProvider(Long id) {
        return providers.findById(id).orElseThrow(() -> providerNotFound(id));
    }

    @Transactional(readOnly = true)
    public Customer getCustomer(Long id) {
        return customers.findById(id).orElseThrow(() -> customerNotFound(id));
    }

    static NotFoundException providerNotFound(Long id) {
        return new NotFoundException(ErrorCode.PROVIDER_NOT_FOUND, "Provider " + id + " not found");
    }

    static NotFoundException customerNotFound(Long id) {
        return new NotFoundException(ErrorCode.CUSTOMER_NOT_FOUND, "Customer " + id + " not found");
    }
}
