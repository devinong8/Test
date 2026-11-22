package com.hackathon.inventory.repository;

import com.hackathon.inventory.domain.SalesVelocity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface SalesVelocityRepository extends JpaRepository<SalesVelocity, Long> {
    Optional<SalesVelocity> findByShopIdAndSkuId(Long shopId, Long skuId);
    List<SalesVelocity> findByShopId(Long shopId);
}
