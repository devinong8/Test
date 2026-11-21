# 10-Minute Implementation Briefing (script)

**Setup before you start:** app running (`mvn spring-boot:run`), Swagger UI open at
http://localhost:8080/swagger-ui.html, a terminal ready for `./demo.sh`, and `docs/DESIGN.md` in another tab.

---

### 0:00–1:00 · The problem and the one-line answer
"The brief asks for an appointment backend that is *robust*. Most people stop at CRUD. I focused on the three places
these systems break: **double booking**, **overlapping availability**, and **half-finished cancellations**. Each
one is guaranteed by the database, not just by an if-statement, and I prove it with tests."

### 1:00–2:30 · Architecture and data model (DESIGN.md §3–4)
* Spring Boot 3, Java 21, layered: controller → service → repository. Controllers have no business logic.
* Four tables. **Slot and appointment are separate**: a slot is capacity, an appointment is a claim on it.
  Cancelled appointments stay as history and the slot can be re-booked.
* SQLite by default so you can run it with one command; a PostgreSQL profile for production.
* Constraints live in SQL: CHECKs on status and time range, and a **partial unique index: one BOOKED appointment per slot**.

### 2:30–4:00 · Criterion 1: providers define slots (live in Swagger)
* `POST /providers/1/slots` 09:00–10:00 → 201, status AVAILABLE.
* Same again with 09:30–10:30 → **409 SLOT_OVERLAP**, and the message lists the conflicting slot.
* 10:00–11:00 → 201: back-to-back is allowed. The rule is `s1 < e2 AND s2 < e1`.
* End before start → **400 INVALID_TIME_RANGE**. Unknown provider → **404**.
* "Concurrent creates are serialised by locking the provider row before the overlap check."

### 4:00–5:00 · Criterion 2: search
* `GET /slots/available?serviceCategory=legal&fromDate=…&size=5&sort=startTime,desc`
* "The query always adds `status = AVAILABLE AND start_time > now`, so BOOKED, LOCKED and EXPIRED are excluded even
  if the expiry job hasn't run." Show the pagination envelope. Bad sort field → 400 (whitelist).

### 5:00–7:00 · Criterion 3: booking and the race condition (the key part)
* Book slot by `slotId` → 201. Book same slot as another customer → **409 SLOT_ALREADY_BOOKED**.
* Book by time range (`providerId+date+start+end`) → 201.
* "Here's the core line:" open `TimeSlotRepository.compareAndSetStatus`:
  `UPDATE time_slot SET status='BOOKED' WHERE id=? AND status='AVAILABLE'`.
  "The database guarantees only one request sees *1 row updated*. Everyone else gets 409. No check-then-act gap."
* Show the test `concurrentBookingsOfOneSlotProduceExactlyOneWinner`: 20 threads, exactly 1 winner, 19 conflicts.
* Extra rules: locked or started slot → 409; a customer can't hold two overlapping bookings → 409.

### 7:00–8:30 · Criterion 4: cancellation
* Cancel as the wrong customer → **403 NOT_APPOINTMENT_OWNER**.
* Cancel as the owner with a reason → 200, `status: CANCELLED`, `cancelledAt`, `cancellationReason`, and the slot is
  back to **AVAILABLE** in the same response.
* "Both writes are in one transaction. If the slot can't be released, the cancellation is rolled back."
* Cancel again → **409 APPOINTMENT_ALREADY_CANCELLED**. Inside 60-minute cut-off → **409 CANCELLATION_WINDOW_CLOSED**.

### 8:30–9:30 · Criterion 5: error handling and tests
* One `@RestControllerAdvice`. Every error has the same envelope with a stable `code` for clients to branch on.
* Show malformed JSON → clean 400 with the field name; unknown field → 400.
* `mvn test` → 18 integration tests over real HTTP + real DB, with a controllable clock for time-based rules.

### 9:30–10:00 · Close
"Next steps: JWT auth so customer ID comes from the token, idempotency keys for safe retries, Flyway migrations,
and notifications via an outbox. Thank you, happy to take questions."

---

### Likely judge questions
* **Why not `synchronized` or a Java lock?** It only works on one server instance. The DB-level compare-and-set works across any number of instances.
* **Why not just optimistic locking?** It's there too (`@Version`), but the conditional UPDATE gives a clean, specific 409 without catching exceptions. The unique index is the last line of defence.
* **Why 403 for ownership?** It's the correct semantic status; 400/404/409 are all used where the brief specifies.
* **Time zones?** All slot times are local to `app.timezone` (Asia/Singapore). "Now" comes from an injected Clock, which is also how the tests move time forward.
* **Scaling?** Switch to the postgres profile; indexes cover the overlap and search queries; stateless app scales horizontally.
