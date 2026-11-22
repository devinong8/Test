package com.hackathon.inventory.repository;

import com.hackathon.inventory.domain.InventoryBatch;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface InventoryBatchRepository extends JpaRepository<InventoryBatch, Long> {

    /** FEFO order (first-expired, first-out): oldest production date first. Rows are locked for the stock-out. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from InventoryBatch b where b.shop.id = :shopId and b.sku.id = :skuId and b.onHandQty > 0 " +
           "order by b.batchProductionDate asc, b.id asc")
    List<InventoryBatch> lockAvailableFefo(@Param("shopId") Long shopId, @Param("skuId") Long skuId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from InventoryBatch b where b.id = :id")
    Optional<InventoryBatch> lockById(@Param("id") Long id);

    Optional<InventoryBatch> findByShopIdAndSkuIdAndBatchNo(Long shopId, Long skuId, String batchNo);

    @Query("select b from InventoryBatch b where b.onHandQty > 0 and (:shopId is null or b.shop.id = :shopId) " +
           "order by b.shop.code, b.sku.code, b.batchProductionDate")
    List<InventoryBatch> findInStock(@Param("shopId") Long shopId);

    @Query("select coalesce(sum(b.onHandQty * b.sku.unitVolumeM3), 0) from InventoryBatch b where b.shop.id = :shopId")
    BigDecimal usedVolume(@Param("shopId") Long shopId);

    @Query("select coalesce(sum(b.onHandQty * b.sku.purchasePrice), 0) from InventoryBatch b where b.shop.id = :shopId")
    BigDecimal costValue(@Param("shopId") Long shopId);

    @Query("select coalesce(sum(b.onHandQty), 0) from InventoryBatch b where b.shop.id = :shopId and b.sku.id = :skuId")
    long onHand(@Param("shopId") Long shopId, @Param("skuId") Long skuId);

    List<InventoryBatch> findByShopIdAndSkuIdAndOnHandQtyGreaterThan(Long shopId, Long skuId, long qty);

    boolean existsByShopIdAndOnHandQtyGreaterThan(Long shopId, long qty);
    boolean existsBySkuIdAndOnHandQtyGreaterThan(Long skuId, long qty);
}
