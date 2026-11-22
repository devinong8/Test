# Independent review — code and documentation

**Scope:** the whole repository: backend (domain, services, security, scheduler), front end, seed data, tests and docs.
**Method:** read every write path and security boundary; **for each suspected bug, write a test that fails first**, then fix it and keep the test as a regression guard. Also checked every documented claim against the running app.
*Update: the organisation model was later simplified to a single HQ (no regional HQs or region managers). The scoping findings below still apply to HQ vs shop manager.*

**Result:** 8 issues fixed (1 high, 4 medium, 3 low); 9 accepted for the hackathon with a stated production path. 53 JUnit tests + 27 browser checks pass.

## Fixed

| # | Severity | Finding | Evidence | Fix |
|---|---|---|---|---|
| 1 | **High** | **Concurrent stock-ins created duplicate batch rows** and could race the storage / budget checks. After a duplicate, every later stock-in of that batch failed with *"Query did not return a unique result: 2 results"*, so the SKU was permanently broken at that shop. | New test `concurrentStockInsNeverBreachStorageCapacity` (12 parallel receipts into a 1 m³ shop) **failed before the fix** with `IncorrectResultSizeDataAccessException`. | Each movement takes a `PESSIMISTIC_WRITE` lock on its shop row (`ShopRepository.lockById`): one shop's movements run one at a time, different shops in parallel. Plus a DB unique constraint on (shop, SKU, batch number). Test now passes: exactly 5 of 12 receipts accepted, capacity never exceeded, 1 batch row. |
| 2 | Medium | `@PastOrPresent` on `batchProductionDate` uses the **JVM default zone, which is UTC on the server**. Between 00:00 and 08:00 China time, receiving a batch produced "today" was rejected with 400. | `user.timezone` is UTC in the container; the business clock is Asia/Shanghai. | Removed the annotation; the service checks against the injected Asia/Shanghai `Clock`. Test `productionDateInTheFutureIsRejectedUsingBusinessClock`. |
| 3 | Medium | The front end used `toISOString()` (UTC) for "today". Before 08:00 in China, the default production date was yesterday, the date picker's `max` blocked choosing today, and on the 1st of a month the report period could default to the wrong month. | Code read: 4 call sites. | `localDate()` helper using the browser's local calendar. |
| 4 | Medium | **CSV formula injection (CWE-1236).** Shop names are user-editable. A name like `=HYPERLINK(...)` was exported as-is and would execute when HQ opens the report in Excel / WPS. | Test `csvNeutralisesSpreadsheetFormulaInjection`. | Text cells starting with `= + - @ \t \r` are prefixed with `'`, and all text cells are quoted. |
| 5 | Medium | **The previous user's data stayed rendered after sign-out.** On a shared till, an HQ session followed by a shop manager could briefly show HQ-wide figures. Found when the browser e2e read a stale table. | The e2e "中秋 lesson shows all 4 regions" read the previous user's table. | Sign-out does a full page reload, so no DOM or JS state survives. e2e now passes. |
| 6 | Low | For a shop with no stock, turnover = COGS / (0 + ε) ≈ 10⁸×. It displayed as a huge number and flattened the turnover chart. | Formula per brief; display issue only. | Tables show "– no stock"; zero-stock shops are left out of the turnover chart. The API still returns the brief's exact value. |
| 7 | Low | Alerts sorted by shop code, so a ¥47.50 milk write-off ranked above ¥5,000 of expired mooncakes. | Seeded data. | Within a severity, alerts are ranked by value at risk. |
| 8 | Low (docs) | Stale or incorrect claims: test counts (36/42 → **53**), e2e checks (23 → **27**), SKU count (20 → **21**), margin range "35–50%" (actual **33–51%**), seeding time. | Counted from surefire reports and the catalogue. | README / BRIEFING corrected; README test table updated per suite. |

Earlier iterations found and fixed (also covered by tests or e2e):
- Monthly reports reused today's velocity for every month (identical COGS). Now uses ledger-observed sales.
- The seeder wrote future-dated ledger rows.
- Horizontal scroll at phone width.
- The `hidden` attribute was overridden by component CSS.
- Chart animation was captured mid-render.

## Checked and found sound

- **Role scoping on every endpoint.** Reads, writes, analytics, ledger, reports, CSV and the festival plan all go through `AccessScope`. `SecurityIntegrationTest` calls the API directly as each role, so the UI is not trusted.
- **Auth.** bcrypt hashes; session-id rotation on login; HttpOnly + SameSite=Lax cookie; cookie-to-header CSRF with rotation on login. The real-HTTP login flow is tested on a random port.
- **XSS.** Every `innerHTML` template escapes user-editable fields (`esc()`); enums and dates come from the server.
- **Formulas.** Match the brief exactly, including tier boundaries (1.00 is HIGH, > 1.00 is EXPIRED) and ε. Money uses `BigDecimal` HALF_UP.
- **Ledger replay.** Month-end positions in reports equal `Σ quantity_delta ≤ T`, hand-checked for Sep / Oct / Nov.
- **Oversell protection.** 12 concurrent stock-outs against 100 units: exactly 6 succeed, and the ledger sum equals the balance.

## Accepted for the hackathon (with the production path)

| Item | Why it's acceptable now | Production path |
|---|---|---|
| No login rate limiting / lockout | Demo with seeded accounts | Bucket4j per username + IP, or SSO (OIDC) via the corporate IdP |
| Demo password shown on the sign-in screen | Only when `app.seed.enabled=true`; the endpoint returns nothing otherwise | Seed off in production; `APP_SECURITY_BOOTSTRAP_ADMIN_PASSWORD` for the first admin |
| Session cookie not `Secure` | Runs on local HTTP | `server.servlet.session.cookie.secure=true` behind TLS |
| H2 console reachable by HQ admin | Handy for the demo | Disable in a `prod` profile; move to Postgres + Flyway |
| Scheduler would run on every replica | Single instance; the unique `period` constraint prevents duplicate reports | ShedLock |
| Festival calendar and lunar dates are code config (to end 2027) | Clear, reviewed, unit-tested | HQ-managed calendar table with an admin screen |
| Festival plan is "extra on top of normal replenishment", not full MRP | Matches how stores pre-build for a festival | Feed into a forecasting / auto-replenishment service |
| Dashboard uses the configured velocity; reports use ledger-observed velocity | Documented in the README; each fits its purpose | Single demand-forecast source |
| No user-management UI | Users are seeded; roles are enforced server-side | HQ admin screen or IdP groups |
