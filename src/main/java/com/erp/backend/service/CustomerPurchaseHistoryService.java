package com.erp.backend.service;

import com.erp.backend.dto.customer.CustomerPurchaseHistoryResponse;
import com.erp.backend.dto.customer.PurchasedLastPrice;
import com.erp.backend.dto.customer.PurchasedProductAggregate;
import com.erp.backend.dto.customer.PurchasedUnitUsage;
import com.erp.backend.dto.inventory.StockInfoDto;
import com.erp.backend.entity.*;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.CustomerRepository;
import com.erp.backend.repository.PriceListItemRepository;
import com.erp.backend.repository.ProductRepository;
import com.erp.backend.repository.SalesOrderRepository;
import com.erp.backend.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * S4-04: Lịch sử mua hàng của đại lý trong N tháng gần nhất (mặc định 3).
 * - Các mặt hàng đã mua kèm số lượng bình quân theo tháng / theo đơn (tính trong DB theo ĐVT cơ sở, quy về ĐVT hay dùng).
 * - Đơn mua gần nhất để thêm nhanh cả nhóm hàng vào đơn mới.
 * - NV kinh doanh chỉ xem được đại lý mình phụ trách (kiểm ở server).
 * "Đã mua" = đơn ở trạng thái SalesOrder.OUTSTANDING_DEBT_STATUSES (đã duyệt trở đi), mốc thời gian = ngày duyệt.
 */
@Service
@RequiredArgsConstructor
public class CustomerPurchaseHistoryService {

    static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    static final int DEFAULT_MONTHS = 3;
    static final int MAX_MONTHS = 12;
    static final int MAX_PRODUCTS = 30;

    private final CustomerRepository customerRepository;
    private final SalesOrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final PriceListItemRepository priceItemRepository;
    private final InventoryService inventoryService;

    Clock clock = Clock.system(VN_ZONE);

    @Transactional(readOnly = true)
    public CustomerPurchaseHistoryResponse getHistory(Long customerId, Integer months, UserDetailsImpl actor) {
        int m = months == null ? DEFAULT_MONTHS : months;
        if (m < 1 || m > MAX_MONTHS) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_MONTHS",
                    "Số tháng xem lịch sử phải từ 1 đến " + MAX_MONTHS, "months");
        }
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy đại lý"));
        CustomerAccess.checkCanAccess(actor, customer);

        LocalDate today = LocalDate.now(clock);
        LocalDate startDate = today.minusMonths(m);
        LocalDateTime from = startDate.atStartOfDay();
        List<String> statuses = SalesOrder.OUTSTANDING_DEBT_STATUSES;

        long totalOrders = orderRepository.countPurchasedOrders(customer.getId(), statuses, from);
        BigDecimal totalRevenue = money(orderRepository.sumPurchasedAmount(customer.getId(), statuses, from));
        List<PurchasedProductAggregate> aggregates = orderRepository.aggregatePurchasedProducts(customer.getId(), statuses, from);

        List<CustomerPurchaseHistoryResponse.ProductItem> items = aggregates.isEmpty()
                ? List.of()
                : buildItems(customer, aggregates, statuses, from, m, today);

        CustomerPurchaseHistoryResponse.Summary summary = new CustomerPurchaseHistoryResponse.Summary(
                totalOrders, totalRevenue, aggregates.size(), startDate.toString(), today.toString());

        User rep = customer.getSalesRep();
        return new CustomerPurchaseHistoryResponse(customer.getId(), customer.getCode(), customer.getName(),
                rep != null ? rep.getId() : null, rep != null ? rep.getFullName() : null, m, summary, items,
                lastOrder(customer.getId(), statuses));
    }

    private List<CustomerPurchaseHistoryResponse.ProductItem> buildItems(Customer customer,
                                                                        List<PurchasedProductAggregate> aggregates,
                                                                        List<String> statuses, LocalDateTime from,
                                                                        int months, LocalDate today) {
        // Mua thường xuyên nhất lên đầu: nhiều đơn hơn, rồi tổng số lượng lớn hơn
        List<PurchasedProductAggregate> top = aggregates.stream()
                .sorted(Comparator.comparing(PurchasedProductAggregate::orderCount, Comparator.reverseOrder())
                        .thenComparing(PurchasedProductAggregate::totalBaseQuantity, Comparator.reverseOrder())
                        .thenComparing(PurchasedProductAggregate::productId))
                .limit(MAX_PRODUCTS)
                .toList();
        List<Long> productIds = top.stream().map(PurchasedProductAggregate::productId).toList();

        Map<Long, Product> products = productRepository.findAllById(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        Map<Long, PurchasedUnitUsage> preferredUnits = preferredUnits(
                orderRepository.purchasedUnitUsages(customer.getId(), statuses, from, productIds));
        Map<Long, PurchasedLastPrice> lastPrices = lastPrices(
                orderRepository.findLastPurchasedPrices(customer.getId(), statuses, productIds));
        Warehouse warehouse = servingWarehouse(customer);

        List<CustomerPurchaseHistoryResponse.ProductItem> items = new ArrayList<>();
        for (PurchasedProductAggregate a : top) {
            Product p = products.get(a.productId());
            if (p == null) {
                continue;
            }
            PurchasedUnitUsage unit = preferredUnits.get(a.productId());
            String unitName = unit != null ? unit.unitName() : p.getBaseUnit();
            BigDecimal factor = unit != null && unit.conversionFactor() != null && unit.conversionFactor().signum() > 0
                    ? unit.conversionFactor() : BigDecimal.ONE;

            BigDecimal totalBase = a.totalBaseQuantity() != null ? a.totalBaseQuantity() : BigDecimal.ZERO;
            BigDecimal total = totalBase.divide(factor, 4, RoundingMode.HALF_UP);
            long orderCount = a.orderCount() != null ? a.orderCount() : 0L;

            BigDecimal currentPrice = priceItemRepository.findEffective(customer.getCustomerGroup(), p.getSku(), today,
                            PageRequest.of(0, 1)).stream().findFirst()
                    .map(i -> i.getPrice().multiply(factor).setScale(2, RoundingMode.HALF_UP))
                    .orElse(null);

            BigDecimal available = null;
            BigDecimal availableInUnit = null;
            if (warehouse != null) {
                StockInfoDto stock = inventoryService.getStockInfo(warehouse, p);
                available = stock.availableStock();
                availableInUnit = available.signum() > 0
                        ? available.divide(factor, 0, RoundingMode.FLOOR) : BigDecimal.ZERO;
            }

            items.add(new CustomerPurchaseHistoryResponse.ProductItem(
                    p.getId(), p.getSku(), p.getName(), categoryName(p), p.getBaseUnit(), unitName, factor,
                    strip(total), orderCount,
                    strip(total.divide(BigDecimal.valueOf(months), 2, RoundingMode.HALF_UP)),
                    orderCount > 0 ? strip(total.divide(BigDecimal.valueOf(orderCount), 2, RoundingMode.HALF_UP)) : BigDecimal.ZERO,
                    a.lastOrderedAt() != null ? a.lastOrderedAt().toLocalDate().toString() : null,
                    lastUnitPrice(lastPrices.get(a.productId()), factor),
                    currentPrice, available, availableInUnit));
        }
        return items;
    }

    /** ĐVT đại lý hay đặt nhất: nhiều dòng nhất, hoà thì tổng SL lớn hơn, rồi ĐVT lớn hơn. */
    static Map<Long, PurchasedUnitUsage> preferredUnits(List<PurchasedUnitUsage> usages) {
        Comparator<PurchasedUnitUsage> order = Comparator
                .comparing((PurchasedUnitUsage u) -> u.lineCount() != null ? u.lineCount() : 0L)
                .thenComparing(u -> u.totalBaseQuantity() != null ? u.totalBaseQuantity() : BigDecimal.ZERO)
                .thenComparing(u -> u.conversionFactor() != null ? u.conversionFactor() : BigDecimal.ONE);
        Map<Long, PurchasedUnitUsage> result = new HashMap<>();
        for (PurchasedUnitUsage u : usages) {
            result.merge(u.productId(), u, (a, b) -> order.compare(a, b) >= 0 ? a : b);
        }
        return result;
    }

    /** Mỗi sản phẩm lấy dòng của lần mua gần nhất (cùng thời điểm thì dòng tạo sau). */
    private static Map<Long, PurchasedLastPrice> lastPrices(List<PurchasedLastPrice> rows) {
        Map<Long, PurchasedLastPrice> result = new HashMap<>();
        for (PurchasedLastPrice r : rows) {
            result.merge(r.productId(), r, (a, b) -> a.lineId() >= b.lineId() ? a : b);
        }
        return result;
    }

    /** Giá 1 ĐVT ưa dùng ở lần mua trước = giá thực bán / ĐVT cơ sở × hệ số ĐVT ưa dùng. */
    static BigDecimal lastUnitPrice(PurchasedLastPrice last, BigDecimal preferredFactor) {
        if (last == null) {
            return null;
        }
        BigDecimal lineFactor = last.conversionFactor() != null && last.conversionFactor().signum() > 0
                ? last.conversionFactor() : BigDecimal.ONE;
        BigDecimal perBase;
        if (last.pricePerUnit() != null) {
            perBase = last.pricePerUnit().divide(lineFactor, 6, RoundingMode.HALF_UP);
        } else if (last.unitPrice() != null) {
            perBase = last.unitPrice();
        } else {
            return null;
        }
        return perBase.multiply(preferredFactor).setScale(2, RoundingMode.HALF_UP);
    }

    private CustomerPurchaseHistoryResponse.LastOrder lastOrder(Long customerId, List<String> statuses) {
        return orderRepository.findLatestPurchasedOrders(customerId, statuses, PageRequest.of(0, 1)).stream()
                .findFirst()
                .map(o -> {
                    List<CustomerPurchaseHistoryResponse.LastOrderItem> lines = o.getLines().stream()
                            .map(l -> new CustomerPurchaseHistoryResponse.LastOrderItem(
                                    l.getProduct() != null ? l.getProduct().getId() : null,
                                    l.getProductSku(), l.getProductName(), l.getUnitName(), l.getConversionFactor(),
                                    strip(l.getQuantity()), linePrice(l)))
                            .toList();
                    BigDecimal totalQty = o.getLines().stream()
                            .map(SalesOrderLine::getQuantity).filter(Objects::nonNull)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    LocalDateTime at = o.getApprovedAt() != null ? o.getApprovedAt() : o.getCreatedAt();
                    return new CustomerPurchaseHistoryResponse.LastOrder(o.getId(), o.getCode(),
                            at != null ? at.toLocalDate().toString() : null, lines.size(), strip(totalQty),
                            o.getTotalAmount(), lines);
                })
                .orElse(null);
    }

    /** Giá 1 ĐVT của dòng (giá sửa tay S4-01 nếu có, không thì giá niêm yết × hệ số). */
    private static BigDecimal linePrice(SalesOrderLine l) {
        if (l.getPricePerUnit() != null) {
            return l.getPricePerUnit();
        }
        if (l.getUnitPrice() == null) {
            return null;
        }
        BigDecimal factor = l.getConversionFactor() != null ? l.getConversionFactor() : BigDecimal.ONE;
        return l.getUnitPrice().multiply(factor).setScale(2, RoundingMode.HALF_UP);
    }

    /** Kho phục vụ đại lý; hệ thống chưa có kho thì bỏ qua phần tồn (không làm hỏng cả lịch sử). */
    private Warehouse servingWarehouse(Customer customer) {
        try {
            return inventoryService.resolveWarehouseForCustomer(customer);
        } catch (BusinessException e) {
            return null;
        }
    }

    private static String categoryName(Product p) {
        if (p.getProductCategory() != null && p.getProductCategory().getName() != null) {
            return p.getProductCategory().getName();
        }
        return p.getCategory();
    }

    private static BigDecimal money(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal strip(BigDecimal v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal s = v.stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0) : s;
    }
}
