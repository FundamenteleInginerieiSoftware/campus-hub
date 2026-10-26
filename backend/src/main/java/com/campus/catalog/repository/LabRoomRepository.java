package com.campus.catalog.repository;

import com.campus.catalog.domain.LabRoom;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LabRoomRepository extends JpaRepository<LabRoom, Long> {

    boolean existsByCodeIgnoreCase(String code);

    Optional<LabRoom> findByCodeIgnoreCase(String code);
}
