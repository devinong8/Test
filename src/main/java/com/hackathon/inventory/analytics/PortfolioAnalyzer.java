package com.hackathon.inventory.analytics;

import com.hackathon.inventory.domain.RiskTier;
import com.hackathon.inventory.domain.Shop;
import com.hackathon.inventory.domain.Sku;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

import static com.hackathon.inventory.analytics.InventoryFormulas.*;

/**
 * Pure aggregation engine shared by live analytics and the monthly report batch, so both always agree.
 * Input: batch positions, the shop list, and daily sales velocity per (shop, sku).
 */
public final class PortfolioAnalyzer {

    public record VelocityKey(Long shopId, Long skuId) {}

    private PortfolioAnalyzer() {}

    public static BatchRisk assess(Position p, LocalDate asOf) {
        Sku sku = p.sku();
        Shop shop = p.shop();
        long elapsed = elapsedDays(asOf, p.productionDate());
        double rDep = shelfLifeConsumptionRatio(elapsed, sku.getShelfLifeDays());
        RiskTier tier = RiskTier.classify(rDep);
        BigDecimal cost = lineValue(p.qty(), sku.getPurchasePrice());
        BigDecimal retail = lineValue(p.qty(), sku.getSalePrice());
        LocalDate expiry = p.productionDate().plusDays(sku.getShelfLifeDays());
        return new BatchRisk(p.batchId(), p.batchNo(), shop.getId(), shop.getCode(), shop.getName(), shop.getCity(),
                sku.getId(), sku.getCode(), sku.getName(), sku.getCategory(), p.productionDate(), expiry,
                sku.getShelfLifeDays(), elapsed, sku.getShelfLifeDays() - elapsed, rDep, tier, p.qty(), cost, retail,
                provision(cost, tier));
    }

    public static PortfolioMetrics analyze(Collection<Shop> shops, Collection<Position> positions,
                                           Map<VelocityKey, BigDecimal> velocity, Map<Long, Sku> skus,
                                           LocalDate asOf) {
        Map<Long, List<BatchRisk>> byShop = new HashMap<>();
        List<BatchRisk> all = new ArrayList<>();
        for (Position p : positions) {
            if (p.qty() <= 0) continue;
            BatchRisk r = assess(p, asOf);
            all.add(r);
            byShop.computeIfAbsent(r.shopId(), k -> new ArrayList<>()).add(r);
        }

        Map<Long, BigDecimal> cogsByShop = new HashMap<>();
        velocity.forEach((k, v) -> {
            Sku sku = skus.get(k.skuId());
            if (sku == null || v == null) return;
            cogsByShop.merge(k.shopId(), cogs30d(v, sku.getPurchasePrice()), BigDecimal::add);
        });

        List<ShopMetrics> shopMetrics = new ArrayList<>();
        for (Shop shop : shops) {
            List<BatchRisk> rs = byShop.getOrDefault(shop.getId(), List.of());
            BigDecimal cost = sum(rs, BatchRisk::costValue);
            BigDecimal retail = sum(rs, BatchRisk::retailValue);
            BigDecimal prov = sum(rs, BatchRisk::provision);
            BigDecimal cogs = cogsByShop.getOrDefault(shop.getId(), BigDecimal.ZERO);
            BigDecimal volume = BigDecimal.ZERO;
            long units = 0;
            Set<Long> skuIds = new HashSet<>();
            for (BatchRisk r : rs) {
                volume = volume.add(skus.get(r.skuId()).getUnitVolumeM3().multiply(BigDecimal.valueOf(r.onHandQty())));
                units += r.onHandQty();
                skuIds.add(r.skuId());
            }
            volume = volume.setScale(3, RoundingMode.HALF_UP);
            shopMetrics.add(new ShopMetrics(shop.getId(), shop.getCode(), shop.getName(), shop.getCity(),
                    shop.getRegion().getId(), shop.getRegion().getCode(), shop.getRegion().getName(), units,
                    skuIds.size(), cost, retail, cogs, turnoverRate(cogs, cost), prov, cost.subtract(prov),
                    tierBreakdown(rs), volume, shop.getStorageCapacityM3(), ratio(volume, shop.getStorageCapacityM3()),
                    shop.getCapitalBudget(), ratio(cost, shop.getCapitalBudget())));
        }
        shopMetrics.sort(Comparator.comparing(ShopMetrics::shopCode));

        BigDecimal cost = sum(all, BatchRisk::costValue);
        BigDecimal retail = sum(all, BatchRisk::retailValue);
        BigDecimal prov = sum(all, BatchRisk::provision);
        BigDecimal cogs = shopMetrics.stream().map(ShopMetrics::cogs30d).reduce(BigDecimal.ZERO, BigDecimal::add);
        long units = all.stream().mapToLong(BatchRisk::onHandQty).sum();
        all.sort(Comparator.comparingDouble(BatchRisk::rDep).reversed());
        return new PortfolioMetrics(asOf, units, cost, retail, retail.subtract(cost), cogs, turnoverRate(cogs, cost),
                prov, cost.subtract(prov), tierBreakdown(all), rollUpRegions(shops, shopMetrics), shopMetrics, all);
    }

    /** Regional subtotals: sums of their shops; turnover recomputed from the sums (not averaged). */
    static List<RegionMetrics> rollUpRegions(Collection<Shop> shops, List<ShopMetrics> shopMetrics) {
        Map<Long, List<ShopMetrics>> byRegion = new TreeMap<>();
        shopMetrics.forEach(m -> byRegion.computeIfAbsent(m.regionId(), k -> new ArrayList<>()).add(m));
        List<RegionMetrics> out = new ArrayList<>();
        byRegion.forEach((id, ms) -> {
            BigDecimal cost = sumS(ms, ShopMetrics::costValue), retail = sumS(ms, ShopMetrics::retailValue);
            BigDecimal cogs = sumS(ms, ShopMetrics::cogs30d), prov = sumS(ms, ShopMetrics::provision);
            BigDecimal used = sumS(ms, ShopMetrics::usedVolumeM3), cap = sumS(ms, ShopMetrics::storageCapacityM3);
            BigDecimal budget = sumS(ms, ShopMetrics::capitalBudget);
            Map<RiskTier, BigDecimal> tiers = new EnumMap<>(RiskTier.class);
            for (RiskTier t : RiskTier.values()) tiers.put(t, ms.stream().map(m -> m.costByTier().get(t))
                    .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add));
            ShopMetrics first = ms.get(0);
            out.add(new RegionMetrics(id, first.regionCode(), first.regionName(), ms.size(),
                    ms.stream().mapToLong(ShopMetrics::units).sum(), cost, retail, cogs, turnoverRate(cogs, cost), prov,
                    cost.subtract(prov), tiers, ratio(used, cap), ratio(cost, budget)));
        });
        out.sort(Comparator.comparing(RegionMetrics::regionCode));
        return out;
    }

    private static BigDecimal sumS(List<ShopMetrics> ms, java.util.function.Function<ShopMetrics, BigDecimal> f) {
        return ms.stream().map(f).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static Map<RiskTier, BigDecimal> tierBreakdown(List<BatchRisk> rs) {
        Map<RiskTier, BigDecimal> m = new EnumMap<>(RiskTier.class);
        for (RiskTier t : RiskTier.values()) m.put(t, BigDecimal.ZERO.setScale(2));
        for (BatchRisk r : rs) m.merge(r.tier(), r.costValue(), BigDecimal::add);
        return m;
    }

    private static BigDecimal sum(List<BatchRisk> rs, java.util.function.Function<BatchRisk, BigDecimal> f) {
        return rs.stream().map(f).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }
}
