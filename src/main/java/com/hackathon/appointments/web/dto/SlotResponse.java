package com.hackathon.appointments.web.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.hackathon.appointments.domain.SlotStatus;
import com.hackathon.appointments.domain.TimeSlot;

import java.time.LocalDate;
import java.time.LocalTime;

public record SlotResponse(
        Long id,
        Long providerId,
        String providerName,
        String serviceCategory,
        LocalDate date,
        @JsonFormat(pattern = "HH:mm") LocalTime startTime,
        @JsonFormat(pattern = "HH:mm") LocalTime endTime,
        SlotStatus status) {

    public static SlotResponse from(TimeSlot s) {
        return new SlotResponse(s.getId(), s.getProvider().getId(), s.getProvider().getName(),
                s.getProvider().getServiceCategory(), s.getStartTime().toLocalDate(),
                s.getStartTime().toLocalTime(), s.getEndTime().toLocalTime(), s.getStatus());
    }
}
