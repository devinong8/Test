package com.hackathon.inventory.service;

import com.hackathon.inventory.TestSupport;
import com.hackathon.inventory.config.BusinessRuleException;
import com.hackathon.inventory.domain.*;
import com.hackathon.inventory.web.dto.MovementResult;
import com.hackathon.inventory.web.dto.StockInRequest;
import com.hackathon.inventory.web.dto.StockOutRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class InventoryServiceIntegrationTest extends TestSupport {
    @Autowired InventoryService inventory;

    StockInRequest in(Shop s, Sku k, long qty, java.time.LocalDate prod) {
        return new StockInRequest(s.getId(), k.getId(), qty, null, prod, "PO-1", null);
    }

    StockOutRequest out(Shop s, Sku k, long qty, StockOutReason reason) {
        return new StockOutRequest(s.getId(), k.getId(), qty, reason, null, "POS-1", null);
    }

    @Test
    void stockInCreatesBatchAndLedgerEntry() {
        Shop shop = shop("SH-01", "10", "10000");
        Sku milk = sku("MILK", "2.00", "3.50", "0.001", 14);
        MovementResult r = inventory.stockIn(in(shop, milk, 100, TODAY.minusDays(1)));

        assertThat(r.onHandAfter()).isEqualTo(100);
        assertThat(r.transactions()).singleElement().satisfies(t -> {
            assertThat(t.type()).isEqualTo("STOCK_IN");
            assertThat(t.quantityDelta()).isEqualTo(100);
            assertThat(t.batchNo()).isEqualTo("MILK-20261121");
        });
        // a second receipt of the same production batch tops up the same batch
        inventory.stockIn(in(shop, milk, 20, TODAY.minusDays(1)));
        assertThat(batchRepo.findAll()).singleElement().extracting(InventoryBatch::getOnHandQty).isEqualTo(120L);
        assertThat(txnRepo.count()).isEqualTo(2);
    }

    @Test
    void stockInRejectedWhenStorageCapacityExceeded() {
        Shop shop = shop("SH-01", "1.000", "100000");
        Sku soda = sku("SODA", "9.00", "15.90", "0.011", 300);
        inventory.stockIn(in(shop, soda, 80, TODAY));                   // 0.88 m3 used
        assertThatThrownBy(() -> inventory.stockIn(in(shop, soda, 20, TODAY)))
                .isInstanceOfSatisfying(BusinessRuleException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("STORAGE_CAPACITY_EXCEEDED");
                    assertThat(e.getDetails().get("maxAcceptableQty").toString()).isEqualTo("10");
                });
        assertThat(inventory.maxReceivableQty(shop.getId(), soda.getId())).isEqualTo(10);
        assertThat(batchRepo.onHand(shop.getId(), soda.getId())).isEqualTo(80);
    }

    @Test
    void stockInRejectedWhenCapitalBudgetExceeded() {
        Shop shop = shop("SH-01", "100", "500.00");
        Sku rice = sku("RICE", "7.50", "12.90", "0.006", 540);
        assertThatThrownBy(() -> inventory.stockIn(in(shop, rice, 67, TODAY)))   // 502.50 > 500
                .isInstanceOfSatisfying(BusinessRuleException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("CAPITAL_BUDGET_EXCEEDED");
                    assertThat(e.getDetails().get("maxAcceptableQty").toString()).isEqualTo("66");
                });
        assertThat(inventory.stockIn(in(shop, rice, 66, TODAY)).onHandAfter()).isEqualTo(66);
    }

    @Test
    void expiredGoodsCannotBeReceivedAndAgedGoodsWarn() {
        Shop shop = shop("SH-01", "100", "10000");
        Sku bread = sku("BREAD", "1.20", "2.60", "0.003", 5);
        assertThatThrownBy(() -> inventory.stockIn(in(shop, bread, 10, TODAY.minusDays(6))))
                .hasFieldOrPropertyWithValue("code", "EXPIRED_ON_ARRIVAL");
        MovementResult r = inventory.stockIn(in(shop, bread, 10, TODAY.minusDays(4)));   // R=0.8
        assertThat(r.warnings()).anyMatch(w -> w.contains("MODERATE"));
    }

    @Test
    void inactiveMasterDataBlocksStockIn() {
        Shop shop = shop("SH-01", "100", "10000");
        Sku sku = sku("X", "1.00", "2.00", "0.001", 10);
        sku.setActive(false);
        skuRepo.save(sku);
        assertThatThrownBy(() -> inventory.stockIn(in(shop, sku, 1, TODAY))).hasFieldOrPropertyWithValue("code", "SKU_INACTIVE");
    }

    @Test
    void stockOutAllocatesFirstExpiredFirstOut() {
        Shop shop = shop("SH-01", "100", "10000");
        Sku milk = sku("MILK", "2.00", "3.50", "0.001", 14);
        inventory.stockIn(in(shop, milk, 30, TODAY.minusDays(2)));   // newer
        inventory.stockIn(in(shop, milk, 20, TODAY.minusDays(8)));   // older -> must go first

        MovementResult r = inventory.stockOut(out(shop, milk, 25, StockOutReason.SALE));
        assertThat(r.onHandAfter()).isEqualTo(25);
        assertThat(r.transactions()).extracting(t -> t.batchNo() + ":" + t.quantityDelta())
                .containsExactly("MILK-20261114:-20", "MILK-20261120:-5");
    }

    @Test
    void salesSkipExpiredStockButWriteOffCanRemoveIt() {
        Shop shop = shop("SH-01", "100", "10000");
        Sku bread = sku("BREAD", "1.20", "2.60", "0.003", 5);
        // received 4 days ago when fresh; produced 6 days ago -> expired today (R=1.2)
        inventory.stockIn(in(shop, bread, 10, TODAY.minusDays(6)), at(TODAY.minusDays(4)));
        inventory.stockIn(in(shop, bread, 5, TODAY));

        assertThatThrownBy(() -> inventory.stockOut(out(shop, bread, 6, StockOutReason.SALE)))
                .isInstanceOfSatisfying(BusinessRuleException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("INSUFFICIENT_STOCK");
                    assertThat(e.getDetails()).containsEntry("available", 5L).containsEntry("expiredUnitsExcluded", 10L);
                });
        MovementResult sale = inventory.stockOut(out(shop, bread, 5, StockOutReason.SALE));
        assertThat(sale.transactions()).singleElement().extracting(t -> t.batchNo()).isEqualTo("BREAD-20261122");

        MovementResult wo = inventory.stockOut(out(shop, bread, 10, StockOutReason.EXPIRED_WRITE_OFF));
        assertThat(wo.onHandAfter()).isZero();
        assertThat(wo.warnings()).anyMatch(w -> w.contains("out of stock"));
    }

    @Test
    void stockOutOfSpecificBatchMustMatchShopAndSku() {
        Shop a = shop("A", "100", "10000");
        Shop b = shop("B", "100", "10000");
        Sku milk = sku("MILK", "2.00", "3.50", "0.001", 14);
        inventory.stockIn(in(a, milk, 10, TODAY));
        Long batchId = batchRepo.findAll().get(0).getId();
        assertThatThrownBy(() -> inventory.stockOut(new StockOutRequest(b.getId(), milk.getId(), 1L,
                StockOutReason.DAMAGE, batchId, null, null))).hasFieldOrPropertyWithValue("code", "BATCH_MISMATCH");
    }

    @Test
    void concurrentStockOutsNeverOversell() throws Exception {
        Shop shop = shop("SH-01", "100", "10000");
        Sku milk = sku("MILK", "2.00", "3.50", "0.001", 14);
        inventory.stockIn(in(shop, milk, 100, TODAY.minusDays(1)));

        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger(), rejected = new AtomicInteger();
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < threads; i++)
            fs.add(pool.submit(() -> {
                go.await();
                try { inventory.stockOut(out(shop, milk, 15, StockOutReason.SALE)); ok.incrementAndGet(); }
                catch (BusinessRuleException e) { rejected.incrementAndGet(); }
                return null;
            }));
        go.countDown();
        for (Future<?> f : fs) f.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(ok.get()).isEqualTo(6);                 // 6 x 15 = 90 <= 100
        assertThat(rejected.get()).isEqualTo(6);
        assertThat(batchRepo.onHand(shop.getId(), milk.getId())).isEqualTo(10);
        long ledgerSum = txnRepo.findAll().stream().mapToLong(StockTransaction::getQuantityDelta).sum();
        assertThat(ledgerSum).isEqualTo(10);               // ledger and balance agree
    }

    @Test
    void concurrentStockInsNeverBreachStorageCapacity() throws Exception {
        Shop shop = shop("SH-01", "1.000", "100000");
        Sku water = sku("WATER", "18.00", "29.90", "0.1", 540);          // 2 units = 0.2 m3 -> at most 5 receipts fit

        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < threads; i++)
            fs.add(pool.submit(() -> {
                go.await();
                try { inventory.stockIn(in(shop, water, 2, TODAY)); ok.incrementAndGet(); }
                catch (BusinessRuleException e) { /* rejected: over capacity */ }
                return null;
            }));
        go.countDown();
        for (Future<?> f : fs) f.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(batchRepo.usedVolume(shop.getId())).isLessThanOrEqualTo(new java.math.BigDecimal("1.000"));
        assertThat(ok.get()).isEqualTo(5);
        assertThat(batchRepo.findAll()).hasSize(1);                         // one batch, never duplicated
    }

    @Test
    void productionDateInTheFutureIsRejectedUsingBusinessClock() {
        Shop shop = shop("SH-01", "100", "10000");
        Sku milk = sku("MILK", "9.50", "14.90", "0.001", 7);
        assertThatThrownBy(() -> inventory.stockIn(in(shop, milk, 1, TODAY.plusDays(1))))
                .hasFieldOrPropertyWithValue("code", "INVALID_PRODUCTION_DATE");
        assertThat(inventory.stockIn(in(shop, milk, 1, TODAY)).onHandAfter()).isEqualTo(1);
    }
}
