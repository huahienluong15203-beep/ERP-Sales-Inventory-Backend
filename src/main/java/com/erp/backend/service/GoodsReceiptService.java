package com.erp.backend.service;

import com.erp.backend.dto.inventory.receipt.*;
import com.erp.backend.entity.*;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.*;
import com.erp.backend.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class GoodsReceiptService {

    private final GoodsReceiptRepository goodsReceiptRepository;
    private final GoodsReceiptLineRepository goodsReceiptLineRepository;
    private final ProductLotRepository productLotRepository;
    private final SupplierRepository supplierRepository;
    private final WarehouseRepository warehouseRepository;
    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;
    private final UserRepository userRepository;

    /**
     * S5-04: Lấy danh sách phiếu nhập kho có bộ lọc & tìm kiếm.
     */
    @Transactional(readOnly = true)
    public List<GoodsReceiptResponse> searchReceipts(String keyword, String warehouseCode, String status,
                                                     LocalDate fromDate, LocalDate toDate) {
        Specification<GoodsReceipt> spec = (root, query, cb) -> cb.conjunction();

        if (StringUtils.hasText(keyword)) {
            String kw = "%" + keyword.trim().toLowerCase() + "%";
            spec = spec.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("code")), kw),
                    cb.like(cb.lower(root.get("supplierName")), kw),
                    cb.like(cb.lower(root.get("supplierCode")), kw),
                    cb.like(cb.lower(root.get("documentNumber")), kw)
            ));
        }

        if (StringUtils.hasText(warehouseCode)) {
            spec = spec.and((root, query, cb) -> cb.equal(cb.upper(root.get("warehouseCode")), warehouseCode.trim().toUpperCase()));
        }

        if (StringUtils.hasText(status)) {
            spec = spec.and((root, query, cb) -> cb.equal(cb.upper(root.get("status")), status.trim().toUpperCase()));
        }

        if (fromDate != null) {
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("receiptDate"), fromDate));
        }

        if (toDate != null) {
            spec = spec.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("receiptDate"), toDate));
        }

        Sort sort = Sort.by(Sort.Direction.DESC, "receiptDate", "id");
        List<GoodsReceipt> list = goodsReceiptRepository.findAll(spec, sort);
        return list.stream().map(this::toResponse).toList();
    }

    /**
     * S5-04: Lấy chi tiết một phiếu nhập kho theo ID.
     */
    @Transactional(readOnly = true)
    public GoodsReceiptResponse getReceiptById(Long id) {
        GoodsReceipt receipt = goodsReceiptRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "RECEIPT_NOT_FOUND",
                        "Không tìm thấy phiếu nhập kho với ID " + id));
        return toResponse(receipt);
    }

    /**
     * S5-04: Tạo mới phiếu nhập kho (Lưu nháp hoặc Xác nhận).
     */
    @Transactional
    public GoodsReceiptResponse createReceipt(GoodsReceiptCreateRequest request, UserDetailsImpl actor) {
        if (request == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Dữ liệu yêu cầu không hợp lệ");
        }

        // 1. Kiểm tra Nhà cung cấp
        Supplier supplier = supplierRepository.findById(request.getSupplierId())
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "SUPPLIER_NOT_FOUND",
                        "Không tìm thấy nhà cung cấp với ID " + request.getSupplierId()));

        if (!"ACTIVE".equalsIgnoreCase(supplier.getStatus())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "SUPPLIER_INACTIVE",
                    "Nhà cung cấp [" + supplier.getName() + "] đang ngừng giao dịch, không thể nhập hàng");
        }

        // 2. Kiểm tra Kho nhận hàng
        Warehouse warehouse = warehouseRepository.findByCodeIgnoreCase(request.getWarehouseCode())
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "WAREHOUSE_NOT_FOUND",
                        "Không tìm thấy kho tiếp nhận với mã " + request.getWarehouseCode()));

        if (!"ACTIVE".equalsIgnoreCase(warehouse.getStatus())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "WAREHOUSE_INACTIVE",
                    "Kho hàng [" + warehouse.getName() + "] đang ngưng hoạt động, không thể nhập hàng");
        }

        // 3. Phân tích ngày nhập kho
        LocalDate receiptDate;
        try {
            receiptDate = LocalDate.parse(request.getReceiptDate().trim());
        } catch (Exception e) {
            receiptDate = LocalDate.now();
        }

        // 4. Sinh mã phiếu nhập kho PNK-yyyyMM-xxx
        String code = generateReceiptCode(receiptDate);

        String targetStatus = GoodsReceipt.STATUS_CONFIRMED.equalsIgnoreCase(request.getStatus())
                ? GoodsReceipt.STATUS_CONFIRMED : GoodsReceipt.STATUS_DRAFT;

        User user = null;
        String username = actor != null ? actor.getUsername() : "system";
        if (actor != null && actor.getId() != null) {
            user = userRepository.findById(actor.getId()).orElse(null);
        }

        GoodsReceipt receipt = GoodsReceipt.builder()
                .code(code)
                .supplier(supplier)
                .supplierCode(supplier.getCode())
                .supplierName(supplier.getName())
                .documentNumber(request.getDocumentNumber().trim())
                .receiptDate(receiptDate)
                .warehouse(warehouse)
                .warehouseCode(warehouse.getCode())
                .warehouseName(warehouse.getName())
                .status(targetStatus)
                .vehiclePlate(StringUtils.hasText(request.getVehiclePlate()) ? request.getVehiclePlate().trim() : null)
                .driverName(StringUtils.hasText(request.getDriverName()) ? request.getDriverName().trim() : null)
                .note(StringUtils.hasText(request.getNote()) ? request.getNote().trim() : null)
                .createdBy(user)
                .createdByUsername(username)
                .build();

        // 5. Xử lý các dòng hàng & quy đổi đơn vị
        BigDecimal totalBaseQty = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;

        for (GoodsReceiptLineCreateRequest lineReq : request.getLines()) {
            Product product = productRepository.findById(lineReq.getProductId())
                    .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "PRODUCT_NOT_FOUND",
                            "Không tìm thấy sản phẩm ID " + lineReq.getProductId()));

            // Tính hệ số quy đổi và số lượng cơ sở (AC2)
            BigDecimal factor = lineReq.getConversionFactor() != null && lineReq.getConversionFactor().compareTo(BigDecimal.ZERO) > 0
                    ? lineReq.getConversionFactor() : BigDecimal.ONE;

            BigDecimal qty = lineReq.getQuantity() != null && lineReq.getQuantity().compareTo(BigDecimal.ZERO) > 0
                    ? lineReq.getQuantity() : BigDecimal.ZERO;

            BigDecimal baseQty = qty.multiply(factor);

            BigDecimal unitPrice = lineReq.getUnitPrice() != null ? lineReq.getUnitPrice() : BigDecimal.ZERO;
            // Thành tiền = baseQty * unitPrice
            BigDecimal lineAmount = lineReq.getTotalAmount() != null && lineReq.getTotalAmount().compareTo(BigDecimal.ZERO) > 0
                    ? lineReq.getTotalAmount() : baseQty.multiply(unitPrice);

            // Kiểm tra số lô và hạn sử dụng (AC3)
            boolean hasBatch = Boolean.TRUE.equals(lineReq.getHasBatchManagement());
            String batchNumber = StringUtils.hasText(lineReq.getBatchNumber()) ? lineReq.getBatchNumber().trim() : null;
            LocalDate expiredDate = null;
            if (StringUtils.hasText(lineReq.getExpiredDate())) {
                try {
                    expiredDate = LocalDate.parse(lineReq.getExpiredDate().trim());
                } catch (Exception ignored) {
                }
            }

            if (hasBatch) {
                if (!StringUtils.hasText(batchNumber)) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "BATCH_NUMBER_REQUIRED",
                            "Mặt hàng [" + product.getName() + "] có quản lý lô, bắt buộc nhập Số lô sản xuất (AC3)");
                }
                if (expiredDate == null) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "EXPIRED_DATE_REQUIRED",
                            "Mặt hàng [" + product.getName() + "] có quản lý lô, bắt buộc chọn Hạn sử dụng (AC3)");
                }
            }

            GoodsReceiptLine line = GoodsReceiptLine.builder()
                    .goodsReceipt(receipt)
                    .product(product)
                    .productSku(product.getSku())
                    .productName(product.getName())
                    .category(product.getCategory())
                    .baseUnit(product.getBaseUnit())
                    .selectedUnit(lineReq.getSelectedUnit())
                    .conversionFactor(factor)
                    .quantity(qty)
                    .baseQuantity(baseQty)
                    .unitPrice(unitPrice)
                    .totalAmount(lineAmount)
                    .batchNumber(batchNumber)
                    .expiredDate(expiredDate)
                    .hasBatchManagement(hasBatch)
                    .note(lineReq.getNote())
                    .build();

            receipt.addLine(line);
            totalBaseQty = totalBaseQty.add(baseQty);
            totalAmount = totalAmount.add(lineAmount);
        }

        receipt.setTotalLines(receipt.getLines().size());
        receipt.setTotalBaseQuantity(totalBaseQty);
        receipt.setTotalAmount(totalAmount);

        // 6. Nếu lưu với trạng thái CONFIRMED -> cộng tồn kho ngay (AC4)
        if (GoodsReceipt.STATUS_CONFIRMED.equalsIgnoreCase(targetStatus)) {
            receipt.setConfirmedAt(LocalDateTime.now());
            receipt.setConfirmedBy(user);
            receipt.setConfirmedByUsername(username);
            applyInventoryAddition(receipt);
        }

        GoodsReceipt saved = goodsReceiptRepository.save(receipt);
        log.info("S5-04: Đã lập phiếu nhập kho {} với trạng thái {}, tổng {} dòng, {} đơn vị cơ sở",
                saved.getCode(), saved.getStatus(), saved.getTotalLines(), saved.getTotalBaseQuantity());

        return toResponse(saved);
    }

    /**
     * S5-04: Xác nhận phiếu nhập kho (AC4: Cộng tồn kho khi xác nhận).
     * Phiếu đã xác nhận không được sửa hay xác nhận lại.
     */
    @Transactional
    public GoodsReceiptResponse confirmReceipt(Long id, UserDetailsImpl actor) {
        GoodsReceipt receipt = goodsReceiptRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "RECEIPT_NOT_FOUND",
                        "Không tìm thấy phiếu nhập kho với ID " + id));

        // AC5: Phiếu đã xác nhận không sửa được, chỉ lập phiếu điều chỉnh
        if (GoodsReceipt.STATUS_CONFIRMED.equalsIgnoreCase(receipt.getStatus())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "CANNOT_MODIFY_CONFIRMED_RECEIPT",
                    "Phiếu đã xác nhận không sửa được, chỉ lập phiếu điều chỉnh");
        }

        if (GoodsReceipt.STATUS_CANCELLED.equalsIgnoreCase(receipt.getStatus())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "RECEIPT_CANCELLED",
                    "Phiếu nhập kho đã bị hủy, không thể xác nhận ghi sổ");
        }

        User user = null;
        String username = actor != null ? actor.getUsername() : "system";
        if (actor != null && actor.getId() != null) {
            user = userRepository.findById(actor.getId()).orElse(null);
        }

        receipt.setStatus(GoodsReceipt.STATUS_CONFIRMED);
        receipt.setConfirmedAt(LocalDateTime.now());
        receipt.setConfirmedBy(user);
        receipt.setConfirmedByUsername(username);

        // Cộng tồn kho theo đơn vị cơ sở và cập nhật lô/hạn
        applyInventoryAddition(receipt);

        GoodsReceipt saved = goodsReceiptRepository.save(receipt);
        log.info("S5-04: Đã xác nhận phiếu nhập kho {} (AC4), tồn kho đã được cộng thêm", saved.getCode());
        return toResponse(saved);
    }

    /**
     * Cộng tồn kho thực tế (physical_stock) theo ĐVT cơ sở và ghi nhận lô/hạn.
     */
    private void applyInventoryAddition(GoodsReceipt receipt) {
        Warehouse warehouse = receipt.getWarehouse();

        for (GoodsReceiptLine line : receipt.getLines()) {
            Product product = line.getProduct();
            BigDecimal baseQty = line.getBaseQuantity();

            // 1. Cập nhật tồn kho vật lý (Physical Stock)
            Inventory inv = inventoryRepository
                    .findByWarehouseIdAndProductIdForUpdate(warehouse.getId(), product.getId())
                    .orElseGet(() -> {
                        Inventory newInv = Inventory.builder()
                                .warehouse(warehouse)
                                .product(product)
                                .physicalStock(BigDecimal.ZERO)
                                .reservedStock(BigDecimal.ZERO)
                                .build();
                        return inventoryRepository.save(newInv);
                    });

            inv.setPhysicalStock(inv.getPhysicalStock().add(baseQty));
            inventoryRepository.save(inv);

            // 2. Ghi nhận thông tin số lô & hạn sử dụng (AC3)
            if (StringUtils.hasText(line.getBatchNumber())) {
                String batchNum = line.getBatchNumber().trim();
                ProductLot lot = productLotRepository
                        .findByProduct_IdAndWarehouse_IdAndBatchNumber(product.getId(), warehouse.getId(), batchNum)
                        .orElseGet(() -> ProductLot.builder()
                                .product(product)
                                .warehouse(warehouse)
                                .batchNumber(batchNum)
                                .expiredDate(line.getExpiredDate())
                                .supplier(receipt.getSupplier())
                                .quantity(BigDecimal.ZERO)
                                .build());

                lot.setQuantity(lot.getQuantity().add(baseQty));
                if (line.getExpiredDate() != null) {
                    lot.setExpiredDate(line.getExpiredDate());
                }
                productLotRepository.save(lot);
            }
        }
    }

    /**
     * Sinh mã phiếu nhập kho: PNK-yyyyMM-xxx (vd: PNK-202610-001)
     */
    private synchronized String generateReceiptCode(LocalDate date) {
        String yearMonth = date.format(DateTimeFormatter.ofPattern("yyyyMM"));
        String prefix = "PNK-" + yearMonth + "-";

        List<String> existing = goodsReceiptRepository.findCodesByPrefix(prefix);
        int maxSeq = 0;
        for (String c : existing) {
            try {
                String suffix = c.substring(prefix.length());
                int seq = Integer.parseInt(suffix);
                if (seq > maxSeq) maxSeq = seq;
            } catch (Exception ignored) {
            }
        }

        return String.format("%s%03d", prefix, maxSeq + 1);
    }

    private GoodsReceiptResponse toResponse(GoodsReceipt r) {
        List<GoodsReceiptLineResponse> lineResponses = r.getLines().stream().map(l ->
                GoodsReceiptLineResponse.builder()
                        .id(String.valueOf(l.getId()))
                        .productId(l.getProduct().getId())
                        .productSku(l.getProductSku())
                        .productName(l.getProductName())
                        .category(l.getCategory())
                        .baseUnit(l.getBaseUnit())
                        .selectedUnit(l.getSelectedUnit())
                        .conversionFactor(l.getConversionFactor())
                        .quantity(l.getQuantity())
                        .baseQuantity(l.getBaseQuantity())
                        .unitPrice(l.getUnitPrice())
                        .totalAmount(l.getTotalAmount())
                        .batchNumber(l.getBatchNumber())
                        .expiredDate(l.getExpiredDate() != null ? l.getExpiredDate().toString() : null)
                        .hasBatchManagement(l.getHasBatchManagement())
                        .note(l.getNote())
                        .build()
        ).toList();

        return GoodsReceiptResponse.builder()
                .id(r.getId())
                .code(r.getCode())
                .supplierId(r.getSupplier().getId())
                .supplierCode(r.getSupplierCode())
                .supplierName(r.getSupplierName())
                .documentNumber(r.getDocumentNumber())
                .receiptDate(r.getReceiptDate() != null ? r.getReceiptDate().toString() : null)
                .warehouseCode(r.getWarehouseCode())
                .warehouseName(r.getWarehouseName())
                .status(r.getStatus())
                .lines(lineResponses)
                .totalLines(r.getTotalLines())
                .totalBaseQuantity(r.getTotalBaseQuantity())
                .totalAmount(r.getTotalAmount())
                .vehiclePlate(r.getVehiclePlate())
                .driverName(r.getDriverName())
                .note(r.getNote())
                .createdByUsername(r.getCreatedByUsername())
                .confirmedAt(r.getConfirmedAt() != null ? r.getConfirmedAt().toString() : null)
                .confirmedByUsername(r.getConfirmedByUsername())
                .createdAt(r.getCreatedAt() != null ? r.getCreatedAt().toString() : null)
                .updatedAt(r.getUpdatedAt() != null ? r.getUpdatedAt().toString() : null)
                .build();
    }
}
