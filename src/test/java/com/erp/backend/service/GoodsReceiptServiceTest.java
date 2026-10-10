package com.erp.backend.service;

import com.erp.backend.dto.inventory.receipt.*;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GoodsReceiptServiceTest {

    @Mock private GoodsReceiptRepository goodsReceiptRepository;
    @Mock private GoodsReceiptLineRepository goodsReceiptLineRepository;
    @Mock private ProductLotRepository productLotRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private WarehouseRepository warehouseRepository;
    @Mock private ProductRepository productRepository;
    @Mock private InventoryRepository inventoryRepository;
    @Mock private UserRepository userRepository;

    @InjectMocks private GoodsReceiptService goodsReceiptService;

    private UserDetailsImpl whStaffActor;
    private Supplier sampleSupplier;
    private Warehouse sampleWarehouse;
    private Product sampleProduct;

    @BeforeEach
    void setUp() {
        whStaffActor = new UserDetailsImpl(
                1L, "wh_staff", "Nhân Viên Kho", "wh@erp.com", "pass", true,
                List.of(new SimpleGrantedAuthority("ROLE_WAREHOUSE"))
        );

        sampleSupplier = Supplier.builder()
                .id(1L)
                .code("NCC-VNM")
                .name("Công ty Cổ phần Sữa Việt Nam")
                .status("ACTIVE")
                .build();

        sampleWarehouse = Warehouse.builder()
                .id(10L)
                .code("WH-MB01")
                .name("Kho Tổng Miền Bắc")
                .status("ACTIVE")
                .build();

        sampleProduct = Product.builder()
                .id(100L)
                .sku("SP-MILK-180")
                .name("Sữa tươi tiệt trùng 180ml")
                .baseUnit("Hộp")
                .category("Sữa")
                .status("ACTIVE")
                .build();
    }

    @Test
    @DisplayName("S5-04 AC4: Lập phiếu nhập kho nháp (DRAFT) thành công, KHÔNG cộng tồn kho")
    void createReceipt_draft_success_doesNotUpdateInventory() {
        when(supplierRepository.findById(1L)).thenReturn(Optional.of(sampleSupplier));
        when(warehouseRepository.findByCodeIgnoreCase("WH-MB01")).thenReturn(Optional.of(sampleWarehouse));
        when(productRepository.findById(100L)).thenReturn(Optional.of(sampleProduct));
        when(goodsReceiptRepository.findCodesByPrefix(anyString())).thenReturn(List.of("PNK-202610-001"));
        when(goodsReceiptRepository.save(any(GoodsReceipt.class))).thenAnswer(inv -> {
            GoodsReceipt r = inv.getArgument(0);
            r.setId(50L);
            return r;
        });

        GoodsReceiptLineCreateRequest line = GoodsReceiptLineCreateRequest.builder()
                .productId(100L)
                .selectedUnit("Thùng")
                .conversionFactor(BigDecimal.valueOf(48)) // 1 Thùng = 48 Hộp
                .quantity(BigDecimal.valueOf(10))        // Nhập 10 thùng
                .unitPrice(BigDecimal.valueOf(7500))
                .hasBatchManagement(false)
                .build();

        GoodsReceiptCreateRequest req = GoodsReceiptCreateRequest.builder()
                .supplierId(1L)
                .warehouseCode("WH-MB01")
                .documentNumber("HD-12345")
                .receiptDate("2026-10-10")
                .status("DRAFT")
                .lines(List.of(line))
                .build();

        GoodsReceiptResponse res = goodsReceiptService.createReceipt(req, whStaffActor);

        assertThat(res).isNotNull();
        assertThat(res.getCode()).isEqualTo("PNK-202610-002");
        assertThat(res.getStatus()).isEqualTo("DRAFT");
        assertThat(res.getTotalLines()).isEqualTo(1);
        assertThat(res.getTotalBaseQuantity()).isEqualByComparingTo(BigDecimal.valueOf(480)); // 10 * 48 = 480
        assertThat(res.getConfirmedAt()).isNull();

        // Kiểm tra chắc chắn: Phiếu nháp không tác động tồn kho và lô hàng
        verify(inventoryRepository, never()).save(any());
        verify(productLotRepository, never()).save(any());
    }

    @Test
    @DisplayName("S5-04 AC2, AC3, AC4: Lập phiếu nhập kho và Xác nhận ngay (CONFIRMED) -> Tự động quy đổi và cộng tồn kho, lưu lô/hạn")
    void createReceipt_confirmed_success_addsInventoryAndProductLot() {
        when(supplierRepository.findById(1L)).thenReturn(Optional.of(sampleSupplier));
        when(warehouseRepository.findByCodeIgnoreCase("WH-MB01")).thenReturn(Optional.of(sampleWarehouse));
        when(productRepository.findById(100L)).thenReturn(Optional.of(sampleProduct));
        when(goodsReceiptRepository.findCodesByPrefix(anyString())).thenReturn(Collections.emptyList());

        Inventory existingInv = Inventory.builder()
                .id(1L)
                .warehouse(sampleWarehouse)
                .product(sampleProduct)
                .physicalStock(BigDecimal.valueOf(100))
                .reservedStock(BigDecimal.ZERO)
                .build();
        when(inventoryRepository.findByWarehouseIdAndProductIdForUpdate(10L, 100L)).thenReturn(Optional.of(existingInv));

        when(productLotRepository.findByProduct_IdAndWarehouse_IdAndBatchNumber(100L, 10L, "LOT-202610-01"))
                .thenReturn(Optional.empty());

        when(goodsReceiptRepository.save(any(GoodsReceipt.class))).thenAnswer(inv -> {
            GoodsReceipt r = inv.getArgument(0);
            r.setId(55L);
            return r;
        });

        GoodsReceiptLineCreateRequest line = GoodsReceiptLineCreateRequest.builder()
                .productId(100L)
                .selectedUnit("Thùng")
                .conversionFactor(BigDecimal.valueOf(48))
                .quantity(BigDecimal.valueOf(5)) // 5 Thùng = 240 Hộp
                .unitPrice(BigDecimal.valueOf(7500))
                .hasBatchManagement(true)
                .batchNumber("LOT-202610-01")
                .expiredDate("2027-10-10")
                .build();

        GoodsReceiptCreateRequest req = GoodsReceiptCreateRequest.builder()
                .supplierId(1L)
                .warehouseCode("WH-MB01")
                .documentNumber("HD-8888")
                .receiptDate("2026-10-10")
                .status("CONFIRMED")
                .lines(List.of(line))
                .build();

        GoodsReceiptResponse res = goodsReceiptService.createReceipt(req, whStaffActor);

        assertThat(res).isNotNull();
        assertThat(res.getStatus()).isEqualTo("CONFIRMED");
        assertThat(res.getConfirmedAt()).isNotNull();

        // Kiểm tra tồn kho được cộng thêm: 100 + 240 = 340
        assertThat(existingInv.getPhysicalStock()).isEqualByComparingTo(BigDecimal.valueOf(340));
        verify(inventoryRepository).save(existingInv);

        // Kiểm tra lô hàng được lưu
        verify(productLotRepository).save(argThat(lot ->
                "LOT-202610-01".equals(lot.getBatchNumber()) &&
                BigDecimal.valueOf(240).compareTo(lot.getQuantity()) == 0
        ));
    }

    @Test
    @DisplayName("S5-04 AC3: Mặt hàng có quản lý lô nhưng thiếu số lô -> Báo lỗi 400 BATCH_NUMBER_REQUIRED")
    void createReceipt_missingBatchNumber_throwsException() {
        when(supplierRepository.findById(1L)).thenReturn(Optional.of(sampleSupplier));
        when(warehouseRepository.findByCodeIgnoreCase("WH-MB01")).thenReturn(Optional.of(sampleWarehouse));
        when(productRepository.findById(100L)).thenReturn(Optional.of(sampleProduct));

        GoodsReceiptLineCreateRequest line = GoodsReceiptLineCreateRequest.builder()
                .productId(100L)
                .selectedUnit("Hộp")
                .conversionFactor(BigDecimal.ONE)
                .quantity(BigDecimal.TEN)
                .hasBatchManagement(true)
                .batchNumber("   ") // Rỗng
                .expiredDate("2027-10-10")
                .build();

        GoodsReceiptCreateRequest req = GoodsReceiptCreateRequest.builder()
                .supplierId(1L)
                .warehouseCode("WH-MB01")
                .documentNumber("HD-1111")
                .receiptDate("2026-10-10")
                .lines(List.of(line))
                .build();

        assertThatThrownBy(() -> goodsReceiptService.createReceipt(req, whStaffActor))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("bắt buộc nhập Số lô sản xuất");
    }

    @Test
    @DisplayName("S5-04 AC3: Mặt hàng có quản lý lô nhưng thiếu hạn dùng -> Báo lỗi 400 EXPIRED_DATE_REQUIRED")
    void createReceipt_missingExpiredDate_throwsException() {
        when(supplierRepository.findById(1L)).thenReturn(Optional.of(sampleSupplier));
        when(warehouseRepository.findByCodeIgnoreCase("WH-MB01")).thenReturn(Optional.of(sampleWarehouse));
        when(productRepository.findById(100L)).thenReturn(Optional.of(sampleProduct));

        GoodsReceiptLineCreateRequest line = GoodsReceiptLineCreateRequest.builder()
                .productId(100L)
                .selectedUnit("Hộp")
                .conversionFactor(BigDecimal.ONE)
                .quantity(BigDecimal.TEN)
                .hasBatchManagement(true)
                .batchNumber("LOT-TEST")
                .expiredDate(null) // Thiếu hạn dùng
                .build();

        GoodsReceiptCreateRequest req = GoodsReceiptCreateRequest.builder()
                .supplierId(1L)
                .warehouseCode("WH-MB01")
                .documentNumber("HD-1111")
                .receiptDate("2026-10-10")
                .lines(List.of(line))
                .build();

        assertThatThrownBy(() -> goodsReceiptService.createReceipt(req, whStaffActor))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("bắt buộc chọn Hạn sử dụng");
    }

    @Test
    @DisplayName("S5-04 AC1: Nhà cung cấp đang INACTIVE -> Báo lỗi SUPPLIER_INACTIVE")
    void createReceipt_inactiveSupplier_throwsException() {
        sampleSupplier.setStatus("INACTIVE");
        when(supplierRepository.findById(1L)).thenReturn(Optional.of(sampleSupplier));

        GoodsReceiptCreateRequest req = GoodsReceiptCreateRequest.builder()
                .supplierId(1L)
                .warehouseCode("WH-MB01")
                .documentNumber("HD-1111")
                .receiptDate("2026-10-10")
                .lines(List.of())
                .build();

        assertThatThrownBy(() -> goodsReceiptService.createReceipt(req, whStaffActor))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ngừng giao dịch");
    }

    @Test
    @DisplayName("S5-04 AC4: Xác nhận phiếu nháp (confirmReceipt) -> Trạng thái sang CONFIRMED và cộng tồn kho")
    void confirmReceipt_draft_success_addsInventory() {
        GoodsReceiptLine line = GoodsReceiptLine.builder()
                .id(1L)
                .product(sampleProduct)
                .productSku(sampleProduct.getSku())
                .productName(sampleProduct.getName())
                .baseUnit("Hộp")
                .selectedUnit("Thùng")
                .conversionFactor(BigDecimal.valueOf(24))
                .quantity(BigDecimal.valueOf(10))
                .baseQuantity(BigDecimal.valueOf(240))
                .build();

        GoodsReceipt draftReceipt = GoodsReceipt.builder()
                .id(20L)
                .code("PNK-202610-010")
                .warehouse(sampleWarehouse)
                .supplier(sampleSupplier)
                .documentNumber("HD-999")
                .receiptDate(LocalDate.now())
                .status(GoodsReceipt.STATUS_DRAFT)
                .lines(new ArrayList<>(List.of(line)))
                .build();
        line.setGoodsReceipt(draftReceipt);

        when(goodsReceiptRepository.findById(20L)).thenReturn(Optional.of(draftReceipt));

        Inventory inventory = Inventory.builder()
                .id(5L)
                .warehouse(sampleWarehouse)
                .product(sampleProduct)
                .physicalStock(BigDecimal.valueOf(50))
                .build();
        when(inventoryRepository.findByWarehouseIdAndProductIdForUpdate(10L, 100L)).thenReturn(Optional.of(inventory));
        when(goodsReceiptRepository.save(any(GoodsReceipt.class))).thenAnswer(inv -> inv.getArgument(0));

        GoodsReceiptResponse res = goodsReceiptService.confirmReceipt(20L, whStaffActor);

        assertThat(res.getStatus()).isEqualTo(GoodsReceipt.STATUS_CONFIRMED);
        assertThat(inventory.getPhysicalStock()).isEqualByComparingTo(BigDecimal.valueOf(290)); // 50 + 240
        verify(inventoryRepository).save(inventory);
    }

    @Test
    @DisplayName("S5-04 AC5: Phiếu đã xác nhận không được sửa hay xác nhận lại -> Báo lỗi CANNOT_MODIFY_CONFIRMED_RECEIPT")
    void confirmReceipt_alreadyConfirmed_throwsException() {
        GoodsReceipt confirmedReceipt = GoodsReceipt.builder()
                .id(20L)
                .code("PNK-202610-010")
                .status(GoodsReceipt.STATUS_CONFIRMED)
                .build();

        when(goodsReceiptRepository.findById(20L)).thenReturn(Optional.of(confirmedReceipt));

        assertThatThrownBy(() -> goodsReceiptService.confirmReceipt(20L, whStaffActor))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Phiếu đã xác nhận không sửa được, chỉ lập phiếu điều chỉnh");
    }

    @Test
    @DisplayName("S5-04: Tìm kiếm theo ID không tồn tại -> Báo lỗi 404 RECEIPT_NOT_FOUND")
    void getReceiptById_notFound_throwsException() {
        when(goodsReceiptRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> goodsReceiptService.getReceiptById(999L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Không tìm thấy phiếu nhập kho với ID 999");
    }
}
