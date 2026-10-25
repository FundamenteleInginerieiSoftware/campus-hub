package com.campus.reassignment.repository;

import com.campus.reassignment.domain.ReassignmentLog;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ReassignmentLogRepository extends JpaRepository<ReassignmentLog, Long> {

    List<ReassignmentLog> findByIncidentId(Long incidentId);
}
