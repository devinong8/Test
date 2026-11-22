# 10-minute tech-to-tech briefing: suggested flow

| Min | Topic | Show |
|---|---|---|
| 0–1 | Problem and scope: 2 regional HQs, 24 shops, 6 cities, 20 SKUs; the 3 acceptance criteria all delivered | README table |
| 1–3 | Architecture: layered Spring Boot; **Region → Shop → Batch** model; **immutable signed ledger**; one pure calculation engine shared by dashboard and batch; security layer (roles + row scope) | README Mermaid diagrams |
| 3–5 | Live demo as `hq.admin`: Regional HQ breakdown and region filter, then Dashboard KPIs (V<sub>cost</sub>, V<sub>retail</sub>, COGS, turnover, provision), tier bar, alerts. Click **Reorder** → Stock-In pre-filled, with headroom shown | UI |
| 5–6 | Rules demo: oversell → 422 `INSUFFICIENT_STOCK`; over capacity → `STORAGE_CAPACITY_EXCEEDED` with `maxAcceptableQty`; selling expired stock is blocked; FEFO allocation shown in the result | Stock In / Out tab, Ledger tab |
| 6–8 | Monthly report: cron `0 0 1 1 * *` SGT → `ReportService` replays the ledger to month-end, so any closed month can be regenerated. Trend charts, MoM, CSV | Reports tab |
| 8–9 | Quality: 42 JUnit tests (tier boundaries, hand-computed example, 12-thread no-oversell test, API contract, role/scope security suite, real-HTTP login flow) + 23 browser checks | `mvn test` output |
| 9–10 | Roles in 30 seconds: sign out → `shop.sg01` (one shop, no Reports tab) → `rhq.mainland` (region only). Then observability and next steps | Sign-in screen, `/actuator/prometheus` |

## Likely reviewer questions

- **Where is the Regional HQ in the model?** `Region` is an entity (code, name, HQ city) and every shop belongs to one. Regional subtotals appear on the dashboard, the Regions page and the monthly report. A `REGION_MANAGER` user *is* the regional HQ: it manages its region's shops and sees only its region's data. Group HQ owns the shared SKU catalogue and the monthly batch.
- **Is the scoping only in the UI?** No. Every controller filters through `AccessScope` on the server, and report totals are re-aggregated from the lines the caller may see. `SecurityIntegrationTest` hits the API directly as each role and asserts 403s and filtered totals.
- **Why session cookies, not JWT?** It's a same-origin SPA. An HttpOnly cookie can't be read by JavaScript (XSS can't steal it), logout is server-side, and CSRF is handled with the cookie-to-header token pattern.
- **Why store batches, not just a quantity per SKU?** R<sub>dep</sub> is defined per `batch_production_date`. One counter can't tell fresh units from nearly expired ones, so FEFO and provisioning would both be wrong.
- **How do you avoid overselling under concurrency?** `SELECT … FOR UPDATE` on candidate batches inside the stock-out transaction, plus `@Version`. A test fires 12 parallel stock-outs of 15 against 100 units: exactly 6 succeed, the balance is 10, and the ledger sum equals 10.
- **How is the monthly report reproducible?** Positions as of `T` = `Σ quantity_delta WHERE occurred_at ≤ T` per batch. Tiers use the month-end date, and velocity is observed from SALE entries in the 30 days before `T`.
- **Why ε?** It is from the brief: it avoids divide-by-zero when a shop has sold through all its stock. The tile and table still show a finite (very high) turnover, and tests assert this.
- **Production hardening next:** Postgres + Flyway migrations, ShedLock so the cron runs once across replicas, SSO (OIDC) instead of local passwords plus a user-admin screen, login rate-limiting and an audit trail of who did what (ledger rows already carry a correlation id), inter-shop transfers as one atomic out+in, and demand forecasting to replace static velocity.
