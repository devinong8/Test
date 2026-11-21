# Solution Approach & Design Document

## 1. Problem

A professional services firm needs a backend where providers publish availability and customers book it,
covering the full appointment lifecycle (define → search → book → cancel → release), and staying correct when
many people act at once.

## 2. Requirements → implementation map

| # | Acceptance criterion | How it is met | Proof |
|---|---|---|---|
| 1 | Providers define slots (provider ID, date, start, end); no overlap per provider; initial status AVAILABLE | `POST /providers/{id}/slots` (+ `/batch`, `PUT` reschedule, `DELETE`, `/lock`, `/unlock`). `SlotService.createSlots` validates the range, **locks the provider row**, runs an interval-overlap query, then saves with `AVAILABLE` | `DefineSlots` tests |
| 2 | Query/filter available slots by provider, date range, category; paginated + sorted; exclude BOOKED/LOCKED/EXPIRED | `GET /slots/available`: JPA Specifications always add `status = AVAILABLE AND start_time > now`, plus optional filters. Sort fields are whitelisted; page size is capped | `SearchSlots` tests |
| 3 | Robust booking (customer ID, slot ID / time range) | `POST /appointments` accepts either form. Checks run in order: customer → slot → not started → AVAILABLE → customer has no overlapping booking → **atomic compare-and-set** on the slot | `Booking` tests incl. 20-thread race |
| 4 | Validate ownership + eligibility; CANCELLED; release slot to AVAILABLE atomically; record timestamp + reason | `POST /appointments/{id}/cancel`: owner check (403), already-cancelled (409), cut-off window (409). Appointment update + slot release happen in **one `@Transactional`**; if the release fails, everything rolls back | `Cancellation` tests |
| 5 | Unified global exception handling: 400 / 404 / 409 with clear messages | `GlobalExceptionHandler` (`@RestControllerAdvice`) maps every exception to one `ApiError` envelope with a stable `code` | Every test asserts status + `code` |

## 3. Architecture

```
Client (Swagger UI / Postman / curl)
        │  JSON over HTTP
        ▼
┌─────────────── web ────────────────┐   Controllers: routing, @Valid DTOs, paging params
│ DirectoryController                │   No business logic.
│ ProviderSlotController             │
│ SlotSearchController               │
│ AppointmentController              │
└──────────────┬─────────────────────┘
               ▼
┌───────────── service ──────────────┐   Business rules + transaction boundaries
│ DirectoryService  SlotService      │   (@Transactional). Clock injected for testability.
│ AppointmentService  PageRequests   │
└──────────────┬─────────────────────┘
               ▼
┌──────────── repository ────────────┐   Spring Data JPA, Specifications,
│ Provider / Customer / TimeSlot /   │   atomic UPDATE ... WHERE status = ? queries
│ Appointment repositories           │
└──────────────┬─────────────────────┘
               ▼
        SQLite (default)  |  PostgreSQL (profile "postgres")
```

Cross-cutting: `GlobalExceptionHandler`, `AppProperties` (tunable rules), `DemoDataSeeder`, a scheduled expiry sweeper.

## 4. Data model

```
provider (id, name, service_category, created_at)
   1
   │
   *
time_slot (id, provider_id FK, start_time, end_time, status, version, created_at, updated_at)
   CHECK end_time > start_time,  CHECK status IN (AVAILABLE, BOOKED, LOCKED, EXPIRED)
   1
   │
   *
appointment (id, customer_id FK, slot_id FK, status, notes, booked_at,
             cancelled_at, cancellation_reason, version)
   CHECK status IN (BOOKED, CANCELLED)
   CHECK cancelled ⇒ cancelled_at and reason are present
   UNIQUE (slot_id) WHERE status = 'BOOKED'     ← at most one active booking per slot
   *
   │
   1
customer (id, name, email UNIQUE, created_at)
```

Design choices:

* **Appointment is separate from slot.** A slot is capacity; an appointment is a customer's claim on it. Cancelled
  appointments are kept as an audit trail and the slot can be re-booked, which a single "slot.customer_id" column
  could not support.
* **Schema is explicit SQL** (`schema-sqlite.sql`, `schema-postgresql.sql`), not Hibernate auto-DDL, so the
  constraints and indexes are visible and reviewable. Invariants are enforced in the database *and* in code.
* **Indexes** match the hot queries: `(provider_id, start_time)` for overlap checks, `(status, start_time)` for search.
* **Times** are stored as local date-times in the business time zone (`app.timezone`, default Asia/Singapore),
  which is how a firm thinks about its calendar. `now` always comes from an injected `Clock`.

## 5. Slot state machine

```
             create
               │
               ▼
  ┌──────► AVAILABLE ──── book ────► BOOKED
  │         │   ▲                      │
  │    lock │   │ unlock               │ cancel (atomic with appointment → CANCELLED)
  │         ▼   │                      │
  │        LOCKED                      │
  │                                    │
  └────────────────────────────────────┘
  AVAILABLE / LOCKED ── start time passes ──► EXPIRED   (terminal)
```

Every transition is a conditional update — `UPDATE time_slot SET status = :to WHERE id = :id AND status = :from` —
so an illegal or racing transition simply changes zero rows and becomes a 409.

## 6. Concurrency & robustness (the core of "robust")

| Risk | Defence |
|---|---|
| Two customers book the same slot at the same moment | **Atomic compare-and-set** `UPDATE … WHERE status='AVAILABLE'`. The database guarantees only one statement sees 1 row updated; the rest get 409. Backed by the **partial unique index** (one BOOKED appointment per slot) and a `@Version` column for optimistic locking. Proven by a 20-thread test: exactly 1 success, 19 conflicts. |
| Two requests create overlapping slots for one provider at the same moment | Provider row is locked with `PESSIMISTIC_WRITE` (`SELECT … FOR UPDATE` on PostgreSQL) before the overlap check, serialising slot creation per provider. A PostgreSQL `EXCLUDE USING gist` constraint is included (commented) as a database-level option. |
| Cancellation half-succeeds (appointment cancelled but slot still BOOKED) | Both writes are in one transaction; if the slot release updates 0 rows, an exception rolls back the cancellation too. |
| A slot that has started is still shown or booked | Search and booking check `start_time > now` directly, so correctness does not depend on the background job. A scheduled sweeper also persists `EXPIRED` for reporting. |
| Customer double-books themselves across providers | Customer row locked for the booking, then overlap check across the customer's BOOKED appointments → 409 `CUSTOMER_DOUBLE_BOOKING`. Holds under concurrency (race test). |
| Late cancellation | Configurable cut-off (default 60 min) → 409 `CANCELLATION_WINDOW_CLOSED`. |
| Bad input | Bean Validation on DTOs, `fail-on-unknown-properties` (typos → 400), whitelisted sort fields, bounded page size, typed path/query params. |
| Leaking data across providers | A slot fetched under the wrong provider returns 404, not the other provider's data. |
| SQLite's single-writer model | Connection pool of 1 so writes queue rather than fail with `SQLITE_BUSY`. The booking logic itself does not rely on this: it is the same compare-and-set on PostgreSQL with a 10-connection pool. |

## 7. Error handling

One `@RestControllerAdvice` translates everything into `ApiError {timestamp, status, error, code, message, path, fieldErrors}`.
`code` is a stable enum (`SLOT_OVERLAP`, `SLOT_ALREADY_BOOKED`, `NOT_APPOINTMENT_OWNER`, …) so clients can branch on
it; `message` is human-readable.

| HTTP | When |
|---|---|
| 400 | Validation failure, malformed JSON, unknown field, wrong type, invalid time range, bad sort/page params |
| 403 | Customer tries to cancel someone else's appointment |
| 404 | Unknown provider / customer / slot / appointment / endpoint |
| 405 / 415 | Wrong HTTP method / content type |
| 409 | Slot overlap, slot already booked / locked / expired, customer double booking, already cancelled, cut-off passed, optimistic-lock or unique-index conflict |
| 500 | Anything unexpected (logged with stack trace; client sees a generic message) |

403 was added for ownership because it is the semantically correct status; the brief's 400/404/409 are all covered as specified.

## 8. API design notes

* Resource-oriented, versioned under `/api/v1`. Slots are nested under providers for management
  (`/providers/{id}/slots`) and exposed flat for customer search (`/slots/available`).
* State changes that are not plain field edits are explicit actions (`/cancel`, `/lock`, `/unlock`), which keeps
  the state machine enforceable and the audit trail clear.
* `201 Created` + `Location` header on creation.
* Pagination envelope: `content, page, size, totalElements, totalPages, first, last, sort`. A tie-breaker on `id`
  is always appended to the sort so pages are stable.

## 9. Testing

Full results and a requirement-by-requirement traceability matrix: [`BACKTEST_REPORT.md`](BACKTEST_REPORT.md).
Three layers: 24 integration tests (run on SQLite **and** PostgreSQL), 56 black-box HTTP checks
(`acceptance-check.sh`), and mutation checks proving each safeguard is covered.

`AppointmentApiIntegrationTest`: 24 end-to-end tests through HTTP (MockMvc) against a real SQLite database,
using a controllable `MutableClock` to simulate time passing.

* Define slots: AVAILABLE on create, overlap/enclose/inside → 409, back-to-back allowed, invalid ranges → 400, unknown provider → 404, batch all-or-nothing
* Search: excludes BOOKED / LOCKED / EXPIRED, category / provider / date filters, pagination + sort, bad params → 400
* Booking: by id and by time range, double booking, locked/started slot, customer overlap, 404s, validation, **20-thread race**, same-customer race, invalid range → 400
* Slot management: reschedule, delete, booked/history slots protected, concurrent overlapping creates → exactly 1
* Cancellation: atomic rollback proven by fault injection, timestamp + reason recorded, slot released and re-bookable, owner-only, twice, cut-off, 404, empty reason
* Expiry sweeper

## 10. Trade-offs and next steps

* **Authentication** is out of scope; `customerId` in the body stands in for an authenticated principal. Next step:
  Spring Security + JWT, taking the customer ID from the token.
* **Idempotency keys** on `POST /appointments` would make client retries safe.
* **Recurring availability** (e.g. "every Monday 9–12") could generate slots via the batch endpoint.
* **Notifications** (email/SMS on book/cancel) via domain events and an outbox table.
* **Flyway** migrations instead of `schema.sql` once the schema starts evolving.
