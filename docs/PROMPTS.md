# Prompts used with the LLM

The solution was built with Claude Code (an agentic coding assistant). These are the prompts and steering instructions used, in order.

## 1. Initial prompt

> *(Attached: photos of the two brief pages "Hackathon Reviewing Questions – BE2: Product Inventory Management" and "(Cont.) Optional Reference Materials".)*
>
> This is hackathon day 2. Help me build an app to answer this. Include a front end. Also make sure you test.

## 2. Follow-up

> Continue

## 3. How the assistant broke the work down (its plan, reviewed by the team)

1. Read both pages of the brief and map them to the three acceptance criteria and the formula sheet.
2. Scaffold Spring Boot 3 / Java 21 / JPA / H2 / Actuator.
3. Model batch-level stock and an immutable signed ledger, so depreciation is per batch and month-end reports can be rebuilt from history.
4. Implement the formulas as a pure, unit-testable engine shared by the live dashboard and the batch report.
5. Stock-In checks storage m³ and capital budget. Stock-Out uses FEFO, blocks selling expired goods, and is concurrency-safe.
6. Add a monthly `@Scheduled` batch, manual trigger, CSV export and a trend view.
7. Seed a realistic region by simulating ~7 months through the real service layer.
8. Build a single-page front end with Chart.js (accessible palette, dark mode, mobile).
9. Write tests: formula boundaries, worked example, integration, concurrency, API contract, and browser e2e with screenshots.

## 4. Issues found while testing and the follow-up fixes

These came from running the tests and reviewing the screenshots:

- *"Charts are missing the last bars in the full-page screenshot"* → disabled chart animation so resizes render immediately.
- *"Page scrolls horizontally on a phone"* → nav and tier table made responsive; an e2e check now guards against it.
- *"Every monthly report shows the same COGS 30d"* → historical reports now derive velocity from the ledger (units sold in the 30 days to month-end), not today's setting.
- *"Seeder wrote today-20:00 sales, which are in the future"* → simulation stops at yesterday; e2e asserts the newest ledger row is the one just created.
- *"Seeding is slow / transaction rolled back on a limit breach"* → per-SKU transactions, and the seeder asks `maxReceivableQty` before receiving instead of catching exceptions.

## 5. Second iteration: making the Regional HQ explicit

> I see shop in the domain model, but where is the regional hq represented?

The assistant explained that HQ was only implicit (the system boundary) and offered three options: document it, add a `Region` entity with roll-ups, or add HQ / shop roles as well.

> All all

Result: a `Region` entity (regional HQ) above `Shop`, with regional subtotals in the dashboard, Regions page and monthly report. Spring Security with three roles (group HQ, regional HQ, shop manager) and server-side row-level scoping. SPA login with CSRF protection, plus security tests (MockMvc per role and a real-HTTP login flow) and per-role browser e2e.

Issues found while testing this iteration:
- *MockMvc didn't surface the `XSRF-TOKEN` cookie* → the real app was checked with curl (cookie present), and the login flow is now tested over real HTTP on a random port.
- *Loaders fired after sign-out and left a promise rejection unhandled* → routing is skipped while signed out and loader errors are caught.
- *The Region filter showed for single-region users* → the `hidden` attribute now always wins over component `display` rules.

## 6. Third iteration: China-relevant data

> What data are we using? Make it realistic relatable to china

The assistant explained that the seed was a synthetic Southeast-Asian dataset (SGD, Singapore time), then rebuilt it for China:
- **Regions and cities:** 4 regional HQs (华东 Shanghai, 华南 Guangzhou, 华北 Beijing, 西南 Chengdu) and 11 cities × 3 district shops = 33 shops.
- **Products and prices:** 20 generic Chinese convenience-store SKUs with bilingual names, priced in RMB.
- **Locale:** Asia/Shanghai time zone, ¥ formatting with 万/亿 on chart axes, and Chinese fonts in the UI.
- **Seasonal events:** the Mid-Autumn mooncake cycle (over-order → post-festival returns, write-offs or expired stock) and Double 11 demand.
- **Alerts:** now ranked by value at risk, so the mooncake write-off leads the alert list.
