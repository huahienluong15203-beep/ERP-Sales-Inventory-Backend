package com.erp.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * S6-03: Chi tiết phân bổ số lượng mặt hàng gom về từng đơn hàng cụ thể.
 * Giúp thủ kho sau khi gom hàng tại kệ có thể chia đúng về từng kiện hàng của mỗi đơn.
 */
@Entity
@Table(name = "picking_list_item_allocations", indexes = {
        @Index(name = "idx_plia_item", columnList = "item_id"),
        @Index(name = "idx_plia_order", columnList = "order_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class PickingListItemAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id", nullable = false)
    private PickingListItem pickingListItem;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private SalesOrder order;

    @Column(name = "order_code", nullable = false, length = 30)
    private String orderCode;

    @Column(name = "customer_name", length = 150)
    private String customerName;

    // Số lượng cần cho đơn này (theo ĐVT cơ sở)
    @Column(name = "requested_quantity", nullable = false, precision = 18, scale = 4)
    @Builder.Default
    private BigDecimal requestedQuantity = BigDecimal.ZERO;

    // Số lượng thực soạn cho đơn này
    @Column(name = "picked_quantity", nullable = false, precision = 18, scale = 4)
    @Builder.Default
    private BigDecimal pickedQuantity = BigDecimal.ZERO;

    // Lý do thiếu nếu đơn này bị thiếu
    @Column(name = "shortage_reason", length = 500)
    private String shortageReason;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
