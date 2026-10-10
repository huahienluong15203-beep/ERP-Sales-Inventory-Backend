package com.erp.backend.dto.customer;

import java.math.BigDecimal;
import java.util.List;

/**
 * S4-04: Lịch sử mua hàng của đại lý trong N tháng gần nhất (mặc định 3), hiện ngay khi NV kinh doanh gõ đơn.
 * Tên field khớp kiểu CustomerPurchaseHistoryData ở Frontend (src/types/order.ts).
 * "Đã mua" = đơn ở trạng thái SalesOrder.OUTSTANDING_DEBT_STATUSES (đã duyệt trở đi). Không chứa giá vốn.
 *
 * @param threeMonthsSummary tổng hợp trong khoảng thời gian xem (tên giữ theo FE, áp dụng cho N tháng)
 * @param frequentProducts   các mặt hàng đã mua, sắp theo số đơn rồi tổng số lượng giảm dần (tối đa 30)
 * @param lastOrder          đơn đã mua gần nhất (null nếu đại lý chưa từng mua)
 */
public record CustomerPurchaseHistoryResponse(
        Long customerId,
        String customerCode,
        String customerName,
        Long assignedRepId,
        String assignedRepName,
        int months,
        Summary threeMonthsSummary,
        List<ProductItem> frequentProducts,
        LastOrder lastOrder
) {

    /**
     * @param totalOrders          số đơn đã mua trong khoảng
     * @param totalRevenue         tổng tiền phải thu của các đơn đó (VND)
     * @param distinctProductCount số mặt hàng khác nhau đã mua (kể cả phần không hiện vì vượt 30 dòng)
     * @param startDate            ngày bắt đầu (yyyy-MM-dd, giờ Việt Nam)
     * @param endDate              hôm nay (yyyy-MM-dd)
     */
    public record Summary(
            long totalOrders,
            BigDecimal totalRevenue,
            long distinctProductCount,
            String startDate,
            String endDate
    ) {
    }

    /**
     * Số lượng tính theo ĐVT đại lý hay đặt nhất (preferredUnit); tồn kho availableStock theo ĐVT cơ sở.
     *
     * @param totalQuantity3M          tổng số lượng trong khoảng (theo preferredUnit)
     * @param orderCount3M             số đơn có mặt hàng này
     * @param avgQuantityPerMonth      tổng / số tháng
     * @param avgQuantityPerOrder      tổng / số đơn
     * @param lastUnitPrice            giá 1 preferredUnit ở lần mua gần nhất
     * @param currentUnitPrice         giá 1 preferredUnit theo bảng giá đang hiệu lực (null nếu chưa có giá)
     * @param availableStock           tồn khả dụng tại kho phục vụ đại lý (ĐVT cơ sở, null nếu không xác định được kho)
     * @param availableInPreferredUnit tồn khả dụng quy theo preferredUnit (làm tròn xuống)
     */
    public record ProductItem(
            Long productId,
            String sku,
            String name,
            String category,
            String baseUnit,
            String preferredUnit,
            BigDecimal preferredConversionFactor,
            BigDecimal totalQuantity3M,
            long orderCount3M,
            BigDecimal avgQuantityPerMonth,
            BigDecimal avgQuantityPerOrder,
            String lastOrderedDate,
            BigDecimal lastUnitPrice,
            BigDecimal currentUnitPrice,
            BigDecimal availableStock,
            BigDecimal availableInPreferredUnit
    ) {
    }

    /**
     * @param totalQuantity tổng số lượng các dòng (theo ĐVT của từng dòng)
     * @param totalAmount   tổng phải thu của đơn
     */
    public record LastOrder(
            Long orderId,
            String orderCode,
            String orderDate,
            int itemCount,
            BigDecimal totalQuantity,
            BigDecimal totalAmount,
            List<LastOrderItem> items
    ) {
    }

    /** @param unitPrice giá 1 unitName ở đơn đó */
    public record LastOrderItem(
            Long productId,
            String sku,
            String name,
            String unitName,
            BigDecimal conversionFactor,
            BigDecimal quantity,
            BigDecimal unitPrice
    ) {
    }
}
