package com.hackathon.appointments;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/** Test clock we can move forward to simulate time passing (expiry, cancellation cut-off). */
public class MutableClock extends Clock {

    private volatile Instant instant;
    private final ZoneId zone;

    public MutableClock(Instant instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    public void set(Instant newInstant) { this.instant = newInstant; }
    public void advance(Duration d) { this.instant = instant.plus(d); }

    @Override public ZoneId getZone() { return zone; }
    @Override public Clock withZone(ZoneId z) { return new MutableClock(instant, z); }
    @Override public Instant instant() { return instant; }
}
