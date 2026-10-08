package com.erp.backend.service;

import com.erp.backend.dto.product.*;
import com.erp.backend.dto.user.PageResponse;
import com.erp.backend.entity.AuditModule;
import com.erp.backend.entity.Product;
import com.erp.backend.entity.ProductCategory;
import com.erp.backend.entity.ProductUnitConversion;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.ProductCategoryRepository;
import com.erp.backend.repository.ProductRepository;
import com.erp.backend.repository.ProductUnitConversionRepository;
import com.erp.backend.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Service quản lý danh mục sản phẩm (S2-05 & S2-07).
 * Hỗ trợ khai báo mã SKU duy nhất, đơn vị tính cơ sở và nhiều đơn vị quy đổi (lon, lốc, thùng) kèm hệ số.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProductService {

    private final ProductRepository productRepository;
    private final ProductUnitConversionRepository unitConversionRepository;
    private final ProductUnitConversionService unitConversionService;
    private final AuditLogService auditLogService;
    private final ProductCategoryRepository productCategoryRepository;

    /**
     * S2-05 & S2-07: Khai báo sản phẩm mới kèm mã SKU duy nhất, đơn vị cơ sở và nhiều đơn vị quy đổi.
     */
    @Transactional(rollbackFor = Exception.class)
    public ProductDetailResponse createProduct(CreateProductRequest request, UserDetailsImpl actor) {
        String trimmedSku = request.getSku().trim().toUpperCase();

        if (productRepository.existsBySku(trimmedSku)) {
            throw BusinessException.conflict("DUPLICATE_SKU",
                    "Mã SKU '" + trimmedSku + "' đã tồn tại trên hệ thống", "sku");
        }

        String baseUnit = request.getBaseUnit().trim();
        validateImageUrl(request.getImageUrl());

        Product product = Product.builder()
                .sku(trimmedSku)
                .name(request.getName().trim())
                .baseUnit(baseUnit)
                .packaging(request.getPackaging() != null ? request.getPackaging().trim() : null)
                .costPrice(request.getCostPrice() != null ? request.getCostPrice() : BigDecimal.ZERO)
                .barcode(request.getBarcode() != null ? request.getBarcode().trim() : null)
                .imageUrl(request.getImageUrl() != null ? request.getImageUrl().trim() : null)
                .description(request.getDescription() != null ? request.getDescription().trim() : null)
                .status(StringUtils.hasText(request.getStatus()) ? request.getStatus().trim().toUpperCase() : "ACTIVE")
                .build();
        applyCategory(product, request.getCategory(), request.getCategoryId());

        // Kiểm tra tính hợp lệ của danh sách đơn vị quy đổi ban đầu (nếu có)
        if (request.getUnitConversions() != null && !request.getUnitConversions().isEmpty()) {
            Set<String> seenUnitNames = new HashSet<>();

            for (CreateProductUnitConversionRequest uReq : request.getUnitConversions()) {
                String uName = uReq.getUnitName().trim();

                if (uName.equalsIgnoreCase(baseUnit)) {
                    throw BusinessException.badRequest("UNIT_EQUALS_BASE_UNIT",
                            "Đơn vị quy đổi '" + uName + "' trùng với đơn vị cơ sở '" + baseUnit +
                                    "'. Đơn vị cơ sở đã mặc định có hệ số là 1.0.");
                }

                if (!seenUnitNames.add(uName.toLowerCase())) {
                    throw BusinessException.badRequest("DUPLICATE_UNIT_REQUEST",
                            "Tên đơn vị quy đổi '" + uName + "' bị trùng lặp trong danh sách khai báo ban đầu");
                }

                if (uReq.getConversionFactor() == null || uReq.getConversionFactor().compareTo(BigDecimal.ZERO) <= 0) {
                    throw BusinessException.badRequest("INVALID_FACTOR",
                            "Hệ số quy đổi của đơn vị '" + uName + "' phải lớn hơn 0");
                }

                ProductUnitConversion conversion = ProductUnitConversion.builder()
                        .unitName(uName)
                        .conversionFactor(uReq.getConversionFactor())
                        .barcode(uReq.getBarcode())
                        .isDefaultPurchase(Boolean.TRUE.equals(uReq.getIsDefaultPurchase()))
                        .isDefaultSale(Boolean.TRUE.equals(uReq.getIsDefaultSale()))
                        .description(uReq.getDescription())
                        .status("ACTIVE")
                        .build();

                product.addUnitConversion(conversion);
            }
        }

        Product saved = productRepository.save(product);

        // Ghi AuditLog
        String logReason = "Khai báo sản phẩm mới SKU=" + saved.getSku() +
                ", Tên=" + saved.getName() +
                ", Đơn vị cơ sở=" + saved.getBaseUnit() +
                ", Số đơn vị quy đổi=" + (saved.getUnitConversions() != null ? saved.getUnitConversions().size() : 0);

        auditLogService.record(
                AuditModule.INVENTORY,
                "CREATE_PRODUCT",
                "PRODUCT",
                saved.getId(),
                saved.getSku(),
                null,
                "Đơn vị cơ sở: " + saved.getBaseUnit(),
                logReason,
                actor
        );

        log.info("S2-05/S2-07 Tạo sản phẩm thành công: id={}, sku={}, name={}", saved.getId(), saved.getSku(), saved.getName());

        return mapToDetailResponse(saved);
    }

    /**
     * S2-05: Cập nhật thông tin sản phẩm.
     */
    @Transactional(rollbackFor = Exception.class)
    public ProductDetailResponse updateProduct(Long id, UpdateProductRequest request, UserDetailsImpl actor) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy sản phẩm với ID " + id));

        String oldBaseUnit = product.getBaseUnit();
        BigDecimal oldCostPrice = product.getCostPrice();
        String newBaseUnit = request.getBaseUnit().trim();

        // Nếu thay đổi đơn vị cơ sở, kiểm tra xem có đơn vị quy đổi nào bị trùng không
        if (!oldBaseUnit.equalsIgnoreCase(newBaseUnit)) {
            boolean hasDuplicateWithConversion = unitConversionRepository.existsByProductIdAndUnitNameIgnoreCase(id, newBaseUnit);
            if (hasDuplicateWithConversion) {
                throw BusinessException.badRequest("BASE_UNIT_CONFLICTS_CONVERSION",
                        "Đơn vị tính cơ sở mới '" + newBaseUnit + "' trùng với một đơn vị quy đổi đã khai báo cho SKU này.");
            }
        }

        product.setName(request.getName().trim());
        applyCategory(product, request.getCategory(), request.getCategoryId());
        product.setBaseUnit(newBaseUnit);
        product.setPackaging(request.getPackaging() != null ? request.getPackaging().trim() : null);
        // Chỉ Quản lý kinh doanh (hoặc Admin) mới được xem và sửa giá vốn (S2-05)
        boolean canManageCost = canViewCostPrice(actor);
        boolean costChanged = false;
        if (request.getCostPrice() != null) {
            if (canManageCost) {
                if (oldCostPrice == null || oldCostPrice.compareTo(request.getCostPrice()) != 0) {
                    costChanged = true;
                }
                product.setCostPrice(request.getCostPrice());
            } else {
                log.warn("S2-05 Người dùng {} không có quyền sửa giá vốn, giữ nguyên giá cũ",
                        actor != null ? actor.getUsername() : "unknown");
            }
        }
        validateImageUrl(request.getImageUrl());
        product.setBarcode(request.getBarcode() != null ? request.getBarcode().trim() : null);
        product.setImageUrl(request.getImageUrl() != null ? request.getImageUrl().trim() : null);
        product.setDescription(request.getDescription() != null ? request.getDescription().trim() : null);
        if (StringUtils.hasText(request.getStatus())) {
            product.setStatus(request.getStatus().trim().toUpperCase());
        }

        Product updated = productRepository.save(product);

        // S205-02: Nếu có thay đổi giá vốn, ghi nhận giá vốn cũ và mới vào AuditLog
        String oldValueLog = costChanged
                ? (oldCostPrice != null ? oldCostPrice.stripTrailingZeros().toPlainString() : "0")
                : oldBaseUnit;
        String newValueLog = costChanged
                ? (updated.getCostPrice() != null ? updated.getCostPrice().stripTrailingZeros().toPlainString() : "0")
                : updated.getBaseUnit();
        String reasonLog = costChanged
                ? String.format("Cập nhật giá vốn sản phẩm SKU %s (%s): %s đ -> %s đ", updated.getSku(), updated.getName(), oldValueLog, newValueLog)
                : "Cập nhật thông tin sản phẩm SKU " + updated.getSku() + " (" + updated.getName() + ")";

        auditLogService.record(
                costChanged ? AuditModule.PRICING : AuditModule.INVENTORY,
                costChanged ? "UPDATE_COST_PRICE" : "UPDATE_PRODUCT",
                "PRODUCT",
                updated.getId(),
                updated.getSku() + ":" + updated.getName(),
                oldValueLog,
                newValueLog,
                reasonLog,
                actor
        );

        return mapToDetailResponse(updated, canManageCost);
    }

    /**
     * Lấy chi tiết sản phẩm kèm danh sách đơn vị quy đổi theo ID (có bảo mật giá vốn S2-05).
     */
    @Transactional(readOnly = true)
    public ProductDetailResponse getProductById(Long id, UserDetailsImpl actor) {
        Product product = productRepository.findByIdWithConversions(id)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy sản phẩm với ID " + id));
        return mapToDetailResponse(product, canViewCostPrice(actor));
    }

    @Transactional(readOnly = true)
    public ProductDetailResponse getProductById(Long id) {
        return getProductById(id, null);
    }

    /**
     * Lấy chi tiết sản phẩm kèm danh sách đơn vị quy đổi theo SKU (có bảo mật giá vốn S2-05).
     */
    @Transactional(readOnly = true)
    public ProductDetailResponse getProductBySku(String sku, UserDetailsImpl actor) {
        Product product = productRepository.findBySkuWithConversions(sku.trim())
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy sản phẩm với SKU '" + sku + "'"));
        return mapToDetailResponse(product, canViewCostPrice(actor));
    }

    @Transactional(readOnly = true)
    public ProductDetailResponse getProductBySku(String sku) {
        return getProductBySku(sku, null);
    }

    /**
     * Tìm kiếm và phân trang danh mục sản phẩm (có bảo mật giá vốn S2-05).
     */
    @Transactional(readOnly = true)
    public PageResponse<ProductResponse> searchProducts(String keyword, String category, String status, Pageable pageable, UserDetailsImpl actor) {
        Page<Product> page = productRepository.searchProducts(
                StringUtils.hasText(keyword) ? keyword.trim() : null,
                StringUtils.hasText(category) ? category.trim() : null,
                StringUtils.hasText(status) ? status.trim() : null,
                pageable
        );

        boolean canViewCost = canViewCostPrice(actor);
        return PageResponse.of(page.map(p -> mapToResponse(p, canViewCost)));
    }

    @Transactional(readOnly = true)
    public PageResponse<ProductResponse> searchProducts(String keyword, String category, String status, Pageable pageable) {
        return searchProducts(keyword, category, status, pageable, null);
    }

    /**
     * Tra cứu nhanh danh sách sản phẩm cho các ô chọn (combobox, bảng giá, đơn hàng).
     */
    @Transactional(readOnly = true)
    public List<ProductOptionItemResponse> searchProductOptions(String keyword, int limit, UserDetailsImpl actor) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(0, safeLimit, org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.ASC, "sku"));
        Page<Product> page = productRepository.searchProducts(
                StringUtils.hasText(keyword) ? keyword.trim() : null,
                null,
                "ACTIVE",
                pageable
        );
        boolean canViewCost = canViewCostPrice(actor);
        return page.getContent().stream()
                .map(p -> new ProductOptionItemResponse(
                        p.getId(),
                        p.getSku(),
                        p.getName(),
                        p.getBaseUnit(),
                        p.getPackaging(),
                        p.getCategory(),
                        canViewCost ? p.getCostPrice() : null,
                        p.getStatus()))
                .toList();
    }

    /**
     * Xóa sản phẩm hoặc chuyển sang trạng thái ngừng kinh doanh INACTIVE.
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteProduct(Long id, UserDetailsImpl actor) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy sản phẩm với ID " + id));

        // Soft-delete: chuyển sang INACTIVE để bảo vệ toàn vẹn lịch sử giao dịch
        product.setStatus("INACTIVE");
        productRepository.save(product);

        auditLogService.record(
                AuditModule.INVENTORY,
                "DEACTIVATE_PRODUCT",
                "PRODUCT",
                id,
                product.getSku(),
                "ACTIVE",
                "INACTIVE",
                "Ngừng kinh doanh sản phẩm SKU " + product.getSku(),
                actor
        );

        log.info("S2-05 Chuyển trạng thái sản phẩm sang INACTIVE: id={}, sku={}", id, product.getSku());
    }

    public ProductResponse mapToResponse(Product p) {
        return mapToResponse(p, canViewCostPrice(null));
    }

    public ProductResponse mapToResponse(Product p, boolean canViewCostPrice) {
        return ProductResponse.builder()
                .id(p.getId())
                .sku(p.getSku())
                .name(p.getName())
                .category(p.getCategory())
                .categoryId(p.getProductCategory() != null ? p.getProductCategory().getId() : null)
                .baseUnit(p.getBaseUnit())
                .packaging(p.getPackaging())
                .costPrice(canViewCostPrice ? p.getCostPrice() : null)
                .status(p.getStatus())
                .barcode(p.getBarcode())
                .imageUrl(p.getImageUrl())
                .description(p.getDescription())
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .build();
    }

    public ProductDetailResponse mapToDetailResponse(Product p) {
        return mapToDetailResponse(p, canViewCostPrice(null));
    }

    public ProductDetailResponse mapToDetailResponse(Product p, boolean canViewCostPrice) {
        List<ProductUnitConversionResponse> allUnits = unitConversionService.buildAllUnitsList(p);
        List<ProductUnitConversionResponse> conversionsOnly = allUnits.stream()
                .filter(u -> !u.isBaseUnit())
                .toList();

        return ProductDetailResponse.builder()
                .id(p.getId())
                .sku(p.getSku())
                .name(p.getName())
                .category(p.getCategory())
                .categoryId(p.getProductCategory() != null ? p.getProductCategory().getId() : null)
                .baseUnit(p.getBaseUnit())
                .packaging(p.getPackaging())
                .costPrice(canViewCostPrice ? p.getCostPrice() : null)
                .status(p.getStatus())
                .barcode(p.getBarcode())
                .imageUrl(p.getImageUrl())
                .description(p.getDescription())
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .unitConversions(conversionsOnly)
                .allUnits(allUnits)
                .build();
    }

    /**
     * S2-06: Gắn sản phẩm vào cây nhóm hàng.
     * - Có categoryId: gắn vào nhóm đó, ô category lấy theo tên nhóm.
     * - Không có categoryId nhưng sản phẩm đã nằm trong cây: giữ nguyên nhóm (đổi nhóm qua categoryId
     *   hoặc API chuyển nhóm), để tên nhóm không bị lệch với nhóm thật.
     * - Chưa nằm trong cây: giữ cách cũ, lưu tên nhóm dạng chữ.
     */
    private void applyCategory(Product product, String categoryText, Long categoryId) {
        if (categoryId != null) {
            ProductCategory category = productCategoryRepository.findById(categoryId)
                    .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST,
                            "CATEGORY_NOT_FOUND", "Không tìm thấy nhóm hàng với ID " + categoryId, "categoryId"));
            product.setProductCategory(category);
            product.setCategory(category.getName());
        } else if (product.getProductCategory() != null) {
            product.setCategory(product.getProductCategory().getName());
        } else {
            product.setCategory(categoryText != null ? categoryText.trim() : null);
        }
    }

    /**
     * S2-05: Kiểm tra xem người dùng hiện tại có quyền xem/sửa Giá vốn hay không.
     * Chỉ có Quản lý kinh doanh (ROLE_SALES_MANAGER) hoặc Quản trị hệ thống (ROLE_ADMIN) mới có quyền.
     */
    public boolean canViewCostPrice(UserDetailsImpl actor) {
        if (actor != null && actor.getAuthorities() != null && !actor.getAuthorities().isEmpty()) {
            return actor.getAuthorities().stream()
                    .anyMatch(a -> "ROLE_SALES_MANAGER".equals(a.getAuthority()) || "ROLE_ADMIN".equals(a.getAuthority()));
        }
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && auth.getAuthorities() != null) {
                return auth.getAuthorities().stream()
                        .anyMatch(a -> "ROLE_SALES_MANAGER".equals(a.getAuthority()) || "ROLE_ADMIN".equals(a.getAuthority()));
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * Kiểm tra tính hợp lệ của đường dẫn ảnh sản phẩm.
     */
    public static void validateImageUrl(String imageUrl) {
        if (!StringUtils.hasText(imageUrl)) return;
        String trimmed = imageUrl.trim();
        try {
            URI uri = URI.create(trimmed);
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                throw BusinessException.badRequest("INVALID_IMAGE_URL",
                        "Đường dẫn hình ảnh phải bắt đầu bằng http:// hoặc https://");
            }
            String host = uri.getHost();
            if (host == null || (!host.contains(".") && !host.equalsIgnoreCase("localhost"))) {
                throw BusinessException.badRequest("INVALID_IMAGE_URL",
                        "Đường dẫn hình ảnh không hợp lệ (tên miền không đúng định dạng)");
            }
        } catch (IllegalArgumentException e) {
            throw BusinessException.badRequest("INVALID_IMAGE_URL",
                    "Đường dẫn hình ảnh không đúng định dạng URL");
        }
    }
}
