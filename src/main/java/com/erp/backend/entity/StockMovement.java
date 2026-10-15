package com.erp.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * S6-02: Một dòng thẻ kho = một lần tồn thực tế (physical_stock) của 1 SKU tại 1 kho thay đổi.
 * - quantity: số lượng theo ĐVT cơ sở, CÓ DẤU (+ nhập / - xuất).
 * - balanceAfter: tồn thực tế ngay sau lần thay đổi này.
 * - docType / docId / docCode: chứng từ gốc (phiếu nhập, đơn hàng, phiếu chuyển kho, phiếu kiểm kê) để mở lại.
 * Ghi trong CÙNG transaction với việc cộng / trừ tồn (dòng tồn đang bị khoá) nên số dư luôn khớp thứ tự thời gian.
 * Chỉ thêm mới, không sửa / xoá (chứng từ đã ghi sổ).
 */
@Entity
@Table(name = "stock_movements", indexes = {
        @Index(name = "idx_stock_movement_wh_product_time", columnList = "warehouse_id, product_id, movement_at"),
        @Index(name = "idx_stock_movement_time", columnList = "movement_at"),
        @Index(name = "idx_stock_movement_doc", columnList = "doc_type, doc_code")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class StockMovement {

    public static final String TYPE_RECEIPT = "RECEIPT";
    public static final String TYPE_SALES_DISPATCH = "SALES_DISPATCH";
    public static final String TYPE_DELIVERY_RETURN = "DELIVERY_RETURN";
    public static final String TYPE_TRANSFER_OUT = "TRANSFER_OUT";
    public static final String TYPE_TRANSFER_IN = "TRANSFER_IN";
    public static final String TYPE_STOCKTAKE_ADJUST = "STOCKTAKE_ADJUST";

    public static final String DOC_GOODS_RECEIPT = "GOODS_RECEIPT";
    public static final String DOC_SALES_ORDER = "SALES_ORDER";
    public static final String DOC_STOCK_TRANSFER = "STOCK_TRANSFER";
    public static final String DOC_STOCK_TAKE = "STOCK_TAKE";

    public static final Map<String, String> TYPE_LABELS = Map.of(
            TYPE_RECEIPT, "Nhập kho",
            TYPE_SALES_DISPATCH, "Xuất bán",
            TYPE_DELIVERY_RETURN, "Hàng giao không thành công nhập lại",
            TYPE_TRANSFER_OUT, "Xuất chuyển kho",
            TYPE_TRANSFER_IN, "Nhận chuyển kho",
            TYPE_STOCKTAKE_ADJUST, "Điều chỉnh kiểm kê");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "warehouse_id", nullable = false, updatable = false)
    private Warehouse warehouse;

    @Column(name = "warehouse_code", nullable = false, length = 50, updatable = false)
    private String warehouseCode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, updatable = false)
    private Product product;

    @Column(name = "product_sku", nullable = false, length = 50, updatable = false)
    private String productSku;

    @Column(name = "product_name", length = 200, updatable = false)
    private String productName;

    @Column(name = "base_unit", length = 30, updatable = false)
    private String baseUnit;

    @Column(name = "movement_type", nullable = false, length = 30, updatable = false)
    private String movementType;

    /** ĐVT cơ sở, có dấu: + tăng tồn, - giảm tồn. */
    @Column(nullable = false, precision = 15, scale = 4, updatable = false)
    private BigDecimal quantity;

    @Column(name = "balance_after", nullable = false, precision = 15, scale = 4, updatable = false)
    private BigDecimal balanceAfter;

    @Column(name = "doc_type", nullable = false, length = 30, updatable = false)
    private String docType;

    /** Có thể null lúc ghi (chứng từ chưa lưu), được gắn ngay sau khi chứng từ lưu xong trong cùng transaction. */
    @Column(name = "doc_id")
    private Long docId;

    @Column(name = "doc_code", length = 50, updatable = false)
    private String docCode;

    @Column(length = 500, updatable = false)
    private String note;

    @Column(name = "actor_id", updatable = false)
    private Long actorId;

    @Column(name = "actor_username", length = 50, updatable = false)
    private String actorUsername;

    @Column(name = "movement_at", nullable = false, updatable = false)
    private LocalDateTime movementAt;
}
