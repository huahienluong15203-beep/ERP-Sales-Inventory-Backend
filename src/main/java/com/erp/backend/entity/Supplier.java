package com.erp.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * S2-09: Nhà cung cấp (nguồn hàng của phiếu nhập kho).
 * - Mã nhà cung cấp và mã số thuế là duy nhất. Mã không đổi sau khi tạo.
 * - Đã có phiếu nhập thì không xoá được, chỉ chuyển INACTIVE (ngừng giao dịch).
 */
@Entity
@Table(name = "suppliers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class Supplier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 30)
    private String code;

    @Column(nullable = false, length = 200)
    private String name;

    // 10 số hoặc 10 số + "-" + 3 số (chi nhánh)
    @Column(name = "tax_code", nullable = false, unique = true, length = 20)
    private String taxCode;

    @Column(name = "contact_name", length = 100)
    private String contactName;

    @Column(length = 20)
    private String phone;

    @Column(length = 100)
    private String email;

    @Column(length = 500)
    private String address;

    // Điều khoản thanh toán, vd: "Thanh toán trong 30 ngày kể từ ngày nhận hàng"
    @Column(name = "payment_terms", length = 255)
    private String paymentTerms;

    @Column(length = 500)
    private String note;

    // ACTIVE (Đang giao dịch) | INACTIVE (Ngừng giao dịch)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "ACTIVE";

    @Column(name = "status_reason", length = 500)
    private String statusReason;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
