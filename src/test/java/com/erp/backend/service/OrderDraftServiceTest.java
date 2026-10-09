package com.erp.backend.service;

import com.erp.backend.dto.customer.CreditStatusResponse;
import com.erp.backend.dto.discount.DiscountCalculationResponse;
import com.erp.backend.dto.order.*;
import com.erp.backend.entity.*;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.*;
import com.erp.backend.security.UserDetailsImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.erp.backend.service.CustomerTestData.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderDraftServiceTest {

    @Mock private SalesOrderRepository orderRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private CustomerDeliveryAddressRepository addressRepository;
    @Mock private ProductRepository productRepository;
    @Mock private PriceListItemRepository priceItemRepository;
    @Mock private PriceListRepository priceListRepository;
    @Mock private DiscountPolicyService discountPolicyService;
    @Mock private CustomerService customerService;
    @Mock private CustomerCreditService creditService;

    @InjectMocks private OrderDraftService service;

    private final UserDetailsImpl rep = actor(7, "ROLE_SALES_REP");
    private Customer customer;
    private Product coca;
    private CustomerDeliveryAddress defaultAddress;
    private final PriceList priceList = PriceList.builder().id(1L).code("BG-C1").name("Cấp 1")
            .customerGroup(CustomerGroup.DEALER_LEVEL_1).startDate(LocalDate.of(2026, 1, 1)).build();

    @BeforeEach
    void setUp() {
        customer = customer(6, salesRep(7));
        coca = Product.builder().id(10L).sku("SP-COCA").name("Coca lon").baseUnit("Lon").status("ACTIVE").build();
        coca.addUnitConversion(ProductUnitConversion.builder().id(1L).unitName("Thùng")
                .conversionFactor(new BigDecimal("24")).status("ACTIVE").build());
        defaultAddress = CustomerDeliveryAddress.builder().id(3L).customer(customer).address("12 Trần Phú")
                .receiverName("Chị Lan").receiverPhone("0912345678").defaultAddress(true).status("ACTIVE").build();

        lenient().when(customerRepository.findById(6L)).thenReturn(Optional.of(customer));
        lenient().when(productRepository.findBySku("SP-COCA")).thenReturn(Optional.of(coca));
        lenient().when(addressRepository.findByCustomer_IdAndStatusOrderByDefaultAddressDescIdAsc(6L, "ACTIVE"))
                .thenReturn(List.of(defaultAddress));
        lenient().when(priceItemRepository.findEffective(eq(CustomerGroup.DEALER_LEVEL_1), eq("SP-COCA"), any(), any(Pageable.class)))
                .thenReturn(List.of(PriceListItem.builder().priceList(priceList).product(coca).productSku("SP-COCA")
                        .productName("Coca lon").price(new BigDecimal("10000")).floorPrice(new BigDecimal("9000")).build()));
        lenient().when(discountPolicyService.calculate(any(), any(Product.class), any(), any(), any())).thenAnswer(inv -> {
            BigDecimal qty = inv.getArgument(2);
            BigDecimal price = inv.getArgument(3);
            BigDecimal gross = price.multiply(qty).setScale(2, RoundingMode.HALF_UP);
            // giả lập: từ 96 lon giảm 5%
            BigDecimal discount = qty.compareTo(new BigDecimal("96")) >= 0
                    ? gross.multiply(new BigDecimal("0.05")).setScale(2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO.setScale(2);
            DiscountCalculationResponse.Candidate applied = discount.signum() > 0
                    ? new DiscountCalculationResponse.Candidate(1L, "CK-COCA", "CK", "PRODUCT", "PERCENT",
                    new BigDecimal("96"), new BigDecimal("5"), new BigDecimal("500"), discount)
                    : null;
            return new DiscountCalculationResponse(10L, "SP-COCA", qty, price, gross, discount, gross.subtract(discount),
                    applied, applied == null ? List.of() : List.of(applied));
        });
        lenient().when(orderRepository.saveAndFlush(any(SalesOrder.class))).thenAnswer(inv -> {
            SalesOrder o = inv.getArgument(0);
            if (o.getId() == null) o.setId(100L);
            return o;
        });
    }

    private OrderLineRequest line(String sku, String unit, String qty) {
        return line(sku, unit, qty, null);
    }

    private OrderLineRequest line(String sku, String unit, String qty, String unitPrice) {
        OrderLineRequest l = new OrderLineRequest();
        l.setProductSku(sku);
        l.setUnitName(unit);
        l.setQuantity(qty == null ? null : new BigDecimal(qty));
        l.setUnitPrice(unitPrice == null ? null : new BigDecimal(unitPrice));
        return l;
    }

    private OrderDraftRequest request(OrderLineRequest... lines) {
        OrderDraftRequest r = new OrderDraftRequest();
        r.setCustomerId(6L);
        r.setLines(new ArrayList<>(List.of(lines)));
        return r;
    }

    @Test
    @DisplayName("S3-09: 5 thùng = 120 lon, giá theo bảng giá nhóm cấp 1, chiết khấu tự động, tổng phải thu")
    void preview_calculatesTotals() {
        OrderResponse res = service.preview(request(line("sp-coca", "thùng", "5")), rep);

        assertThat(res.id()).isNull();
        OrderLineResponse l = res.lines().get(0);
        assertThat(l.unitName()).isEqualTo("Thùng");
        assertThat(l.baseQuantity()).isEqualByComparingTo("120");
        assertThat(l.unitPrice()).isEqualByComparingTo("10000");
        assertThat(l.pricePerUnit()).isEqualByComparingTo("240000");
        assertThat(l.priceListCode()).isEqualTo("BG-C1");
        assertThat(l.discountPolicyCode()).isEqualTo("CK-COCA");
        assertThat(res.subtotal()).isEqualByComparingTo("1200000");
        assertThat(res.discountTotal()).isEqualByComparingTo("60000");
        assertThat(res.totalAmount()).isEqualByComparingTo("1140000");
        assertThat(res.deliveryAddress().id()).isEqualTo(3L);
        verify(orderRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("S3-09: Lưu nháp -> có mã đơn DH..., trạng thái DRAFT, người tạo; mở lại xem được")
    void createDraft_thenReopen() {
        OrderResponse saved = service.createDraft(request(line("SP-COCA", null, "10")), rep);

        assertThat(saved.id()).isEqualTo(100L);
        assertThat(saved.code()).startsWith("DH").hasSize(13);
        assertThat(saved.status()).isEqualTo("DRAFT");
        assertThat(saved.createdByUsername()).isEqualTo("user7");
        assertThat(saved.lines().get(0).unitName()).isEqualTo("Lon");
        assertThat(saved.discountTotal()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("S3-09: Mở lại đơn nháp và lưu tiếp -> thay dòng hàng, tính lại tổng")
    void updateDraft_replacesLines() {
        SalesOrder draft = SalesOrder.builder().id(100L).code("DH261004-AAAA").customer(customer).status("DRAFT").build();
        when(orderRepository.findById(100L)).thenReturn(Optional.of(draft));

        OrderResponse res = service.updateDraft(100L, request(line("SP-COCA", "Lon", "50")), rep);

        assertThat(res.lines()).hasSize(1);
        assertThat(res.totalAmount()).isEqualByComparingTo("500000");
        assertThat(res.code()).isEqualTo("DH261004-AAAA");
    }

    @Test
    @DisplayName("S3-09: Đơn không còn ở trạng thái nháp -> không sửa được (409)")
    void updateDraft_notDraft() {
        SalesOrder order = SalesOrder.builder().id(100L).code("DH1").customer(customer).status("SUBMITTED").build();
        when(orderRepository.findById(100L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.updateDraft(100L, request(), rep))
                .extracting("code").isEqualTo("ORDER_NOT_DRAFT");
    }

    @Test
    @DisplayName("S3-09: NV kinh doanh tạo đơn cho đại lý không phụ trách -> 404, không lưu")
    void otherRepsCustomer_hidden() {
        assertThatThrownBy(() -> service.createDraft(request(line("SP-COCA", null, "1")), actor(8, "ROLE_SALES_REP")))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
        verify(orderRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("S3-09: Đại lý bị khoá giao dịch -> chặn tạo đơn (dùng kiểm tra của S3-07)")
    void lockedCustomer_blocked() {
        doThrow(BusinessException.forbidden("CUSTOMER_TRANSACTION_LOCKED", "Đại lý đang bị khóa giao dịch"))
                .when(customerService).assertCanCreateOrder(customer);

        assertThatThrownBy(() -> service.preview(request(), rep))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "CUSTOMER_TRANSACTION_LOCKED")
                .hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT);
    }

    private void lockCustomer() {
        customer.setTransactionLocked(true);
        customer.setTransactionLockReason("Nợ quá hạn");
        lenient().doThrow(BusinessException.forbidden("CUSTOMER_TRANSACTION_LOCKED", "Đại lý đang bị khóa giao dịch"))
                .when(customerService).assertCanCreateOrder(customer);
    }

    @Test
    @DisplayName("S3-07 AC3: Đơn nháp tạo trước khi khoá -> vẫn sửa tiếp được, có cảnh báo")
    void lockedCustomer_existingDraft_canContinueWithWarning() {
        lockCustomer();
        SalesOrder draft = SalesOrder.builder().id(100L).code("DH261004-AAAA").customer(customer).status("DRAFT").build();
        when(orderRepository.findById(100L)).thenReturn(Optional.of(draft));

        OrderResponse res = service.updateDraft(100L, request(line("SP-COCA", "Lon", "50")), rep);

        assertThat(res.totalAmount()).isEqualByComparingTo("500000");
        assertThat(res.warnings()).hasSize(1);
        assertThat(res.warnings().get(0)).contains("đang bị khoá giao dịch").contains("Nợ quá hạn");
        verify(customerService, never()).assertCanCreateOrder(any());
    }

    @Test
    @DisplayName("S3-07 AC3: Xem trước khi đang sửa đơn nháp của đại lý bị khoá -> được, có cảnh báo")
    void lockedCustomer_previewOfExistingDraft_allowed() {
        lockCustomer();
        SalesOrder draft = SalesOrder.builder().id(100L).code("DH261004-AAAA").customer(customer).status("DRAFT").build();
        when(orderRepository.findById(100L)).thenReturn(Optional.of(draft));
        OrderDraftRequest req = request(line("SP-COCA", "Lon", "5"));
        req.setDraftId(100L);

        OrderResponse res = service.preview(req, rep);

        assertThat(res.totalAmount()).isEqualByComparingTo("50000");
        assertThat(res.warnings()).hasSize(1);
    }

    @Test
    @DisplayName("S3-07: Sửa đơn nháp nhưng đổi sang đại lý khác đang bị khoá -> vẫn chặn")
    void lockedCustomer_switchToOtherLockedCustomer_blocked() {
        Customer other = customer(9, salesRep(7));
        SalesOrder draft = SalesOrder.builder().id(100L).code("DH261004-AAAA").customer(other).status("DRAFT").build();
        when(orderRepository.findById(100L)).thenReturn(Optional.of(draft));
        lockCustomer();

        assertThatThrownBy(() -> service.updateDraft(100L, request(line("SP-COCA", "Lon", "5")), rep))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "CUSTOMER_TRANSACTION_LOCKED")
                .hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT);
        verify(orderRepository, never()).saveAndFlush(any());
    }

    private static CreditStatusResponse credit(boolean exceeds, String message) {
        return new CreditStatusResponse(6L, "DL006", "Đại lý", new BigDecimal("1000000"), new BigDecimal("900000"),
                new BigDecimal("100000"), new BigDecimal("500000"), new BigDecimal("1400000"), exceeds,
                exceeds ? new BigDecimal("400000") : BigDecimal.ZERO, 30, false, 0, BigDecimal.ZERO, 0, false, message);
    }

    @Test
    @DisplayName("S4-02: Xem trước đơn vượt hạn mức -> trả về công nợ, cờ cần duyệt và cảnh báo")
    void preview_overCreditLimit_flaggedNeedsApproval() {
        when(creditService.evaluate(eq(customer), any())).thenReturn(credit(true, "Công nợ sau đơn vượt hạn mức. Đơn cần được duyệt"));

        OrderResponse res = service.preview(request(line("SP-COCA", "Lon", "50")), rep);

        verify(creditService).evaluate(eq(customer), argThat(a -> a.compareTo(new BigDecimal("500000")) == 0));
        assertThat(res.credit()).isNotNull();
        assertThat(res.credit().exceedsLimit()).isTrue();
        assertThat(res.credit().currentDebt()).isEqualByComparingTo("900000");
        assertThat(res.warnings()).containsExactly("Công nợ sau đơn vượt hạn mức. Đơn cần được duyệt");
    }

    @Test
    @DisplayName("S4-02: Đại lý nợ quá hạn -> chặn tạo đơn nháp mới (409)")
    void overdueCustomer_createDraftBlocked() {
        doThrow(BusinessException.conflict("CUSTOMER_DEBT_OVERDUE", "Nợ quá hạn", "customerId"))
                .when(customerService).assertCanCreateOrder(customer);

        assertThatThrownBy(() -> service.createDraft(request(line("SP-COCA", null, "1")), rep))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "CUSTOMER_DEBT_OVERDUE")
                .hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT);
        verify(orderRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("S4-02: Đơn đã duyệt -> không tính lại công nợ (tiền đơn đã nằm trong công nợ)")
    void approvedOrder_noCreditEvaluation() {
        SalesOrder approved = SalesOrder.builder().id(100L).code("DH261004-AAAA").customer(customer)
                .status(SalesOrder.STATUS_APPROVED).build();
        when(orderRepository.findById(100L)).thenReturn(Optional.of(approved));

        OrderResponse res = service.getById(100L, rep);

        assertThat(res.credit()).isNull();
        verify(creditService, never()).evaluate(any(), any());
    }

    @Test
    @DisplayName("S3-07: Đại lý không bị khoá -> không có cảnh báo")
    void unlockedCustomer_noWarnings() {
        assertThat(service.preview(request(line("SP-COCA", null, "1")), rep).warnings()).isEmpty();
    }

    @Test
    @DisplayName("S3-09: SKU chưa có giá hiệu lực của nhóm khách -> chặn thêm dòng, nói rõ lý do")
    void noPrice_blocked() {
        Product pepsi = Product.builder().id(11L).sku("SP-PEPSI").name("Pepsi").baseUnit("Lon").status("ACTIVE").build();
        when(productRepository.findBySku("SP-PEPSI")).thenReturn(Optional.of(pepsi));
        when(priceItemRepository.findEffective(eq(CustomerGroup.DEALER_LEVEL_1), eq("SP-PEPSI"), any(), any(Pageable.class)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.preview(request(line("SP-PEPSI", null, "1")), rep))
                .hasMessageContaining("Dòng 1").hasMessageContaining("chưa có giá")
                .extracting("code").isEqualTo("NO_EFFECTIVE_PRICE");
    }

    @Test
    @DisplayName("S3-09: Dòng hàng sai -> chặn (đơn vị chưa khai báo, số lượng 0, trùng SKU, SP ngừng kinh doanh)")
    void invalidLines() {
        assertThatThrownBy(() -> service.preview(request(line("SP-COCA", "Két", "1")), rep))
                .extracting("code").isEqualTo("UNIT_NOT_FOUND");
        assertThatThrownBy(() -> service.preview(request(line("SP-COCA", null, "0")), rep))
                .extracting("code").isEqualTo("INVALID_QUANTITY");
        assertThatThrownBy(() -> service.preview(request(line("SP-COCA", null, "1000001")), rep))
                .extracting("code").isEqualTo("INVALID_QUANTITY");
        assertThat(service.preview(request(line("SP-COCA", null, "1.50000")), rep).lines()).hasSize(1);
        assertThatThrownBy(() -> service.preview(request(line("SP-COCA", null, "1"), line("sp-coca", "Thùng", "1")), rep))
                .hasMessageContaining("Dòng 2")
                .extracting("code").isEqualTo("DUPLICATE_ORDER_LINE");

        coca.setStatus("INACTIVE");
        assertThatThrownBy(() -> service.preview(request(line("SP-COCA", null, "1")), rep))
                .extracting("code").isEqualTo("PRODUCT_INACTIVE");
    }

    @Test
    @DisplayName("S3-09: Điểm giao của đại lý khác / ngày giao trong quá khứ -> 400")
    void invalidHeader() {
        OrderDraftRequest r = request();
        r.setDeliveryAddressId(99L);
        when(addressRepository.findByIdAndCustomer_Id(99L, 6L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.preview(r, rep)).extracting("code").isEqualTo("INVALID_DELIVERY_ADDRESS");

        OrderDraftRequest r2 = request();
        r2.setDesiredDeliveryDate(LocalDate.now(OrderDraftService.VN_ZONE).minusDays(1));
        assertThatThrownBy(() -> service.preview(r2, rep)).extracting("code").isEqualTo("INVALID_DELIVERY_DATE");
    }

    @Test
    @DisplayName("S3-09: Gợi ý sản phẩm -> có đơn vị tính (Lon, Thùng) và giá theo nhóm khách")
    void productOptions() {
        when(productRepository.findByNameContainingIgnoreCaseOrSkuContainingIgnoreCase(eq("coca"), eq("coca"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(coca)));
        when(productRepository.findByNameContainingIgnoreCaseOrSkuContainingIgnoreCase(eq("c"), eq("c"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        List<ProductOptionResponse> res = service.productOptions(6L, "coca", rep);

        assertThat(res).hasSize(1);
        assertThat(res.get(0).units()).extracting(ProductOptionResponse.UnitOption::unitName).containsExactly("Lon", "Thùng");
        assertThat(res.get(0).priceAvailable()).isTrue();
        assertThat(res.get(0).unitPrice()).isEqualByComparingTo("10000");
        assertThat(service.productOptions(6L, "c", rep)).isEmpty();
    }

    @Test
    @DisplayName("S4-01: Sửa giá thủ công trên giá sàn -> áp dụng giá mới, tính lại chiết khấu, không vi phạm giá sàn")
    void preview_manualPriceAboveFloor() {
        // Giá niêm yết: 240.000 đ/thùng (10.000 đ/lon), giá sàn: 9.000 đ/lon (216.000 đ/thùng)
        // Nhân viên sửa giá thủ công thành 230.000 đ/thùng (9.583 đ/lon > 9.000 đ)
        OrderResponse res = service.preview(request(line("sp-coca", "thùng", "5", "230000")), rep);

        OrderLineResponse l = res.lines().get(0);
        assertThat(l.isCustomPrice()).isTrue();
        assertThat(l.isBelowFloor()).isFalse();
        assertThat(l.pricePerUnit()).isEqualByComparingTo("230000");
        assertThat(res.subtotal()).isEqualByComparingTo("1150000"); // 5 * 230.000
        assertThat(res.discountTotal()).isEqualByComparingTo("57500"); // 5% trên 1.150.000
        assertThat(res.totalAmount()).isEqualByComparingTo("1092500");
        assertThat(res.warnings()).doesNotContain("giá sàn");
        assertThat(res.approvalReasons()).isEmpty();
    }

    @Test
    @DisplayName("S4-01: Sửa giá thủ công dưới giá sàn -> đánh dấu dòng vi phạm, thêm cảnh báo và lý do cần duyệt")
    void preview_manualPriceBelowFloor() {
        // Giá sàn: 9.000 đ/lon (216.000 đ/thùng). Sửa thành 200.000 đ/thùng (8.333 đ/lon < 9.000 đ/lon)
        OrderResponse res = service.preview(request(line("sp-coca", "thùng", "5", "200000")), rep);

        OrderLineResponse l = res.lines().get(0);
        assertThat(l.isCustomPrice()).isTrue();
        assertThat(l.isBelowFloor()).isTrue();
        assertThat(l.pricePerUnit()).isEqualByComparingTo("200000");
        assertThat(res.subtotal()).isEqualByComparingTo("1000000"); // 5 * 200.000
        assertThat(res.discountTotal()).isEqualByComparingTo("50000"); // 5% trên 1.000.000
        assertThat(res.totalAmount()).isEqualByComparingTo("950000");
        assertThat(res.warnings()).anyMatch(w -> w.contains("bán dưới giá sàn quy định"));
        assertThat(res.approvalReasons()).extracting(ApprovalReason::code).contains(ApprovalReason.BELOW_FLOOR_PRICE);
    }

    @Test
    @DisplayName("S4-01: Sửa giá âm -> ném lỗi 400 INVALID_UNIT_PRICE")
    void preview_negativePrice_throwsBadRequest() {
        OrderDraftRequest r = request(line("sp-coca", "thùng", "1", "-50000"));
        assertThatThrownBy(() -> service.preview(r, rep))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_UNIT_PRICE");
    }

    @Test
    @DisplayName("S4-01: Không có bảng giá hiệu lực cho SKU -> chặn thêm dòng kèm lý do rõ ràng")
    void preview_noEffectivePrice_throwsBadRequestWithReason() {
        Product pepsi = Product.builder().id(20L).sku("SP-PEPSI").name("Pepsi").baseUnit("Lon").status("ACTIVE").build();
        when(productRepository.findBySku("SP-PEPSI")).thenReturn(Optional.of(pepsi));
        when(priceItemRepository.findEffective(eq(CustomerGroup.DEALER_LEVEL_1), eq("SP-PEPSI"), any(), any(Pageable.class)))
                .thenReturn(List.of());

        OrderDraftRequest r = request(line("SP-PEPSI", "Lon", "10"));
        assertThatThrownBy(() -> service.preview(r, rep))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "NO_EFFECTIVE_PRICE")
                .hasMessageContaining("SP-PEPSI")
                .hasMessageContaining("chưa có giá trong bảng giá đang hiệu lực của nhóm");
    }

    @Test
    @DisplayName("S4-01: Chốt đơn bảo toàn đơn giá sửa thủ công của dòng hàng")
    void recalculateForSubmit_preservesCustomPrice() {
        SalesOrder order = SalesOrder.builder().id(100L).code("DH261008-0001").customer(customer).status(SalesOrder.STATUS_DRAFT).build();
        SalesOrderLine line = SalesOrderLine.builder()
                .lineNo(1)
                .product(coca)
                .productSku("SP-COCA")
                .productName("Coca")
                .unitName("Thùng")
                .conversionFactor(new BigDecimal("24"))
                .quantity(new BigDecimal("5"))
                .baseUnit("Lon")
                .baseQuantity(new BigDecimal("120"))
                .priceList(priceList)
                .unitPrice(new BigDecimal("8333.33"))
                .pricePerUnit(new BigDecimal("200000"))
                .isCustomPrice(true)
                .floorPrice(new BigDecimal("9000"))
                .grossAmount(new BigDecimal("1000000"))
                .discountAmount(new BigDecimal("50000"))
                .netAmount(new BigDecimal("950000"))
                .build();
        order.addLine(line);

        service.recalculateForSubmit(order, rep);

        SalesOrderLine updated = order.getLines().get(0);
        assertThat(updated.getIsCustomPrice()).isTrue();
        assertThat(updated.getPricePerUnit()).isEqualByComparingTo("200000");
        assertThat(order.getBelowFloorLineCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("S4-01: Gợi ý sản phẩm khi có bảng giá hiệu lực -> trả về giá sàn và liệt kê cả SKU chưa có giá kèm lý do chặn")
    void productOptions_withEffectivePriceList_includesUnpricedCatalogMatches() {
        when(priceListRepository.findEffectiveByCustomerGroup(eq(CustomerGroup.DEALER_LEVEL_1), any()))
                .thenReturn(List.of(priceList));
        PriceListItem item = PriceListItem.builder().priceList(priceList).product(coca).productSku("SP-COCA")
                .productName("Coca lon").price(new BigDecimal("10000")).floorPrice(new BigDecimal("9000")).build();
        when(priceItemRepository.findByPriceListIdAndKeyword(eq(priceList.getId()), eq("coca"), any(Pageable.class)))
                .thenReturn(List.of(item));

        Product sprite = Product.builder().id(30L).sku("SP-SPRITE-COCA").name("Sprite Coca").baseUnit("Lon").status("ACTIVE").build();
        when(productRepository.findByNameContainingIgnoreCaseOrSkuContainingIgnoreCase(eq("coca"), eq("coca"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(coca, sprite)));

        List<ProductOptionResponse> res = service.productOptions(6L, "coca", rep);

        assertThat(res).hasSize(2);
        // Sản phẩm 1: Có trong bảng giá
        ProductOptionResponse opt1 = res.get(0);
        assertThat(opt1.sku()).isEqualTo("SP-COCA");
        assertThat(opt1.priceAvailable()).isTrue();
        assertThat(opt1.unitPrice()).isEqualByComparingTo("10000");
        assertThat(opt1.floorPrice()).isEqualByComparingTo("9000");
        assertThat(opt1.priceListCode()).isEqualTo("BG-C1");
        assertThat(opt1.message()).isNull();

        // Sản phẩm 2: Khớp từ khóa nhưng chưa có trong bảng giá
        ProductOptionResponse opt2 = res.get(1);
        assertThat(opt2.sku()).isEqualTo("SP-SPRITE-COCA");
        assertThat(opt2.priceAvailable()).isFalse();
        assertThat(opt2.unitPrice()).isNull();
        assertThat(opt2.message()).contains("SP-SPRITE-COCA").contains("chưa có giá trong bảng giá đang hiệu lực của nhóm " + customer.getCustomerGroup().getLabel());
    }
}
