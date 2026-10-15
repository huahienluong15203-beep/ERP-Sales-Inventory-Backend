package com.erp.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * S6-03: Đơn hàng bán được gom vào phiếu soạn hàng (Wave / Batch Picking).
 */
@Entity
@Table(name = "picking_list_orders", indexes = {
        @Index(name = "idx_plo_picking_list", columnList = "picking_list_id"),
        @Index(name = "idx_plo_order", columnList = "order_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class PickingListOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "picking_list_id", nullable = false)
    private PickingList pickingList;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private SalesOrder order;

    @Column(name = "order_code", nullable = false, length = 30)
    private String orderCode;

    @Column(name = "customer_name", length = 150)
    private String customerName;

    @Column(name = "total_lines")
    private Integer totalLines;

    @Column(name = "total_amount", precision = 18, scale = 2)
    private BigDecimal totalAmount;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
