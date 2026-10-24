package com.campus.catalog.repository;

import com.campus.catalog.api.AssetStatus;
import com.campus.catalog.domain.Asset;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AssetRepository extends JpaRepository<Asset, Long> {

    // Acquires a PostgreSQL "SELECT ... FOR UPDATE" row lock so concurrent booking attempts
    // wait here until the active transaction finishes.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Asset a WHERE a.id = :id")
    Optional<Asset> findByIdForUpdate(@Param("id") Long id);

    List<Asset> findByModelAndStatusAndIdNot(String model, AssetStatus status, Long excludeId);

    List<Asset> findByCategoryIdAndStatusAndIdNot(Long categoryId, AssetStatus status, Long excludeId);

    boolean existsBySerialNumberIgnoreCase(String serialNumber);

    boolean existsByCategoryId(Long categoryId);

    boolean existsByLabRoomId(Long labRoomId);
}
