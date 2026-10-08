package com.erp.backend.service;

import com.erp.backend.dto.product.CreateProductUnitConversionRequest;
import com.erp.backend.dto.product.ProductUnitConversionResponse;
import com.erp.backend.dto.product.UnitConversionCalculateRequest;
import com.erp.backend.dto.product.UnitConversionResult;
import com.erp.backend.dto.product.UpdateProductUnitConversionRequest;
import com.erp.backend.entity.AuditModule;
import com.erp.backend.entity.Product;
import com.erp.backend.entity.ProductUnitConversion;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.ProductRepository;
import com.erp.backend.repository.ProductUnitConversionRepository;
import com.erp.backend.security.UserDetailsImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Unit test ProductUnitConversionService - S2-07 Đơn vị tính quy đổi của SKU")
class ProductUnitConversionServiceTest {

    @Mock
    private ProductUnitConversionRepository unitConversionRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private ProductUnitConversionService unitConversionService;

    private Product product;
    private UserDetailsImpl actor;

    @BeforeEach
    void setUp() {
        product = Product.builder()
                .id(1L)
                .sku("SP-COCA-330")
                .name("Nước ngọt Coca-Cola 330ml")
                .baseUnit("Lon")
                .packaging("Thùng 24 lon")
                .status("ACTIVE")
                .build();

        actor = new UserDetailsImpl(99L, "warehouse_staff", "Nguyễn Văn Kho", "wh@erp.com", "password", true, List.of());
    }

    @Test
    @DisplayName("S2-07: Lấy danh sách đơn vị tính -> bao gồm đơn vị cơ sở hệ số 1 và các đơn vị quy đổi")
    void getUnitConversions_Success() {
        ProductUnitConversion box = ProductUnitConversion.builder()
                .id(10L)
                .product(product)
                .unitName("Thùng")
                .conversionFactor(BigDecimal.valueOf(24))
                .status("ACTIVE")
                .build();

        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(unitConversionRepository.findByProductId(1L)).thenReturn(List.of(box));

        List<ProductUnitConversionResponse> result = unitConversionService.getUnitConversions(1L);

        assertThat(result).hasSize(2);
        // Đơn vị cơ sở luôn đứng đầu
        assertThat(result.get(0).getUnitName()).isEqualTo("Lon");
        assertThat(result.get(0).isBaseUnit()).isTrue();
        assertThat(result.get(0).getConversionFactor()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(result.get(0).getFormula()).isEqualTo("1 Lon = 1 Lon");

        // Đơn vị quy đổi
        assertThat(result.get(1).getUnitName()).isEqualTo("Thùng");
        assertThat(result.get(1).isBaseUnit()).isFalse();
        assertThat(result.get(1).getConversionFactor()).isEqualByComparingTo(BigDecimal.valueOf(24));
        assertThat(result.get(1).getFormula()).isEqualTo("1 Thùng = 24 Lon");
    }

    @Test
    @DisplayName("S2-07 AC1: Khai báo thêm đơn vị quy đổi (Lốc hệ số 6) thành công")
    void addUnitConversion_Success() {
        CreateProductUnitConversionRequest request = CreateProductUnitConversionRequest.builder()
                .unitName("Lốc")
                .conversionFactor(BigDecimal.valueOf(6))
                .description("Lốc 6 lon")
                .build();

        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(unitConversionRepository.existsByProductIdAndUnitNameIgnoreCase(1L, "Lốc")).thenReturn(false);
        when(unitConversionRepository.save(any(ProductUnitConversion.class))).thenAnswer(inv -> {
            ProductUnitConversion c = inv.getArgument(0);
            c.setId(101L);
            return c;
        });

        ProductUnitConversionResponse response = unitConversionService.addUnitConversion(1L, request, actor);

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(101L);
        assertThat(response.getUnitName()).isEqualTo("Lốc");
        assertThat(response.getConversionFactor()).isEqualByComparingTo(BigDecimal.valueOf(6));
        assertThat(response.getFormula()).isEqualTo("1 Lốc = 6 Lon");

        // Kiểm tra đã ghi nhận AuditLog hệ thống S2-04
        verify(auditLogService).record(
                eq(AuditModule.INVENTORY),
                eq("CREATE_UNIT_CONVERSION"),
                eq("PRODUCT_UNIT"),
                eq(101L),
                eq("SP-COCA-330:Lốc"),
                isNull(),
                contains("6"),
                anyString(),
                eq(actor)
        );
    }

    @Test
    @DisplayName("S2-07 AC1: Khai báo đơn vị quy đổi trùng với đơn vị cơ sở -> throw bad request")
    void addUnitConversion_Fails_WhenUnitNameEqualsBaseUnit() {
        CreateProductUnitConversionRequest request = CreateProductUnitConversionRequest.builder()
                .unitName("Lon") // trùng baseUnit
                .conversionFactor(BigDecimal.valueOf(1))
                .build();

        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        assertThatThrownBy(() -> unitConversionService.addUnitConversion(1L, request, actor))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "UNIT_EQUALS_BASE_UNIT")
                .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST);

        verify(unitConversionRepository, never()).save(any());
    }

    @Test
    @DisplayName("S2-07 AC1: Khai báo đơn vị quy đổi đã tồn tại -> throw conflict")
    void addUnitConversion_Fails_WhenUnitAlreadyExists() {
        CreateProductUnitConversionRequest request = CreateProductUnitConversionRequest.builder()
                .unitName("Thùng")
                .conversionFactor(BigDecimal.valueOf(24))
                .build();

        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(unitConversionRepository.existsByProductIdAndUnitNameIgnoreCase(1L, "Thùng")).thenReturn(true);

        assertThatThrownBy(() -> unitConversionService.addUnitConversion(1L, request, actor))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "DUPLICATE_UNIT")
                .hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("S2-07 AC1: Hệ số quy đổi <= 0 -> throw bad request")
    void addUnitConversion_Fails_WhenFactorIsZeroOrNegative() {
        CreateProductUnitConversionRequest request = CreateProductUnitConversionRequest.builder()
                .unitName("Khay")
                .conversionFactor(BigDecimal.ZERO)
                .build();

        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        assertThatThrownBy(() -> unitConversionService.addUnitConversion(1L, request, actor))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_FACTOR");
    }

    @Test
    @DisplayName("S2-07 AC3: Cập nhật hệ số quy đổi (từ 20 sang 24) -> Lưu AuditLog, không ảnh hưởng giao dịch cũ")
    void updateUnitConversion_Success() {
        ProductUnitConversion existing = ProductUnitConversion.builder()
                .id(10L)
                .product(product)
                .unitName("Thùng")
                .conversionFactor(BigDecimal.valueOf(20))
                .status("ACTIVE")
                .build();

        UpdateProductUnitConversionRequest request = UpdateProductUnitConversionRequest.builder()
                .conversionFactor(BigDecimal.valueOf(24))
                .changeReason("Nhà sản xuất thay đổi quy cách từ 20 sang 24 lon")
                .build();

        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(unitConversionRepository.findById(10L)).thenReturn(Optional.of(existing));
        when(unitConversionRepository.save(any(ProductUnitConversion.class))).thenReturn(existing);

        ProductUnitConversionResponse response = unitConversionService.updateUnitConversion(1L, 10L, request, actor);

        assertThat(response.getConversionFactor()).isEqualByComparingTo(BigDecimal.valueOf(24));

        // Kiểm tra audit log ghi lại hệ số cũ và hệ số mới
        verify(auditLogService).record(
                eq(AuditModule.INVENTORY),
                eq("UPDATE_UNIT_CONVERSION"),
                eq("PRODUCT_UNIT"),
                eq(10L),
                eq("SP-COCA-330:Thùng"),
                contains("20"),
                contains("24"),
                eq("Nhà sản xuất thay đổi quy cách từ 20 sang 24 lon"),
                eq(actor)
        );
    }

    @Test
    @DisplayName("S2-07: Xóa đơn vị quy đổi thành công")
    void deleteUnitConversion_Success() {
        ProductUnitConversion existing = ProductUnitConversion.builder()
                .id(10L)
                .product(product)
                .unitName("Thùng")
                .conversionFactor(BigDecimal.valueOf(24))
                .status("ACTIVE")
                .build();

        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(unitConversionRepository.findById(10L)).thenReturn(Optional.of(existing));

        unitConversionService.deleteUnitConversion(1L, 10L, actor);

        verify(unitConversionRepository).delete(existing);
        verify(auditLogService).record(
                eq(AuditModule.INVENTORY),
                eq("DELETE_UNIT_CONVERSION"),
                eq("PRODUCT_UNIT"),
                eq(10L),
                eq("SP-COCA-330:Thùng"),
                anyString(),
                isNull(),
                anyString(),
                eq(actor)
        );
    }

    @Test
    @DisplayName("S2-07 AC2: Quy đổi theo đơn vị cơ sở (Lon) -> Hệ số = 1, baseQuantity = inputQuantity")
    void convertToBaseUnit_BaseUnit() {
        UnitConversionResult result = unitConversionService.convertToBaseUnit(product, "Lon", BigDecimal.valueOf(15));

        assertThat(result.getInputUnit()).isEqualTo("Lon");
        assertThat(result.getConversionFactor()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(result.getBaseQuantity()).isEqualByComparingTo(BigDecimal.valueOf(15));
        assertThat(result.getBaseUnit()).isEqualTo("Lon");
        assertThat(result.getSnapshot().getBaseQuantity()).isEqualByComparingTo(BigDecimal.valueOf(15));
    }

    @Test
    @DisplayName("S2-07 AC2: Quy đổi theo đơn vị quy đổi (10 Thùng x 24 -> 240 Lon) và đóng băng Snapshot")
    void convertToBaseUnit_ConversionUnit_Box() {
        ProductUnitConversion box = ProductUnitConversion.builder()
                .id(10L)
                .product(product)
                .unitName("Thùng")
                .conversionFactor(BigDecimal.valueOf(24))
                .status("ACTIVE")
                .build();

        when(unitConversionRepository.findByProductIdAndUnitNameIgnoreCase(1L, "Thùng"))
                .thenReturn(Optional.of(box));

        UnitConversionResult result = unitConversionService.convertToBaseUnit(product, "Thùng", BigDecimal.valueOf(10));

        assertThat(result.getInputUnit()).isEqualTo("Thùng");
        assertThat(result.getInputQuantity()).isEqualByComparingTo(BigDecimal.valueOf(10));
        assertThat(result.getConversionFactor()).isEqualByComparingTo(BigDecimal.valueOf(24));
        assertThat(result.getBaseUnit()).isEqualTo("Lon");
        assertThat(result.getBaseQuantity()).isEqualByComparingTo(BigDecimal.valueOf(240));
        assertThat(result.getFormula()).isEqualTo("10 Thùng x 24 = 240 Lon");

        // Kiểm tra snapshot đóng băng đầy đủ dữ liệu lịch sử
        assertThat(result.getSnapshot()).isNotNull();
        assertThat(result.getSnapshot().getTransactionUnit()).isEqualTo("Thùng");
        assertThat(result.getSnapshot().getTransactionQuantity()).isEqualByComparingTo(BigDecimal.valueOf(10));
        assertThat(result.getSnapshot().getConversionFactor()).isEqualByComparingTo(BigDecimal.valueOf(24));
        assertThat(result.getSnapshot().getBaseQuantity()).isEqualByComparingTo(BigDecimal.valueOf(240));
        assertThat(result.getSnapshot().getBaseUnit()).isEqualTo("Lon");
    }

    @Test
    @DisplayName("S2-07 AC2: Quy đổi đơn vị đã INACTIVE -> throw bad request")
    void convertToBaseUnit_Fails_WhenUnitInactive() {
        ProductUnitConversion inactiveUnit = ProductUnitConversion.builder()
                .id(10L)
                .product(product)
                .unitName("Két")
                .conversionFactor(BigDecimal.valueOf(24))
                .status("INACTIVE")
                .build();

        when(unitConversionRepository.findByProductIdAndUnitNameIgnoreCase(1L, "Két"))
                .thenReturn(Optional.of(inactiveUnit));

        assertThatThrownBy(() -> unitConversionService.convertToBaseUnit(product, "Két", BigDecimal.valueOf(5)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "UNIT_INACTIVE");
    }

    @Test
    @DisplayName("S2-07 AC2: Quy đổi đơn vị không tồn tại -> throw bad request")
    void convertToBaseUnit_Fails_WhenUnitNotFound() {
        when(unitConversionRepository.findByProductIdAndUnitNameIgnoreCase(1L, "Pallet"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> unitConversionService.convertToBaseUnit(product, "Pallet", BigDecimal.valueOf(1)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "UNIT_NOT_FOUND");
    }

    @Test
    @DisplayName("Nghiệp vụ cốt lõi: Đổi hệ số quy đổi không làm sai lệch các giao dịch/đơn hàng đã ghi trước đó")
    void changeConversionFactor_DoesNotCorruptHistoricTransactions_SnapshotImmutable() {
        // GIAI ĐOẠN 1: Ban đầu hệ số quy đổi của Thùng là 24 (1 Thùng = 24 Lon)
        ProductUnitConversion boxUnit = ProductUnitConversion.builder()
                .id(101L)
                .product(product)
                .unitName("Thùng")
                .conversionFactor(BigDecimal.valueOf(24))
                .status("ACTIVE")
                .build();

        when(unitConversionRepository.findByProductIdAndUnitNameIgnoreCase(1L, "Thùng"))
                .thenReturn(Optional.of(boxUnit));

        // Phiếu nhập kho / Đơn hàng số 1 phát sinh 10 Thùng tại thời điểm T1
        UnitConversionResult tx1Result = unitConversionService.convertToBaseUnit(product, "Thùng", BigDecimal.valueOf(10));
        assertThat(tx1Result.getBaseQuantity()).isEqualByComparingTo(BigDecimal.valueOf(240));
        assertThat(tx1Result.getSnapshot().getConversionFactor()).isEqualByComparingTo(BigDecimal.valueOf(24));
        assertThat(tx1Result.getSnapshot().getBaseQuantity()).isEqualByComparingTo(BigDecimal.valueOf(240));

        // Mô phỏng dòng phiếu / đơn hàng lịch sử chốt snapshot đóng băng
        var historicSnapshot = tx1Result.getSnapshot();

        // GIAI ĐOẠN 2: Sau 1 tháng, nhà sản xuất đổi quy cách Thùng lên 30 lon.
        // Thủ kho cập nhật hệ số quy đổi trong danh mục từ 24 thành 30
        boxUnit.setConversionFactor(BigDecimal.valueOf(30));

        // GIAI ĐOẠN 3: Phiếu nhập kho / Đơn hàng số 2 phát sinh 10 Thùng tại thời điểm T2 (sau khi đổi hệ số)
        UnitConversionResult tx2Result = unitConversionService.convertToBaseUnit(product, "Thùng", BigDecimal.valueOf(10));
        assertThat(tx2Result.getBaseQuantity()).isEqualByComparingTo(BigDecimal.valueOf(300));
        assertThat(tx2Result.getSnapshot().getConversionFactor()).isEqualByComparingTo(BigDecimal.valueOf(30));
        assertThat(tx2Result.getSnapshot().getBaseQuantity()).isEqualByComparingTo(BigDecimal.valueOf(300));

        // KIỂM TRA BẤT BIẾN: Giao dịch số 1 cũ trong quá khứ TUYỆT ĐỐI KHÔNG BỊ SAI LỆCH
        assertThat(historicSnapshot.getConversionFactor())
                .as("Hệ số giao dịch cũ phải cố định ở 24, không bị nhảy lên 30")
                .isEqualByComparingTo(BigDecimal.valueOf(24));
        assertThat(historicSnapshot.getBaseQuantity())
                .as("Số lượng quy về đơn vị cơ sở của giao dịch cũ phải bất biến ở 240 Lon, không bị nhảy lên 300 Lon")
                .isEqualByComparingTo(BigDecimal.valueOf(240));
        assertThat(historicSnapshot.getTransactionUnit()).isEqualTo("Thùng");
        assertThat(historicSnapshot.getBaseUnit()).isEqualTo("Lon");
    }
}

