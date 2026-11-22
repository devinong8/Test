# 10-minute tech-to-tech briefing: suggested flow

| Min | Topic | Show |
|---|---|---|
| 0–1 | Problem and scope: 24 shops, 6 cities, 20 SKUs; the 3 acceptance criteria all delivered | README table |
| 1–3 | Architecture: layered Spring Boot; **batch-level stock + immutable signed ledger**; one pure calculation engine shared by dashboard and batch | README Mermaid diagram |
| 3–5 | Live demo: Dashboard KPIs (V<sub>cost</sub>, V<sub>retail</sub>, COGS, turnover, provision), tier bar, alerts. Click **Reorder** → Stock-In pre-filled, with headroom shown | UI |
| 5–6 | Rules demo: oversell → 422 `INSUFFICIENT_STOCK`; over capacity → `STORAGE_CAPACITY_EXCEEDED` with `maxAcceptableQty`; selling expired stock is blocked; FEFO allocation shown in the result | Stock In / Out tab, Ledger tab |
| 6–8 | Monthly report: cron `0 0 1 1 * *` SGT → `ReportService` replays the ledger to month-end, so any closed month can be regenerated. Trend charts, MoM, CSV | Reports tab |
| 8–9 | Quality: 36 JUnit tests (tier boundaries, hand-computed example, 12-thread no-oversell test, API contract) + browser e2e | `mvn test` output |
| 9–10 | Observability and next steps | `/actuator/prometheus`, correlation id in ledger |

## Likely reviewer questions

- **Why store batches, not just a quantity per SKU?** R<sub>dep</sub> is defined per `batch_production_date`. One counter can't tell fresh units from nearly expired ones, so FEFO and provisioning would both be wrong.
- **How do you avoid overselling under concurrency?** `SELECT … FOR UPDATE` on candidate batches inside the stock-out transaction, plus `@Version`. A test fires 12 parallel stock-outs of 15 against 100 units: exactly 6 succeed, the balance is 10, and the ledger sum equals 10.
- **How is the monthly report reproducible?** Positions as of `T` = `Σ quantity_delta WHERE occurred_at ≤ T` per batch. Tiers use the month-end date, and velocity is observed from SALE entries in the 30 days before `T`.
- **Why ε?** It is from the brief: it avoids divide-by-zero when a shop has sold through all its stock. The tile and table still show a finite (very high) turnover, and tests assert this.
- **Production hardening next:** Postgres + Flyway migrations, ShedLock so the cron runs once across replicas, authentication (Spring Security, HQ vs shop roles), inter-shop transfers as one atomic out+in, and demand forecasting to replace static velocity.
