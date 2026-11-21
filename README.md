# Appointment Management System — Hackathon BE1

A REST backend for a professional services firm. **Providers** publish time slots, **customers** search and book
them, and bookings can be cancelled with the slot released atomically.

**Stack:** Java 21 · Spring Boot 3.3 · Spring Data JPA / Hibernate 6 · SQLite (default, no setup) or PostgreSQL ·
springdoc OpenAPI / Swagger UI · JUnit 5 + MockMvc.

| Submission item | Where |
|---|---|
| Source code | `src/main/java/...` (tests in `src/test/java/...`) |
| Solution approach & design document | [`docs/DESIGN.md`](docs/DESIGN.md) |
| AI prompts | [`docs/AI_PROMPTS.md`](docs/AI_PROMPTS.md) |
| 10-minute briefing script | [`docs/BRIEFING.md`](docs/BRIEFING.md) |
| **Visual verification report** (open in a browser) | [`docs/verification-report.html`](docs/verification-report.html) |
| **Backtest report** (requirements traceability + results) | [`docs/BACKTEST_REPORT.md`](docs/BACKTEST_REPORT.md) |
| Postman collection | [`postman/AppointmentSystem.postman_collection.json`](postman/AppointmentSystem.postman_collection.json) |
| Black-box acceptance check (cURL, PASS/FAIL per requirement) | [`acceptance-check.sh`](acceptance-check.sh) |

## Run it

```bash
mvn spring-boot:run            # or: mvn package && java -jar target/appointment-management-1.0.0.jar
```

* Swagger UI: http://localhost:8080/swagger-ui.html
* OpenAPI JSON: http://localhost:8080/v3/api-docs
* Health: http://localhost:8080/actuator/health

On first start an empty database is seeded with demo data: providers **1** (LEGAL), **2** (TAX),
**3** (FINANCIAL_ADVISORY), customers **1** and **2**, and 09:00–12:00 hourly slots for the next 5 business days.
Delete `appointments.db` to reset.

PostgreSQL instead of SQLite:

```bash
java -jar target/appointment-management-1.0.0.jar --spring.profiles.active=postgres \
     --DB_HOST=localhost --DB_NAME=appointments --DB_USER=postgres --DB_PASSWORD=postgres
```

## Test it

```bash
mvn test                                        # 24 integration tests (SQLite)
mvn test -Dtest.profiles=test,postgres,pgtest   # same suite on PostgreSQL
./acceptance-check.sh                           # 56 black-box checks against a running server
python3 perf/loadtest.py                        # load test (then perf/integrity-check.sql on PostgreSQL)
```

## API summary (base `/api/v1`)

| Method | Path | Purpose | Main errors |
|---|---|---|---|
| POST | `/providers` | Register provider | 400 |
| GET | `/providers/{id}` | Get provider | 404 |
| POST | `/customers` | Register customer | 400, 409 duplicate email |
| GET | `/customers/{id}` | Get customer | 404 |
| POST | `/providers/{pid}/slots` | Create slot (AVAILABLE) | 400 bad range, 404, **409 overlap** |
| POST | `/providers/{pid}/slots/batch` | Create many slots, all-or-nothing | 400, 404, 409 |
| GET | `/providers/{pid}/slots` | Provider's slots, any status, paged | 400, 404 |
| GET | `/providers/{pid}/slots/{sid}` | One slot | 404 |
| PUT | `/providers/{pid}/slots/{sid}` | Reschedule an unbooked slot | 400, 404, 409 |
| DELETE | `/providers/{pid}/slots/{sid}` | Delete a never-booked slot | 404, 409 |
| POST | `/providers/{pid}/slots/{sid}/lock` | AVAILABLE → LOCKED | 404, 409 |
| POST | `/providers/{pid}/slots/{sid}/unlock` | LOCKED → AVAILABLE | 404, 409 |
| GET | `/slots/available` | **Customer search**: `providerId`, `serviceCategory`, `fromDate`, `toDate`, `page`, `size`, `sort` | 400 |
| POST | `/appointments` | Book by `slotId` **or** `providerId+date+startTime+endTime` | 400, 404, **409 double booking** |
| GET | `/appointments/{id}` | Get appointment | 404 |
| POST | `/appointments/{id}/cancel` | Cancel (owner only) & release slot atomically | 400, **403**, 404, 409 |
| GET | `/customers/{cid}/appointments` | Customer's appointments, paged, `status` filter | 404 |

Every error has the same shape:

```json
{
  "timestamp": "2026-11-21T10:18:48.53+08:00",
  "status": 409,
  "error": "Conflict",
  "code": "SLOT_ALREADY_BOOKED",
  "message": "Slot 1 is not available (status: BOOKED)",
  "path": "/api/v1/appointments",
  "fieldErrors": [ { "field": "...", "rejectedValue": "...", "message": "..." } ]
}
```

## Configuration (`application.yml` → `app.*`)

| Key | Default | Meaning |
|---|---|---|
| `timezone` | `Asia/Singapore` | Business time zone for "now" |
| `min-slot-minutes` / `max-slot-minutes` | 15 / 480 | Allowed slot length |
| `max-advance-days` | 365 | How far ahead slots may be published |
| `cancellation-cutoff-minutes` | 60 | Must cancel at least this long before start |
| `max-page-size` | 100 | Upper bound on `size` |
| `expiry-sweep-interval-ms` | 60000 | How often started slots are marked EXPIRED |
| `seed-demo-data` | true | Seed an empty DB on startup |
