package com.hackathon.inventory.repository;

import com.hackathon.inventory.domain.Shop;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface ShopRepository extends JpaRepository<Shop, Long> {
    /** Serialises stock movements per shop so limit checks and batch creation cannot race. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Shop s where s.id = :id")
    java.util.Optional<Shop> lockById(@Param("id") Long id);

    boolean existsByCodeIgnoreCase(String code);
    boolean existsByCodeIgnoreCaseAndIdNot(String code, Long id);
    List<Shop> findAllByOrderByCodeAsc();
    List<Shop> findByRegionId(Long regionId);
    long countByRegionId(Long regionId);
}
