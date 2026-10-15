package com.erp.backend.service;

import com.erp.backend.dto.inventory.stockcard.StockCardResponse;
import com.erp.backend.dto.inventory.stockcard.StockCardRow;
import com.erp.backend.dto.user.PageResponse;
import com.erp.backend.entity.Inventory;
import com.erp.backend.entity.Product;
import com.erp.backend.entity.StockMovement;
import com.erp.backend.entity.Warehouse;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.InventoryRepository;
import com.erp.backend.repository.ProductRepository;
import com.erp.backend.repository.StockMovementRepository;
import com.erp.backend.repository.WarehouseRepository;
import com.erp.backend.security.UserDetailsImpl;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * S6-02: Thẻ kho.
 * - record(...): gọi NGAY sau mỗi lần cộng / trừ tồn thực tế, trong cùng transaction (dòng tồn đang bị khoá).
 * - stockCard(...): xem mọi nghiệp vụ nhập / xuất / chuyển / điều chỉnh theo thời gian, số dư sau từng nghiệp vụ,
 *   lọc theo kho, sản phẩm, loại nghiệp vụ và khoảng thời gian. NV kho chỉ xem kho mình được gắn.
 */
@Service
@RequiredArgsConstructor
public class StockMovementService {

    static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    static final int DEFAULT_PAGE_SIZE = 50;
    static final int MAX_PAGE_SIZE = 200;
    static final long MAX_RANGE_DAYS = 366;
    static final int DEFAULT_RANGE_DAYS = 30;

    private final StockMovementRepository movementRepository;
    private final InventoryRepository inventoryRepository;
    private final WarehouseRepository warehouseRepository;
    private final ProductRepository productRepository;
    private final StockLedgerService stockLedgerService;

    Clock clock = Clock.system(VN_ZONE);

    // ======================= GHI THẺ KHO =======================

    /**
     * Ghi 1 dòng thẻ kho cho dòng tồn vừa thay đổi. inv.getPhysicalStock() phải là số SAU khi cộng / trừ.
     * signedQuantity: + tăng tồn, - giảm tồn (ĐVT cơ sở). Bằng 0 thì bỏ qua (không ghi dòng rỗng).
     *
     * @return dòng vừa ghi (để gắn docId sau khi chứng từ được lưu), hoặc null nếu bỏ qua
     */
    @Transactional
    public StockMovement record(Inventory inv, String movementType, BigDecimal signedQuantity,
                                String docType, Long docId, String docCode, String note, UserDetailsImpl actor) {
        if (inv == null || signedQuantity == null || signedQuantity.signum() == 0) {
            return null;
        }
        Warehouse w = inv.getWarehouse();
        Product p = inv.getProduct();
        StockMovement m = StockMovement.builder()
                .warehouse(w)
                .warehouseCode(w != null ? w.getCode() : "")
                .product(p)
                .productSku(p != null ? p.getSku() : "")
                .productName(p != null ? p.getName() : null)
                .baseUnit(p != null ? p.getBaseUnit() : null)
                .movementType(movementType)
                .quantity(signedQuantity)
                .balanceAfter(inv.getPhysicalStock() != null ? inv.getPhysicalStock() : BigDecimal.ZERO)
                .docType(docType)
                .docId(docId)
                .docCode(cut(docCode, 50))
                .note(cut(note, 500))
                .actorId(actor != null ? actor.getId() : null)
                .actorUsername(actor != null ? cut(actor.getUsername(), 50) : null)
                .movementAt(LocalDateTime.now(clock))
                .build();
        return movementRepository.save(m);
    }

    /** Gắn id chứng từ cho các dòng thẻ kho ghi trước khi chứng từ được lưu (cùng transaction). */
    public void linkDocument(List<StockMovement> movements, Long docId) {
        if (movements == null || docId == null) {
            return;
        }
        for (StockMovement m : movements) {
            if (m != null && m.getDocId() == null) {
                m.setDocId(docId);
            }
        }
    }

    // ======================= XEM THẺ KHO =======================

    /**
     * Vd: ?warehouseId=1&productId=10&fromDate=2026-10-01&toDate=2026-10-31&type=RECEIPT&page=0&size=50
     * Không truyền ngày: mặc định 30 ngày gần nhất.
     */
    @Transactional(readOnly = true)
    public StockCardResponse stockCard(Long warehouseId, Long productId, LocalDate fromDate, LocalDate toDate,
                                       String type, int page, int size, UserDetailsImpl actor) {
        LocalDate to = toDate != null ? toDate : LocalDate.now(clock);
        LocalDate from = fromDate != null ? fromDate : to.minusDays(DEFAULT_RANGE_DAYS - 1L);
        if (from.isAfter(to)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE",
                    "Ngày bắt đầu không được sau ngày kết thúc", "fromDate");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "DATE_RANGE_TOO_LONG",
                    "Khoảng thời gian xem thẻ kho tối đa 1 năm", "toDate");
        }
        String cleanType = StringUtils.hasText(type) ? type.trim().toUpperCase(Locale.ROOT) : null;
        if (cleanType != null && !StockMovement.TYPE_LABELS.containsKey(cleanType)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_MOVEMENT_TYPE",
                    "Loại nghiệp vụ không hợp lệ: " + cleanType, "type");
        }
        if (warehouseId != null && !warehouseRepository.existsById(warehouseId)) {
            throw BusinessException.notFound("Không tìm thấy kho");
        }
        if (productId != null && !productRepository.existsById(productId)) {
            throw BusinessException.notFound("Không tìm thấy sản phẩm");
        }

        // NV kho chỉ xem kho mình được gắn (null = xem mọi kho)
        Set<Long> allowed = stockLedgerService.allowedWarehouseIds(actor);
        if (warehouseId != null && allowed != null && !allowed.contains(warehouseId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "STOCK_CARD_WAREHOUSE_NOT_ASSIGNED",
                    "Bạn không được phân công kho này nên không xem được thẻ kho", "warehouseId");
        }

        int safePage = Math.max(page, 0);
        int safeSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        LocalDateTime start = from.atStartOfDay();
        LocalDateTime end = to.plusDays(1).atStartOfDay();

        PageResponse<StockCardRow> rows;
        if (allowed != null && allowed.isEmpty()) {
            rows = PageResponse.of(Page.<StockCardRow>empty(PageRequest.of(safePage, safeSize)));
        } else {
            Specification<StockMovement> spec = filter(warehouseId, allowed, productId, cleanType, start, end);
            rows = PageResponse.of(movementRepository.findAll(spec, PageRequest.of(safePage, safeSize,
                            Sort.by(Sort.Direction.ASC, "movementAt").and(Sort.by(Sort.Direction.ASC, "id"))))
                    .map(StockMovementService::toRow));
        }

        BigDecimal opening = null;
        BigDecimal totalIn = null;
        BigDecimal totalOut = null;
        BigDecimal closing = null;
        BigDecimal current = null;
        // Số dư đầu / cuối kỳ chỉ có nghĩa khi xem 1 SKU tại 1 kho (không lọc theo loại nghiệp vụ)
        if (warehouseId != null && productId != null) {
            List<StockMovement> before = movementRepository.findLatestBefore(warehouseId, productId, start,
                    PageRequest.of(0, 1));
            opening = before.isEmpty() ? null : strip(before.get(0).getBalanceAfter());
            totalIn = strip(nz(movementRepository.sumIn(warehouseId, productId, start, end)));
            totalOut = strip(nz(movementRepository.sumOut(warehouseId, productId, start, end)).negate());
            List<StockMovement> last = movementRepository.findLatestInRange(warehouseId, productId, start, end,
                    PageRequest.of(0, 1));
            closing = !last.isEmpty() ? strip(last.get(0).getBalanceAfter()) : opening;
            current = inventoryRepository.findByWarehouse_IdAndProduct_Id(warehouseId, productId)
                    .map(i -> strip(nz(i.getPhysicalStock())))
                    .orElse(BigDecimal.ZERO);
        }
        return new StockCardResponse(warehouseId, productId, from, to, opening, totalIn, totalOut, closing, current, rows);
    }

    static Specification<StockMovement> filter(Long warehouseId, Set<Long> allowedWarehouseIds, Long productId,
                                               String type, LocalDateTime start, LocalDateTime end) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (warehouseId != null) {
                ps.add(cb.equal(root.get("warehouse").get("id"), warehouseId));
            }
            if (allowedWarehouseIds != null) {
                ps.add(root.get("warehouse").get("id").in(allowedWarehouseIds));
            }
            if (productId != null) {
                ps.add(cb.equal(root.get("product").get("id"), productId));
            }
            if (type != null) {
                ps.add(cb.equal(root.get("movementType"), type));
            }
            ps.add(cb.greaterThanOrEqualTo(root.get("movementAt"), start));
            ps.add(cb.lessThan(root.get("movementAt"), end));
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }

    static StockCardRow toRow(StockMovement m) {
        BigDecimal q = nz(m.getQuantity());
        return new StockCardRow(m.getId(), m.getMovementAt(), m.getMovementType(),
                StockMovement.TYPE_LABELS.getOrDefault(m.getMovementType(), m.getMovementType()),
                m.getWarehouse() != null ? m.getWarehouse().getId() : null, m.getWarehouseCode(),
                m.getProduct() != null ? m.getProduct().getId() : null, m.getProductSku(), m.getProductName(),
                m.getBaseUnit(),
                q.signum() > 0 ? strip(q) : BigDecimal.ZERO,
                q.signum() < 0 ? strip(q.negate()) : BigDecimal.ZERO,
                strip(nz(m.getBalanceAfter())),
                m.getDocType(), m.getDocId(), m.getDocCode(), m.getNote(), m.getActorUsername());
    }

    private static String cut(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal strip(BigDecimal v) {
        if (v == null) {
            return null;
        }
        BigDecimal s = v.stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0) : s;
    }
}
