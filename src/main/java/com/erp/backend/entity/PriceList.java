package com.erp.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * S2-10: Bảng giá theo nhóm khách hàng và thời gian hiệu lực.
 * - Nhiều bảng giá song song: đại lý cấp 1, cấp 2, khách lẻ.
 * - Bảng giá đã phát sinh đơn (hasOrders) thì không sửa, chỉ tạo phiên bản mới.
 */
@Entity
@Table(name = "price_lists")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class PriceList {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String code;

    @Column(nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "customer_group", nullable = false, length = 30)
    private CustomerGroup customerGroup;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    // null = hiệu lực không thời hạn
    @Column(name = "end_date")
    private LocalDate endDate;

    // ACTIVE | INACTIVE
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "ACTIVE";

    // Đã có đơn hàng dùng bảng giá này -> khoá, chỉ tạo phiên bản mới (S3-09 sẽ bật cờ này)
    @Column(name = "has_orders", nullable = false, columnDefinition = "boolean default false")
    @Builder.Default
    private boolean hasOrders = false;

    @Column(nullable = false, columnDefinition = "integer default 1")
    @Builder.Default
    private Integer version = 1;

    // Bảng giá gốc khi tạo phiên bản mới
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_price_list_id")
    private PriceList sourcePriceList;

    @Column(length = 500)
    private String note;

    @OneToMany(mappedBy = "priceList", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("productSku ASC")
    @Builder.Default
    private List<PriceListItem> items = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public void addItem(PriceListItem item) {
        item.setPriceList(this);
        items.add(item);
    }
}
