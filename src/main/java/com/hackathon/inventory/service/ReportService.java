package com.hackathon.inventory.service;

import com.hackathon.inventory.analytics.InventoryFormulas;
import com.hackathon.inventory.analytics.PortfolioAnalyzer;
import com.hackathon.inventory.analytics.PortfolioMetrics;
import com.hackathon.inventory.analytics.Position;
import com.hackathon.inventory.analytics.ShopMetrics;
import com.hackathon.inventory.config.BusinessRuleException;
import com.hackathon.inventory.config.NotFoundException;
import com.hackathon.inventory.domain.*;
import com.hackathon.inventory.repository.InventoryBatchRepository;
import com.hackathon.inventory.repository.MonthlyReportRepository;
import com.hackathon.inventory.repository.StockTransactionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Monthly management report batch. Inventory is rebuilt as of the period's month-end by replaying the immutable
 * ledger, so a report can be (re)generated for any closed month and the trend stays consistent.
 */
@Service
public class ReportService {
    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    private final MonthlyReportRepository reports;
    private final StockTransactionRepository txns;
    private final InventoryBatchRepository batches;
    private final AnalyticsService analytics;
    private final MeterRegistry meters;
    private final Clock clock;

    public ReportService(MonthlyReportRepository reports, StockTransactionRepository txns,
                         InventoryBatchRepository batches, AnalyticsService analytics, MeterRegistry meters, Clock clock) {
        this.reports = reports; this.txns = txns; this.batches = batches; this.analytics = analytics;
        this.meters = meters; this.clock = clock;
    }

    /** Point-in-time positions at the given instant, reconstructed from the ledger. */
    @Transactional(readOnly = true)
    public List<Position> positionsAsOf(Instant asOf) {
        Map<Long, Long> qtyByBatch = new HashMap<>();
        for (Object[] row : txns.onHandByBatchAsOf(asOf)) qtyByBatch.put((Long) row[0], ((Number) row[1]).longValue());
        Map<Long, InventoryBatch> byId = batches.findAllById(qtyByBatch.keySet()).stream()
                .collect(Collectors.toMap(InventoryBatch::getId, Function.identity()));
        List<Position> out = new ArrayList<>();
        qtyByBatch.forEach((id, qty) -> {
            InventoryBatch b = byId.get(id);
            out.add(new Position(b.getShop(), b.getSku(), b.getId(), b.getBatchNo(), b.getBatchProductionDate(), qty));
        });
        return out;
    }

    /**
     * Historical daily_sales_velocity for a report = units actually sold (SALE stock-outs) in the 30 days up to the
     * as-of instant / 30, taken from the ledger. This keeps each month's COGS_30d and turnover true to that month
     * rather than re-using today's velocity setting.
     */
    @Transactional(readOnly = true)
    public Map<PortfolioAnalyzer.VelocityKey, BigDecimal> observedVelocity(Instant asOf) {
        Map<PortfolioAnalyzer.VelocityKey, BigDecimal> m = new HashMap<>();
        for (Object[] row : txns.soldUnits(asOf.minus(Duration.ofDays(InventoryFormulas.COGS_WINDOW_DAYS)), asOf))
            m.put(new PortfolioAnalyzer.VelocityKey((Long) row[0], (Long) row[1]),
                    BigDecimal.valueOf(((Number) row[2]).longValue())
                            .divide(BigDecimal.valueOf(InventoryFormulas.COGS_WINDOW_DAYS), 6, java.math.RoundingMode.HALF_UP));
        return m;
    }

    @Transactional
    public MonthlyReport generate(YearMonth period, String trigger) {
        YearMonth current = YearMonth.now(clock);
        if (period.isAfter(current))
            throw new BusinessRuleException("FUTURE_PERIOD", "Cannot report on a future period " + period);
        Timer.Sample sample = Timer.start(meters);

        ZoneId zone = clock.getZone();
        Instant asOf = period.equals(current) ? clock.instant()
                : period.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().minusNanos(1);
        LocalDate asOfDate = LocalDate.ofInstant(asOf, zone);
        PortfolioMetrics pm = analytics.analyze(positionsAsOf(asOf), asOfDate, observedVelocity(asOf));

        MonthlyReport r = reports.findByPeriod(period.toString()).orElseGet(MonthlyReport::new);
        r.setPeriod(period.toString());
        r.setAsOfDate(asOfDate);
        r.setGeneratedAt(clock.instant());
        r.setTrigger(trigger);
        r.setTotalCostValue(pm.costValue());
        r.setTotalRetailValue(pm.retailValue());
        r.setTotalCogs30d(pm.cogs30d());
        r.setTurnoverRate(pm.turnoverRate());
        r.setTotalProvision(pm.provision());
        r.setNetRealisableCost(pm.netRealisableCost());
        r.setNormalCost(pm.costByTier().get(RiskTier.NORMAL));
        r.setModerateCost(pm.costByTier().get(RiskTier.MODERATE));
        r.setHighCost(pm.costByTier().get(RiskTier.HIGH));
        r.setExpiredCost(pm.costByTier().get(RiskTier.EXPIRED));
        r.setShopCount(pm.shops().size());
        r.getLines().clear();
        for (ShopMetrics s : pm.shops()) {
            MonthlyReportLine l = new MonthlyReportLine();
            l.setReport(r);
            l.setShopId(s.shopId());
            l.setShopCode(s.shopCode());
            l.setShopName(s.shopName());
            l.setCity(s.city());
            l.setCostValue(s.costValue());
            l.setRetailValue(s.retailValue());
            l.setCogs30d(s.cogs30d());
            l.setTurnoverRate(s.turnoverRate());
            l.setProvision(s.provision());
            l.setNetRealisableCost(s.netRealisableCost());
            l.setExpiredCost(s.costByTier().get(RiskTier.EXPIRED));
            l.setHighRiskCost(s.costByTier().get(RiskTier.HIGH));
            l.setStorageUtilisation(s.storageUtilisation());
            l.setBudgetUtilisation(s.budgetUtilisation());
            r.getLines().add(l);
        }
        MonthlyReport saved = reports.save(r);
        sample.stop(meters.timer("inventory.report.generation", "trigger", trigger));
        log.info("Monthly report {} generated ({}): cost={} retail={} turnover={} provision={}", period, trigger,
                pm.costValue(), pm.retailValue(), String.format("%.3f", pm.turnoverRate()), pm.provision());
        return saved;
    }

    @Transactional(readOnly = true)
    public List<MonthlyReport> list() { return reports.findAllByOrderByPeriodAsc(); }

    @Transactional(readOnly = true)
    public MonthlyReport get(String period) {
        MonthlyReport r = reports.findByPeriod(period)
                .orElseThrow(() -> new NotFoundException("No report for period " + period));
        r.getLines().size(); // initialise lines inside the transaction
        return r;
    }

    /** Report rows with month-over-month deltas for the trend view. */
    @Transactional(readOnly = true)
    public String csv(String period) {
        MonthlyReport r = get(period);
        StringBuilder sb = new StringBuilder("period,shop_code,shop_name,city,cost_value,retail_value,cogs_30d," +
                "turnover_rate,provision,net_realisable_cost,high_risk_cost,expired_cost,storage_util,budget_util\n");
        for (MonthlyReportLine l : r.getLines()) {
            sb.append(String.join(",", r.getPeriod(), l.getShopCode(), quote(l.getShopName()), quote(l.getCity()),
                    l.getCostValue().toPlainString(), l.getRetailValue().toPlainString(), l.getCogs30d().toPlainString(),
                    String.format("%.4f", l.getTurnoverRate()), l.getProvision().toPlainString(),
                    l.getNetRealisableCost().toPlainString(), l.getHighRiskCost().toPlainString(),
                    l.getExpiredCost().toPlainString(), String.format("%.4f", l.getStorageUtilisation()),
                    String.format("%.4f", l.getBudgetUtilisation()))).append('\n');
        }
        return sb.toString();
    }

    private static String quote(String s) { return "\"" + (s == null ? "" : s.replace("\"", "\"\"")) + "\""; }
}
