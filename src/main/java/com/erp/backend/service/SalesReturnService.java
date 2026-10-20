package com.erp.backend.service;

import com.erp.backend.dto.salesreturn.*;
import com.erp.backend.dto.user.PageResponse;
import com.erp.backend.entity.*;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.*;
import com.erp.backend.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * S8-01: Dịch vụ lập phiếu trả hàng từ đại lý và nhập lại kho (Sales Return Order).
 * - SCRUM-263: Viết API trả hàng, check số lượng, nhập kho ngược.
 * - SCRUM-238: Test BE: Test nhập lại đúng lô/chặn vượt số gốc.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SalesReturnService {

    private final SalesReturnRepository salesReturnRepository;
    private final SalesReturnLineRepository salesReturnLineRepository;
    private final InvoiceRepository invoiceRepository;
    private final InvoiceLineRepository invoiceLineRepository;
    private final WarehouseRepository warehouseRepository;
    private final InventoryRepository inventoryRepository;
    private final ProductLotRepository productLotRepository;
    private final StockMovementRepository stockMovementRepository;
    private final AuditLogService auditLogService;
    private final CustomerRepository customerRepository;
    private final SalesOrderLineRepository salesOrderLineRepository;
    private final UserRepository userRepository;

    Clock clock = Clock.system(ZoneId.of("Asia/Ho_Chi_Minh"));

    private static final Map<String, String> REASON_LABELS = Map.of(
            SalesReturn.REASON_DEFECTIVE_TRANSPORT, "Hỏng/vỡ do vận chuyển",
            SalesReturn.REASON_EXPIRED_OR_NEAR, "Hết hạn / cận hạn sử dụng",
            SalesReturn.REASON_WRONG_SPECIFICATION, "Sai quy cách / không đúng mẫu",
            SalesReturn.REASON_AGENCY_OVERSTOCK, "Đại lý thừa hàng / giảm quy mô",
            SalesReturn.REASON_CUSTOMER_COMPLAINT, "Khiếu nại chất lượng",
            SalesReturn.REASON_OTHER, "Lý do khác"
    );

    /** Nhãn lý do mà giao diện đang gửi lên (lưu nguyên văn) -> mã lý do dùng cho xử lý nghiệp vụ. */
    private static final Map<String, String> UI_REASON_CODES = Map.of(
            "hàng lỗi do nhà sản xuất", SalesReturn.REASON_CUSTOMER_COMPLAINT,
            "hàng cận date / hết hạn sử dụng", SalesReturn.REASON_EXPIRED_OR_NEAR,
            "giao sai quy cách / mã sản phẩm", SalesReturn.REASON_WRONG_SPECIFICATION,
            "đại lý chậm luân chuyển hàng tồn", SalesReturn.REASON_AGENCY_OVERSTOCK,
            "bao bì móp méo / hỏng khi vận chuyển", SalesReturn.REASON_DEFECTIVE_TRANSPORT,
            "lý do khác", SalesReturn.REASON_OTHER
    );

    private static final Map<String, String> STATUS_LABELS = Map.of(
            SalesReturn.STATUS_PENDING_RECEIPT, "Chờ thủ kho nhận hàng",
            SalesReturn.STATUS_RECEIVED, "Đã nhập kho",
            SalesReturn.STATUS_CANCELLED, "Đã huỷ"
    );

    /**
     * AC1 & AC2 & AC3: Lập phiếu trả hàng từ đại lý.
     * - Bắt buộc chọn hoá đơn gốc, chọn dòng hàng và số lượng trả, bắt buộc chọn lý do trả.
     * - Số lượng trả không vượt quá số lượng đã giao trừ phần đã trả trước đó.
     * - Bắt buộc nhập số lô nếu SKU có quản lý theo lô.
     * - Phiếu lập xong ở trạng thái PENDING_RECEIPT, chưa thay đổi tồn kho (AC4).
     */
    @Transactional
    public SalesReturnResponse createSalesReturn(SalesReturnCreateRequest req, UserDetailsImpl actor) {
        if (!StringUtils.hasText(req.getReturnReason())) {
            throw BusinessException.badRequest("Bắt buộc phải chọn lý do trả hàng (AC1).");
        }
        // TC801-06: chỉ nhận lý do trong danh mục (mã hoặc đúng nhãn hiển thị)
        String returnReason = req.getReturnReason().trim();
        if (reasonCode(returnReason) == null) {
            throw BusinessException.badRequest("INVALID_RETURN_REASON",
                    "Lý do trả hàng \"" + returnReason + "\" không có trong danh mục lý do trả hàng.");
        }

        Invoice invoice = invoiceRepository.findById(req.getInvoiceId())
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy hoá đơn gốc ID: " + req.getInvoiceId()));
        // TC801-17: NV kinh doanh chỉ lập phiếu trả cho đại lý mình phụ trách (khác -> 404 như không thấy hoá đơn)
        if (invoice.getCustomer() != null) {
            CustomerAccess.checkCanAccess(actor, invoice.getCustomer(), customerRepository);
        }

        if (!Invoice.STATUS_ISSUED.equals(invoice.getStatus())) {
            throw BusinessException.badRequest("Hoá đơn " + invoice.getCode() + " chưa được phát hành, không thể lập phiếu trả hàng.");
        }

        Warehouse warehouse = warehouseRepository.findById(req.getReceivingWarehouseId())
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy kho nhận hàng ID: " + req.getReceivingWarehouseId()));

        if (!"ACTIVE".equalsIgnoreCase(warehouse.getStatus())) {
            throw BusinessException.badRequest("Kho nhận " + warehouse.getName() + " đang ngừng hoạt động.");
        }

        if (req.getItems() == null || req.getItems().isEmpty()) {
            throw BusinessException.badRequest("Phiếu trả hàng phải có ít nhất một dòng mặt hàng trả.");
        }

        LocalDate returnDate = req.getReturnDate() != null ? req.getReturnDate() : LocalDate.now(clock);
        String code = generateReturnCode(returnDate);

        Customer customer = invoice.getCustomer();
        String customerCode = customer != null ? customer.getCode() : invoice.getCustomerCode();
        String customerName = customer != null ? customer.getName() : invoice.getCustomerName();

        SalesReturn salesReturn = SalesReturn.builder()
                .code(code)
                .returnDate(returnDate)
                .invoice(invoice)
                .invoiceCode(invoice.getCode())
                .customer(customer)
                .customerCode(customerCode)
                .customerName(customerName)
                .receivingWarehouse(warehouse)
                .warehouseCode(warehouse.getCode())
                .warehouseName(warehouse.getName())
                .returnReason(returnReason)
                .reasonDescription(req.getReasonDescription())
                .status(SalesReturn.STATUS_PENDING_RECEIPT)
                .notes(req.getNotes())
                .createdById(actor != null ? actor.getId() : null)
                .createdByName(actor != null ? actor.getUsername() : "system")
                .build();

        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmt = BigDecimal.ZERO;
        int lineNo = 1;

        // TC801-04 + TC801-05: gộp số lượng các dòng trùng cùng dòng hoá đơn, khoá dòng hoá đơn (theo thứ tự id để tránh deadlock)
        Map<Long, BigDecimal> requestedByLine = new HashMap<>();
        for (SalesReturnItemRequest itemReq : req.getItems()) {
            if (itemReq.getInvoiceLineId() == null) {
                throw BusinessException.badRequest("Dòng trả hàng phải chọn dòng hoá đơn gốc.");
            }
            BigDecimal q = itemReq.getReturnQuantity() != null ? itemReq.getReturnQuantity() : BigDecimal.ZERO;
            requestedByLine.merge(itemReq.getInvoiceLineId(), q, BigDecimal::add);
        }
        Map<Long, InvoiceLine> lockedLines = new HashMap<>();
        Map<Long, BigDecimal> returnedBefore = new HashMap<>();
        for (Long lineId : new TreeSet<>(requestedByLine.keySet())) {
            InvoiceLine l = invoiceLineRepository.findByIdForUpdate(lineId)
                    .orElseThrow(() -> BusinessException.notFound("Không tìm thấy dòng hoá đơn ID: " + lineId));
            lockedLines.put(lineId, l);
        }

        for (SalesReturnItemRequest itemReq : req.getItems()) {
            InvoiceLine invLine = lockedLines.get(itemReq.getInvoiceLineId());

            if (!invLine.getInvoice().getId().equals(invoice.getId())) {
                throw BusinessException.badRequest("Dòng hoá đơn ID " + itemReq.getInvoiceLineId() + " không thuộc hoá đơn " + invoice.getCode());
            }

            BigDecimal returnQty = itemReq.getReturnQuantity();
            if (returnQty == null || returnQty.compareTo(BigDecimal.ZERO) <= 0) {
                throw BusinessException.badRequest("Số lượng trả của mặt hàng " + invLine.getProductSku() + " phải lớn hơn 0.");
            }

            // AC2: Số lượng trả không vượt số lượng đã giao trừ phần đã trả trước đó
            // Đọc sau khi đã khoá dòng hoá đơn -> thấy cả phiếu vừa được người khác lập xong
            BigDecimal previouslyReturned = returnedBefore.computeIfAbsent(invLine.getId(),
                    id -> salesReturnLineRepository.getPreviouslyReturnedQuantity(id, null, SalesReturn.STATUS_CANCELLED));
            BigDecimal remainingReturnable = invLine.getQuantity().subtract(previouslyReturned);
            BigDecimal requestedTotal = requestedByLine.get(invLine.getId());

            if (requestedTotal.compareTo(remainingReturnable) > 0) {
                throw BusinessException.badRequest(String.format(
                        "Số lượng trả (%s) vượt quá số lượng đã giao còn lại (%s) của mặt hàng %s (Đã giao: %s, Đã trả trước đó: %s).",
                        requestedTotal.stripTrailingZeros().toPlainString(),
                        remainingReturnable.stripTrailingZeros().toPlainString(),
                        invLine.getProductSku(),
                        invLine.getQuantity().stripTrailingZeros().toPlainString(),
                        previouslyReturned.stripTrailingZeros().toPlainString()
                ));
            }

            // AC3: Hàng trả về nhập lại đúng lô nếu SKU có quản lý lô
            Product product = invLine.getProduct();
            boolean hasBatch = Boolean.TRUE.equals(product.getHasBatchManagement());
            if (hasBatch && !StringUtils.hasText(itemReq.getBatchNumber())) {
                throw BusinessException.badRequest("Mặt hàng " + product.getSku() + " có quản lý theo lô, bắt buộc phải chọn/nhập số lô sản xuất (AC3).");
            }
            String batchNumber = StringUtils.hasText(itemReq.getBatchNumber()) ? itemReq.getBatchNumber().trim() : null;
            if (hasBatch) {
                batchNumber = checkShippedBatch(invLine, product, batchNumber);
            }

            BigDecimal factor = invLine.getConversionFactor() != null ? invLine.getConversionFactor() : BigDecimal.ONE;
            BigDecimal baseQty = returnQty.multiply(factor);
            BigDecimal lineAmount = returnQty.multiply(invLine.getPricePerUnit());

            SalesReturnLine returnLine = SalesReturnLine.builder()
                    .lineNo(lineNo++)
                    .invoiceLine(invLine)
                    .product(product)
                    .productSku(invLine.getProductSku())
                    .productName(invLine.getProductName())
                    .unitName(invLine.getUnitName())
                    .conversionFactor(factor)
                    .deliveredQuantity(invLine.getQuantity())
                    .previouslyReturnedQuantity(previouslyReturned)
                    .returnQuantity(returnQty)
                    .baseQuantity(baseQty)
                    .pricePerUnit(invLine.getPricePerUnit())
                    .lineAmount(lineAmount)
                    .hasBatchManagement(hasBatch)
                    .batchNumber(batchNumber)
                    .manufactureDate(itemReq.getManufactureDate())
                    .expiredDate(itemReq.getExpiredDate())
                    .isDamaged(Boolean.TRUE.equals(itemReq.getIsDamaged()))
                    .lineNotes(itemReq.getLineNotes())
                    .build();

            salesReturn.addLine(returnLine);
            totalQty = totalQty.add(returnQty);
            totalAmt = totalAmt.add(lineAmount);
        }

        salesReturn.setTotalQuantity(totalQty);
        salesReturn.setTotalAmount(totalAmt);

        SalesReturn saved = salesReturnRepository.save(salesReturn);
        log.info("S8-01: Lập phiếu trả hàng thành công: code={}, đại lý={}, tổng tiền={}", saved.getCode(), customerName, totalAmt);

        return toResponse(saved);
    }

    /**
     * AC4: Quản lý kho xác nhận nhận hàng và thực hiện nhập kho ngược (Reverse Stock In).
     * - Chỉ khi Quản lý kho xác nhận nhận hàng mới cộng tồn.
     * - Cộng physicalStock vào Inventory tại kho nhận.
     * - Cộng tồn lô ProductLot tương ứng nếu SKU quản lý lô; nếu là hàng lỗi/hỏng thì khóa trạng thái lô.
     * - Ghi thẻ kho StockMovement (docType = SALES_RETURN).
     */
    @Transactional
    public SalesReturnResponse receiveAndRestock(Long returnId, String receiveNotes, UserDetailsImpl actor) {
        SalesReturn salesReturn = salesReturnRepository.findByIdForUpdate(returnId)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy phiếu trả hàng ID: " + returnId));

        if (!SalesReturn.STATUS_PENDING_RECEIPT.equals(salesReturn.getStatus())) {
            String vnStatus = SalesReturn.STATUS_RECEIVED.equalsIgnoreCase(salesReturn.getStatus())
                    ? "Đã nhận hàng vào kho"
                    : SalesReturn.STATUS_CANCELLED.equalsIgnoreCase(salesReturn.getStatus())
                    ? "Đã huỷ"
                    : salesReturn.getStatus();
            throw BusinessException.badRequest("Phiếu trả hàng " + salesReturn.getCode() + " không ở trạng thái chờ nhận hàng (Trạng thái hiện tại: " + vnStatus + ").");
        }

        Warehouse warehouse = salesReturn.getReceivingWarehouse();
        assertCanReceive(actor, warehouse);
        LocalDateTime now = LocalDateTime.now(clock);
        boolean damagedByReason = isDamagedReason(salesReturn.getReturnReason());

        log.info("S8-01: Quản lý kho bắt đầu xác nhận nhận hàng phiếu {}: kho={}, số dòng={}",
                salesReturn.getCode(), warehouse.getCode(), salesReturn.getLines().size());

        for (SalesReturnLine line : salesReturn.getLines()) {
            Product product = line.getProduct();
            BigDecimal baseQty = line.getBaseQuantity();

            // 1. Cập nhật tồn kho (Inventory)
            Inventory inventory = inventoryRepository.findByWarehouseIdAndProductIdForUpdate(warehouse.getId(), product.getId())
                    .orElseGet(() -> {
                        Inventory newInv = Inventory.builder()
                                .warehouse(warehouse)
                                .product(product)
                                .physicalStock(BigDecimal.ZERO)
                                .reservedStock(BigDecimal.ZERO)
                                .build();
                        return inventoryRepository.save(newInv);
                    });

            BigDecimal oldPhysical = inventory.getPhysicalStock() != null ? inventory.getPhysicalStock() : BigDecimal.ZERO;
            BigDecimal oldDamaged = inventory.getDamagedStock() != null ? inventory.getDamagedStock() : BigDecimal.ZERO;
            boolean damaged = damagedByReason || Boolean.TRUE.equals(line.getIsDamaged());

            if (damaged) {
                // TC801-11/12: hàng hỏng nhập vào khu hàng lỗi (cách ly), không cộng tồn bán được, không động vào lô hàng tốt
                inventory.setDamagedStock(oldDamaged.add(baseQty));
                inventoryRepository.save(inventory);
                auditReceive(salesReturn, line, inventory, oldPhysical, oldDamaged, true, actor);
                continue;
            }

            BigDecimal newPhysical = oldPhysical.add(baseQty);
            inventory.setPhysicalStock(newPhysical);
            inventoryRepository.save(inventory);
            auditReceive(salesReturn, line, inventory, oldPhysical, oldDamaged, false, actor);

            // 2. Nhập lại đúng lô nếu SKU có quản lý lô (AC3)
            if (Boolean.TRUE.equals(line.getHasBatchManagement()) && StringUtils.hasText(line.getBatchNumber())) {
                String batch = line.getBatchNumber();
                ProductLot lot = productLotRepository.findByProductAndWarehouseAndBatchNumberForUpdate(product.getId(), warehouse.getId(), batch)
                        .orElseGet(() -> ProductLot.builder()
                                .product(product)
                                .warehouse(warehouse)
                                .batchNumber(batch)
                                .manufactureDate(line.getManufactureDate())
                                .expiredDate(line.getExpiredDate())
                                .quantity(BigDecimal.ZERO)
                                .status("ACTIVE")
                                .build());

                BigDecimal oldLotQty = lot.getQuantity() != null ? lot.getQuantity() : BigDecimal.ZERO;
                lot.setQuantity(oldLotQty.add(baseQty));

                if (line.getManufactureDate() != null && lot.getManufactureDate() == null) {
                    lot.setManufactureDate(line.getManufactureDate());
                }
                if (line.getExpiredDate() != null && lot.getExpiredDate() == null) {
                    lot.setExpiredDate(line.getExpiredDate());
                }

                productLotRepository.save(lot);
            }

            // 3. Ghi thẻ kho StockMovement
            StockMovement movement = StockMovement.builder()
                    .warehouse(warehouse)
                    .warehouseCode(warehouse.getCode())
                    .product(product)
                    .productSku(product.getSku())
                    .productName(product.getName())
                    .baseUnit(product.getBaseUnit())
                    .movementType(StockMovement.TYPE_SALES_RETURN)
                    .quantity(baseQty) // Dấu dương: nhập lại vào kho
                    .balanceAfter(newPhysical)
                    .docType(StockMovement.DOC_SALES_RETURN)
                    .docId(salesReturn.getId())
                    .docCode(salesReturn.getCode())
                    .note(String.format("Nhập lại hàng trả từ đại lý %s theo phiếu %s (HĐ gốc %s)",
                            salesReturn.getCustomerName(), salesReturn.getCode(), salesReturn.getInvoiceCode()))
                    .actorId(actor != null ? actor.getId() : null)
                    .actorUsername(actor != null ? actor.getUsername() : "system")
                    .movementAt(now)
                    .build();

            stockMovementRepository.save(movement);
        }

        salesReturn.setStatus(SalesReturn.STATUS_RECEIVED);
        salesReturn.setReceivedById(actor != null ? actor.getId() : null);
        salesReturn.setReceivedByName(actor != null ? actor.getUsername() : "system");
        salesReturn.setReceivedAt(now);
        if (StringUtils.hasText(receiveNotes)) {
            salesReturn.setNotes(salesReturn.getNotes() != null
                    ? salesReturn.getNotes() + " | Nhận hàng: " + receiveNotes
                    : "Nhận hàng: " + receiveNotes);
        }

        SalesReturn saved = salesReturnRepository.save(salesReturn);
        log.info("S8-01: Quản lý kho đã xác nhận nhận hàng phiếu trả hàng {}. Tồn kho đã được cộng ngược.", saved.getCode());

        return toResponse(saved);
    }

    /**
     * Huỷ phiếu trả hàng (chỉ huỷ được khi đang ở trạng thái PENDING_RECEIPT chưa nhận hàng).
     */
    @Transactional
    public SalesReturnResponse cancelSalesReturn(Long returnId, String cancelReason, UserDetailsImpl actor) {
        SalesReturn salesReturn = salesReturnRepository.findByIdForUpdate(returnId)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy phiếu trả hàng ID: " + returnId));

        if (!SalesReturn.STATUS_PENDING_RECEIPT.equals(salesReturn.getStatus())) {
            throw BusinessException.badRequest("Không thể huỷ phiếu trả hàng ở trạng thái " + salesReturn.getStatus());
        }

        salesReturn.setStatus(SalesReturn.STATUS_CANCELLED);
        if (StringUtils.hasText(cancelReason)) {
            salesReturn.setNotes(salesReturn.getNotes() != null
                    ? salesReturn.getNotes() + " | Lý do huỷ: " + cancelReason
                    : "Lý do huỷ: " + cancelReason);
        }

        SalesReturn saved = salesReturnRepository.save(salesReturn);
        log.info("S8-01: Đã huỷ phiếu trả hàng {}", saved.getCode());

        return toResponse(saved);
    }

    /** Mã lý do (DEFECTIVE_TRANSPORT...) từ giá trị lưu trên phiếu: mã, nhãn backend hoặc nhãn giao diện. Không khớp -> null. */
    static String reasonCode(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String v = value.trim();
        for (Map.Entry<String, String> e : REASON_LABELS.entrySet()) {
            if (e.getKey().equalsIgnoreCase(v) || e.getValue().equalsIgnoreCase(v)) {
                return e.getKey();
            }
        }
        return UI_REASON_CODES.get(v.toLowerCase(Locale.ROOT));
    }

    /** Lý do mà toàn bộ hàng trả coi là hàng lỗi (giữ như quy tắc cũ): hỏng do vận chuyển, hết hạn / cận hạn. */
    static boolean isDamagedReason(String value) {
        String code = reasonCode(value);
        return SalesReturn.REASON_DEFECTIVE_TRANSPORT.equals(code) || SalesReturn.REASON_EXPIRED_OR_NEAR.equals(code);
    }

    /**
     * TC801-10: Số lô trả phải đúng lô đã xuất cho đại lý trên dòng đơn gốc.
     * Dòng đơn cũ chưa lưu lô xuất thì ít nhất số lô phải là lô có thật của SKU.
     */
    private String checkShippedBatch(InvoiceLine invLine, Product product, String batchNumber) {
        String shipped = invLine.getOrderLineId() == null ? null
                : salesOrderLineRepository.findById(invLine.getOrderLineId())
                        .map(SalesOrderLine::getBatchNumber).orElse(null);
        if (StringUtils.hasText(shipped)) {
            if (!StringUtils.hasText(batchNumber)) {
                return shipped.trim();
            }
            if (!shipped.trim().equalsIgnoreCase(batchNumber.trim())) {
                throw BusinessException.badRequest("RETURN_BATCH_MISMATCH", "Số lô " + batchNumber + " không phải lô đã giao cho đại lý của mặt hàng "
                        + product.getSku() + " (lô đã giao: " + shipped.trim() + ").");
            }
            return shipped.trim();
        }
        if (StringUtils.hasText(batchNumber) && !productLotRepository.existsByProduct_IdAndBatchNumberIgnoreCase(product.getId(), batchNumber)) {
            throw BusinessException.badRequest("RETURN_BATCH_MISMATCH", "Số lô " + batchNumber + " không tồn tại cho mặt hàng " + product.getSku() + ".");
        }
        return batchNumber;
    }

    /**
     * TC801-15/16: Chỉ Quản lý kho được phân công kho nhận (hoặc Admin) mới xác nhận nhận hàng.
     * Trả 409 thay vì 403 để giao diện không đăng xuất người dùng.
     */
    private void assertCanReceive(UserDetailsImpl actor, Warehouse warehouse) {
        Set<String> roles = new HashSet<>();
        if (actor != null && actor.getAuthorities() != null) {
            actor.getAuthorities().forEach(a -> roles.add(a.getAuthority()));
        }
        if (roles.contains("ROLE_ADMIN") || roles.contains("ROLE_SALES_MANAGER")) {
            return;
        }
        if (!roles.contains("ROLE_WH_MANAGER")) {
            throw BusinessException.conflict("RETURN_RECEIVE_MANAGER_ONLY",
                    "Chỉ Quản lý kho mới được xác nhận nhận hàng trả về.", "status");
        }
        boolean isManager = warehouse.getManager() != null && Objects.equals(warehouse.getManager().getId(), actor.getId());
        boolean assigned = isManager || userRepository.findById(actor.getId())
                .map(u -> u.getWarehouses() == null || u.getWarehouses().isEmpty() || u.getWarehouses().stream().anyMatch(w -> Objects.equals(w.getId(), warehouse.getId())))
                .orElse(true);
        if (!assigned) {
            throw BusinessException.conflict("RETURN_WAREHOUSE_NOT_ASSIGNED",
                    "Bạn không được phân công quản lý kho nhận hàng này.", "status");
        }
    }

    /** TC801-18: Nhật ký tồn kho trước / sau khi nhận hàng trả. */
    private void auditReceive(SalesReturn r, SalesReturnLine line, Inventory inv, BigDecimal oldPhysical,
                              BigDecimal oldDamaged, boolean damaged, UserDetailsImpl actor) {
        String unit = line.getProduct().getBaseUnit() != null ? " " + line.getProduct().getBaseUnit() : "";
        String before = "Tồn thực tế: " + plain(oldPhysical) + unit + "; Hàng lỗi cách ly: " + plain(oldDamaged) + unit;
        String after = "Tồn thực tế: " + plain(inv.getPhysicalStock()) + unit + "; Hàng lỗi cách ly: " + plain(inv.getDamagedStock()) + unit;
        String reason = (damaged ? "Nhận hàng trả bị lỗi vào khu cách ly" : "Nhận hàng trả nhập lại tồn bán được")
                + " theo phiếu " + r.getCode() + " (HĐ " + r.getInvoiceCode() + ", đại lý " + r.getCustomerName() + ")"
                + ", SKU " + line.getProduct().getSku()
                + (StringUtils.hasText(line.getBatchNumber()) ? ", lô " + line.getBatchNumber() : "")
                + ", số lượng " + plain(line.getBaseQuantity()) + unit;
        auditLogService.record(AuditModule.INVENTORY, "SALES_RETURN_RECEIVE", "INVENTORY", inv.getId(),
                r.getCode() + "/" + line.getProduct().getSku(), before, after, reason, actor);
    }

    private static String plain(BigDecimal v) {
        return (v != null ? v : BigDecimal.ZERO).stripTrailingZeros().toPlainString();
    }

    /**
     * Lấy danh sách các dòng của hoá đơn kèm số lượng còn có thể trả lại.
     */
    @Transactional(readOnly = true)
    public List<ReturnableInvoiceLineResponse> getReturnableInvoiceLines(Long invoiceId) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy hoá đơn ID: " + invoiceId));

        List<InvoiceLine> lines = invoiceLineRepository.findByInvoice_IdOrderByLineNoAsc(invoiceId);
        List<ReturnableInvoiceLineResponse> result = new ArrayList<>();

        for (InvoiceLine line : lines) {
            BigDecimal previouslyReturned = salesReturnLineRepository.getPreviouslyReturnedQuantity(
                    line.getId(), null, SalesReturn.STATUS_CANCELLED);
            BigDecimal remaining = line.getQuantity().subtract(previouslyReturned);
            if (remaining.compareTo(BigDecimal.ZERO) < 0) {
                remaining = BigDecimal.ZERO;
            }

            Product product = line.getProduct();
            boolean hasBatch = product != null && Boolean.TRUE.equals(product.getHasBatchManagement());
            String shippedBatch = null;
            LocalDate shippedExp = null;
            LocalDate shippedMfg = null;
            if (hasBatch) {
                if (line.getOrderLineId() != null) {
                    shippedBatch = salesOrderLineRepository.findById(line.getOrderLineId())
                            .map(SalesOrderLine::getBatchNumber).orElse(null);
                }
                if (!StringUtils.hasText(shippedBatch) && product != null) {
                    ProductLot pl = productLotRepository.findByProduct_Id(product.getId()).stream()
                            .filter(l -> StringUtils.hasText(l.getBatchNumber()))
                            .findFirst().orElse(null);
                    if (pl != null) {
                        shippedBatch = pl.getBatchNumber();
                        shippedExp = pl.getExpiredDate();
                        shippedMfg = pl.getManufactureDate();
                    }
                } else if (StringUtils.hasText(shippedBatch) && product != null) {
                    ProductLot pl = productLotRepository.findFirstByProduct_IdAndBatchNumberIgnoreCase(product.getId(), shippedBatch).orElse(null);
                    if (pl != null) {
                        shippedExp = pl.getExpiredDate();
                        shippedMfg = pl.getManufactureDate();
                    }
                }
            }

            result.add(ReturnableInvoiceLineResponse.builder()
                    .invoiceLineId(line.getId())
                    .lineNo(line.getLineNo())
                    .productId(product != null ? product.getId() : null)
                    .productSku(line.getProductSku())
                    .productName(line.getProductName())
                    .unitName(line.getUnitName())
                    .conversionFactor(line.getConversionFactor())
                    .deliveredQuantity(line.getQuantity())
                    .previouslyReturnedQuantity(previouslyReturned)
                    .remainingReturnableQuantity(remaining)
                    .pricePerUnit(line.getPricePerUnit())
                    .hasBatchManagement(hasBatch)
                    .batchNumber(shippedBatch)
                    .manufactureDate(shippedMfg)
                    .expiredDate(shippedExp)
                    .build());
        }

        return result;
    }

    @Transactional(readOnly = true)
    public SalesReturnResponse getSalesReturnDetail(Long id) {
        SalesReturn r = salesReturnRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy phiếu trả hàng ID: " + id));
        return toResponse(r);
    }

    @Transactional(readOnly = true)
    public PageResponse<SalesReturnResponse> searchSalesReturns(
            Long customerId, Long warehouseId, String status,
            LocalDate fromDate, LocalDate toDate, String keyword,
            int page, int size) {
        int p = Math.max(0, page);
        int s = Math.min(100, Math.max(1, size));
        Pageable pageable = PageRequest.of(p, s, Sort.by(Sort.Direction.DESC, "createdAt"));

        Specification<SalesReturn> spec = (root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (customerId != null) {
                ps.add(cb.equal(root.get("customer").get("id"), customerId));
            }
            if (warehouseId != null) {
                ps.add(cb.equal(root.get("receivingWarehouse").get("id"), warehouseId));
            }
            if (StringUtils.hasText(status) && !"ALL".equalsIgnoreCase(status.trim())) {
                ps.add(cb.equal(root.get("status"), status.trim()));
            }
            if (fromDate != null) {
                ps.add(cb.greaterThanOrEqualTo(root.<LocalDate>get("returnDate"), fromDate));
            }
            if (toDate != null) {
                ps.add(cb.lessThanOrEqualTo(root.<LocalDate>get("returnDate"), toDate));
            }
            if (StringUtils.hasText(keyword)) {
                String kw = "%" + keyword.trim().toLowerCase() + "%";
                ps.add(cb.or(
                        cb.like(cb.lower(root.get("code")), kw),
                        cb.like(cb.lower(root.get("invoiceCode")), kw),
                        cb.like(cb.lower(root.get("customerName")), kw)
                ));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };

        Page<SalesReturn> pageResult = salesReturnRepository.findAll(spec, pageable);

        List<SalesReturnResponse> content = pageResult.getContent().stream()
                .map(this::toResponse)
                .toList();

        return new PageResponse<>(content, pageResult.getNumber(), pageResult.getSize(),
                pageResult.getTotalElements(), pageResult.getTotalPages());
    }

    private synchronized String generateReturnCode(LocalDate date) {
        String yearMonth = date.format(DateTimeFormatter.ofPattern("yyyyMM"));
        String prefix = "PTH-" + yearMonth + "-";

        List<String> existing = salesReturnRepository.findCodesByPrefix(prefix);
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

    private SalesReturnResponse toResponse(SalesReturn r) {
        List<SalesReturnLineResponse> lineResponses = r.getLines().stream()
                .map(l -> SalesReturnLineResponse.builder()
                        .id(l.getId())
                        .lineNo(l.getLineNo())
                        .invoiceLineId(l.getInvoiceLine() != null ? l.getInvoiceLine().getId() : null)
                        .productId(l.getProduct() != null ? l.getProduct().getId() : null)
                        .productSku(l.getProductSku())
                        .productName(l.getProductName())
                        .unitName(l.getUnitName())
                        .conversionFactor(l.getConversionFactor())
                        .deliveredQuantity(l.getDeliveredQuantity())
                        .previouslyReturnedQuantity(l.getPreviouslyReturnedQuantity())
                        .returnQuantity(l.getReturnQuantity())
                        .baseQuantity(l.getBaseQuantity())
                        .pricePerUnit(l.getPricePerUnit())
                        .lineAmount(l.getLineAmount())
                        .hasBatchManagement(l.getHasBatchManagement())
                        .batchNumber(l.getBatchNumber())
                        .manufactureDate(l.getManufactureDate())
                        .expiredDate(l.getExpiredDate())
                        .isDamaged(l.getIsDamaged())
                        .lineNotes(l.getLineNotes())
                        .build())
                .toList();

        return SalesReturnResponse.builder()
                .id(r.getId())
                .code(r.getCode())
                .returnDate(r.getReturnDate())
                .invoiceId(r.getInvoice() != null ? r.getInvoice().getId() : null)
                .invoiceCode(r.getInvoiceCode())
                .customerId(r.getCustomer() != null ? r.getCustomer().getId() : null)
                .customerCode(r.getCustomerCode())
                .customerName(r.getCustomerName())
                .receivingWarehouseId(r.getReceivingWarehouse() != null ? r.getReceivingWarehouse().getId() : null)
                .warehouseCode(r.getWarehouseCode())
                .warehouseName(r.getWarehouseName())
                .returnReason(r.getReturnReason())
                .returnReasonLabel(r.getReturnReason() != null ? REASON_LABELS.getOrDefault(r.getReturnReason(), r.getReturnReason()) : "")
                .reasonDescription(r.getReasonDescription())
                .status(r.getStatus())
                .statusLabel(r.getStatus() != null ? STATUS_LABELS.getOrDefault(r.getStatus(), r.getStatus()) : "")
                .totalQuantity(r.getTotalQuantity())
                .totalAmount(r.getTotalAmount())
                .notes(r.getNotes())
                .createdById(r.getCreatedById())
                .createdByName(r.getCreatedByName())
                .receivedById(r.getReceivedById())
                .receivedByName(r.getReceivedByName())
                .receivedAt(r.getReceivedAt())
                .createdAt(r.getCreatedAt())
                .updatedAt(r.getUpdatedAt())
                .lines(lineResponses)
                .build();
    }
}
