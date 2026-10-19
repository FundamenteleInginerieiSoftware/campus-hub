package com.campus.incident_hub.catalog;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;


public interface AssetRepository extends JpaRepository<Asset, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Asset a WHERE a.id = :id")
    Optional<Asset> findByIdForUpdate(@Param("id") Long id);

    List<Asset> findByModelAndStatusAndIdNot(String model, AssetStatus status, Long excludeId);
    List<Asset> findByCategoryIdAndStatusAndIdNot(Long categoryId, AssetStatus status, Long excludeId);
    boolean existsBySerialNumberIgnoreCase(String serialNumber);



}
