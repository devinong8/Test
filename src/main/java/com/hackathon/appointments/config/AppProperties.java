package com.hackathon.appointments.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Business rules that a firm would want to tune without a code change.
 *
 * @param timezone                 business time zone used for "now" (all slot times are local to it)
 * @param minSlotMinutes           shortest slot a provider may publish
 * @param maxSlotMinutes           longest slot a provider may publish
 * @param maxAdvanceDays           how far ahead slots may be published
 * @param cancellationCutoffMinutes customers must cancel at least this long before the slot starts
 * @param maxPageSize              hard cap on page size to protect the DB
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String timezone,
        int minSlotMinutes,
        int maxSlotMinutes,
        int maxAdvanceDays,
        int cancellationCutoffMinutes,
        int maxPageSize) {
}
