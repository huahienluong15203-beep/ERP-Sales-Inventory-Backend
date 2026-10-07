package com.erp.backend.service;

import com.erp.backend.dto.product.*;
import com.erp.backend.entity.AuditModule;
import com.erp.backend.entity.Product;
import com.erp.backend.entity.ProductUnitConversion;
import com.erp.backend.entity.UnitConversionSnapshot;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.ProductRepository;
import com.erp.backend.repository.ProductUnitConversionRepository;
import com.erp.backend.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Service xử lý nghiệp vụ Đơn vị tính quy đổi của sản phẩm (S2-07).
 * Đáp ứng các tiêu chí:
 * 1. Mỗi SKU khai báo được nhiều đơn vị quy đổi (lon, lốc, thùng) kèm hệ số về đơn vị cơ sở.
 * 2. Đơn hàng và phiếu kho nhập theo đơn vị nào cũng quy về đơn vị cơ sở khi ghi sổ.
 * 3. Đổi hệ số quy đổi không làm sai lệch các giao dịch đã ghi trước đó (Immutable historical snapshot).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProductUnitConversionService {

    private final ProductUnitConversionRepository unitConversionRepository;
    private final ProductRepository productRepository;
    private final AuditLogService auditLogService;

    /**
     * S2-07: Lấy danh sách toàn bộ đơn vị tính khả dụng của SKU (bao gồm đơn vị cơ sở hệ số 1).
     */
    @Transactional(readOnly = true)
    public List<ProductUnitConversionResponse> getUnitConversions(Long productId) {
        Product product = getProductOrThrow(productId);
        return buildAllUnitsList(product);
    }

    /**
     * S2-07 AC1: Khai báo thêm đơn vị quy đổi mới cho SKU.
     */
    @Transactional(rollbackFor = Exception.class)
    public ProductUnitConversionResponse addUnitConversion(Long productId,
                                                           CreateProductUnitConversionRequest request,
                                                           UserDetailsImpl actor) {
        Product product = getProductOrThrow(productId);
        String trimmedUnitName = request.getUnitName().trim();

        validateUnitNameAgainstBaseUnit(product, trimmedUnitName);

        if (unitConversionRepository.existsByProductIdAndUnitNameIgnoreCase(productId, trimmedUnitName)) {
            throw BusinessException.conflict("DUPLICATE_UNIT",
                    "Đơn vị tính '" + trimmedUnitName + "' đã tồn tại trong danh mục quy đổi của SKU " + product.getSku(),
                    "unitName");
        }

        if (request.getConversionFactor().compareTo(BigDecimal.ZERO) <= 0) {
            throw BusinessException.badRequest("INVALID_FACTOR", "Hệ số quy đổi phải lớn hơn 0");
        }

        ProductUnitConversion conversion = ProductUnitConversion.builder()
                .product(product)
                .unitName(trimmedUnitName)
                .conversionFactor(request.getConversionFactor())
                .barcode(request.getBarcode())
                .isDefaultPurchase(Boolean.TRUE.equals(request.getIsDefaultPurchase()))
                .isDefaultSale(Boolean.TRUE.equals(request.getIsDefaultSale()))
                .description(request.getDescription())
                .status("ACTIVE")
                .build();

        ProductUnitConversion saved = unitConversionRepository.save(conversion);

        // Ghi nhật ký kiểm toán hệ thống (S2-04)
        String logReason = "Thêm đơn vị quy đổi " + saved.getUnitName() +
                " (Hệ số: " + formatDecimal(saved.getConversionFactor()) + ") cho SKU " + product.getSku();
        auditLogService.record(
                AuditModule.INVENTORY,
                "CREATE_UNIT_CONVERSION",
                "PRODUCT_UNIT",
                saved.getId(),
                product.getSku(),
                null,
                "Hệ số: " + formatDecimal(saved.getConversionFactor()),
                logReason,
                actor
        );

        log.info("S2-07 Thêm đơn vị quy đổi thành công: SKU={}, Unit={}, Factor={}",
                product.getSku(), saved.getUnitName(), saved.getConversionFactor());

        return mapToResponse(saved, product);
    }

    /**
     * S2-07 AC3: Cập nhật thông tin hoặc thay đổi hệ số quy đổi.
     * Lưu ý quan trọng: Việc thay đổi hệ số ở đây chỉ áp dụng cho các giao dịch mới sau này.
     * Toàn bộ giao dịch quá khứ đã được đóng băng bằng snapshot và không bị thay đổi!
     */
    @Transactional(rollbackFor = Exception.class)
    public ProductUnitConversionResponse updateUnitConversion(Long productId,
                                                              Long conversionId,
                                                              UpdateProductUnitConversionRequest request,
                                                              UserDetailsImpl actor) {
        Product product = getProductOrThrow(productId);
        ProductUnitConversion conversion = unitConversionRepository.findById(conversionId)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy đơn vị quy đổi với ID " + conversionId));

        if (!conversion.getProduct().getId().equals(productId)) {
            throw BusinessException.badRequest("UNIT_MISMATCH", "Đơn vị quy đổi không thuộc về sản phẩm này");
        }

        if (request.getConversionFactor().compareTo(BigDecimal.ZERO) <= 0) {
            throw BusinessException.badRequest("INVALID_FACTOR", "Hệ số quy đổi phải lớn hơn 0");
        }

        if (StringUtils.hasText(request.getUnitName())) {
            String newUnitName = request.getUnitName().trim();
            validateUnitNameAgainstBaseUnit(product, newUnitName);

            if (unitConversionRepository.existsByProductIdAndUnitNameIgnoreCaseAndIdNot(productId, newUnitName, conversionId)) {
                throw BusinessException.conflict("DUPLICATE_UNIT",
                        "Đơn vị tính '" + newUnitName + "' đã được sử dụng cho quy cách khác của SKU này",
                        "unitName");
            }
            conversion.setUnitName(newUnitName);
        }

        BigDecimal oldFactor = conversion.getConversionFactor();
        String oldStatus = conversion.getStatus();

        conversion.setConversionFactor(request.getConversionFactor());
        if (request.getBarcode() != null) {
            conversion.setBarcode(request.getBarcode());
        }
        if (request.getIsDefaultPurchase() != null) {
            conversion.setIsDefaultPurchase(request.getIsDefaultPurchase());
        }
        if (request.getIsDefaultSale() != null) {
            conversion.setIsDefaultSale(request.getIsDefaultSale());
        }
        if (request.getDescription() != null) {
            conversion.setDescription(request.getDescription());
        }
        if (StringUtils.hasText(request.getStatus())) {
            conversion.setStatus(request.getStatus());
        }

        ProductUnitConversion updated = unitConversionRepository.save(conversion);

        // Ghi AuditLog ghi nhận lịch sử thay đổi hệ số quy đổi
        String reason = StringUtils.hasText(request.getChangeReason())
                ? request.getChangeReason()
                : "Cập nhật đơn vị quy đổi " + updated.getUnitName() + " của SKU " + product.getSku();

        auditLogService.record(
                AuditModule.INVENTORY,
                "UPDATE_UNIT_CONVERSION",
                "PRODUCT_UNIT",
                updated.getId(),
                product.getSku(),
                "Hệ số: " + formatDecimal(oldFactor) + ", Trạng thái: " + oldStatus,
                "Hệ số: " + formatDecimal(updated.getConversionFactor()) + ", Trạng thái: " + updated.getStatus(),
                reason,
                actor
        );

        log.info("S2-07 Cập nhật đơn vị quy đổi: SKU={}, Unit={}, OldFactor={}, NewFactor={}",
                product.getSku(), updated.getUnitName(), oldFactor, updated.getConversionFactor());

        return mapToResponse(updated, product);
    }

    /**
     * S2-07: Xoá hoặc ngừng sử dụng một đơn vị quy đổi.
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteUnitConversion(Long productId, Long conversionId, UserDetailsImpl actor) {
        Product product = getProductOrThrow(productId);
        ProductUnitConversion conversion = unitConversionRepository.findById(conversionId)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy đơn vị quy đổi với ID " + conversionId));

        if (!conversion.getProduct().getId().equals(productId)) {
            throw BusinessException.badRequest("UNIT_MISMATCH", "Đơn vị quy đổi không thuộc về sản phẩm này");
        }

        unitConversionRepository.delete(conversion);

        auditLogService.record(
                AuditModule.INVENTORY,
                "DELETE_UNIT_CONVERSION",
                "PRODUCT_UNIT",
                conversionId,
                product.getSku(),
                "Hệ số: " + formatDecimal(conversion.getConversionFactor()),
                null,
                "Xoá đơn vị quy đổi " + conversion.getUnitName() + " của SKU " + product.getSku(),
                actor
        );

        log.info("S2-07 Xoá đơn vị quy đổi: SKU={}, Unit={}", product.getSku(), conversion.getUnitName());
    }

    /**
     * S2-07 AC2: Tính toán quy đổi đơn vị tính (phục vụ nhập/xuất kho hoặc lên đơn hàng).
     * Bất kể nhập theo lon, lốc, thùng, két... đều được quy đổi chuẩn xác về đơn vị cơ sở.
     */
    @Transactional(readOnly = true)
    public UnitConversionResult calculateConversion(UnitConversionCalculateRequest request) {
        Product product;
        if (request.getProductId() != null) {
            product = getProductOrThrow(request.getProductId());
        } else if (StringUtils.hasText(request.getSku())) {
            product = productRepository.findBySku(request.getSku().trim())
                    .orElseThrow(() -> BusinessException.notFound("Không tìm thấy SKU '" + request.getSku() + "'"));
        } else {
            throw BusinessException.badRequest("MISSING_PRODUCT_IDENTIFIER", "Vui lòng cung cấp productId hoặc mã sku");
        }

        return convertToBaseUnit(product, request.getUnitName(), request.getQuantity());
    }

    /**
     * S2-07 AC2 & AC3: Phương thức cốt lõi quy đổi về đơn vị cơ sở và tạo Snapshot chốt giao dịch.
     * Được dùng trực tiếp bởi:
     * - Module Nhập kho / Xuất kho (S5)
     * - Module Đơn bán hàng / Đơn nhập hàng (S4)
     */
    @Transactional(readOnly = true)
    public UnitConversionResult convertToBaseUnit(Product product, String unitName, BigDecimal quantity) {
        if (!StringUtils.hasText(unitName)) {
            throw BusinessException.badRequest("INVALID_UNIT", "Tên đơn vị tính không được để trống");
        }
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw BusinessException.badRequest("INVALID_QUANTITY", "Số lượng phải lớn hơn 0");
        }

        String trimmedUnit = unitName.trim();
        BigDecimal factor;

        if (trimmedUnit.equalsIgnoreCase(product.getBaseUnit())) {
            factor = BigDecimal.ONE;
        } else {
            ProductUnitConversion conversion = unitConversionRepository
                    .findByProductIdAndUnitNameIgnoreCase(product.getId(), trimmedUnit)
                    .orElseThrow(() -> BusinessException.badRequest("UNIT_NOT_FOUND",
                            "Đơn vị tính '" + trimmedUnit + "' không được định nghĩa cho SKU " + product.getSku()
                                    + " (Đơn vị cơ sở: " + product.getBaseUnit() + ")"));

            if (!"ACTIVE".equalsIgnoreCase(conversion.getStatus())) {
                throw BusinessException.badRequest("UNIT_INACTIVE",
                        "Đơn vị tính '" + trimmedUnit + "' của SKU " + product.getSku() + " đang ở trạng thái ngừng áp dụng (INACTIVE)");
            }

            factor = conversion.getConversionFactor();
        }

        // Tính số lượng cơ sở: baseQuantity = quantity * factor
        BigDecimal baseQuantity = quantity.multiply(factor).setScale(4, RoundingMode.HALF_UP);

        // Tạo snapshot cố định cho giao dịch
        UnitConversionSnapshot snapshot = UnitConversionSnapshot.builder()
                .transactionUnit(trimmedUnit)
                .transactionQuantity(quantity)
                .conversionFactor(factor)
                .baseUnit(product.getBaseUnit())
                .baseQuantity(baseQuantity)
                .build();

        String formula = String.format("%s %s x %s = %s %s",
                formatDecimal(quantity), trimmedUnit,
                formatDecimal(factor),
                formatDecimal(baseQuantity), product.getBaseUnit());

        return UnitConversionResult.builder()
                .productId(product.getId())
                .sku(product.getSku())
                .productName(product.getName())
                .inputUnit(trimmedUnit)
                .inputQuantity(quantity)
                .conversionFactor(factor)
                .baseUnit(product.getBaseUnit())
                .baseQuantity(baseQuantity)
                .formula(formula)
                .convertedAt(LocalDateTime.now())
                .snapshot(snapshot)
                .build();
    }

    /**
     * Tiện ích quy đổi nhanh theo SKU cho các Service khác gọi qua Java code.
     */
    @Transactional(readOnly = true)
    public UnitConversionResult convertToBaseUnitBySku(String sku, String unitName, BigDecimal quantity) {
        Product product = productRepository.findBySku(sku)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy sản phẩm với SKU " + sku));
        return convertToBaseUnit(product, unitName, quantity);
    }

    /**
     * Tạo danh sách tất cả các đơn vị khả dụng của SKU:
     * 1. Đơn vị tính cơ sở (luôn đứng đầu, factor = 1.0, isBaseUnit = true)
     * 2. Các đơn vị tính quy đổi đã khai báo trong DB
     */
    public List<ProductUnitConversionResponse> buildAllUnitsList(Product product) {
        List<ProductUnitConversionResponse> results = new ArrayList<>();

        // 1. Đơn vị cơ sở
        results.add(ProductUnitConversionResponse.builder()
                .id(null)
                .productId(product.getId())
                .sku(product.getSku())
                .unitName(product.getBaseUnit())
                .conversionFactor(BigDecimal.ONE)
                .isBaseUnit(true)
                .formula("1 " + product.getBaseUnit() + " = 1 " + product.getBaseUnit())
                .barcode(product.getBarcode())
                .isDefaultPurchase(false)
                .isDefaultSale(false)
                .description("Đơn vị tính cơ sở chuẩn của SKU")
                .status("ACTIVE")
                .build());

        // 2. Các đơn vị quy đổi
        List<ProductUnitConversion> conversions = unitConversionRepository.findByProductId(product.getId());
        for (ProductUnitConversion c : conversions) {
            results.add(mapToResponse(c, product));
        }

        return results;
    }

    public ProductUnitConversionResponse mapToResponse(ProductUnitConversion c, Product p) {
        String formula = String.format("1 %s = %s %s",
                c.getUnitName(),
                formatDecimal(c.getConversionFactor()),
                p.getBaseUnit());

        return ProductUnitConversionResponse.builder()
                .id(c.getId())
                .productId(p.getId())
                .sku(p.getSku())
                .unitName(c.getUnitName())
                .conversionFactor(c.getConversionFactor())
                .isBaseUnit(false)
                .formula(formula)
                .barcode(c.getBarcode())
                .isDefaultPurchase(c.getIsDefaultPurchase())
                .isDefaultSale(c.getIsDefaultSale())
                .description(c.getDescription())
                .status(c.getStatus())
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .build();
    }

    private Product getProductOrThrow(Long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy sản phẩm với ID " + productId));
    }

    private void validateUnitNameAgainstBaseUnit(Product product, String unitName) {
        if (unitName.equalsIgnoreCase(product.getBaseUnit())) {
            throw BusinessException.badRequest("UNIT_EQUALS_BASE_UNIT",
                    "Đơn vị '" + unitName + "' trùng với đơn vị tính cơ sở của SKU. Đơn vị cơ sở đã mặc định có hệ số quy đổi là 1.0.");
        }
    }

    private String formatDecimal(BigDecimal val) {
        if (val == null) return "0";
        return val.stripTrailingZeros().toPlainString();
    }
}
