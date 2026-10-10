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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static com.erp.backend.service.CustomerTestData.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CustomerPurchaseHistoryServiceTest {

    @Mock private CustomerRepository customerRepository;
    @Mock private SalesOrderRepository orderRepository;
    @Mock private ProductRepository productRepository;
    @Mock private PriceListItemRepository priceItemRepository;
    @Mock private InventoryService inventoryService;

    @InjectMocks private CustomerPurchaseHistoryService service;

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 10);
    private Customer customer;
    private Product coca;
    private final Warehouse warehouse = Warehouse.builder().id(1L).code("WH-MB01").name("Kho Tổng Miền Bắc").build();

    @BeforeEach
    void setUp() {
        service.clock = Clock.fixed(TODAY.atStartOfDay(CustomerPurchaseHistoryService.VN_ZONE).plusHours(9).toInstant(),
                CustomerPurchaseHistoryService.VN_ZONE);
        customer = customer(6, salesRep(7));
        coca = Product.builder().id(10L).sku("SP-COCA").name("Coca lon").baseUnit("Lon").category("Nước ngọt")
                .status("ACTIVE").build();
        lenient().when(customerRepository.findById(6L)).thenReturn(Optional.of(customer));
    }

    private void noPurchasesInPeriod() {
        when(orderRepository.countPurchasedOrders(eq(6L), any(), any())).thenReturn(0L);
        when(orderRepository.sumPurchasedAmount(eq(6L), any(), any())).thenReturn(BigDecimal.ZERO);
        when(orderRepository.aggregatePurchasedProducts(eq(6L), any(), any())).thenReturn(List.of());
    }

    @Test
    @DisplayName("S4-04: Bình quân theo tháng / theo đơn tính theo ĐVT đại lý hay đặt (thùng 24 lon)")
    void averages_inPreferredUnit() {
        when(orderRepository.countPurchasedOrders(eq(6L), any(), eq(LocalDateTime.of(2026, 7, 10, 0, 0)))).thenReturn(4L);
        when(orderRepository.sumPurchasedAmount(eq(6L), any(), any())).thenReturn(new BigDecimal("12000000"));
        // 4 đơn, tổng 288 lon = 12 thùng
        when(orderRepository.aggregatePurchasedProducts(eq(6L), any(), any())).thenReturn(List.of(
                new PurchasedProductAggregate(10L, new BigDecimal("288"), 4L, LocalDateTime.of(2026, 10, 1, 8, 0))));
        when(productRepository.findAllById(List.of(10L))).thenReturn(List.of(coca));
        when(orderRepository.purchasedUnitUsages(eq(6L), any(), any(), eq(List.of(10L)))).thenReturn(List.of(
                new PurchasedUnitUsage(10L, "Lon", BigDecimal.ONE, 1L, new BigDecimal("24")),
                new PurchasedUnitUsage(10L, "Thùng", new BigDecimal("24"), 3L, new BigDecimal("264"))));
        // Lần trước: 1 thùng giá 230.000 (sửa tay) -> 230.000 / thùng
        when(orderRepository.findLastPurchasedPrices(eq(6L), any(), eq(List.of(10L)))).thenReturn(List.of(
                new PurchasedLastPrice(10L, 99L, 500L, LocalDateTime.of(2026, 10, 1, 8, 0), "Thùng",
                        new BigDecimal("24"), new BigDecimal("10000"), new BigDecimal("230000"))));
        when(priceItemRepository.findEffective(eq(customer.getCustomerGroup()), eq("SP-COCA"), eq(TODAY), any(Pageable.class)))
                .thenReturn(List.of(PriceListItem.builder().price(new BigDecimal("10000")).build()));
        when(inventoryService.resolveWarehouseForCustomer(customer)).thenReturn(warehouse);
        when(inventoryService.getStockInfo(warehouse, coca)).thenReturn(StockInfoDto.of("WH-MB01", "Kho",
                new BigDecimal("1000"), new BigDecimal("50"), new BigDecimal("950")));
        when(orderRepository.findLatestPurchasedOrders(eq(6L), any(), any(Pageable.class))).thenReturn(List.of());

        CustomerPurchaseHistoryResponse res = service.getHistory(6L, null, actor(7, "ROLE_SALES_REP"));

        assertThat(res.months()).isEqualTo(3);
        assertThat(res.threeMonthsSummary().totalOrders()).isEqualTo(4);
        assertThat(res.threeMonthsSummary().startDate()).isEqualTo("2026-07-10");
        assertThat(res.assignedRepId()).isEqualTo(7L);
        CustomerPurchaseHistoryResponse.ProductItem item = res.frequentProducts().get(0);
        assertThat(item.preferredUnit()).isEqualTo("Thùng");
        assertThat(item.totalQuantity3M()).isEqualByComparingTo("12");
        assertThat(item.avgQuantityPerMonth()).isEqualByComparingTo("4");
        assertThat(item.avgQuantityPerOrder()).isEqualByComparingTo("3");
        assertThat(item.lastUnitPrice()).isEqualByComparingTo("230000");
        assertThat(item.currentUnitPrice()).isEqualByComparingTo("240000");
        assertThat(item.availableInPreferredUnit()).isEqualByComparingTo("39");
        assertThat(item.lastOrderedDate()).isEqualTo("2026-10-01");
        assertThat(item.category()).isEqualTo("Nước ngọt");
        assertThat(res.lastOrder()).isNull();
    }

    @Test
    @DisplayName("S4-04: Đơn mua gần nhất trả đủ dòng để thêm nhanh vào đơn mới")
    void lastOrder_itemsForQuickAdd() {
        noPurchasesInPeriod();
        SalesOrder last = SalesOrder.builder().id(99L).code("DH261001-AAAA").customer(customer)
                .status(SalesOrder.STATUS_APPROVED).approvedAt(LocalDateTime.of(2026, 10, 1, 8, 0))
                .totalAmount(new BigDecimal("480000")).build();
        last.addLine(SalesOrderLine.builder().id(1L).product(coca).productSku("SP-COCA").productName("Coca lon")
                .unitName("Thùng").conversionFactor(new BigDecimal("24")).quantity(new BigDecimal("2"))
                .unitPrice(new BigDecimal("10000")).build());
        when(orderRepository.findLatestPurchasedOrders(eq(6L), any(), any(Pageable.class))).thenReturn(List.of(last));

        CustomerPurchaseHistoryResponse res = service.getHistory(6L, 3, actor(3, "ROLE_SALES_MANAGER"));

        assertThat(res.frequentProducts()).isEmpty();
        assertThat(res.lastOrder().orderCode()).isEqualTo("DH261001-AAAA");
        assertThat(res.lastOrder().orderDate()).isEqualTo("2026-10-01");
        assertThat(res.lastOrder().items()).hasSize(1);
        assertThat(res.lastOrder().items().get(0).unitPrice()).isEqualByComparingTo("240000");
        assertThat(res.lastOrder().items().get(0).quantity()).isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("S4-04: Đại lý chưa mua gì -> danh sách rỗng, không có đơn gần nhất")
    void noPurchases_empty() {
        noPurchasesInPeriod();
        when(orderRepository.findLatestPurchasedOrders(eq(6L), any(), any(Pageable.class))).thenReturn(List.of());

        CustomerPurchaseHistoryResponse res = service.getHistory(6L, 3, actor(7, "ROLE_SALES_REP"));

        assertThat(res.frequentProducts()).isEmpty();
        assertThat(res.lastOrder()).isNull();
        assertThat(res.threeMonthsSummary().totalRevenue()).isEqualByComparingTo("0");
        verifyNoInteractions(productRepository, inventoryService);
    }

    @Test
    @DisplayName("S4-04: NV kinh doanh xem đại lý người khác phụ trách -> 404, không truy vấn đơn")
    void otherRep_hidden() {
        assertThatThrownBy(() -> service.getHistory(6L, 3, actor(8, "ROLE_SALES_REP")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.NOT_FOUND);
        verifyNoInteractions(orderRepository);
    }

    @Test
    @DisplayName("S4-04: Số tháng ngoài 1..12 -> 400")
    void invalidMonths_badRequest() {
        assertThatThrownBy(() -> service.getHistory(6L, 13, actor(3, "ROLE_SALES_MANAGER")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_MONTHS");
        assertThatThrownBy(() -> service.getHistory(6L, 0, actor(3, "ROLE_SALES_MANAGER")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("S4-04: Hệ thống chưa có kho -> vẫn trả lịch sử, tồn để trống")
    void noWarehouse_stockNull() {
        when(orderRepository.countPurchasedOrders(eq(6L), any(), any())).thenReturn(1L);
        when(orderRepository.sumPurchasedAmount(eq(6L), any(), any())).thenReturn(new BigDecimal("100000"));
        when(orderRepository.aggregatePurchasedProducts(eq(6L), any(), any())).thenReturn(List.of(
                new PurchasedProductAggregate(10L, new BigDecimal("10"), 1L, LocalDateTime.of(2026, 9, 1, 8, 0))));
        when(productRepository.findAllById(List.of(10L))).thenReturn(List.of(coca));
        when(orderRepository.purchasedUnitUsages(eq(6L), any(), any(), any())).thenReturn(List.of());
        when(orderRepository.findLastPurchasedPrices(eq(6L), any(), any())).thenReturn(List.of());
        when(priceItemRepository.findEffective(any(), any(), any(), any(Pageable.class))).thenReturn(List.of());
        when(inventoryService.resolveWarehouseForCustomer(customer)).thenThrow(BusinessException.notFound("Không có kho"));
        when(orderRepository.findLatestPurchasedOrders(eq(6L), any(), any(Pageable.class))).thenReturn(List.of());

        CustomerPurchaseHistoryResponse.ProductItem item = service.getHistory(6L, 3, actor(3, "ROLE_SALES_MANAGER"))
                .frequentProducts().get(0);

        assertThat(item.preferredUnit()).isEqualTo("Lon");
        assertThat(item.availableStock()).isNull();
        assertThat(item.currentUnitPrice()).isNull();
        assertThat(item.lastUnitPrice()).isNull();
    }
}
