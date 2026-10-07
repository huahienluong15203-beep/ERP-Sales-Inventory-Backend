package com.erp.backend.repository;

import com.erp.backend.entity.AuditLog;
import com.erp.backend.entity.AuditModule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long>, JpaSpecificationExecutor<AuditLog> {

    List<AuditLog> findByTargetTypeAndTargetIdOrderByCreatedAtDesc(String targetType, Long targetId);

    List<AuditLog> findByModuleOrderByCreatedAtDesc(AuditModule module);
}
