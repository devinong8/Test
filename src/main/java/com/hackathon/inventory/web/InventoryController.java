package com.hackathon.inventory.web;

import com.hackathon.inventory.analytics.BatchRisk;
import com.hackathon.inventory.analytics.PortfolioAnalyzer;
import com.hackathon.inventory.domain.TransactionType;
import com.hackathon.inventory.repository.InventoryBatchRepository;
import com.hackathon.inventory.security.AccessScope;
import com.hackathon.inventory.security.CurrentUser;
import com.hackathon.inventory.service.AnalyticsService;
import com.hackathon.inventory.service.InventoryService;
import com.hackathon.inventory.web.dto.*;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Stock movements and stock views. Every role may move stock, but only for shops inside its scope. */
@RestController
@RequestMapping("/api/inventory")
public class InventoryController {
    private final InventoryService inventory;
    private final InventoryBatchRepository batches;
    private final CurrentUser currentUser;
    private final Clock clock;

    public InventoryController(InventoryService inventory, InventoryBatchRepository batches, CurrentUser currentUser,
                               Clock clock) {
        this.inventory = inventory; this.batches = batches; this.currentUser = currentUser; this.clock = clock;
    }

    /** Batch-level stock on hand, each with its live depreciation assessment. */
    @GetMapping("/batches")
    @Transactional(readOnly = true)
    public List<BatchRisk> batches(@RequestParam(required = false) Long shopId) {
        AccessScope scope = currentUser.scope();
        scope.requireShop(shopId);
        LocalDate today = LocalDate.now(clock);
        return batches.findInStock(shopId).stream().filter(b -> scope.canSeeShop(b.getShop().getId()))
                .map(b -> PortfolioAnalyzer.assess(AnalyticsService.toPosition(b), today)).toList();
    }

    /** How many units of a SKU the shop can still receive (storage + capital budget headroom). */
    @GetMapping("/headroom")
    public Map<String, Long> headroom(@RequestParam Long shopId, @RequestParam Long skuId) {
        currentUser.scope().requireShop(shopId);
        return Map.of("maxReceivableQty", inventory.maxReceivableQty(shopId, skuId));
    }

    @PostMapping("/stock-in")
    public MovementResult stockIn(@Valid @RequestBody StockInRequest r) {
        currentUser.scope().requireShop(r.shopId());
        return inventory.stockIn(r);
    }

    @PostMapping("/stock-out")
    public MovementResult stockOut(@Valid @RequestBody StockOutRequest r) {
        currentUser.scope().requireShop(r.shopId());
        return inventory.stockOut(r);
    }

    @GetMapping("/transactions")
    public Page<TransactionView> ledger(@RequestParam(required = false) Long shopId,
                                        @RequestParam(required = false) Long skuId,
                                        @RequestParam(required = false) TransactionType type,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "50") int size) {
        AccessScope scope = currentUser.scope();
        scope.requireShop(shopId);
        return inventory.ledger(scope.narrow(shopId == null ? null : Set.of(shopId)), skuId, type, page, size);
    }
}
