-- Appointment Management System schema (SQLite).
-- Business invariants are enforced in the database as well as in code (defence in depth).

CREATE TABLE IF NOT EXISTS provider (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    name             VARCHAR(120) NOT NULL,
    service_category VARCHAR(60)  NOT NULL,
    created_at       TIMESTAMP    NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_provider_category ON provider (service_category);

CREATE TABLE IF NOT EXISTS customer (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    name       VARCHAR(120) NOT NULL,
    email      VARCHAR(200) NOT NULL UNIQUE,
    created_at TIMESTAMP    NOT NULL
);

CREATE TABLE IF NOT EXISTS time_slot (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    provider_id INTEGER     NOT NULL REFERENCES provider (id),
    start_time  TIMESTAMP   NOT NULL,
    end_time    TIMESTAMP   NOT NULL,
    status      VARCHAR(20) NOT NULL CHECK (status IN ('AVAILABLE', 'BOOKED', 'LOCKED', 'EXPIRED')),
    version     INTEGER     NOT NULL DEFAULT 0,
    created_at  TIMESTAMP   NOT NULL,
    updated_at  TIMESTAMP   NOT NULL,
    CHECK (end_time > start_time)
);
CREATE INDEX IF NOT EXISTS ix_slot_provider_start ON time_slot (provider_id, start_time);
CREATE INDEX IF NOT EXISTS ix_slot_status_start ON time_slot (status, start_time);

CREATE TABLE IF NOT EXISTS appointment (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    customer_id         INTEGER      NOT NULL REFERENCES customer (id),
    slot_id             INTEGER      NOT NULL REFERENCES time_slot (id),
    status              VARCHAR(20)  NOT NULL CHECK (status IN ('BOOKED', 'CANCELLED')),
    notes               VARCHAR(500),
    booked_at           TIMESTAMP    NOT NULL,
    cancelled_at        TIMESTAMP,
    cancellation_reason VARCHAR(500),
    version             INTEGER      NOT NULL DEFAULT 0,
    CHECK (status <> 'CANCELLED' OR (cancelled_at IS NOT NULL AND cancellation_reason IS NOT NULL))
);
CREATE INDEX IF NOT EXISTS ix_appt_customer ON appointment (customer_id);
CREATE INDEX IF NOT EXISTS ix_appt_slot ON appointment (slot_id);

-- Hard guarantee against double booking: at most ONE active (BOOKED) appointment per slot.
-- Cancelled rows are kept for audit, so the same slot can be re-booked after a cancellation.
CREATE UNIQUE INDEX IF NOT EXISTS ux_appt_one_active_per_slot ON appointment (slot_id) WHERE status = 'BOOKED';
