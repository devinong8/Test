package com.hackathon.inventory.service;

import com.hackathon.inventory.analytics.FestivalCalendar;
import com.hackathon.inventory.analytics.FestivalCalendar.Festival;
import com.hackathon.inventory.analytics.FestivalCalendar.Uplift;
import com.hackathon.inventory.analytics.InventoryFormulas;
import com.hackathon.inventory.domain.*;
import com.hackathon.inventory.repository.*;
import com.hackathon.inventory.web.dto.FestivalPlan;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 节日备货: festival pre-stock recommendations per shop.
 * <ul>
 *   <li>Regular SKUs: extra units = velocity x (factor - 1) x window days, on top of normal replenishment.</li>
 *   <li>One-off seasonal SKUs (平安果): units = seasonal daily units x shop traffic index x window days, minus usable
 *       stock, then scaled by the region's sell-through on the last seasonal one-off (月饼), learned from the ledger.
 *       The mooncake overstock is not repeated.</li>
 *   <li>Each line is capped by the shop's remaining storage m3 and capital budget, which are consumed in turn by
 *       the event's lines.</li>
 *   <li>Receive window: late enough that stock is still NORMAL tier (R_dep &lt; 0.60) on the festival's last day.</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class FestivalPlanner {
    static final String LESSON_SKU = "MOON-GIFT";

    private final ShopRepository shops;
    private final SkuRepository skus;
    private final SalesVelocityRepository velocities;
    private final InventoryBatchRepository batches;
    private final StockTransactionRepository txns;
    private final RegionRepository regions;
    private final Clock clock;

    public FestivalPlanner(ShopRepository shops, SkuRepository skus, SalesVelocityRepository velocities,
                           InventoryBatchRepository batches, StockTransactionRepository txns, RegionRepository regions,
                           Clock clock) {
        this.shops = shops; this.skus = skus; this.velocities = velocities; this.batches = batches; this.txns = txns;
        this.regions = regions; this.clock = clock;
    }

    /** @param shopIds caller's scope (null = all shops) */
    public FestivalPlan plan(int horizonDays, Set<Long> shopIds) {
        LocalDate today = LocalDate.now(clock);
        List<Shop> scopedShops = shops.findAllByOrderByCodeAsc().stream().filter(Shop::isActive)
                .filter(s -> shopIds == null || shopIds.contains(s.getId())).toList();
        Map<String, Sku> skuByCode = skus.findAll().stream().filter(Sku::isActive)
                .collect(Collectors.toMap(Sku::getCode, s -> s));

        // velocity per shop/sku, and a traffic index per shop (its total daily units vs the group average)
        Map<Long, Map<Long, Double>> vel = new HashMap<>();
        for (SalesVelocity v : velocities.findAll())
            vel.computeIfAbsent(v.getShop().getId(), k -> new HashMap<>())
                    .put(v.getSku().getId(), v.getDailySalesVelocity().doubleValue());
        double avgTraffic = vel.values().stream().mapToDouble(m -> m.values().stream().mapToDouble(d -> d).sum())
                .average().orElse(1);

        // shown: the caller's own scope; used for sizing: the whole region, so every role gets the same plan
        List<FestivalPlan.SeasonalLesson> lessons = lessons(scopedShops);
        List<Shop> allActive = shops.findAll().stream().filter(Shop::isActive).toList();
        Map<String, Double> sellThrough = lessons(allActive).stream().filter(l -> l.received() > 0)
                .collect(Collectors.toMap(FestivalPlan.SeasonalLesson::regionCode, FestivalPlan.SeasonalLesson::sellThrough));

        List<FestivalPlan.Event> events = new ArrayList<>();
        for (Festival f : FestivalCalendar.EVENTS) {
            long daysAway = ChronoUnit.DAYS.between(today, f.date());
            if (f.windowEnd().isBefore(today) || daysAway > horizonDays) continue;
            List<FestivalPlan.Line> lines = new ArrayList<>();
            for (Shop shop : scopedShops) {
                Map<Long, Double> shopVel = vel.getOrDefault(shop.getId(), Map.of());
                double traffic = avgTraffic <= 0 ? 1 : shopVel.values().stream().mapToDouble(d -> d).sum() / avgTraffic;
                BigDecimal freeVol = shop.getStorageCapacityM3().subtract(batches.usedVolume(shop.getId())).max(BigDecimal.ZERO);
                BigDecimal freeBudget = shop.getCapitalBudget().subtract(batches.costValue(shop.getId())).max(BigDecimal.ZERO);
                for (Uplift u : f.uplifts()) {
                    Sku sku = skuByCode.get(u.skuCode());
                    String region = shop.getRegion().getCode();
                    if (sku == null || !u.appliesTo(region) || !shopVel.containsKey(sku.getId())) continue; // not ranged here
                    Line l = line(f, shop, sku, u, shopVel.get(sku.getId()), traffic,
                            sellThrough.getOrDefault(region, 1.0), today, freeVol, freeBudget);
                    if (l == null) continue;
                    freeVol = freeVol.subtract(sku.getUnitVolumeM3().multiply(BigDecimal.valueOf(l.qty))).max(BigDecimal.ZERO);
                    freeBudget = freeBudget.subtract(sku.getPurchasePrice().multiply(BigDecimal.valueOf(l.qty))).max(BigDecimal.ZERO);
                    lines.add(l.view);
                }
            }
            lines.sort(Comparator.comparing(FestivalPlan.Line::cost).reversed());
            events.add(new FestivalPlan.Event(f.code(), f.name(), f.date(), f.windowStart(), f.windowEnd(), daysAway,
                    f.story(), lines.stream().mapToLong(FestivalPlan.Line::recommendedQty).sum(),
                    lines.stream().map(FestivalPlan.Line::cost).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add), lines));
        }
        return new FestivalPlan(today, lessons, events);
    }

    private record Line(long qty, FestivalPlan.Line view) {}

    private Line line(Festival f, Shop shop, Sku sku, Uplift u, double velocity, double traffic, double sellThrough,
                      LocalDate today, BigDecimal freeVol, BigDecimal freeBudget) {
        int days = f.windowDays();
        boolean seasonal = u.seasonalDailyUnits() > 0;
        double baseline = seasonal ? u.seasonalDailyUnits() * traffic : velocity;
        long expected = (long) Math.ceil(seasonal ? baseline * days : velocity * u.factor() * days);
        String note;
        long want;
        if (seasonal) {
            // stock that will still be sellable (not expired) on the festival's last day counts towards the plan
            long usable = batches.findByShopIdAndSkuIdAndOnHandQtyGreaterThan(shop.getId(), sku.getId(), 0).stream()
                    .filter(b -> !b.getBatchProductionDate().plusDays(sku.getShelfLifeDays()).isBefore(f.windowEnd()))
                    .mapToLong(InventoryBatch::getOnHandQty).sum();
            want = (long) Math.ceil(Math.max(0, expected - usable) * Math.min(1.0, sellThrough));
            note = String.format("One-off seasonal. Sized to %.0f%% of forecast: last %s this region sold only %.0f%%.",
                    Math.min(1.0, sellThrough) * 100, "中秋 mooncake season", sellThrough * 100);
        } else {
            want = (long) Math.ceil(velocity * (u.factor() - 1) * days);
            note = String.format("Normal %.1f/day x %.1f for %d days; this is the extra on top of usual replenishment.",
                    velocity, u.factor(), days);
        }
        if (want <= 0) return null;

        long byVol = freeVol.divide(sku.getUnitVolumeM3(), 0, RoundingMode.FLOOR).longValue();
        long byBudget = sku.getPurchasePrice().signum() == 0 ? Long.MAX_VALUE
                : freeBudget.divide(sku.getPurchasePrice(), 0, RoundingMode.FLOOR).longValue();
        long qty = Math.min(want, Math.min(byVol, byBudget));
        String cappedBy = qty >= want ? null : byVol <= byBudget ? "STORAGE" : "BUDGET";
        if (cappedBy != null) note += " Capped by " + cappedBy.toLowerCase() + " headroom (wanted " + want + ").";
        BigDecimal cost = InventoryFormulas.lineValue(qty, sku.getPurchasePrice());
        return new Line(qty, new FestivalPlan.Line(shop.getId(), shop.getCode(), shop.getName(), shop.getRegion().getCode(),
                sku.getId(), sku.getCode(), sku.getName(), Math.round(baseline * 10) / 10.0, seasonal ? 0 : u.factor(),
                expected, qty, cappedBy, cost, FestivalCalendar.orderFrom(f, sku.getShelfLifeDays(), today),
                FestivalCalendar.orderBy(f), note));
    }

    /** Ledger post-mortem of the last one-off seasonal product (月饼), over the given shops, rolled up by region. */
    List<FestivalPlan.SeasonalLesson> lessons(List<Shop> shopsInScope) {
        Sku sku = skus.findAll().stream().filter(s -> s.getCode().equals(LESSON_SKU)).findFirst().orElse(null);
        if (sku == null) return List.of();
        Map<Long, Long> regionOfShop = shopsInScope.stream()
                .collect(Collectors.toMap(Shop::getId, s -> s.getRegion().getId()));
        Map<Long, long[]> agg = new TreeMap<>();   // received, sold, returned, writtenOff, net
        for (Object[] row : txns.movementSummaryByShop(LESSON_SKU)) {
            Long regionId = regionOfShop.get((Long) row[0]);
            if (regionId == null) continue;
            long qty = ((Number) row[3]).longValue();
            long[] a = agg.computeIfAbsent(regionId, k -> new long[5]);
            a[4] += qty;
            if (row[1] == TransactionType.STOCK_IN) a[0] += qty;
            else if (row[2] == StockOutReason.SALE) a[1] -= qty;
            else if (row[2] == StockOutReason.RETURN_TO_SUPPLIER) a[2] -= qty;
            else if (row[2] == StockOutReason.EXPIRED_WRITE_OFF) a[3] -= qty;
        }
        List<FestivalPlan.SeasonalLesson> out = new ArrayList<>();
        agg.forEach((id, a) -> {
            Region r = regions.findById(id).orElseThrow();
            long unsold = a[0] - a[1];
            out.add(new FestivalPlan.SeasonalLesson(r.getCode(), r.getName(), LESSON_SKU, a[0], a[1], a[2], a[3], a[4],
                    a[0] == 0 ? 1.0 : (double) a[1] / a[0], InventoryFormulas.lineValue(unsold, sku.getPurchasePrice())));
        });
        out.sort(Comparator.comparing(FestivalPlan.SeasonalLesson::regionCode));
        return out;
    }
}
