package com.erp.backend.service;

import com.erp.backend.dto.inventory.transfer.*;
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
public class StockTransferService {

    private final StockTransferRepository stockTransferRepository;
    private final StockTransferLineRepository stockTransferLineRepository;
    private final ProductLotRepository productLotRepository;
    private final WarehouseRepository warehouseRepository;
    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;
    private final UserRepository userRepository;

    /**
     * S5-07: Tìm kiếm danh sách phiếu chuyển kho theo bộ lọc.
     */
    @Transactional(readOnly = true)
    public List<StockTransferResponse> searchTransfers(String status, String sourceWarehouse,
                                                       String destWarehouse, String keyword) {
        Specification<StockTransfer> spec = (root, query, cb) -> cb.conjunction();

        if (StringUtils.hasText(status) && !"ALL".equalsIgnoreCase(status.trim())) {
            spec = spec.and((root, query, cb) -> cb.equal(cb.upper(root.get("status")), status.trim().toUpperCase()));
        }

        if (StringUtils.hasText(sourceWarehouse) && !"ALL".equalsIgnoreCase(sourceWarehouse.trim())) {
            spec = spec.and((root, query, cb) -> cb.equal(cb.upper(root.get("sourceWarehouseCode")), sourceWarehouse.trim().toUpperCase()));
        }

        if (StringUtils.hasText(destWarehouse) && !"ALL".equalsIgnoreCase(destWarehouse.trim())) {
            spec = spec.and((root, query, cb) -> cb.equal(cb.upper(root.get("destWarehouseCode")), destWarehouse.trim().toUpperCase()));
        }

        if (StringUtils.hasText(keyword)) {
            String kw = "%" + keyword.trim().toLowerCase() + "%";
            spec = spec.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("code")), kw),
                    cb.like(cb.lower(root.get("vehiclePlate")), kw),
                    cb.like(cb.lower(root.get("transporterName")), kw)
            ));
        }

        Sort sort = Sort.by(Sort.Direction.DESC, "transferDate", "id");
        List<StockTransfer> list = stockTransferRepository.findAll(spec, sort);
        return list.stream().map(this::toResponse).toList();
    }

    /**
     * S5-07: Xem chi tiết một phiếu chuyển kho.
     */
    @Transactional(readOnly = true)
    public StockTransferResponse getTransferById(Long id) {
        StockTransfer transfer = stockTransferRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND",
                        "Không tìm thấy phiếu chuyển kho với ID " + id));
        return toResponse(transfer);
    }

    /**
     * S5-07 AC1: Lập phiếu chuyển kho nội bộ (Chọn kho đi, kho đến, danh sách hàng và số lượng).
     */
    @Transactional
    public StockTransferResponse createTransfer(CreateStockTransferPayload payload, UserDetailsImpl actor) {
        if (payload == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Dữ liệu yêu cầu không hợp lệ");
        }

        // 1. Kiểm tra kho đi và kho đến
        if (payload.getSourceWarehouseCode().equalsIgnoreCase(payload.getDestWarehouseCode())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "SAME_WAREHOUSE",
                    "Kho nguồn và kho đích không được trùng nhau");
        }

        Warehouse sourceWh = warehouseRepository.findByCodeIgnoreCase(payload.getSourceWarehouseCode())
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "SOURCE_WAREHOUSE_NOT_FOUND",
                        "Không tìm thấy kho xuất với mã " + payload.getSourceWarehouseCode()));

        if (!"ACTIVE".equalsIgnoreCase(sourceWh.getStatus())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "SOURCE_WAREHOUSE_INACTIVE",
                    "Kho xuất [" + sourceWh.getName() + "] đang ngưng hoạt động");
        }

        Warehouse destWh = warehouseRepository.findByCodeIgnoreCase(payload.getDestWarehouseCode())
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "DEST_WAREHOUSE_NOT_FOUND",
                        "Không tìm thấy kho đích với mã " + payload.getDestWarehouseCode()));

        if (!"ACTIVE".equalsIgnoreCase(destWh.getStatus())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "DEST_WAREHOUSE_INACTIVE",
                    "Kho đích [" + destWh.getName() + "] đang ngưng hoạt động");
        }

        LocalDate transferDate;
        try {
            transferDate = LocalDate.parse(payload.getTransferDate().trim());
        } catch (Exception e) {
            transferDate = LocalDate.now();
        }

        LocalDate expectedReceiveDate = null;
        if (StringUtils.hasText(payload.getExpectedReceiveDate())) {
            try {
                expectedReceiveDate = LocalDate.parse(payload.getExpectedReceiveDate().trim());
            } catch (Exception ignored) {
            }
        }

        String code = generateTransferCode(transferDate);

        String targetStatus = StockTransfer.STATUS_IN_TRANSIT.equalsIgnoreCase(payload.getStatus())
                ? StockTransfer.STATUS_IN_TRANSIT : StockTransfer.STATUS_DRAFT;

        User user = null;
        String username = actor != null ? actor.getUsername() : "system";
        if (actor != null && actor.getId() != null) {
            user = userRepository.findById(actor.getId()).orElse(null);
        }

        StockTransfer transfer = StockTransfer.builder()
                .code(code)
                .sourceWarehouse(sourceWh)
                .sourceWarehouseCode(sourceWh.getCode())
                .sourceWarehouseName(sourceWh.getName())
                .destWarehouse(destWh)
                .destWarehouseCode(destWh.getCode())
                .destWarehouseName(destWh.getName())
                .transferDate(transferDate)
                .expectedReceiveDate(expectedReceiveDate)
                .status(targetStatus)
                .vehiclePlate(StringUtils.hasText(payload.getVehiclePlate()) ? payload.getVehiclePlate().trim() : null)
                .transporterName(StringUtils.hasText(payload.getTransporterName()) ? payload.getTransporterName().trim() : null)
                .note(StringUtils.hasText(payload.getNote()) ? payload.getNote().trim() : null)
                .createdBy(user)
                .createdByUsername(username)
                .dispatchedAt(StockTransfer.STATUS_IN_TRANSIT.equalsIgnoreCase(targetStatus) ? LocalDateTime.now() : null)
                .build();

        // 2. Kiểm tra danh sách hàng và tồn kho
        for (CreateStockTransferLinePayload lineReq : payload.getLines()) {
            Product product = productRepository.findById(lineReq.getProductId())
                    .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "PRODUCT_NOT_FOUND",
                            "Không tìm thấy sản phẩm ID " + lineReq.getProductId()));

            BigDecimal transferQty = lineReq.getTransferQuantity();

            // Kiểm tra tồn khả dụng tại kho đi
            Inventory sourceInv = inventoryRepository.findByWarehouseIdAndProductIdForUpdate(sourceWh.getId(), product.getId())
                    .orElse(null);

            BigDecimal currentPhysical = sourceInv != null ? sourceInv.getPhysicalStock() : BigDecimal.ZERO;
            BigDecimal currentReserved = sourceInv != null ? sourceInv.getReservedStock() : BigDecimal.ZERO;
            BigDecimal available = currentPhysical.subtract(currentReserved);

            if (available.compareTo(transferQty) < 0) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "INSUFFICIENT_STOCK",
                        "Tồn khả dụng tại kho xuất không đủ để chuyển mặt hàng [" + product.getName() + "]. " +
                                "Khả dụng: " + available + ", yêu cầu: " + transferQty);
            }

            LocalDate expDate = null;
            if (StringUtils.hasText(lineReq.getExpiredDate())) {
                try {
                    expDate = LocalDate.parse(lineReq.getExpiredDate().trim());
                } catch (Exception ignored) {
                }
            }

            StockTransferLine line = StockTransferLine.builder()
                    .stockTransfer(transfer)
                    .product(product)
                    .productSku(product.getSku())
                    .productName(product.getName())
                    .category(product.getCategory())
                    .unit(StringUtils.hasText(lineReq.getUnit()) ? lineReq.getUnit() : product.getBaseUnit())
                    .sourceAvailableStock(available)
                    .transferQuantity(transferQty)
                    .batchNumber(StringUtils.hasText(lineReq.getBatchNumber()) ? lineReq.getBatchNumber().trim() : null)
                    .expiredDate(expDate)
                    .build();

            transfer.addLine(line);

            // AC2: Nếu xuất kho ngay (IN_TRANSIT) -> trừ tồn kho nguồn ngay, CHƯA cộng kho đến
            if (StockTransfer.STATUS_IN_TRANSIT.equalsIgnoreCase(targetStatus)) {
                sourceInv.setPhysicalStock(sourceInv.getPhysicalStock().subtract(transferQty));
                inventoryRepository.save(sourceInv);
            }
        }

        StockTransfer saved = stockTransferRepository.save(transfer);
        log.info("S5-07: Đã tạo phiếu chuyển kho {} từ {} sang {}, trạng thái: {}",
                saved.getCode(), saved.getSourceWarehouseCode(), saved.getDestWarehouseCode(), saved.getStatus());

        return toResponse(saved);
    }

    /**
     * S5-07 AC2: Xuất kho bắt đầu vận chuyển (chuyển trạng thái từ DRAFT sang IN_TRANSIT).
     * Trừ tồn kho nguồn, ghi nhận hàng Đang đi đường, CHƯA cộng vào kho đến.
     */
    @Transactional
    public StockTransferResponse dispatchTransfer(Long id, UserDetailsImpl actor) {
        StockTransfer transfer = stockTransferRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND",
                        "Không tìm thấy phiếu chuyển kho với ID " + id));

        if (!StockTransfer.STATUS_DRAFT.equalsIgnoreCase(transfer.getStatus())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_STATUS",
                    "Chỉ có thể xuất kho cho phiếu đang ở trạng thái Nháp (DRAFT)");
        }

        Warehouse sourceWh = transfer.getSourceWarehouse();

        for (StockTransferLine line : transfer.getLines()) {
            Product product = line.getProduct();
            BigDecimal transferQty = line.getTransferQuantity();

            Inventory sourceInv = inventoryRepository
                    .findByWarehouseIdAndProductIdForUpdate(sourceWh.getId(), product.getId())
                    .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "INSUFFICIENT_STOCK",
                            "Không tìm thấy tồn kho cho mặt hàng [" + product.getName() + "] tại kho xuất"));

            BigDecimal available = sourceInv.getPhysicalStock().subtract(sourceInv.getReservedStock());
            if (available.compareTo(transferQty) < 0) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "INSUFFICIENT_STOCK",
                        "Tồn khả dụng không đủ để xuất kho mặt hàng [" + product.getName() + "]. " +
                                "Khả dụng: " + available + ", yêu cầu: " + transferQty);
            }

            // Trừ tồn kho nguồn
            sourceInv.setPhysicalStock(sourceInv.getPhysicalStock().subtract(transferQty));
            inventoryRepository.save(sourceInv);
        }

        transfer.setStatus(StockTransfer.STATUS_IN_TRANSIT);
        transfer.setDispatchedAt(LocalDateTime.now());

        StockTransfer saved = stockTransferRepository.save(transfer);
        log.info("S5-07: Đã xuất kho phiếu chuyển kho {} (AC2: Trừ kho đi, ghi nhận Đang trên đường)", saved.getCode());
        return toResponse(saved);
    }

    /**
     * S5-07 AC3 & AC4: Kho đến xác nhận nhận hàng.
     * - AC3: Kho đến nhận đủ thì tồn mới được cộng chính thức vào kho đến.
     * - AC4: Nếu có chênh lệch khi nhận thì bắt buộc phải nhập lý do.
     */
    @Transactional
    public StockTransferResponse receiveTransfer(Long id, ReceiveTransferPayload payload, UserDetailsImpl actor) {
        StockTransfer transfer = stockTransferRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND",
                        "Không tìm thấy phiếu chuyển kho với ID " + id));

        if (!StockTransfer.STATUS_IN_TRANSIT.equalsIgnoreCase(transfer.getStatus())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "CANNOT_RECEIVE_NON_TRANSIT",
                    "Chỉ có thể xác nhận nhận hàng khi phiếu ở trạng thái Đang đi đường (IN_TRANSIT)");
        }

        Warehouse destWh = transfer.getDestWarehouse();
        boolean hasDiscrepancy = false;

        Map<String, ReceiveTransferLineItem> receivedMap = new HashMap<>();
        if (payload != null && payload.getReceivedLines() != null) {
            for (ReceiveTransferLineItem item : payload.getReceivedLines()) {
                receivedMap.put(item.getLineId(), item);
            }
        }

        for (StockTransferLine line : transfer.getLines()) {
            String lineKey = String.valueOf(line.getId());
            ReceiveTransferLineItem item = receivedMap.get(lineKey);

            BigDecimal receivedQty = item != null && item.getReceivedQuantity() != null
                    ? item.getReceivedQuantity() : line.getTransferQuantity();

            if (receivedQty.compareTo(BigDecimal.ZERO) < 0) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_RECEIVED_QUANTITY",
                        "Số lượng nhận không được âm");
            }

            BigDecimal diff = line.getTransferQuantity().subtract(receivedQty);
            line.setReceivedQuantity(receivedQty);
            line.setDifferenceQuantity(diff);

            String reason = item != null ? item.getDiscrepancyReason() : null;

            // AC4: Chênh lệch khi nhận phải nhập lý do
            if (diff.compareTo(BigDecimal.ZERO) != 0) {
                hasDiscrepancy = true;
                boolean hasLineReason = StringUtils.hasText(reason);
                boolean hasGenReason = payload != null && StringUtils.hasText(payload.getGeneralReason());

                if (!hasLineReason && !hasGenReason) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "DISCREPANCY_REASON_REQUIRED",
                            "Phát hiện chênh lệch tại mặt hàng [" + line.getProductName() +
                                    "]. Bắt buộc nhập lý do chênh lệch theo AC4.");
                }
            }

            if (StringUtils.hasText(reason)) {
                line.setDiscrepancyReason(reason.trim());
            }

            // AC3: Cộng tồn kho đến theo số lượng thực tế nhận được
            Product product = line.getProduct();
            Inventory destInv = inventoryRepository
                    .findByWarehouseIdAndProductIdForUpdate(destWh.getId(), product.getId())
                    .orElseGet(() -> {
                        Inventory newInv = Inventory.builder()
                                .warehouse(destWh)
                                .product(product)
                                .physicalStock(BigDecimal.ZERO)
                                .reservedStock(BigDecimal.ZERO)
                                .build();
                        return inventoryRepository.save(newInv);
                    });

            destInv.setPhysicalStock(destInv.getPhysicalStock().add(receivedQty));
            inventoryRepository.save(destInv);

            // Cập nhật lô hàng tại kho đích nếu có số lô
            if (StringUtils.hasText(line.getBatchNumber())) {
                String batchNum = line.getBatchNumber().trim();
                ProductLot lot = productLotRepository
                        .findByProduct_IdAndWarehouse_IdAndBatchNumber(product.getId(), destWh.getId(), batchNum)
                        .orElseGet(() -> ProductLot.builder()
                                .product(product)
                                .warehouse(destWh)
                                .batchNumber(batchNum)
                                .expiredDate(line.getExpiredDate())
                                .quantity(BigDecimal.ZERO)
                                .build());

                lot.setQuantity(lot.getQuantity().add(receivedQty));
                if (line.getExpiredDate() != null) {
                    lot.setExpiredDate(line.getExpiredDate());
                }
                productLotRepository.save(lot);
            }
        }

        transfer.setStatus(hasDiscrepancy ? StockTransfer.STATUS_DISCREPANCY_RESOLVED : StockTransfer.STATUS_COMPLETED);
        transfer.setReceivedAt(LocalDateTime.now());
        if (payload != null && StringUtils.hasText(payload.getGeneralReason())) {
            transfer.setDiscrepancyGeneralReason(payload.getGeneralReason().trim());
        }

        StockTransfer saved = stockTransferRepository.save(transfer);
        log.info("S5-07: Kho đến đã xác nhận nhận hàng cho phiếu {} (Trạng thái: {})", saved.getCode(), saved.getStatus());
        return toResponse(saved);
    }

    private synchronized String generateTransferCode(LocalDate date) {
        String yearMonth = date.format(DateTimeFormatter.ofPattern("yyyyMM"));
        String prefix = "TRF-" + yearMonth + "-";

        List<String> existing = stockTransferRepository.findCodesByPrefix(prefix);
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

    private StockTransferResponse toResponse(StockTransfer t) {
        List<StockTransferLineResponse> lineResponses = t.getLines().stream().map(l ->
                StockTransferLineResponse.builder()
                        .id(String.valueOf(l.getId()))
                        .productId(l.getProduct().getId())
                        .productSku(l.getProductSku())
                        .productName(l.getProductName())
                        .category(l.getCategory())
                        .unit(l.getUnit())
                        .sourceAvailableStock(l.getSourceAvailableStock())
                        .transferQuantity(l.getTransferQuantity())
                        .receivedQuantity(l.getReceivedQuantity())
                        .differenceQuantity(l.getDifferenceQuantity())
                        .discrepancyReason(l.getDiscrepancyReason())
                        .batchNumber(l.getBatchNumber())
                        .expiredDate(l.getExpiredDate() != null ? l.getExpiredDate().toString() : null)
                        .build()
        ).toList();

        return StockTransferResponse.builder()
                .id(String.valueOf(t.getId()))
                .code(t.getCode())
                .sourceWarehouseCode(t.getSourceWarehouseCode())
                .sourceWarehouseName(t.getSourceWarehouseName())
                .destWarehouseCode(t.getDestWarehouseCode())
                .destWarehouseName(t.getDestWarehouseName())
                .transferDate(t.getTransferDate() != null ? t.getTransferDate().toString() : null)
                .expectedReceiveDate(t.getExpectedReceiveDate() != null ? t.getExpectedReceiveDate().toString() : null)
                .status(t.getStatus())
                .lines(lineResponses)
                .vehiclePlate(t.getVehiclePlate())
                .transporterName(t.getTransporterName())
                .note(t.getNote())
                .discrepancyGeneralReason(t.getDiscrepancyGeneralReason())
                .createdBy(t.getCreatedByUsername())
                .dispatchedAt(t.getDispatchedAt() != null ? t.getDispatchedAt().toString() : null)
                .receivedAt(t.getReceivedAt() != null ? t.getReceivedAt().toString() : null)
                .createdAt(t.getCreatedAt() != null ? t.getCreatedAt().toString() : null)
                .updatedAt(t.getUpdatedAt() != null ? t.getUpdatedAt().toString() : null)
                .build();
    }
}
