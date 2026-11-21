package com.hackathon.appointments.repository;

import com.hackathon.appointments.domain.Provider;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ProviderRepository extends JpaRepository<Provider, Long> {

    /**
     * Row lock on the provider (SELECT ... FOR UPDATE on PostgreSQL/MySQL). Used to serialise slot
     * creation per provider so two concurrent requests cannot both pass the overlap check.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Provider p where p.id = :id")
    Optional<Provider> findByIdForUpdate(@Param("id") Long id);
}
