package com.erp.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * S8-02: Kế toán từ chối lập chứng từ giảm trừ cho 1 phiếu trả hàng đã nhập kho (kèm lý do).
 * Mỗi phiếu trả chỉ có 1 quyết định: hoặc có chứng từ giảm trừ (CreditNote), hoặc bị từ chối. Không sửa / xoá.
 */
@Entity
@Table(name = "debt_adjustment_rejections", uniqueConstraints = {
        @UniqueConstraint(name = "uk_debt_adj_reject_return", columnNames = "sales_return_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class DebtAdjustmentRejection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sales_return_id", nullable = false, updatable = false)
    private SalesReturn salesReturn;

    @Column(nullable = false, length = 500, updatable = false)
    private String reason;

    @Column(name = "rejected_at", nullable = false, updatable = false)
    private LocalDateTime rejectedAt;

    @Column(name = "rejected_by_id", updatable = false)
    private Long rejectedById;

    @Column(name = "rejected_by_username", length = 50, updatable = false)
    private String rejectedByUsername;

    @PreRemove
    void preventDelete() {
        throw new IllegalStateException("Quyết định từ chối giảm trừ công nợ không được xoá");
    }
}
