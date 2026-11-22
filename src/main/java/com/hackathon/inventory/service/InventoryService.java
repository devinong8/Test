package com.hackathon.inventory.service;

import com.hackathon.inventory.analytics.InventoryFormulas;
import com.hackathon.inventory.config.BusinessRuleException;
import com.hackathon.inventory.config.CorrelationIdFilter;
import com.hackathon.inventory.config.NotFoundException;
import com.hackathon.inventory.domain.*;
import com.hackathon.inventory.repository.InventoryBatchRepository;
import com.hackathon.inventory.repository.ShopRepository;
import com.hackathon.inventory.repository.StockTransactionRepository;
import com.hackathon.inventory.web.dto.*;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * On-going inventory management. Every movement is a ledger entry; batch balances are updated under a
 * pessimistic row lock (plus @Version as a safety net) so concurrent stock-outs can never oversell.
 */
@Service
@Transactional
public class InventoryService {
    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);
    private static final double WARN_UTILISATION = 0.90;

    private final InventoryBatchRepository batches;
    private final StockTransactionRepository txns;
    private final MasterDataService master;
    private final MeterRegistry meters;
    private final Clock clock;
    private final ShopRepository shops;

    public InventoryService(InventoryBatchRepository batches, StockTransactionRepository txns,
                            MasterDataService master, MeterRegistry meters, Clock clock, ShopRepository shops) {
        this.batches = batches; this.txns = txns; this.master = master; this.meters = meters; this.clock = clock;
        this.shops = shops;
    }

    /**
     * Takes a row lock on the shop for the rest of the transaction. Every movement at one shop is serialised, so the
     * storage / budget checks and find-or-create of a batch see a consistent picture. Different shops run in parallel.
     */
    private Shop lockShop(Long shopId) {
        return shops.lockById(shopId).orElseThrow(() -> new NotFoundException("Shop " + shopId + " not found"));
    }

    public MovementResult stockIn(StockInRequest r) { return stockIn(r, clock.instant()); }

    /** Stock-In: validates shop storage boundary and capital budget ceiling before accepting goods. */
    public MovementResult stockIn(StockInRequest r, Instant at) {
        Shop shop = lockShop(r.shopId());
        Sku sku = master.getSku(r.skuId());
        if (!shop.isActive()) throw new BusinessRuleException("SHOP_INACTIVE", "Shop " + shop.getCode() + " is inactive");
        if (!sku.isActive()) throw new BusinessRuleException("SKU_INACTIVE", "SKU " + sku.getCode() + " is inactive");

        LocalDate today = LocalDate.ofInstant(at, clock.getZone());
        if (r.batchProductionDate().isAfter(today))
            throw new BusinessRuleException("INVALID_PRODUCTION_DATE", "Batch production date is in the future");
        long elapsed = InventoryFormulas.elapsedDays(today, r.batchProductionDate());
        double rDep = InventoryFormulas.shelfLifeConsumptionRatio(elapsed, sku.getShelfLifeDays());
        if (RiskTier.classify(rDep) == RiskTier.EXPIRED)
            throw new BusinessRuleException("EXPIRED_ON_ARRIVAL", "Batch is already past its shelf life",
                    Map.of("elapsedDays", elapsed, "shelfLifeDays", sku.getShelfLifeDays()));

        long qty = r.quantity();
        BigDecimal addVolume = sku.getUnitVolumeM3().multiply(BigDecimal.valueOf(qty));
        BigDecimal addCost = sku.getPurchasePrice().multiply(BigDecimal.valueOf(qty));
        BigDecimal usedVolume = batches.usedVolume(shop.getId());
        BigDecimal costValue = batches.costValue(shop.getId());
        BigDecimal newVolume = usedVolume.add(addVolume);
        BigDecimal newCost = costValue.add(addCost);

        if (newVolume.compareTo(shop.getStorageCapacityM3()) > 0) {
            BigDecimal free = shop.getStorageCapacityM3().subtract(usedVolume).max(BigDecimal.ZERO);
            throw new BusinessRuleException("STORAGE_CAPACITY_EXCEEDED",
                    "Stock-in would exceed storage capacity of shop " + shop.getCode(), Map.of(
                    "capacityM3", shop.getStorageCapacityM3(), "usedM3", usedVolume.setScale(3, RoundingMode.HALF_UP),
                    "requestedM3", addVolume, "maxAcceptableQty",
                    free.divide(sku.getUnitVolumeM3(), 0, RoundingMode.FLOOR)));
        }
        if (newCost.compareTo(shop.getCapitalBudget()) > 0) {
            BigDecimal free = shop.getCapitalBudget().subtract(costValue).max(BigDecimal.ZERO);
            throw new BusinessRuleException("CAPITAL_BUDGET_EXCEEDED",
                    "Stock-in would exceed capital budget of shop " + shop.getCode(), Map.of(
                    "budget", shop.getCapitalBudget(), "currentCost", costValue.setScale(2, RoundingMode.HALF_UP),
                    "requestedCost", addCost, "maxAcceptableQty", sku.getPurchasePrice().signum() == 0 ? qty :
                    free.divide(sku.getPurchasePrice(), 0, RoundingMode.FLOOR)));
        }

        String batchNo = (r.batchNo() == null || r.batchNo().isBlank())
                ? sku.getCode() + "-" + r.batchProductionDate().format(DateTimeFormatter.BASIC_ISO_DATE)
                : r.batchNo().trim();
        InventoryBatch batch = batches.findByShopIdAndSkuIdAndBatchNo(shop.getId(), sku.getId(), batchNo)
                .orElseGet(() -> batches.save(new InventoryBatch(shop, sku, batchNo, r.batchProductionDate(), at)));
        if (!batch.getBatchProductionDate().equals(r.batchProductionDate()))
            throw new BusinessRuleException("BATCH_DATE_MISMATCH",
                    "Batch " + batchNo + " already exists with production date " + batch.getBatchProductionDate());
        batch.setOnHandQty(batch.getOnHandQty() + qty);

        StockTransaction t = txns.save(new StockTransaction(TransactionType.STOCK_IN, null, batch, qty,
                batch.getOnHandQty(), r.reference(), r.note(), at, CorrelationIdFilter.current()));
        meters.counter("inventory.movements.units", "type", "STOCK_IN", "reason", "RECEIPT").increment(qty);

        List<String> warnings = new ArrayList<>();
        double volUtil = InventoryFormulas.ratio(newVolume, shop.getStorageCapacityM3());
        double budUtil = InventoryFormulas.ratio(newCost, shop.getCapitalBudget());
        if (volUtil >= WARN_UTILISATION) warnings.add(String.format("Storage utilisation now %.1f%%", volUtil * 100));
        if (budUtil >= WARN_UTILISATION) warnings.add(String.format("Capital budget utilisation now %.1f%%", budUtil * 100));
        RiskTier tier = RiskTier.classify(rDep);
        if (tier != RiskTier.NORMAL)
            warnings.add(String.format("Batch arrives with %s depreciation risk (R_dep=%.2f)", tier, rDep));

        log.info("STOCK_IN shop={} sku={} batch={} qty={} balance={}", shop.getCode(), sku.getCode(), batchNo, qty,
                batch.getOnHandQty());
        return new MovementResult(batches.onHand(shop.getId(), sku.getId()), List.of(TransactionView.of(t)), warnings);
    }

    /** Largest quantity of a SKU the shop can still receive without breaching storage or capital budget limits. */
    @Transactional(readOnly = true)
    public long maxReceivableQty(Long shopId, Long skuId) {
        Shop shop = master.getShop(shopId);
        Sku sku = master.getSku(skuId);
        BigDecimal freeVol = shop.getStorageCapacityM3().subtract(batches.usedVolume(shopId)).max(BigDecimal.ZERO);
        BigDecimal freeBudget = shop.getCapitalBudget().subtract(batches.costValue(shopId)).max(BigDecimal.ZERO);
        long byVol = freeVol.divide(sku.getUnitVolumeM3(), 0, RoundingMode.FLOOR).longValue();
        long byBudget = sku.getPurchasePrice().signum() == 0 ? Long.MAX_VALUE
                : freeBudget.divide(sku.getPurchasePrice(), 0, RoundingMode.FLOOR).longValue();
        return Math.min(byVol, byBudget);
    }

    public MovementResult stockOut(StockOutRequest r) { return stockOut(r, clock.instant()); }

    /**
     * Stock-Out: allocates FEFO (earliest production date first) across batches. Sales skip expired batches -
     * expired stock can only leave through a write-off / return / damage movement.
     */
    public MovementResult stockOut(StockOutRequest r, Instant at) {
        Shop shop = lockShop(r.shopId());
        Sku sku = master.getSku(r.skuId());
        LocalDate today = LocalDate.ofInstant(at, clock.getZone());
        boolean isSale = r.reason() == StockOutReason.SALE;

        List<InventoryBatch> candidates;
        if (r.batchId() != null) {
            InventoryBatch b = batches.lockById(r.batchId())
                    .orElseThrow(() -> new NotFoundException("Batch " + r.batchId() + " not found"));
            if (!b.getShop().getId().equals(shop.getId()) || !b.getSku().getId().equals(sku.getId()))
                throw new BusinessRuleException("BATCH_MISMATCH", "Batch does not belong to this shop/SKU");
            candidates = List.of(b);
        } else {
            candidates = batches.lockAvailableFefo(shop.getId(), sku.getId());
        }

        List<InventoryBatch> usable = candidates.stream()
                .filter(b -> !isSale || !isExpired(b, today))
                .toList();
        long available = usable.stream().mapToLong(InventoryBatch::getOnHandQty).sum();
        if (available < r.quantity()) {
            long expired = candidates.stream().filter(b -> isExpired(b, today)).mapToLong(InventoryBatch::getOnHandQty).sum();
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("requested", r.quantity());
            details.put("available", available);
            if (isSale && expired > 0) details.put("expiredUnitsExcluded", expired);
            throw new BusinessRuleException("INSUFFICIENT_STOCK",
                    "Insufficient " + (isSale ? "sellable " : "") + "stock of " + sku.getCode() + " at " + shop.getCode(),
                    details);
        }

        long remaining = r.quantity();
        List<TransactionView> out = new ArrayList<>();
        for (InventoryBatch b : usable) {
            if (remaining == 0) break;
            long take = Math.min(remaining, b.getOnHandQty());
            if (take == 0) continue;
            b.setOnHandQty(b.getOnHandQty() - take);
            remaining -= take;
            out.add(TransactionView.of(txns.save(new StockTransaction(TransactionType.STOCK_OUT, r.reason(), b, -take,
                    b.getOnHandQty(), r.reference(), r.note(), at, CorrelationIdFilter.current()))));
        }
        meters.counter("inventory.movements.units", "type", "STOCK_OUT", "reason", r.reason().name())
                .increment(r.quantity());
        log.info("STOCK_OUT shop={} sku={} qty={} reason={} batches={}", shop.getCode(), sku.getCode(), r.quantity(),
                r.reason(), out.size());

        long onHand = batches.onHand(shop.getId(), sku.getId());
        List<String> warnings = new ArrayList<>();
        if (onHand == 0) warnings.add("SKU " + sku.getCode() + " is now out of stock at " + shop.getCode());
        return new MovementResult(onHand, out, warnings);
    }

    private boolean isExpired(InventoryBatch b, LocalDate today) {
        long elapsed = InventoryFormulas.elapsedDays(today, b.getBatchProductionDate());
        return RiskTier.classify(InventoryFormulas.shelfLifeConsumptionRatio(elapsed, b.getSku().getShelfLifeDays()))
                == RiskTier.EXPIRED;
    }

    @Transactional(readOnly = true)
    public Page<TransactionView> ledger(Set<Long> shopIds, Long skuId, TransactionType type, int page, int size) {
        boolean all = shopIds == null;
        Collection<Long> ids = all || shopIds.isEmpty() ? List.of(-1L) : shopIds;
        return txns.search(all, ids, skuId, type, PageRequest.of(page, Math.min(size, 500),
                Sort.by(Sort.Direction.DESC, "occurredAt", "id"))).map(TransactionView::of);
    }

    public ZoneId zone() { return clock.getZone(); }
}
