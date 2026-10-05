package com.erp.backend.service;

import com.erp.backend.dto.audit.AuditLogResponse;
import com.erp.backend.dto.audit.CreateAuditLogEntry;
import com.erp.backend.dto.user.PageResponse;
import com.erp.backend.entity.AuditLog;
import com.erp.backend.entity.AuditModule;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.AuditLogRepository;
import com.erp.backend.repository.UserRepository;
import com.erp.backend.security.UserDetailsImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditLogServiceTest {

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private AuditLogService auditLogService;

    private final UserDetailsImpl actor = new UserDetailsImpl(
            100L, "accountant", "Phạm Thị Kế Toán", "acc@erp.com", "pass", true,
            List.of(new SimpleGrantedAuthority("ROLE_ACCOUNTANT")));

    @Test
    @DisplayName("S2-04: Ghi nhận nhật ký thành công với đầy đủ thông tin")
    void record_success() {
        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(inv -> {
            AuditLog log = inv.getArgument(0);
            log.setId(1L);
            log.setCreatedAt(LocalDateTime.now());
            return log;
        });

        AuditLog result = auditLogService.record(
                AuditModule.DEBT_LIMIT,
                "UPDATE_DEBT_LIMIT",
                "CUSTOMER",
                10L,
                "DL-010",
                "{\"creditLimit\":50000000}",
                "{\"creditLimit\":100000000}",
                "Nâng hạn mức",
                actor);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(1L);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        AuditLog saved = captor.getValue();
        assertThat(saved.getModule()).isEqualTo(AuditModule.DEBT_LIMIT);
        assertThat(saved.getAction()).isEqualTo("UPDATE_DEBT_LIMIT");
        assertThat(saved.getTargetType()).isEqualTo("CUSTOMER");
        assertThat(saved.getTargetId()).isEqualTo(10L);
        assertThat(saved.getTargetCode()).isEqualTo("DL-010");
        assertThat(saved.getOldValue()).isEqualTo("{\"creditLimit\":50000000}");
        assertThat(saved.getNewValue()).isEqualTo("{\"creditLimit\":100000000}");
        assertThat(saved.getReason()).isEqualTo("Nâng hạn mức");
        assertThat(saved.getActorId()).isEqualTo(100L);
        assertThat(saved.getActorUsername()).isEqualTo("accountant");
        assertThat(saved.getActorFullName()).isEqualTo("Phạm Thị Kế Toán");
    }

    @Test
    @DisplayName("S2-04: Tra cứu và phân trang nhật ký thành công")
    void search_success() {
        AuditLog logItem = AuditLog.builder()
                .id(1L)
                .module(AuditModule.DEBT_LIMIT)
                .action("LOCK_TRANSACTION")
                .targetType("CUSTOMER")
                .targetId(5L)
                .targetCode("DL-005")
                .actorId(100L)
                .actorUsername("accountant")
                .actorFullName("Phạm Thị Kế Toán")
                .reason("Khóa nợ xấu")
                .createdAt(LocalDateTime.now())
                .build();

        Page<AuditLog> page = new PageImpl<>(List.of(logItem));
        when(auditLogRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

        PageResponse<AuditLogResponse> res = auditLogService.search(
                "DL-005", AuditModule.DEBT_LIMIT, "CUSTOMER", 100L,
                null, null, "LOCK_TRANSACTION", 0, 20);

        assertThat(res.content()).hasSize(1);
        AuditLogResponse item = res.content().get(0);
        assertThat(item.id()).isEqualTo(1L);
        assertThat(item.module()).isEqualTo("DEBT_LIMIT");
        assertThat(item.moduleLabel()).isEqualTo("Hạn mức công nợ");
        assertThat(item.action()).isEqualTo("LOCK_TRANSACTION");
        assertThat(item.targetCode()).isEqualTo("DL-005");
        assertThat(item.reason()).isEqualTo("Khóa nợ xấu");
    }

    @Test
    @DisplayName("S2-04: Xem chi tiết nhật ký - Không tìm thấy ném 404")
    void getById_notFound_throwsException() {
        when(auditLogRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> auditLogService.getById(999L))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND);
    }
}
