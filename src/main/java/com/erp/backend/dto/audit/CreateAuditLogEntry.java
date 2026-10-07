package com.erp.backend.dto.audit;

import com.erp.backend.entity.AuditModule;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateAuditLogEntry {
    private AuditModule module;
    private String action;
    private String targetType;
    private Long targetId;
    private String targetCode;
    private Long actorId;
    private String actorUsername;
    private String actorFullName;
    private String actorAvatarUrl;
    private String oldValue;
    private String newValue;
    private String reason;
    private String ipAddress;
    private String userAgent;
    private String httpMethod;
    private String requestUri;
}
