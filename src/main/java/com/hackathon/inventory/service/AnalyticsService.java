package com.hackathon.inventory.service;

import com.hackathon.inventory.analytics.*;
import com.hackathon.inventory.analytics.PortfolioAnalyzer.VelocityKey;
import com.hackathon.inventory.domain.*;
import com.hackathon.inventory.repository.*;
import com.hackathon.inventory.web.dto.Alert;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Live inventory analytics (valuation, turnover, depreciation) and actionable alerts. */
@Service
@Transactional(readOnly = true)
public class AnalyticsService {
    /** Replenishment target in days of cover used for reorder suggestions. */
    static final int TARGET_COVER_DAYS = 21;
    static final int LOW_COVER_DAYS = 3;
    static final int OVERSTOCK_COVER_DAYS = 90;

    private final InventoryBatchRepository batches;
    private final SalesVelocityRepository velocities;
    private final ShopRepository shops;
    private final SkuRepository skus;
    private final Clock clock;

    public AnalyticsService(InventoryBatchRepository batches, SalesVelocityRepository velocities, ShopRepository shops,
                            SkuRepository skus, Clock clock) {
        this.batches = batches; this.velocities = velocities; this.shops = shops; this.skus = skus; this.clock = clock;
    }

    public PortfolioMetrics portfolio() { return portfolio(null); }

    /** Live metrics restricted to the given shops (null = every shop). */
    public PortfolioMetrics portfolio(Set<Long> shopIds) {
        return analyze(batches.findInStock(null).stream().map(AnalyticsService::toPosition).toList(),
                LocalDate.now(clock), velocityMap(), shopIds);
    }

    /** Runs the shared analyzer over an arbitrary set of positions (live or point-in-time). */
    public PortfolioMetrics analyze(List<Position> positions, LocalDate asOf) {
        return analyze(positions, asOf, velocityMap(), null);
    }

    public PortfolioMetrics analyze(List<Position> positions, LocalDate asOf, Map<VelocityKey, BigDecimal> vel,
                                    Set<Long> shopIds) {
        Map<Long, Sku> skuMap = skus.findAll().stream().collect(Collectors.toMap(Sku::getId, Function.identity()));
        List<Position> scoped = shopIds == null ? positions
                : positions.stream().filter(p -> shopIds.contains(p.shop().getId())).toList();
        Map<VelocityKey, BigDecimal> scopedVel = shopIds == null ? vel : vel.entrySet().stream()
                .filter(e -> shopIds.contains(e.getKey().shopId()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        Set<Long> shopsWithStock = scoped.stream().map(p -> p.shop().getId()).collect(Collectors.toSet());
        List<Shop> shopList = shops.findAll().stream()
                .filter(s -> shopIds == null || shopIds.contains(s.getId()))
                .filter(s -> s.isActive() || shopsWithStock.contains(s.getId())).toList();
        return PortfolioAnalyzer.analyze(shopList, scoped, scopedVel, skuMap, asOf);
    }

    public Map<VelocityKey, BigDecimal> velocityMap() {
        Map<VelocityKey, BigDecimal> m = new HashMap<>();
        velocities.findAll().forEach(v -> m.put(new VelocityKey(v.getShop().getId(), v.getSku().getId()),
                v.getDailySalesVelocity()));
        return m;
    }

    public static Position toPosition(InventoryBatch b) {
        return new Position(b.getShop(), b.getSku(), b.getId(), b.getBatchNo(), b.getBatchProductionDate(), b.getOnHandQty());
    }

    /** Alerts: expiry risk, stock-out / low cover (with capacity & budget-aware reorder qty), overstock, limits. */
    public List<Alert> alerts() { return alerts(null); }

    public List<Alert> alerts(Set<Long> shopIds) {
        PortfolioMetrics pm = portfolio(shopIds);
        List<Alert> alerts = new ArrayList<>();

        for (BatchRisk b : pm.batches()) {
            if (b.tier() == RiskTier.EXPIRED)
                alerts.add(new Alert("CRITICAL", "EXPIRED", b.shopId(), b.shopCode(), b.skuId(), b.skuCode(),
                        String.format("%d units of batch %s expired %s ago - write off (provision ¥%s)",
                                b.onHandQty(), b.batchNo(), days(-b.daysToExpiry()), b.provision()), null, b.costValue()));
            else if (b.tier() == RiskTier.HIGH)
                alerts.add(new Alert("HIGH", "EXPIRING", b.shopId(), b.shopCode(), b.skuId(), b.skuCode(),
                        String.format("%d units of batch %s %s (R_dep %.2f) - markdown / transfer",
                                b.onHandQty(), b.batchNo(), b.daysToExpiry() == 0 ? "expire today" : "expire in " + days(b.daysToExpiry()), b.rDep()), null, b.costValue()));
        }

        Map<Long, ShopMetrics> shopMetrics = pm.shops().stream()
                .collect(Collectors.toMap(ShopMetrics::shopId, Function.identity()));
        Map<VelocityKey, Long> onHand = new HashMap<>();
        for (BatchRisk b : pm.batches())
            if (b.tier() != RiskTier.EXPIRED) onHand.merge(new VelocityKey(b.shopId(), b.skuId()), b.onHandQty(), Long::sum);

        for (SalesVelocity v : velocities.findAll()) {
            double vel = v.getDailySalesVelocity().doubleValue();
            if (vel <= 0 || !v.getShop().isActive() || !v.getSku().isActive()) continue;
            if (shopIds != null && !shopIds.contains(v.getShop().getId())) continue;
            VelocityKey key = new VelocityKey(v.getShop().getId(), v.getSku().getId());
            long qty = onHand.getOrDefault(key, 0L);
            double cover = qty / vel;
            ShopMetrics sm = shopMetrics.get(v.getShop().getId());
            if (cover < LOW_COVER_DAYS) {
                long suggested = suggestReorder(v.getSku(), vel, qty, sm);
                alerts.add(new Alert(qty == 0 ? "HIGH" : "MEDIUM", qty == 0 ? "STOCK_OUT" : "LOW_STOCK",
                        key.shopId(), v.getShop().getCode(), key.skuId(), v.getSku().getCode(),
                        String.format("%d sellable units = %.1f days of cover at %.1f/day", qty, cover, vel), suggested));
            } else if (cover > OVERSTOCK_COVER_DAYS) {
                alerts.add(new Alert("LOW", "OVERSTOCK", key.shopId(), v.getShop().getCode(), key.skuId(),
                        v.getSku().getCode(), String.format("%.0f days of cover ties up capital - consider transfer", cover), null));
            }
        }

        for (ShopMetrics s : pm.shops()) {
            if (s.storageUtilisation() >= 0.9)
                alerts.add(new Alert("MEDIUM", "STORAGE_NEAR_LIMIT", s.shopId(), s.shopCode(), null, null,
                        String.format("Storage %.1f%% used", s.storageUtilisation() * 100), null));
            if (s.budgetUtilisation() >= 0.9)
                alerts.add(new Alert("MEDIUM", "BUDGET_NEAR_LIMIT", s.shopId(), s.shopCode(), null, null,
                        String.format("Capital budget %.1f%% used", s.budgetUtilisation() * 100), null));
        }
        List<String> order = List.of("CRITICAL", "HIGH", "MEDIUM", "LOW");
        // most severe first; within a severity, the most money at stake first
        alerts.sort(Comparator.comparing((Alert a) -> order.indexOf(a.severity()))
                .thenComparing(Alert::valueAtRisk, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Alert::shopCode));
        return alerts;
    }

    private static String days(long n) { return n + (n == 1 ? " day" : " days"); }

    /** Order up to TARGET_COVER_DAYS, capped by the shop's remaining storage and capital budget. */
    static long suggestReorder(Sku sku, double velocity, long onHand, ShopMetrics sm) {
        long want = (long) Math.ceil(velocity * TARGET_COVER_DAYS) - onHand;
        if (want <= 0) return 0;
        if (sm == null) return want;
        BigDecimal freeVol = sm.storageCapacityM3().subtract(sm.usedVolumeM3()).max(BigDecimal.ZERO);
        BigDecimal freeBudget = sm.capitalBudget().subtract(sm.costValue()).max(BigDecimal.ZERO);
        long byVol = freeVol.divide(sku.getUnitVolumeM3(), 0, RoundingMode.FLOOR).longValue();
        long byBudget = sku.getPurchasePrice().signum() == 0 ? want
                : freeBudget.divide(sku.getPurchasePrice(), 0, RoundingMode.FLOOR).longValue();
        return Math.max(0, Math.min(want, Math.min(byVol, byBudget)));
    }
}
