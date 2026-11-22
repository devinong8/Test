# Product Inventory Management — Hackathon BE2

Regional HQ system that manages product inventory for 24 shops across 6 cities in 2 regions. It covers regions, SKU and shop master data, Stock-In / Stock-Out with storage and capital-budget limits, batch-level depreciation risk, and an automated **Monthly Management Report** with trends. Access is role-based: group HQ, regional HQ and shop manager.

**Stack:** Java 21 · Spring Boot 3.4 (Web, Data JPA, Validation, Security, Actuator, Scheduling) · H2 (file DB; JPA makes it swappable for Postgres) · Micrometer/Prometheus · vanilla JS + Chart.js front end (bundled locally, works offline).

| Acceptance criterion | Status | Where |
|---|---|---|
| (Mandatory) Elementary information management: SKU and shop | ✅ CRUD, validation, unique codes, soft-deactivate; shops belong to a **Region** (regional HQ) | `MasterDataService`, UI **Regions** / **SKUs** / **Shops** tabs |
| (Mandatory) On-going inventory management: Stock-In / Stock-Out, analysis and observability | ✅ Ledger, FEFO, limits, alerts, metrics, correlation ids | `InventoryService`, `AnalyticsService`, UI **Stock In / Out**, **Ledger**, **Inventory**, **Dashboard** |
| (Optional plus) Monthly management report: automated batch, valuation / turnover / depreciation across all shops, with trend | ✅ `@Scheduled` batch, point-in-time rebuild from the ledger, regional subtotals, CSV export | `ReportService`, `ReportScheduler`, UI **Monthly Reports** |

---

## Run it

```bash
mvn spring-boot:run              # or: mvn package && java -jar target/inventory-management-1.0.0.jar
open http://localhost:8080       # UI
```

On first start against an empty DB, `DataSeeder` creates 2 regions, 20 SKUs, 24 shops and 5 demo users, then simulates about 7 months of receipts, sales, damage and expiry write-offs (~20k ledger entries) **through the real `InventoryService`**, so every business rule is applied. It then back-fills 6 monthly reports so the trend view has history. This takes about 20 seconds once. To reset, delete `./data/`. **Delete it when upgrading from a build without regions**, because the schema changed. To turn seeding off, set `app.seed.enabled=false`.

### Demo logins

All seeded accounts use the password `demo1234` (set by `app.seed.demo-password`; stored as bcrypt). The sign-in screen lists them, and selecting one fills in the form.

| Username | Role | Sees and manages |
|---|---|---|
| `hq.admin` | **Group HQ** (`HQ_ADMIN`) | Everything. The only role that can edit regions and the SKU catalogue and run the monthly batch |
| `rhq.maritime` | **Regional HQ** (`REGION_MANAGER`), Singapore | Region SEA-MARITIME (SG, KL, JK, MN: 16 shops): its shops, limits, velocities, stock, reports |
| `rhq.mainland` | **Regional HQ** (`REGION_MANAGER`), Bangkok | Region SEA-MAINLAND (BK, HC: 8 shops) |
| `shop.sg01` / `shop.bk01` | **Shop manager** (`SHOP_MANAGER`) | One shop: dashboard, inventory, stock in/out, ledger, alerts. Read-only master data, no reports |

For a real deployment with seeding off, set `APP_SECURITY_BOOTSTRAP_ADMIN_PASSWORD` to create the first `hq.admin` on an empty database.

Other endpoints (HQ admin only, except health): `/h2-console` (JDBC URL `jdbc:h2:file:./data/inventory`), `/actuator/health`, `/actuator/prometheus`.

## Test it

```bash
mvn test                          # 42 tests: unit, integration, API and security
node e2e/screenshots.js           # with the app running: browser e2e + regenerates docs/screenshots
```

| Suite | What it proves |
|---|---|
| `InventoryFormulasTest` (18) | Each reference formula; **every R<sub>dep</sub> tier boundary** (0.5999 / 0.60 / 0.8499 / 0.85 / 1.00 inclusive in HIGH / 1.0001); provision rates; ε stops division by zero on empty inventory |
| `PortfolioAnalyzerTest` (2) | A fully hand-computed, two-shop worked example (V<sub>cost</sub>, V<sub>retail</sub>, COGS, turnover, provision, NRV, volume, utilisation) |
| `InventoryServiceIntegrationTest` (9) | Capacity and budget rejection (with max acceptable qty); expired-on-arrival; FEFO allocation; sales skip expired stock; write-off; batch ownership; **12 concurrent stock-outs never oversell** and the ledger always matches the balance |
| `ReportServiceIntegrationTest` (4) | Report rebuilds inventory **as at month-end from the ledger** (Sep / Oct / Nov checked by hand); regeneration is idempotent; future periods are rejected; scheduler targets the previous month; budget-aware reorder suggestion |
| `ApiIntegrationTest` (3) | REST contract: 201/400/404/422 problem responses, correlation-id propagation, ledger filters, analytics numbers, region subtotals, CSV, health, metrics |
| `SecurityIntegrationTest` (5) | **Role and row-level scoping**: anonymous → 401; group HQ sees both regions with subtotals that add up; a regional HQ sees only its region (shops, ledger, analytics, report totals, CSV) and can't open shops elsewhere, edit SKUs or run the batch; a shop manager sees and moves only its own shop; missing CSRF token → 403 |
| `LoginFlowHttpTest` (1) | The browser's real HTTP flow on a random port: XSRF cookie → bad password 401 → login → HttpOnly session cookie → API → logout → 401 |
| `e2e/screenshots.js` (23 checks) | Real browser, signed in as each role: sign-in screen and bad password; the shop manager's single-shop view with no Reports/Regions tabs; the regional HQ's region-only dashboard and report; group HQ dashboard, regions, tier filter, stock-in, oversell rejected, ledger, ≥ 6-month trend, dark theme, phone width, zero console errors |

Tests run against an in-memory DB with the clock fixed to `2026-11-22 10:00 SGT`, so they are deterministic.

---

## Where is the Regional HQ?

It is modelled explicitly at two levels:

1. **`Region`**: a regional HQ (code, name, HQ city). Every `Shop` belongs to exactly one region. The dashboard, Regions page and monthly report roll shops up into **regional subtotals**. Turnover is recomputed from summed COGS and cost, not averaged across shops.
2. **Roles (`AppUser`)**: *Group HQ* owns the SKU catalogue, the regions and the monthly batch. A *Regional HQ* user manages the shops, limits and velocities of its own region. A *Shop manager* runs one shop. Every API call is filtered to the caller's scope on the server (`CurrentUser` → `AccessScope`), not just hidden in the UI.

The SKU catalogue is intentionally group-wide (one price list), and shop limits are set by HQ. That matches the brief's "Regional HQ centrally manages product inventory".

## Architecture

```mermaid
flowchart LR
  subgraph Browser["Browser (static SPA)"]
    UI["index.html / app.js<br/>Dashboard · Inventory · Stock In/Out<br/>Ledger · SKUs · Shops · Reports"]
  end

  subgraph Boot["Spring Boot application"]
    F["CorrelationIdFilter<br/>X-Correlation-Id → MDC → ledger"]
    SEC["Spring Security<br/>session cookie · CSRF (cookie+header)<br/>@PreAuthorize roles · AccessScope rows"]
    subgraph Web["REST controllers  /api/**"]
      MC[MasterDataController]
      IC[InventoryController]
      AC[AnalyticsController]
    end
    subgraph Svc["Services"]
      MDS[MasterDataService]
      INV["InventoryService<br/>limits · FEFO · row locks"]
      ANS["AnalyticsService<br/>alerts · reorder"]
      RS["ReportService<br/>ledger replay as-of month-end"]
      SCH["ReportScheduler<br/>cron 0 0 1 1 * * (SGT)"]
    end
    CORE["analytics core (pure Java)<br/>InventoryFormulas · PortfolioAnalyzer"]
    EH["GlobalExceptionHandler<br/>RFC 7807 problem+json"]
    ACT["Actuator / Micrometer<br/>health · prometheus"]
  end

  DB[("H2 / JPA<br/>region · app_user · sku · shop · sales_velocity<br/>inventory_batch · stock_transaction<br/>monthly_report · monthly_report_line")]

  UI -->|JSON| F --> SEC --> Web
  MC --> MDS
  IC --> INV
  AC --> ANS & RS
  SCH --> RS
  ANS & RS --> CORE
  MDS & INV & ANS & RS --> DB
  INV -. counters .-> ACT
  RS -. timer .-> ACT
```

### Domain model

```mermaid
erDiagram
  REGION ||--o{ SHOP : "regional HQ runs"
  REGION ||--o{ APP_USER : "regional HQ staff"
  SHOP ||--o{ APP_USER : "shop manager"
  SHOP ||--o{ INVENTORY_BATCH : holds
  SKU  ||--o{ INVENTORY_BATCH : "of"
  SHOP ||--o{ SALES_VELOCITY : has
  SKU  ||--o{ SALES_VELOCITY : has
  INVENTORY_BATCH ||--o{ STOCK_TRANSACTION : "ledger (signed qty)"
  MONTHLY_REPORT ||--o{ MONTHLY_REPORT_LINE : "per shop"
  REGION { string code UK  string name  string hq_city }
  APP_USER { string username UK  string password_hash  enum role }
  SKU { string code UK  decimal purchase_price  decimal sale_price  decimal unit_volume_m3  int shelf_life_days }
  SHOP { string code UK  string city  decimal storage_capacity_m3  decimal capital_budget }
  INVENTORY_BATCH { date batch_production_date  long on_hand_qty  long version }
  STOCK_TRANSACTION { enum type  enum reason  long quantity_delta  long balance_after  decimal unit_cost  instant occurred_at  string correlation_id }
```

### Key design decisions

1. **Batch-level stock.** Depreciation depends on `batch_production_date`, so stock is held per (shop, SKU, batch) rather than as a single counter. This enables FEFO and per-batch provisions.
2. **Immutable, signed ledger.** Every movement is a `stock_transaction` (+in / −out) with `balance_after`. The balance at any moment is `Σ quantity_delta WHERE occurred_at ≤ t`, so the monthly report can be **regenerated for any closed month** from history, not just from a snapshot taken on the night.
3. **One calculation engine.** `PortfolioAnalyzer` is pure Java with no Spring or DB. The live dashboard and the monthly batch both call it, so the two always agree, and it is unit-testable with hand-computed numbers.
4. **Concurrency safety.** Stock-out takes `PESSIMISTIC_WRITE` locks on candidate batches (`SELECT … FOR UPDATE`), with `@Version` optimistic locking as a backstop (409 `CONCURRENT_UPDATE`). This is covered by a 12-thread test.
5. **Rules are checked before any write.** Capacity, budget, expired-on-arrival and insufficient stock are validated first. Failures return 422 with machine-readable `details` (for example `maxAcceptableQty`), and the UI shows them.
6. **Money uses `BigDecimal`** (scale 2, HALF_UP). Ratios use `double`.
7. **Time is injected through `Clock`** in Asia/Singapore, which makes tests deterministic and keeps the cron in SGT.
8. **Security in two layers.** Roles are checked with `@PreAuthorize` (for example, only group HQ edits SKUs). Row-level scope is enforced by `AccessScope` in every controller, covering reads, writes, analytics, ledger, reports and CSV. Report totals are **re-aggregated from the visible lines**, so a regional HQ never sees group totals.
9. **SPA auth done properly.** Session cookie (`HttpOnly`, `SameSite=Lax`), session-id rotation on login, CSRF via the `XSRF-TOKEN` cookie plus `X-XSRF-TOKEN` header (token rotated on login), bcrypt passwords, and JSON 401/403 problem responses instead of browser pop-ups.

### Formulas (from the brief) → code

| Brief | Implementation |
|---|---|
| V<sub>cost</sub> = Σ(on_hand_qty × purchase_price) | `InventoryFormulas.lineValue` summed in `PortfolioAnalyzer` |
| V<sub>retail</sub> = Σ(on_hand_qty × sale_price) | same |
| COGS<sub>30d</sub> = daily_sales_velocity × 30 × purchase_price | `InventoryFormulas.cogs30d`, summed per shop over its SKUs |
| Turnover = COGS<sub>30d</sub> / (V<sub>cost</sub> + ε), ε = 10⁻⁶ | `InventoryFormulas.turnoverRate` |
| Elapsed days = current date − batch_production_date | `InventoryFormulas.elapsedDays` |
| R<sub>dep</sub> = elapsed / shelf_life_days | `InventoryFormulas.shelfLifeConsumptionRatio` |
| Normal < 0.60 → 0% · Moderate 0.60–<0.85 → 25% · High 0.85–≤1.00 → 60% · Expired > 1.00 → 100% | `RiskTier.classify` / `RiskTier.provisionRate` |

**Velocity source.** The live dashboard uses each shop's configured historical `daily_sales_velocity` (editable in the Shops tab). Monthly reports use the velocity **observed in the ledger** (SALE units in the 30 days to month-end ÷ 30), so each month's turnover reflects what actually happened that month.

### Beyond the brief (analysis and observability)

- **Alerts:** expired, expiring (HIGH tier), stock-out, low cover (< 3 days), overstock (> 90 days), storage or budget ≥ 90%.
- **Reorder suggestions:** order up to 21 days of cover, **capped by the shop's remaining storage m³ and capital budget**. One click pre-fills Stock-In.
- **Headroom API:** `GET /api/inventory/headroom` returns the maximum receivable qty for a shop and SKU.
- **Net realisable cost** = V<sub>cost</sub> − provision. **Potential gross margin** = V<sub>retail</sub> − V<sub>cost</sub>.
- **Observability:** correlation id on every request and ledger row; structured request logs with latency; Prometheus counters `inventory_movements_units_total{type,reason}`; timer `inventory_report_generation`; health with DB details.

## REST API

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/auth/login` · `/api/auth/logout` | sign in / out (session cookie) |
| GET | `/api/auth/me` | current user, role and scope |
| GET/POST | `/api/regions` | list (scoped) / create region (group HQ) |
| GET/PUT/DELETE | `/api/regions/{id}` | read / update / deactivate (group HQ; blocked while active shops remain) |
| GET/POST | `/api/skus` | list / create SKU |
| GET/PUT/DELETE | `/api/skus/{id}` | read / update / deactivate (blocked while stock is on hand) |
| POST | `/api/skus/{id}/activate` | reactivate |
| GET/POST | `/api/shops?regionId=` | list (scoped, with live utilisation) / create shop (group HQ, or regional HQ in its own region) |
| GET/PUT/DELETE | `/api/shops/{id}` | read / update (capacity and budget can't go below current usage) / deactivate |
| GET | `/api/shops/{id}/velocities` | velocity, on hand and days of cover per SKU |
| PUT | `/api/shops/{id}/velocities/{skuId}` | set daily sales velocity |
| POST | `/api/inventory/stock-in` | `{shopId, skuId, quantity, batchProductionDate, batchNo?, reference?, note?}` |
| POST | `/api/inventory/stock-out` | `{shopId, skuId, quantity, reason, batchId?, reference?, note?}`; reason ∈ SALE, DAMAGE, EXPIRED_WRITE_OFF, TRANSFER_OUT, RETURN_TO_SUPPLIER, STOCKTAKE_ADJUSTMENT |
| GET | `/api/inventory/batches?shopId=` | batches with R<sub>dep</sub>, tier and provision |
| GET | `/api/inventory/headroom?shopId=&skuId=` | max receivable qty |
| GET | `/api/inventory/transactions?shopId=&skuId=&type=&page=&size=` | ledger (paged) |
| GET | `/api/analytics/portfolio?regionId=&includeBatches=` | total, **per-region** and per-shop valuation, turnover, depreciation (scoped) |
| GET | `/api/analytics/alerts?regionId=` | prioritised alerts with reorder qty (scoped) |
| GET | `/api/reports` | report history (trend) with regional subtotals. Group or regional HQ only |
| GET | `/api/reports/{yyyy-MM}` | report and per-shop lines |
| GET | `/api/reports/{yyyy-MM}/csv` | CSV export |
| POST | `/api/reports/generate?period=yyyy-MM` | manual run of the monthly batch |

Each role gets **only the data in its scope**: 401 when signed out, 403 when outside role or scope. Errors are RFC 7807 `application/problem+json` with `code`, `detail`, `correlationId`, plus `details` or `errors` where relevant.

## Screenshots

`docs/screenshots/` contains the sign-in screen, the dashboard per role (group HQ light/dark/mobile, regional HQ, shop manager), the Regions page, batch inventory, stock movements (success and rejected oversell), ledger, SKUs, shops with velocity, and monthly reports.

## Submission materials

- Source code: this repo
- Output screenshots: `docs/screenshots/`
- Prompts used with the LLM: [`docs/PROMPTS.md`](docs/PROMPTS.md)
- Architecture diagram: above (Mermaid renders on GitHub)
- 10-minute briefing notes: [`docs/BRIEFING.md`](docs/BRIEFING.md)
