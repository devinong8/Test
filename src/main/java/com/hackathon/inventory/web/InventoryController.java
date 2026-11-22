package com.hackathon.inventory.web;

import com.hackathon.inventory.analytics.BatchRisk;
import com.hackathon.inventory.analytics.PortfolioAnalyzer;
import com.hackathon.inventory.domain.TransactionType;
import com.hackathon.inventory.repository.InventoryBatchRepository;
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

@RestController
@RequestMapping("/api/inventory")
public class InventoryController {
    private final InventoryService inventory;
    private final InventoryBatchRepository batches;
    private final Clock clock;

    public InventoryController(InventoryService inventory, InventoryBatchRepository batches, Clock clock) {
        this.inventory = inventory; this.batches = batches; this.clock = clock;
    }

    /** Batch-level stock on hand, each with its live depreciation assessment. */
    @GetMapping("/batches")
    @Transactional(readOnly = true)
    public List<BatchRisk> batches(@RequestParam(required = false) Long shopId) {
        LocalDate today = LocalDate.now(clock);
        return batches.findInStock(shopId).stream()
                .map(b -> PortfolioAnalyzer.assess(AnalyticsService.toPosition(b), today)).toList();
    }

    /** How many units of a SKU the shop can still receive (storage + capital budget headroom). */
    @GetMapping("/headroom")
    public java.util.Map<String, Long> headroom(@RequestParam Long shopId, @RequestParam Long skuId) {
        return java.util.Map.of("maxReceivableQty", inventory.maxReceivableQty(shopId, skuId));
    }

    @PostMapping("/stock-in")
    public MovementResult stockIn(@Valid @RequestBody StockInRequest r) { return inventory.stockIn(r); }

    @PostMapping("/stock-out")
    public MovementResult stockOut(@Valid @RequestBody StockOutRequest r) { return inventory.stockOut(r); }

    @GetMapping("/transactions")
    public Page<TransactionView> ledger(@RequestParam(required = false) Long shopId,
                                        @RequestParam(required = false) Long skuId,
                                        @RequestParam(required = false) TransactionType type,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "50") int size) {
        return inventory.ledger(shopId, skuId, type, page, size);
    }
}
