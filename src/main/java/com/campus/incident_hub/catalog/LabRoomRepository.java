package com.campus.incident_hub.catalog;

import com.campus.incident_hub.catalog.LabRoom;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface LabRoomRepository extends JpaRepository<LabRoom, Long> {
    boolean existsByCodeIgnoreCase(String code);
    Optional<LabRoom> findByCodeIgnoreCase(String code);
}
