package com.erp.backend.service;

import com.erp.backend.config.AuditLogInterceptor;
import com.erp.backend.dto.stocktake.StockTakeCountRequest;
import com.erp.backend.dto.stocktake.StockTakeCreateRequest;
import com.erp.backend.dto.stocktake.StockTakeResponse;
import com.erp.backend.dto.user.PageResponse;
import com.erp.backend.entity.*;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.InventoryRepository;
import com.erp.backend.repository.ProductCategoryRepository;
import com.erp.backend.repository.StockTakeRepository;
import com.erp.backend.repository.WarehouseRepository;
import com.erp.backend.security.UserDetailsImpl;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * S5-08: Kiểm kê kho và chênh lệch.
 * - Tạo phiếu theo kho hoặc theo nhóm hàng (gồm nhóm con): chụp số tồn sổ tại thời điểm tạo.
 * - Nhập số đếm thực tế, hệ thống tự tính chênh lệch từng dòng.
 * - Chốt kiểm kê (chỉ QL kho, bắt buộc lý do): điều chỉnh tồn về số thực đếm trong MỘT transaction, khoá dòng tồn,
 *   tồn không được âm; ghi nhật ký tồn kho trước / sau cho từng dòng có chênh lệch. Phiếu đã chốt không sửa được.
 *   Điều chỉnh theo chênh lệch (tồn mới = tồn hiện tại + (đếm - sổ lúc chụp)) để không mất các nhập / xuất xảy ra
 *   sau lúc tạo phiếu.
 */
@Service
@RequiredArgsConstructor
public class StockTakeService {

    static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    static final BigDecimal MAX_QUANTITY = new BigDecimal("100000000000");
    static final int MAX_REASON = 500;
    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;
    static final Map<String, String> STATUS_LABELS = Map.of(
            StockTake.STATUS_DRAFT, "Đang kiểm kê",
            StockTake.STATUS_CLOSED, "Đã chốt",
            StockTake.STATUS_CANCELLED, "Đã huỷ");
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StockTakeRepository stockTakeRepository;
    private final InventoryRepository inventoryRepository;
    private final WarehouseRepository warehouseRepository;
    private final ProductCategoryRepository categoryRepository;
    private final StockLedgerService stockLedgerService;
    private final AuditLogService auditLogService;

    Clock clock = Clock.system(VN_ZONE);

    // ======================= TẠO PHIẾU =======================

    @Transactional
    public StockTakeResponse create(StockTakeCreateRequest req, UserDetailsImpl actor) {
        Warehouse warehouse = warehouseRepository.findById(req.getWarehouseId())
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy kho"));
        assertWarehouseAllowed(warehouse.getId(), actor);
        ProductCategory category = null;
        Set<Long> categoryIds = null;
        if (req.getCategoryId() != null) {
            category = categoryRepository.findById(req.getCategoryId())
                    .orElseThrow(() -> BusinessException.notFound("Không tìm thấy nhóm hàng"));
            categoryIds = stockLedgerService.categoryWithDescendants(category.getId());
        }
        boolean duplicate = category == null
                ? stockTakeRepository.existsByWarehouse_IdAndCategoryIsNullAndStatus(warehouse.getId(), StockTake.STATUS_DRAFT)
                : stockTakeRepository.existsByWarehouse_IdAndCategory_IdAndStatus(warehouse.getId(), category.getId(),
                StockTake.STATUS_DRAFT);
        if (duplicate) {
            throw BusinessException.conflict("STOCKTAKE_IN_PROGRESS",
                    "Kho " + warehouse.getName() + " đang có phiếu kiểm kê chưa chốt cho phạm vi này", "warehouseId");
        }

        StockTake st = StockTake.builder()
                .code(generateCode())
                .warehouse(warehouse)
                .category(category)
                .status(StockTake.STATUS_DRAFT)
                .note(StringUtils.hasText(req.getNote()) ? req.getNote().trim() : null)
                .createdById(actor != null ? actor.getId() : null)
                .createdByUsername(actor != null ? actor.getUsername() : null)
                .build();

        // Chụp số tồn sổ tại thời điểm tạo phiếu
        final Set<Long> scope = categoryIds;
        inventoryRepository.findByWarehouse_Id(warehouse.getId()).stream()
                .filter(i -> i.getProduct() != null && "ACTIVE".equalsIgnoreCase(i.getProduct().getStatus()))
                .filter(i -> scope == null || (i.getProduct().getProductCategory() != null
                        && scope.contains(i.getProduct().getProductCategory().getId())))
                .sorted(Comparator.comparing(i -> i.getProduct().getSku()))
                .forEach(i -> st.addLine(StockTakeLine.builder()
                        .product(i.getProduct())
                        .sku(i.getProduct().getSku())
                        .productName(i.getProduct().getName())
                        .baseUnit(i.getProduct().getBaseUnit())
                        .systemQuantity(i.getPhysicalStock() != null ? i.getPhysicalStock() : BigDecimal.ZERO)
                        .build()));
        if (st.getLines().isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "STOCKTAKE_EMPTY",
                    "Kho / nhóm hàng đã chọn không có mặt hàng nào để kiểm kê", "categoryId");
        }
        return toResponse(stockTakeRepository.save(st), true);
    }

    // ======================= NHẬP SỐ ĐẾM =======================

    @Transactional
    public StockTakeResponse updateCounts(Long id, StockTakeCountRequest req, UserDetailsImpl actor) {
        StockTake st = lockDraft(id, actor);
        Map<Long, StockTakeLine> byId = new HashMap<>();
        st.getLines().forEach(l -> byId.put(l.getId(), l));
        for (StockTakeCountRequest.Line in : req.getLines()) {
            StockTakeLine line = in.getLineId() != null ? byId.get(in.getLineId()) : null;
            if (line == null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_STOCKTAKE_LINE",
                        "Dòng kiểm kê " + in.getLineId() + " không thuộc phiếu " + st.getCode(), "lines");
            }
            BigDecimal counted = in.getCountedQuantity();
            if (counted != null && (counted.signum() < 0 || counted.compareTo(MAX_QUANTITY) > 0
                    || counted.stripTrailingZeros().scale() > 4)) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_COUNTED_QUANTITY",
                        "SKU " + line.getSku() + ": số đếm phải từ 0, tối đa 4 chữ số thập phân", "lines");
            }
            line.setCountedQuantity(counted);
            line.setDifference(counted != null ? counted.subtract(line.getSystemQuantity()) : null);
            line.setNote(StringUtils.hasText(in.getNote()) ? in.getNote().trim() : line.getNote());
        }
        return toResponse(stockTakeRepository.save(st), true);
    }

    // ======================= CHỐT / HUỶ =======================

    /** Chỉ QL kho (kiểm ở controller và ở đây). Mọi dòng phải đã đếm; điều chỉnh tồn trong cùng transaction. */
    @Transactional
    public StockTakeResponse close(Long id, String reason, UserDetailsImpl actor) {
        if (!hasRole(actor, "ROLE_WH_MANAGER")) {
            throw BusinessException.conflict("STOCKTAKE_CLOSE_NOT_ALLOWED", "Chỉ Quản lý kho được chốt phiếu kiểm kê", null);
        }
        String why = requireReason(reason);
        StockTake st = lockDraft(id, actor);
        long notCounted = st.getLines().stream().filter(l -> l.getCountedQuantity() == null).count();
        if (notCounted > 0) {
            throw BusinessException.conflict("STOCKTAKE_NOT_COUNTED",
                    "Còn " + notCounted + " dòng chưa nhập số đếm thực tế", "lines");
        }

        List<StockTakeLine> changed = st.getLines().stream()
                .filter(l -> l.getDifference() != null && l.getDifference().signum() != 0)
                .sorted(Comparator.comparing(l -> l.getProduct().getId()))
                .toList();
        // Bước 1: khoá mọi dòng tồn liên quan và kiểm tra trước (chưa ghi gì) -> lỗi thì hoàn lại toàn bộ,
        // không để nhật ký ghi nhận điều chỉnh "dở dang" (nhật ký ghi ở transaction riêng)
        List<Inventory> inventories = new ArrayList<>();
        List<BigDecimal> afters = new ArrayList<>();
        for (StockTakeLine l : changed) {
            Inventory inv = inventoryRepository.findByWarehouseIdAndProductIdForUpdate(
                            st.getWarehouse().getId(), l.getProduct().getId())
                    .orElseThrow(() -> BusinessException.conflict("INVENTORY_NOT_FOUND",
                            "Không tìm thấy dòng tồn của SKU " + l.getSku() + " tại kho", "lines"));
            BigDecimal before = inv.getPhysicalStock() != null ? inv.getPhysicalStock() : BigDecimal.ZERO;
            BigDecimal after = before.add(l.getDifference());
            if (after.signum() < 0) {
                throw BusinessException.conflict("NEGATIVE_STOCK",
                        "SKU " + l.getSku() + ": điều chỉnh làm tồn thực tế âm (" + after.stripTrailingZeros().toPlainString()
                                + "), kiểm tra lại số đếm", "lines");
            }
            inventories.add(inv);
            afters.add(after);
        }
        // Bước 2: điều chỉnh tồn + ghi nhật ký trước / sau từng dòng
        for (int i = 0; i < changed.size(); i++) {
            StockTakeLine l = changed.get(i);
            Inventory inv = inventories.get(i);
            BigDecimal before = inv.getPhysicalStock() != null ? inv.getPhysicalStock() : BigDecimal.ZERO;
            inv.setPhysicalStock(afters.get(i));
            inventoryRepository.save(inv);
            auditLogService.record(AuditModule.INVENTORY, "STOCKTAKE_ADJUST", "INVENTORY", inv.getId(),
                    st.getWarehouse().getCode() + ":" + l.getSku(), plain(before), plain(afters.get(i)),
                    "Kiểm kê " + st.getCode() + " (chênh lệch " + plain(l.getDifference()) + "): " + why, actor);
        }

        st.setStatus(StockTake.STATUS_CLOSED);
        st.setClosedAt(LocalDateTime.now(clock));
        st.setClosedByUsername(actor != null ? actor.getUsername() : null);
        st.setCloseReason(why);
        StockTake saved = stockTakeRepository.save(st);
        auditLogService.record(AuditModule.INVENTORY, "CLOSE_STOCKTAKE", "STOCK_TAKE", saved.getId(), saved.getCode(),
                StockTake.STATUS_DRAFT, StockTake.STATUS_CLOSED,
                changed.size() + " dòng chênh lệch được điều chỉnh. Lý do: " + why, actor);
        markAuditLogged();
        return toResponse(saved, true);
    }

    @Transactional
    public StockTakeResponse cancel(Long id, String reason, UserDetailsImpl actor) {
        String why = requireReason(reason);
        StockTake st = lockDraft(id, actor);
        st.setStatus(StockTake.STATUS_CANCELLED);
        st.setClosedAt(LocalDateTime.now(clock));
        st.setClosedByUsername(actor != null ? actor.getUsername() : null);
        st.setCloseReason(why);
        StockTake saved = stockTakeRepository.save(st);
        auditLogService.record(AuditModule.INVENTORY, "CANCEL_STOCKTAKE", "STOCK_TAKE", saved.getId(), saved.getCode(),
                StockTake.STATUS_DRAFT, StockTake.STATUS_CANCELLED, why, actor);
        markAuditLogged();
        return toResponse(saved, true);
    }

    // ======================= XEM =======================

    @Transactional(readOnly = true)
    public StockTakeResponse get(Long id, UserDetailsImpl actor) {
        StockTake st = stockTakeRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy phiếu kiểm kê"));
        assertWarehouseAllowed(st.getWarehouse().getId(), actor);
        return toResponse(st, true);
    }

    @Transactional(readOnly = true)
    public PageResponse<StockTakeResponse> search(Long warehouseId, String status, LocalDate fromDate, LocalDate toDate,
                                                  int page, int size, UserDetailsImpl actor) {
        int safePage = Math.max(page, 0);
        int safeSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        if ((long) safePage * safeSize > 1_000_000L) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "PAGE_TOO_LARGE", "Số trang quá lớn", "page");
        }
        String st = StringUtils.hasText(status) ? status.trim().toUpperCase() : null;
        if (st != null && !STATUS_LABELS.containsKey(st)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_STATUS",
                    "Trạng thái phiếu không hợp lệ (DRAFT, CLOSED, CANCELLED)", "status");
        }
        if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE",
                    "Ngày bắt đầu không được sau ngày kết thúc", "fromDate");
        }
        Set<Long> allowed = stockLedgerService.allowedWarehouseIds(actor);
        Specification<StockTake> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (allowed != null) {
                if (allowed.isEmpty()) {
                    return cb.disjunction();
                }
                ps.add(root.get("warehouse").get("id").in(allowed));
            }
            if (warehouseId != null) {
                ps.add(cb.equal(root.get("warehouse").get("id"), warehouseId));
            }
            if (st != null) {
                ps.add(cb.equal(root.get("status"), st));
            }
            if (fromDate != null) {
                ps.add(cb.greaterThanOrEqualTo(root.get("createdAt"), fromDate.atStartOfDay()));
            }
            if (toDate != null) {
                ps.add(cb.lessThan(root.get("createdAt"), toDate.plusDays(1).atStartOfDay()));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
        return PageResponse.of(stockTakeRepository.findAll(spec,
                        PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id").descending())))
                .map(s -> toResponse(s, false)));
    }

    // ======================= HÀM PHỤ =======================

    private StockTake lockDraft(Long id, UserDetailsImpl actor) {
        StockTake st = stockTakeRepository.findByIdForUpdate(id)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy phiếu kiểm kê"));
        assertWarehouseAllowed(st.getWarehouse().getId(), actor);
        if (!StockTake.STATUS_DRAFT.equals(st.getStatus())) {
            throw BusinessException.conflict("STOCKTAKE_LOCKED",
                    "Phiếu " + st.getCode() + " đã " + STATUS_LABELS.get(st.getStatus()).toLowerCase()
                            + ", không sửa được", null);
        }
        return st;
    }

    /** NV kho chỉ thao tác trên kho mình được gắn (khác -> 404 như không tồn tại). */
    private void assertWarehouseAllowed(Long warehouseId, UserDetailsImpl actor) {
        Set<Long> allowed = stockLedgerService.allowedWarehouseIds(actor);
        if (allowed != null && !allowed.contains(warehouseId)) {
            throw BusinessException.notFound("Không tìm thấy kho hoặc bạn không được phân công kho này");
        }
    }

    private static String requireReason(String reason) {
        String r = reason != null ? reason.trim() : "";
        if (r.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "REASON_REQUIRED", "Vui lòng nhập lý do", "reason");
        }
        if (r.length() > MAX_REASON) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "REASON_TOO_LONG",
                    "Lý do tối đa " + MAX_REASON + " ký tự", "reason");
        }
        return r;
    }

    private String generateCode() {
        String prefix = "KK" + LocalDate.now(clock).format(DateTimeFormatter.ofPattern("yyMMdd")) + "-";
        for (int attempt = 0; attempt < 20; attempt++) {
            StringBuilder sb = new StringBuilder(prefix);
            for (int i = 0; i < 4; i++) {
                sb.append(CODE_CHARS.charAt(RANDOM.nextInt(CODE_CHARS.length())));
            }
            if (!stockTakeRepository.existsByCode(sb.toString())) {
                return sb.toString();
            }
        }
        throw new IllegalStateException("Không sinh được mã phiếu kiểm kê, vui lòng thử lại");
    }

    static StockTakeResponse toResponse(StockTake s, boolean withLines) {
        int counted = 0;
        int diffCount = 0;
        BigDecimal surplus = BigDecimal.ZERO;
        BigDecimal shortage = BigDecimal.ZERO;
        for (StockTakeLine l : s.getLines()) {
            if (l.getCountedQuantity() != null) {
                counted++;
            }
            if (l.getDifference() != null && l.getDifference().signum() != 0) {
                diffCount++;
                if (l.getDifference().signum() > 0) {
                    surplus = surplus.add(l.getDifference());
                } else {
                    shortage = shortage.add(l.getDifference());
                }
            }
        }
        List<StockTakeResponse.Line> lines = withLines ? s.getLines().stream()
                .map(l -> new StockTakeResponse.Line(l.getId(), l.getProduct() != null ? l.getProduct().getId() : null,
                        l.getSku(), l.getProductName(), l.getBaseUnit(), strip(l.getSystemQuantity()),
                        strip(l.getCountedQuantity()), strip(l.getDifference()), l.getNote()))
                .toList() : List.of();
        Warehouse w = s.getWarehouse();
        ProductCategory c = s.getCategory();
        return new StockTakeResponse(s.getId(), s.getCode(), s.getStatus(), STATUS_LABELS.get(s.getStatus()),
                w.getId(), w.getCode(), w.getName(), c != null ? c.getId() : null, c != null ? c.getName() : null,
                s.getNote(), s.getCreatedByUsername(), s.getCreatedAt(), s.getClosedByUsername(), s.getClosedAt(),
                s.getCloseReason(), s.getLines().size(), counted, diffCount, strip(surplus), strip(shortage), lines);
    }

    private static boolean hasRole(UserDetailsImpl actor, String role) {
        return actor != null && actor.getAuthorities() != null
                && actor.getAuthorities().stream().anyMatch(a -> role.equals(a.getAuthority()));
    }

    private static String plain(BigDecimal v) {
        return v == null ? null : strip(v).toPlainString();
    }

    private static BigDecimal strip(BigDecimal v) {
        if (v == null) {
            return null;
        }
        BigDecimal s = v.stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0) : s;
    }

    private static void markAuditLogged() {
        try {
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes != null && attributes.getRequest() != null) {
                attributes.getRequest().setAttribute(AuditLogInterceptor.AUDIT_LOGGED_ATTR, Boolean.TRUE);
            }
        } catch (Exception ignored) {
            // ngoài HTTP request (test) thì bỏ qua
        }
    }
}
