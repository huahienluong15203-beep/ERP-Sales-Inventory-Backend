package com.erp.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/** S5-08: Một dòng kiểm kê (số lượng theo ĐVT cơ sở). difference = countedQuantity - systemQuantity. */
@Entity
@Table(name = "stock_take_lines")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class StockTakeLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stock_take_id", nullable = false)
    private StockTake stockTake;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false, length = 50)
    private String sku;

    @Column(name = "product_name", nullable = false, length = 200)
    private String productName;

    @Column(name = "base_unit", length = 30)
    private String baseUnit;

    /** Tồn sổ chụp lúc tạo phiếu */
    @Column(name = "system_quantity", nullable = false, precision = 15, scale = 4)
    private BigDecimal systemQuantity;

    /** Số đếm thực tế (null = chưa đếm) */
    @Column(name = "counted_quantity", precision = 15, scale = 4)
    private BigDecimal countedQuantity;

    @Column(precision = 15, scale = 4)
    private BigDecimal difference;

    @Column(length = 255)
    private String note;
}
