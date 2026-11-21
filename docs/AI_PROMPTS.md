# AI Prompts Used

The solution was built with an AI coding assistant (Claude Code). Below are the prompts used, in order, with what
each produced. Every generated piece was compiled, run and covered by tests before being kept.

### 1. Kick-off
> I'm at a hackathon. [photo of the BE1 "Appointment Management System" brief] How to build this. Build for me and
> explain what was built and why.

The assistant broke the brief into the five acceptance criteria and chose Spring Boot 3 + SQLite (no setup needed
for judges) with a PostgreSQL profile for production.

### 2. Design prompts (what the assistant was asked to reason through)
> Design a relational schema for providers, customers, time slots and appointments. Keep cancelled appointments
> for audit and allow a slot to be re-booked after cancellation. Enforce the invariants in the database too.

> How do I guarantee that two concurrent booking requests for the same slot can never both succeed, in a way that
> works on SQLite, PostgreSQL and MySQL?

→ Atomic `UPDATE … WHERE status='AVAILABLE'` compare-and-set + partial unique index + `@Version`.

> How do I prevent overlapping slots for the same provider when two create requests arrive together?

→ Pessimistic lock on the provider row before the overlap query; interval rule `s1 < e2 AND s2 < e1`.

### 3. Implementation prompts
> Implement the entities, repositories, services and controllers. Use DTO records with Bean Validation, a global
> exception handler that returns one standard error body with a machine-readable code, whitelisted sort fields and a
> page-size cap, and an injectable Clock.

> Customer search must exclude BOOKED, LOCKED and EXPIRED slots even if the background expiry job has not run yet.

> Cancellation must set CANCELLED, record timestamp and reason, and release the slot in a single transaction; roll
> back everything if the slot cannot be released.

### 4. Debugging prompts
> The available-slot endpoint returns 500 with LazyInitializationException.

→ Fetch the provider with `@EntityGraph` (keeps `open-in-view=false`, avoids N+1).

> `sort=startTime,desc` returns "Cannot sort by 'desc'".

→ Spring splits comma values in `List<String>` params; the parser now treats `asc`/`desc` tokens as modifiers of the previous field.

> Malformed JSON errors leak Jackson internals.

→ Map `MismatchedInputException` / `UnrecognizedPropertyException` to a clean field-level 400.

### 5. Testing prompts
> Write integration tests through HTTP for every acceptance criterion, including a test that fires 20 concurrent
> bookings at one slot and asserts exactly one succeeds. Use a mutable clock to test expiry and the cancellation cut-off.

### 6. Documentation prompts
> Write the solution approach & design document, a README, a Postman collection, a curl demo script and a
> 10-minute briefing script for the judges.

### 7. Backtest prompt
> Before showing the assessor, backtest against the requirements.

The assistant decomposed each criterion into atomic statements, found five gaps (no slot update/delete, 400 vs 404
for invalid booking ranges, unproven atomic rollback, a same-customer race on PostgreSQL, no concurrent-creation
test), fixed them, ran everything on SQLite and PostgreSQL, mutation-checked the safeguards, and wrote
`docs/BACKTEST_REPORT.md` plus the black-box `acceptance-check.sh`.
