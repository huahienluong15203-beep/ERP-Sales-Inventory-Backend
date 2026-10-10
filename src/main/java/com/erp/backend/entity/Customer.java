package com.erp.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * S3-03: Hồ sơ đại lý (khách hàng mua sỉ).
 * - Mã đại lý là duy nhất, không đổi sau khi tạo.
 * - Đã phát sinh giao dịch thì không xoá cứng, chỉ chuyển INACTIVE (ngừng giao dịch);
 *   chưa có giao dịch (vd tạo nhầm) thì Admin / Kế toán xoá được (CustomerService.delete).
 * S3-06: Mỗi đại lý có một nhân viên kinh doanh phụ trách chính (salesRep).
 */
@Entity
@Table(name = "customers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 30)
    private String code;

    @Column(nullable = false, length = 200)
    private String name;

    // Mã số thuế: 10 số hoặc 10 số + "-" + 3 số (chi nhánh). Không bắt buộc nhưng không được trùng.
    @Column(name = "tax_code", unique = true, length = 20)
    private String taxCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "customer_group", nullable = false, length = 30)
    private CustomerGroup customerGroup;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "region_id", nullable = false)
    private Region region;

    // S3-06: Nhân viên kinh doanh phụ trách chính (có thể chưa gán)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sales_rep_id")
    private User salesRep;

    @Column(name = "contact_name", length = 100)
    private String contactName;

    @Column(length = 20)
    private String phone;

    @Column(length = 100)
    private String email;

    // Địa chỉ trụ sở / đăng ký kinh doanh (điểm giao hàng khai báo riêng ở S3-04)
    @Column(length = 500)
    private String address;

    @Column(length = 500)
    private String note;

    // ACTIVE (Đang giao dịch) | INACTIVE (Ngừng giao dịch)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "ACTIVE";

    // Lý do ngừng giao dịch
    @Column(name = "status_reason", length = 500)
    private String statusReason;

    // S3-05: Hạn mức tiền tối đa cho phép nợ (VND). Bắt buộc nhập lý do khi thay đổi.
    @Column(name = "credit_limit", precision = 15, scale = 2)
    @Builder.Default
    private java.math.BigDecimal creditLimit = java.math.BigDecimal.ZERO;

    // S3-05: Số ngày nợ tối đa cho phép.
    @Column(name = "max_debt_days")
    @Builder.Default
    private Integer maxDebtDays = 30;

    // S4-10: Tài khoản đăng nhập cổng đại lý (vai trò CUSTOMER) gắn với đại lý này. Mỗi tài khoản chỉ gắn 1 đại lý.
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "portal_user_id", unique = true)
    private User portalUser;

    // S3-07: Khóa giao dịch đại lý (chặn tạo đơn mới trên mọi nền tảng khi có rủi ro công nợ).
    // columnDefinition có DEFAULT để ddl-auto=update thêm cột được trên bảng đã có dữ liệu
    @Column(name = "transaction_locked", nullable = false, columnDefinition = "boolean default false")
    @Builder.Default
    private Boolean transactionLocked = false;

    // S3-07: Lý do khóa / mở giao dịch (bắt buộc nhập)
    @Column(name = "transaction_lock_reason", length = 500)
    private String transactionLockReason;

    // S3-07: Thời điểm khóa giao dịch
    @Column(name = "transaction_locked_at")
    private LocalDateTime transactionLockedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public boolean isTransactionLocked() {
        return Boolean.TRUE.equals(transactionLocked);
    }
}
