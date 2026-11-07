package com.campus.catalog.repository;

import com.campus.catalog.api.AssetStatus;
import com.campus.catalog.domain.Asset;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AssetRepository extends JpaRepository<Asset, Long>, JpaSpecificationExecutor<Asset> {

    // Fetches category and labRoom in the same SQL SELECT so mapping a page to AssetResponse
    // never triggers 2*N extra queries (and works with spring.jpa.open-in-view=false).
    @Override
    @EntityGraph(attributePaths = {"category", "labRoom"})
    Page<Asset> findAll(@Nullable Specification<Asset> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"category", "labRoom"})
    Optional<Asset> findWithDetailsById(Long id);

    // Acquires a PostgreSQL "SELECT ... FOR UPDATE" row lock so concurrent booking attempts
    // wait here until the active transaction finishes. Eagerly loads category and labRoom so
    // mapping to AssetResponse during retire does not trigger N+1 lazy queries (#160).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"category", "labRoom"})
    @Query("SELECT a FROM Asset a WHERE a.id = :id")
    Optional<Asset> findByIdForUpdate(@Param("id") Long id);

    List<Asset> findByModelAndStatusAndIdNot(String model, AssetStatus status, Long excludeId);

    // Explicit JPQL on a.category.id filters directly on the assets.category_id foreign key column
    // without an unnecessary LEFT OUTER JOIN on the categories table (#160).
    @Query("SELECT a FROM Asset a WHERE a.category.id = :categoryId AND a.status = :status AND a.id <> :excludeId")
    List<Asset> findByCategoryIdAndStatusAndIdNot(
            @Param("categoryId") Long categoryId,
            @Param("status") AssetStatus status,
            @Param("excludeId") Long excludeId);

    boolean existsBySerialNumberIgnoreCase(String serialNumber);

    boolean existsByCategoryId(Long categoryId);

    boolean existsByLabRoomId(Long labRoomId);
}
