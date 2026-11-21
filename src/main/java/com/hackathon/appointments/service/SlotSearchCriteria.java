package com.hackathon.appointments.service;

import java.time.LocalDate;

/** Optional filters for the customer-facing available-slot search. Null = no filter. */
public record SlotSearchCriteria(Long providerId, String serviceCategory, LocalDate fromDate, LocalDate toDate) {
}
