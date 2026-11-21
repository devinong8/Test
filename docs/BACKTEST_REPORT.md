# Backtest Report: Requirements vs. Implementation

**Date:** 2026-11-21 · **Build:** this commit · **Databases tested:** SQLite 3.46 (default) and PostgreSQL 16

## Summary

| Check | SQLite | PostgreSQL |
|---|---|---|
| Automated integration tests (`mvn test`) | **24 / 24 pass** | **24 / 24 pass** |
| Black-box acceptance check over HTTP (`./acceptance-check.sh`) | **56 / 56 pass** | **56 / 56 pass** |
| Mutation check: each safeguard removed → a test must fail | n/a | **6 / 6 caught** |

## Continuous integration (GitHub Actions)

`.github/workflows/ci.yml` runs on every push and pull request. Run #1
(https://github.com/devinong8/Test/actions/runs/44860360275) passed all three jobs in about 2 minutes:

| Job | What it proves | Result |
|---|---|---|
| Tests · SQLite | 24 integration tests | passed |
| Tests · PostgreSQL 16 + coverage | same 24 tests on a real multi-connection database; JaCoCo report | passed |
| Acceptance + load + integrity | 56/56 acceptance checks; 1,000 bookings at 565 req/s (all 201); 2,000 searches at 1,275 req/s; 200 simultaneous bookings of one slot → 1 × 201, 199 × 409; `perf/integrity-assert.sql` → 1,004 booked appointments = 1,004 booked slots, 0 violations | passed |

The integrity step raises an error, and so fails the build, on any double booking, any slot whose status disagrees
with its bookings, or any customer holding overlapping bookings.

## Performance and integrity under load (PostgreSQL 16)

`perf/loadtest.py` (Python standard library only) against the running server; app, database and load generator share one
4-CPU cloud container, so these are a floor, not a benchmark.

| Scenario | Requests | Throughput | p50 | p95 | p99 | Result |
|---|---|---|---|---|---|---|
| Book 1,000 different slots, 50 parallel clients | 1,000 | 413 req/s | 104 ms | 278 ms | 392 ms | 1,000 × 201 |
| Search with mixed filters, 50 parallel clients | 2,000 | 967 req/s | 39 ms | 116 ms | 159 ms | 2,000 × 200 |
| 200 customers, one slot, same instant | 200 | 802 req/s | 94 ms | 167 ms | 197 ms | **1 × 201, 199 × 409** |

Database checked afterwards with `perf/integrity-check.sql`: 1,001 BOOKED appointments = 1,001 BOOKED slots;
0 slots with more than one active booking; 0 slots whose status disagrees with their bookings; 0 customers with overlapping bookings.

## Test coverage (JaCoCo, PostgreSQL run)

| Package | Line coverage | Branch coverage |
|---|---|---|
| **Overall** | **85.9 %** (456/531) | **74.2 %** (132/178) |
| service (business rules) | 93.5 % | 81.5 % |
| web (controllers) | 93.8 % | n/a |
| domain | 93.7 % | 100 % |
| exception (error handling) | 77.2 % | 64.3 % |
| config | 22.2 % | 0 % (the demo-data seeder is switched off in tests) |

Report: `target/site/jacoco/index.html` after `mvn test`.

## Method

1. **Requirement decomposition.** Each acceptance criterion in the brief was split into atomic, testable statements (R1.x … R5.x below).
2. **White-box tests.** `AppointmentApiIntegrationTest` drives the real HTTP layer and a real database, with a controllable clock for time-based rules (expiry, cancellation cut-off).
3. **Black-box acceptance.** `acceptance-check.sh` runs against a live server using only `curl`, exactly as an assessor would, and prints PASS/FAIL per requirement.
4. **Both databases.** The same suites run on SQLite (one connection) and PostgreSQL (10-connection pool). PostgreSQL is where races are real, so it is the meaningful test of the concurrency safeguards.
5. **Mutation testing.** Each safeguard was deliberately removed and the suite re-run on PostgreSQL to prove the tests detect its absence:

| Safeguard removed | Detected by |
|---|---|
| Atomic compare-and-set on booking | `concurrentBookingsOfOneSlotProduceExactlyOneWinner` |
| Customer row lock (same customer, overlapping slots, simultaneously) | `sameCustomerRacingForOverlappingSlotsGetsExactlyOne` |
| Provider row lock on slot creation | `concurrentOverlappingSlotCreationProducesExactlyOneSlot` |
| Rollback when slot release fails during cancel | `cancellationIsAtomicAndRollsBackIfSlotCannotBeReleased` |
| `start_time > now` filter in search | `excludesBookedLockedAndExpired…` |
| Overlap check on slot creation | `overlappingSlotRejectedButBackToBackAllowed` (+ 6 acceptance checks) |

## Gaps found during the backtest, and fixed

| # | Brief wording | Gap | Fix |
|---|---|---|---|
| G1 | "define and **manage** their available time slots" | Only create / lock / unlock existed | Added `PUT` (reschedule, overlap check excludes itself) and `DELETE` (only never-booked, unheld slots; otherwise 409 "lock it instead" to preserve audit history) |
| G2 | "HTTP 400 … for **invalid time ranges**" | Booking by time range with end ≤ start returned 404 | Now 400 `INVALID_TIME_RANGE` |
| G3 | "release … within an **atomic transaction**" | Atomic in code but not proven | Test forces the slot release to fail and asserts the appointment stays BOOKED with no cancellation data |
| G4 | "robust consideration" | Same customer firing two overlapping bookings at once on PostgreSQL could pass the overlap check twice | Customer row is locked (`SELECT … FOR UPDATE`) for the booking; race test added |
| G5 | "validate slot non-overlap" | No concurrent-creation test | Race test: 10 overlapping creates at once → exactly 1 slot |

## Traceability matrix

| ID | Requirement (from brief) | Implementation | Automated test | Acceptance check |
|---|---|---|---|---|
| **R1** | **Providers define & manage slots (provider ID, date, start, end); non-overlap per provider; persisted as AVAILABLE** | | | |
| R1.a | Create slot → AVAILABLE, persisted | `POST /providers/{id}/slots`, `SlotService.createSlots` | `createdSlotIsAvailable` | R1.1, R1.2 |
| R1.b | Reject overlap for the same provider | `TimeSlotRepository.findOverlapping` (`s1<e2 AND s2<e1`) | `overlappingSlotRejectedButBackToBackAllowed` | R1.3, R1.4, R1.13 |
| R1.c | Allow back-to-back / other providers | same | same | R1.5, R1.6 |
| R1.d | Non-overlap holds under concurrency | provider `PESSIMISTIC_WRITE` lock | `concurrentOverlappingSlotCreationProducesExactlyOneSlot` | — |
| R1.e | Invalid time range → 400 | `SlotService.validateRange` | `invalidTimeRangesAreBadRequests` | R1.7, R1.8 |
| R1.f | Manage: batch, reschedule, lock/unlock, delete | `/batch`, `PUT`, `/lock`, `/unlock`, `DELETE` | `batchIsAllOrNothing`, `providerCanRescheduleAndDeleteUnbookedSlots`, `bookedOrPreviouslyBookedSlotsCannotBeChangedOrDeleted` | R1.10–R1.15 |
| **R2** | **Query/filter available slots by provider ID, date range, service category; paginated/sorted; exclude booked, locked, expired** | | | |
| R2.a | Filters (provider, category, date range) | `GET /slots/available`, `SlotSpecifications` | `excludesBookedLockedAndExpiredAndFilters…` | R2.1–R2.3 |
| R2.b | Paginated & sorted | `PageRequests` (whitelist, size cap, id tie-breaker) | `paginatesAndSorts`, `rejectsBadSearchParams` | R2.4, R2.5, R2.7 |
| R2.c | Exclude BOOKED | `status = AVAILABLE` | `excludesBookedLocked…` | R3.3 |
| R2.d | Exclude LOCKED | `status = AVAILABLE` | same | R2.6 |
| R2.e | Exclude EXPIRED (even before sweeper runs) | `start_time > now` + scheduled sweeper | same + `sweeperMarksStartedSlotsExpired` | (needs clock control: covered by automated test) |
| **R3** | **Process booking requests (customer ID, slot ID / time range), robust** | | | |
| R3.a | Book by slot ID | `POST /appointments` | `bookBySlotIdAndByTimeRange` | R3.1 |
| R3.b | Book by time range | `AppointmentService.resolveSlot` | same | R3.4 |
| R3.c | No double booking, incl. under concurrency | atomic `UPDATE … WHERE status='AVAILABLE'`, partial unique index, `@Version` | `doubleBookingIs409`, `concurrentBookingsOfOneSlotProduceExactlyOneWinner` | R3.2, R3.12 |
| R3.d | Locked / started slots not bookable | status + time checks | `lockedOrStartedSlotsCannotBeBooked` | R3.9 |
| R3.e | Customer can't hold overlapping bookings, incl. concurrently | overlap query + customer row lock | `customerCannotHoldOverlappingBookings`, `sameCustomerRacing…` | R3.10 |
| R3.f | 404 / 400 cases | service + validation | `notFoundAndValidationErrors`, `bookingWithInvalidTimeRangeIs400` | R3.5–R3.8, R3.11 |
| **R4** | **Validate ownership & eligibility; CANCELLED; release slot to AVAILABLE atomically; record timestamp & reason** | | | |
| R4.a | Ownership | `Appointment.isOwnedBy` → 403 | `onlyOwnerCanCancel` | R4.1, R4.2 |
| R4.b | Eligibility (not already cancelled, before cut-off) | 409 `APPOINTMENT_ALREADY_CANCELLED` / `CANCELLATION_WINDOW_CLOSED` | `cannotCancelTwiceOrInsideCutoffOrMissing` | R4.7 |
| R4.c | CANCELLED + timestamp + reason | `Appointment.cancel` | `cancelRecordsReasonAndTimestamp…` | R4.3 |
| R4.d | Slot back to AVAILABLE, re-bookable | compare-and-set BOOKED→AVAILABLE | same | R4.4–R4.6 |
| R4.e | Atomic: all or nothing | single `@Transactional`, throw on failed release | `cancellationIsAtomicAndRollsBack…` | — (requires fault injection) |
| **R5** | **Unified global exception handling: 400 invalid ranges, 404 non-existent slots, 409 double booking, clear messages** | | | |
| R5.a | One standard envelope | `GlobalExceptionHandler`, `ApiError` | asserted in every test | R5.1, R5.5 |
| R5.b | 400 / 404 / 409 as specified | see above | throughout | R1.7, R3.7, R3.2 |
| R5.c | Malformed input handled cleanly | Jackson / type-mismatch handlers | `notFoundAndValidationErrors` | R5.2–R5.4 |
| **T** | **Technologies: backend framework, relational DB, REST, Swagger/Postman/cURL** | Spring Boot 3, SQLite + PostgreSQL, Swagger UI, Postman collection, cURL script | — | D.1, D.2 |

## Known limitations (stated, not hidden)

* **No authentication.** `customerId` in the request stands in for the logged-in user. Production would take it from a JWT.
* **Search with an unknown `providerId`** returns an empty page (200), the usual REST behaviour for filters. Direct lookups of an unknown provider return 404.
* **Booking by time range** matches a slot exactly (same start and end). It does not book part of a larger slot.
* **On SQLite**, a single connection serialises writes, so the race tests prove correctness there trivially. **PostgreSQL** is the real proof of the concurrency safeguards, which is why the suites are run on both.

## Reproduce

```bash
mvn test                                         # SQLite
mvn test -Dtest.profiles=test,postgres,pgtest    # PostgreSQL (needs DB appointments_test, user postgres/postgres)
mvn spring-boot:run & ./acceptance-check.sh      # black-box, against a live server
```

## Appendix: acceptance-check output (SQLite run)

```
Acceptance check against http://localhost:8080  (slot date 2026-12-31, category ACC_1721825317)

Setup
  PASS S.1    Register provider                                              [201]
  PASS S.2    Register second provider (other category)                      [201]
  PASS S.3    Register customer 1                                            [201]
  PASS S.4    Register customer 2                                            [201]

R1  Providers define & manage slots; non-overlap; initial AVAILABLE
  PASS R1.1   Create slot (provider, date, start, end) -> AVAILABLE          [201]
  PASS R1.2   Slot is persisted and retrievable                              [200]
  PASS R1.3   Partially overlapping slot rejected                            [409]
  PASS R1.4   Enclosing slot rejected                                        [409]
  PASS R1.5   Back-to-back slot allowed                                      [201]
  PASS R1.6   Same time, different provider allowed                          [201]
  PASS R1.7   End before start -> 400                                        [400]
  PASS R1.8   Slot in the past -> 400                                        [400]
  PASS R1.9   Unknown provider -> 404                                        [404]
  PASS R1.10  Batch with internal overlap rejected (all-or-nothing)          [409]
  PASS R1.11  Batch create                                                   [201]
  PASS R1.12  Reschedule unbooked slot                                       [200]
  PASS R1.13  Reschedule onto another slot rejected                          [409]
  PASS R1.14  Lock slot                                                      [200]
  PASS R1.15  Delete unbooked slot                                           [204]

R2  Search available slots: filters, pagination, sorting, exclusions
  PASS R2.1   Filter by service category                                     [200]
  PASS R2.2   Filter by provider ID                                          [200]
  PASS R2.3   Filter by date range                                           [200]
  PASS R2.4   Paginated (page 1, size 2 of 5)                                [200]
  PASS R2.5   Sorted (startTime desc -> latest first)                        [200]
  PASS R2.6   LOCKED slot excluded from search                               [200]
  PASS R2.7   Non-whitelisted sort field -> 400                              [400]
  PASS R2.8   fromDate after toDate -> 400                                   [400]

R3  Booking (customer ID + slot ID / time range), robust
  PASS R3.1   Book by slot ID                                                [201]
  PASS R3.2   Double booking same slot -> 409 with clear message             [409]
  PASS R3.3   BOOKED slot now excluded from search                           [200]
  PASS R3.4   Book by time range (provider + date + start + end)             [201]
  PASS R3.5   Booking with invalid time range -> 400                         [400]
  PASS R3.6   Time range matching no slot -> 404                             [404]
  PASS R3.7   Non-existent slot -> 404                                       [404]
  PASS R3.8   Non-existent customer -> 404                                   [404]
  PASS R3.9   LOCKED slot cannot be booked -> 409                            [409]
  PASS R3.10  Customer overlapping own booking -> 409                        [409]
  PASS R3.11  Missing customerId -> 400 with field error                     [400]
  PASS R3.12  20 parallel bookings of one slot: exactly 1 wins, 19 get 409   [1/19]

R4  Cancellation: ownership, eligibility, atomic release, timestamp + reason
  PASS R4.1   Non-owner cannot cancel                                        [403]
  PASS R4.2   ...and the appointment is untouched                            [200]
  PASS R4.3   Owner cancels -> CANCELLED with timestamp and reason           [200]
  PASS R4.4   ...slot released to AVAILABLE in the same transaction          [200]
  PASS R4.5   Slot is AVAILABLE when read back                               [200]
  PASS R4.6   Released slot can be booked again                              [201]
  PASS R4.7   Cancel twice -> 409                                            [409]
  PASS R4.8   Cancel non-existent appointment -> 404                         [404]
  PASS R4.9   Cancel without reason -> 400                                   [400]
  PASS R4.10  Cancelled appointment kept in history                          [200]

R5  Unified error handling (standard envelope)
  PASS R5.1   Error envelope has timestamp/status/error/code/message/path    [404]
  PASS R5.2   Malformed JSON -> 400                                          [400]
  PASS R5.3   Unknown JSON field -> 400                                      [400]
  PASS R5.4   Wrong path type -> 400                                         [400]
  PASS R5.5   Unknown endpoint -> 404 (same envelope)                        [404]

Documentation
  PASS D.1    OpenAPI spec served at /v3/api-docs                            [200]
  PASS D.2    Swagger UI served at /swagger-ui.html                          [200]

Result: 56 passed, 0 failed
```
