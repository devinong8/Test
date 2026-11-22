package com.hackathon.inventory.repository;

import com.hackathon.inventory.domain.StockTransaction;
import com.hackathon.inventory.domain.TransactionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface StockTransactionRepository extends JpaRepository<StockTransaction, Long> {

    @Query("select t from StockTransaction t where (:shopId is null or t.shop.id = :shopId) " +
           "and (:skuId is null or t.sku.id = :skuId) and (:type is null or t.type = :type)")
    Page<StockTransaction> search(@Param("shopId") Long shopId, @Param("skuId") Long skuId,
                                  @Param("type") TransactionType type, Pageable pageable);

    /** Point-in-time on-hand per batch: [batchId, qty]. Used to rebuild historical inventory for reports. */
    @Query("select t.batch.id, sum(t.quantityDelta) from StockTransaction t where t.occurredAt <= :asOf " +
           "group by t.batch.id having sum(t.quantityDelta) > 0")
    List<Object[]> onHandByBatchAsOf(@Param("asOf") Instant asOf);

    /** Units sold per shop/SKU in a window: [shopId, skuId, units]. */
    @Query("select t.shop.id, t.sku.id, -sum(t.quantityDelta) from StockTransaction t where t.type = 'STOCK_OUT' " +
           "and t.reason = 'SALE' and t.occurredAt > :from and t.occurredAt <= :to group by t.shop.id, t.sku.id")
    List<Object[]> soldUnits(@Param("from") Instant from, @Param("to") Instant to);
}
