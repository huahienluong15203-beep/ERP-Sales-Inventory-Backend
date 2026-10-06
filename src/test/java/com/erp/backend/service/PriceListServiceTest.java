package com.erp.backend.service;

import com.erp.backend.dto.pricing.*;
import com.erp.backend.entity.*;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.PriceListItemRepository;
import com.erp.backend.repository.PriceListRepository;
import com.erp.backend.repository.ProductRepository;
import com.erp.backend.security.UserDetailsImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PriceListServiceTest {

    @Mock private PriceListRepository priceListRepository;
    @Mock private PriceListItemRepository itemRepository;
    @Mock private ProductRepository productRepository;
    @Mock private AuditLogService auditLogService;
    @Mock private PriceHistoryService priceHistoryService;

    @InjectMocks private PriceListService service;

    private final UserDetailsImpl manager = new UserDetailsImpl(2L, "sales_manager", "QL kinh doanh",
            "m@erp.com", "x", true, List.of(new SimpleGrantedAuthority("ROLE_SALES_MANAGER")));

    private final Product coca = Product.builder().id(10L).sku("SP-COCA").name("Coca lon").baseUnit("Lon").build();
    private final Product pepsi = Product.builder().id(11L).sku("SP-PEPSI").name("Pepsi lon").baseUnit("Lon").build();
    private long nextItemId = 100;

    @BeforeEach
    void setUp() {
        lenient().when(productRepository.findBySku("SP-COCA")).thenReturn(Optional.of(coca));
        lenient().when(productRepository.findBySku("SP-PEPSI")).thenReturn(Optional.of(pepsi));
        lenient().when(priceListRepository.save(any(PriceList.class))).thenAnswer(inv -> {
            PriceList p = inv.getArgument(0);
            if (p.getId() == null) p.setId(1L);
            p.getItems().forEach(i -> { if (i.getId() == null) i.setId(nextItemId++); });
            return p;
        });
    }

    private PriceListItemRequest item(String sku, String price, String floor) {
        PriceListItemRequest r = new PriceListItemRequest();
        r.setProductSku(sku);
        r.setPrice(price == null ? null : new BigDecimal(price));
        r.setFloorPrice(floor == null ? null : new BigDecimal(floor));
        return r;
    }

    private PriceListRequest request(PriceListItemRequest... items) {
        PriceListRequest r = new PriceListRequest();
        r.setCode(" bg-c1-t10 ");
        r.setName("Bảng giá cấp 1 tháng 10");
        r.setCustomerGroup("DEALER_LEVEL_1");
        r.setStartDate(LocalDate.of(2026, 10, 1));
        r.setEndDate(LocalDate.of(2026, 10, 31));
        r.setItems(new ArrayList<>(List.of(items)));
        return r;
    }

    private PriceList existing(boolean hasOrders) {
        PriceList p = PriceList.builder().id(5L).code("BG-OLD").name("Bảng cũ").customerGroup(CustomerGroup.DEALER_LEVEL_2)
                .startDate(LocalDate.of(2026, 9, 1)).status("ACTIVE").version(1).hasOrders(hasOrders).build();
        p.addItem(PriceListItem.builder().id(50L).product(coca).productSku("SP-COCA").productName("Coca lon")
                .price(new BigDecimal("9000")).floorPrice(new BigDecimal("8000")).build());
        lenient().when(priceListRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(p));
        lenient().when(priceListRepository.findById(5L)).thenReturn(Optional.of(p));
        return p;
    }

    @Test
    @DisplayName("S2-10: Tạo bảng giá hợp lệ -> mã viết hoa, đủ dòng giá, tên SP lấy theo danh mục, ghi nhật ký")
    void create_valid() {
        PriceListResponse res = service.create(request(item("sp-coca", "10000", "9000"), item("SP-PEPSI", "9500", "9500")), manager);

        assertThat(res.code()).isEqualTo("BG-C1-T10");
        assertThat(res.customerGroupLabel()).isEqualTo("Đại lý cấp 1");
        assertThat(res.version()).isEqualTo(1);
        assertThat(res.hasOrders()).isFalse();
        assertThat(res.itemsCount()).isEqualTo(2);
        assertThat(res.items()).extracting(PriceListItemResponse::productName).containsExactlyInAnyOrder("Coca lon", "Pepsi lon");
        verify(auditLogService).record(eq(AuditModule.PRICING), eq("CREATE_PRICE_LIST"), any(), any(), any(), any(), any(), any(), eq(manager));
    }

    @Test
    @DisplayName("S2-10: Giá sàn lớn hơn giá bán -> 400 FLOOR_PRICE_TOO_HIGH, không lưu")
    void create_floorAbovePrice() {
        assertThatThrownBy(() -> service.create(request(item("SP-COCA", "10000", "10001")), manager))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("giá sàn")
                .extracting("code").isEqualTo("FLOOR_PRICE_TOO_HIGH");
        verify(priceListRepository, never()).save(any());
    }

    @Test
    @DisplayName("S2-10: Giá bán bằng 0, thiếu giá sàn, giá lẻ quá 2 số -> đều bị chặn")
    void create_invalidPrices() {
        assertThatThrownBy(() -> service.create(request(item("SP-COCA", "0", "0")), manager))
                .extracting("code").isEqualTo("INVALID_PRICE");
        assertThatThrownBy(() -> service.create(request(item("SP-COCA", "1000", null)), manager))
                .extracting("code").isEqualTo("PRICE_REQUIRED");
        assertThatThrownBy(() -> service.create(request(item("SP-COCA", "1000.555", "900")), manager))
                .extracting("code").isEqualTo("INVALID_PRICE");
    }

    @Test
    @DisplayName("S2-10: Ngày kết thúc trước ngày bắt đầu -> 400 INVALID_DATE_RANGE")
    void create_invalidDates() {
        PriceListRequest r = request(item("SP-COCA", "10000", "9000"));
        r.setEndDate(LocalDate.of(2026, 9, 30));

        assertThatThrownBy(() -> service.create(r, manager))
                .extracting("code").isEqualTo("INVALID_DATE_RANGE");
    }

    @Test
    @DisplayName("S2-10: Trùng mã bảng giá -> 409; nhóm khách hàng sai -> 400")
    void create_duplicateCodeOrBadGroup() {
        when(priceListRepository.existsByCodeIgnoreCase("BG-C1-T10")).thenReturn(true);
        assertThatThrownBy(() -> service.create(request(), manager))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(HttpStatus.CONFLICT);

        PriceListRequest r = request();
        r.setCode("BG-OTHER");
        r.setCustomerGroup("VIP");
        assertThatThrownBy(() -> service.create(r, manager))
                .extracting("code").isEqualTo("INVALID_CUSTOMER_GROUP");
    }

    @Test
    @DisplayName("S2-10: SKU không có trong danh mục / bị lặp trong cùng bảng giá -> 400")
    void create_badItems() {
        when(productRepository.findBySku("SP-X")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(request(item("SP-X", "1000", "900")), manager))
                .extracting("code").isEqualTo("PRODUCT_NOT_FOUND");

        assertThatThrownBy(() -> service.create(request(item("SP-COCA", "1000", "900"), item("sp-coca", "1100", "900")), manager))
                .extracting("code").isEqualTo("DUPLICATE_PRICE_ITEM");
    }

    @Test
    @DisplayName("S2-10: Sửa bảng giá chưa có đơn -> sửa giá dòng cũ, thêm dòng mới")
    void update_editable() {
        PriceList p = existing(false);
        PriceListRequest r = request(item("SP-COCA", "9500", "8500"), item("SP-PEPSI", "9000", "8000"));
        r.setCode("BG-OLD");

        PriceListResponse res = service.update(5L, r, manager);

        assertThat(res.itemsCount()).isEqualTo(2);
        PriceListItem cocaItem = p.getItems().stream().filter(i -> i.getId() == 50L).findFirst().orElseThrow();
        assertThat(cocaItem.getPrice()).isEqualByComparingTo("9500");
        assertThat(res.customerGroup()).isEqualTo("DEALER_LEVEL_1");
    }

    @Test
    @DisplayName("S2-10: Bảng giá đã phát sinh đơn -> không sửa, không sửa/xoá dòng giá (409 PRICE_LIST_LOCKED)")
    void lockedPriceList_cannotBeEdited() {
        existing(true);

        assertThatThrownBy(() -> service.update(5L, request(), manager))
                .extracting("code").isEqualTo("PRICE_LIST_LOCKED");
        assertThatThrownBy(() -> service.upsertItem(5L, item("SP-COCA", "1", "1"), manager))
                .extracting("code").isEqualTo("PRICE_LIST_LOCKED");
        assertThatThrownBy(() -> service.deleteItem(5L, 50L, manager))
                .hasMessageContaining("phát sinh đơn");
        verify(priceListRepository, never()).save(any());
    }

    @Test
    @DisplayName("S2-10: Tạo phiên bản mới từ bảng đã khoá -> v2, mã -V2, sao chép dòng giá, chưa phát sinh đơn")
    void cloneVersion_copiesItems() {
        PriceList source = existing(true);
        PriceListRequest r = new PriceListRequest();
        r.setStartDate(LocalDate.of(2026, 11, 1));

        PriceListResponse res = service.cloneVersion(5L, r, manager);

        assertThat(res.code()).isEqualTo("BG-OLD-V2");
        assertThat(res.version()).isEqualTo(2);
        assertThat(res.hasOrders()).isFalse();
        assertThat(res.sourcePriceListId()).isEqualTo(5L);
        assertThat(res.items()).hasSize(1);
        assertThat(res.items().get(0).price()).isEqualByComparingTo("9000");
        assertThat(source.getItems()).hasSize(1);
    }

    @Test
    @DisplayName("S2-10: Mã phiên bản mới không nối đuôi lặp và không quá 40 ký tự")
    void defaultVersionCode() {
        assertThat(PriceListService.defaultVersionCode("BG-OLD", 2)).isEqualTo("BG-OLD-V2");
        assertThat(PriceListService.defaultVersionCode("BG-OLD-V2", 3)).isEqualTo("BG-OLD-V3");
        assertThat(PriceListService.defaultVersionCode("A".repeat(40), 12)).hasSize(40).endsWith("-V12");
    }

    @Test
    @DisplayName("S2-10: SKU lưu chữ thường (nhập Excel) vẫn thêm vào bảng giá được")
    void lowercaseSkuProduct_found() {
        Product imported = Product.builder().id(12L).sku("sp-tra").name("Trà xanh").baseUnit("Chai").build();
        when(productRepository.findFirstBySkuIgnoreCaseOrderByIdAsc("SP-TRA")).thenReturn(Optional.of(imported));

        PriceListResponse res = service.create(request(item("sp-tra", "8000", "7000")), manager);

        assertThat(res.items()).extracting(PriceListItemResponse::productId).containsExactly(12L);
    }

    @Test
    @DisplayName("S2-10: Thêm dòng giá mới và sửa giá dòng đã có")
    void upsertItem_addAndUpdate() {
        PriceList p = existing(false);

        service.upsertItem(5L, item("SP-PEPSI", "9000", "8000"), manager);
        service.upsertItem(5L, item("SP-COCA", "9900", "8800"), manager);

        assertThat(p.getItems()).hasSize(2);
        assertThat(p.getItems().stream().filter(i -> i.getProduct() == coca).findFirst().orElseThrow().getPrice())
                .isEqualByComparingTo("9900");
        verify(auditLogService).record(eq(AuditModule.PRICING), eq("UPDATE_PRICE_ITEM"), any(), any(), any(),
                eq("giá bán 9000, giá sàn 8000"), eq("giá bán 9900, giá sàn 8800"), any(), eq(manager));
    }

    @Test
    @DisplayName("S2-10: Xoá dòng giá không tồn tại -> 404")
    void deleteItem_notFound() {
        existing(false);

        assertThatThrownBy(() -> service.deleteItem(5L, 999L, manager))
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("S2-10: Tra giá đang hiệu lực -> trả giá bán và giá sàn; không có -> 404 'Không tìm thấy ... hiệu lực'")
    void lookup() {
        PriceList p = existing(false);
        LocalDate day = LocalDate.of(2026, 9, 15);
        when(itemRepository.findEffective(eq(CustomerGroup.DEALER_LEVEL_2), eq("SP-COCA"), eq(day), any(Pageable.class)))
                .thenReturn(List.of(p.getItems().get(0)));

        PriceLookupResponse res = service.lookup("DEALER_LEVEL_2", " sp-coca ", day);
        assertThat(res.price()).isEqualByComparingTo("9000");
        assertThat(res.floorPrice()).isEqualByComparingTo("8000");
        assertThat(res.priceListCode()).isEqualTo("BG-OLD");

        when(itemRepository.findEffective(eq(CustomerGroup.RETAIL), any(), any(), any(Pageable.class))).thenReturn(List.of());
        assertThatThrownBy(() -> service.lookup("RETAIL", "SP-COCA", day))
                .hasMessageContaining("Không tìm thấy").hasMessageContaining("hiệu lực");
    }

    @SuppressWarnings("unchecked")
    private List<PriceChange> capturedChanges() {
        ArgumentCaptor<List<PriceChange>> captor = ArgumentCaptor.forClass(List.class);
        verify(priceHistoryService).record(any(PriceList.class), captor.capture(), eq(manager));
        return captor.getValue();
    }

    @Test
    @DisplayName("S3-02: Tạo bảng giá -> mỗi dòng giá ghi 1 lịch sử 'Thêm giá'")
    void history_onCreate() {
        service.create(request(item("SP-COCA", "10000", "9000"), item("SP-PEPSI", "9500", "9000")), manager);

        List<PriceChange> changes = capturedChanges();
        assertThat(changes).hasSize(2).allMatch(c -> c.changeType().equals(PriceHistory.CREATE) && c.oldPrice() == null);
    }

    @Test
    @DisplayName("S3-02: Sửa bảng giá -> ghi giá cũ/giá mới cho dòng đổi giá, 'Bỏ giá' cho dòng bị xoá, bỏ qua dòng không đổi")
    void history_onUpdate() {
        PriceList p = existing(false);
        p.addItem(PriceListItem.builder().id(51L).product(pepsi).productSku("SP-PEPSI").productName("Pepsi lon")
                .price(new BigDecimal("8000")).floorPrice(new BigDecimal("7000")).build());
        PriceListRequest r = request(item("SP-COCA", "9500", "8000"));
        r.setCode("BG-OLD");

        service.update(5L, r, manager);

        List<PriceChange> changes = capturedChanges();
        assertThat(changes).hasSize(2);
        PriceChange update = changes.stream().filter(c -> c.changeType().equals(PriceHistory.UPDATE)).findFirst().orElseThrow();
        assertThat(update.oldPrice()).isEqualByComparingTo("9000");
        assertThat(update.newPrice()).isEqualByComparingTo("9500");
        PriceChange delete = changes.stream().filter(c -> c.changeType().equals(PriceHistory.DELETE)).findFirst().orElseThrow();
        assertThat(delete.product()).isSameAs(pepsi);
        assertThat(delete.newPrice()).isNull();
    }

    @Test
    @DisplayName("S3-02: Sửa dòng giá nhưng giữ nguyên giá -> không ghi lịch sử")
    void history_unchangedPriceNotRecorded() {
        existing(false);

        service.upsertItem(5L, item("SP-COCA", "9000", "8000"), manager);

        assertThat(capturedChanges()).isEmpty();
    }

    @Test
    @DisplayName("S2-10: Danh sách lọc + phân trang phía server, trả kèm dòng giá")
    @SuppressWarnings("unchecked")
    void search_pagedOnServer() {
        PriceList a = existing(false);
        when(priceListRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class), any(Pageable.class)))
                .thenAnswer(inv -> new org.springframework.data.domain.PageImpl<>(List.of(a), inv.getArgument(1), 41));

        com.erp.backend.dto.user.PageResponse<PriceListResponse> res = service.search("dealer_level_1", "ACTIVE", "cũ", 2, 20);

        assertThat(res.content()).extracting(PriceListResponse::code).containsExactly("BG-OLD");
        assertThat(res.content().get(0).items()).isNotNull();
        assertThat(res.page()).isEqualTo(2);
        assertThat(res.totalElements()).isEqualTo(41);
        assertThat(res.totalPages()).isEqualTo(3);
    }

    @Test
    @DisplayName("S2-10: Kích thước trang được giới hạn 1..100, trang âm về 0")
    @SuppressWarnings("unchecked")
    void search_clampsPaging() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        when(priceListRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class), captor.capture()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        service.search(null, null, null, -3, 5000);

        assertThat(captor.getValue().getPageNumber()).isZero();
        assertThat(captor.getValue().getPageSize()).isEqualTo(100);
    }

    @Test
    @DisplayName("S2-10: Nhóm khách hàng lọc không hợp lệ -> 400")
    void search_invalidGroup() {
        assertThatThrownBy(() -> service.search("VIP", null, null, 0, 20)).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("S2-10: Thống kê tính trên toàn bộ dữ liệu, không theo trang")
    void stats_countsAll() {
        when(priceListRepository.count()).thenReturn(7L);
        when(priceListRepository.countByStatus("ACTIVE")).thenReturn(5L);
        when(priceListRepository.countByCustomerGroup(CustomerGroup.DEALER_LEVEL_1)).thenReturn(3L);
        when(priceListRepository.countByCustomerGroup(CustomerGroup.DEALER_LEVEL_2)).thenReturn(2L);
        when(priceListRepository.countByCustomerGroup(CustomerGroup.RETAIL)).thenReturn(2L);
        when(priceListRepository.countByHasOrdersTrue()).thenReturn(1L);

        assertThat(service.stats()).isEqualTo(new PriceListStatsResponse(7, 5, 3, 2, 2, 1));
    }
}
