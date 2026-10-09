package com.erp.backend.service;

import com.erp.backend.dto.pricing.*;
import com.erp.backend.entity.*;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.PriceHistoryRepository;
import com.erp.backend.repository.PriceListItemRepository;
import com.erp.backend.repository.PriceListRepository;
import com.erp.backend.repository.PriceListSpecifications;
import com.erp.backend.dto.user.PageResponse;
import com.erp.backend.repository.CustomerRepository;
import com.erp.backend.repository.ProductRepository;
import com.erp.backend.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Pattern;

/**
 * S2-10: Quản lý bảng giá theo nhóm khách hàng và thời gian hiệu lực.
 * - Nhiều bảng giá song song (đại lý cấp 1, cấp 2, khách lẻ), mỗi bảng có ngày bắt đầu / kết thúc.
 * - Mỗi dòng giá có giá bán và giá sàn (giá sàn không lớn hơn giá bán).
 * - Bảng giá đã phát sinh đơn thì không sửa, chỉ tạo phiên bản mới.
 * - Tra giá: nhiều bảng cùng hiệu lực thì lấy bảng có ngày bắt đầu mới nhất, rồi phiên bản cao nhất.
 * Mọi thay đổi giá đều ghi nhật ký hệ thống (phân hệ PRICING) và lịch sử giá (S3-02).
 */
@Service
@RequiredArgsConstructor
public class PriceListService {

    static final String ACTIVE = "ACTIVE";
    static final String INACTIVE = "INACTIVE";
    static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{1,39}$");
    private static final BigDecimal MAX_PRICE = new BigDecimal("9999999999999.99");

    private final PriceListRepository priceListRepository;
    private final PriceListItemRepository itemRepository;
    private final ProductRepository productRepository;
    private final AuditLogService auditLogService;
    private final PriceHistoryService priceHistoryService;
    private final PriceHistoryRepository priceHistoryRepository;
    private final CustomerRepository customerRepository;

    // ======================= XEM / TRA CỨU =======================

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;

    /** Lọc và phân trang phía server. Trả kèm dòng giá: màn hình sửa của Frontend lấy dữ liệu từ danh sách. */
    @Transactional(readOnly = true)
    public PageResponse<PriceListResponse> search(String customerGroup, String status, String keyword, int page, int size) {
        CustomerGroup group = StringUtils.hasText(customerGroup) ? parseGroup(customerGroup) : null;
        int safePage = Math.max(page, 0);
        int safeSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        Sort sort = Sort.by("startDate").descending().and(Sort.by("version").descending()).and(Sort.by("id").descending());
        return PageResponse.of(priceListRepository.findAll(PriceListSpecifications.filter(group, status, keyword),
                PageRequest.of(safePage, safeSize, sort)).map(p -> toResponse(p, true)));
    }

    /** Số liệu cho các thẻ thống kê (toàn bộ dữ liệu, không phụ thuộc trang đang xem). */
    @Transactional(readOnly = true)
    public PriceListStatsResponse stats() {
        return new PriceListStatsResponse(
                priceListRepository.count(),
                priceListRepository.countByStatus("ACTIVE"),
                priceListRepository.countByCustomerGroup(CustomerGroup.DEALER_LEVEL_1),
                priceListRepository.countByCustomerGroup(CustomerGroup.DEALER_LEVEL_2),
                priceListRepository.countByCustomerGroup(CustomerGroup.RETAIL),
                priceListRepository.countByHasOrdersTrue());
    }

    @Transactional(readOnly = true)
    public PriceListResponse getById(Long id) {
        return toResponse(findPriceList(id), true);
    }

    /** Giá đang áp dụng cho một sản phẩm theo nhóm khách hàng tại một ngày (mặc định hôm nay). */
    @Transactional(readOnly = true)
    public PriceLookupResponse lookup(String customerGroup, String productSku, LocalDate date) {
        CustomerGroup group = parseGroup(customerGroup);
        if (!StringUtils.hasText(productSku)) {
            throw BusinessException.badRequest("SKU_REQUIRED", "Mã SKU cần tra giá không được để trống");
        }
        LocalDate day = date != null ? date : LocalDate.now(VN_ZONE);
        String sku = productSku.trim().toUpperCase();

        List<PriceListItem> found = itemRepository.findEffective(group, sku, day, PageRequest.of(0, 1));
        if (found.isEmpty()) {
            throw BusinessException.notFound("Không tìm thấy giá đang hiệu lực của SKU '" + sku + "' cho nhóm "
                    + group.getLabel() + " vào ngày " + day);
        }
        PriceListItem item = found.get(0);
        PriceList p = item.getPriceList();
        return new PriceLookupResponse(p.getId(), p.getCode(), p.getName(), p.getCustomerGroup().name(),
                p.getCustomerGroup().getLabel(), day, item.getProduct().getId(), item.getProductSku(),
                item.getProductName(), item.getPrice(), item.getFloorPrice());
    }

    // ======================= TẠO / SỬA =======================

    @Transactional
    public PriceListResponse create(PriceListRequest req, UserDetailsImpl actor) {
        String code = normalizeCode(req.getCode());
        if (priceListRepository.existsByCodeIgnoreCase(code)) {
            throw BusinessException.conflict("PRICE_LIST_CODE_EXISTS", "Mã bảng giá '" + code + "' đã tồn tại", "code");
        }
        validateName(req.getName());
        validateDates(req.getStartDate(), req.getEndDate());

        PriceList priceList = PriceList.builder()
                .code(code)
                .name(req.getName().trim())
                .customerGroup(parseGroup(req.getCustomerGroup()))
                .startDate(req.getStartDate())
                .endDate(req.getEndDate())
                .note(blankToNull(req.getNote()))
                .status(ACTIVE)
                .version(1)
                .build();
        List<PriceChange> changes = replaceItems(priceList, req.getItems() != null ? req.getItems() : List.of());

        PriceList saved = priceListRepository.save(priceList);
        priceHistoryService.record(saved, changes, actor);
        audit("CREATE_PRICE_LIST", saved, null, summary(saved), "Tạo bảng giá " + saved.getCode(), actor);
        return toResponse(saved, true);
    }

    /** Sửa bảng giá chưa phát sinh đơn. items gửi lên thay cho toàn bộ danh sách dòng giá (null = giữ nguyên). */
    @Transactional
    public PriceListResponse update(Long id, PriceListRequest req, UserDetailsImpl actor) {
        PriceList priceList = findForUpdate(id);
        checkEditable(priceList);
        String before = summary(priceList);

        String code = normalizeCode(req.getCode());
        if (priceListRepository.existsByCodeIgnoreCaseAndIdNot(code, id)) {
            throw BusinessException.conflict("PRICE_LIST_CODE_EXISTS", "Mã bảng giá '" + code + "' đã tồn tại", "code");
        }
        validateName(req.getName());
        validateDates(req.getStartDate(), req.getEndDate());

        priceList.setCode(code);
        priceList.setName(req.getName().trim());
        priceList.setCustomerGroup(parseGroup(req.getCustomerGroup()));
        priceList.setStartDate(req.getStartDate());
        priceList.setEndDate(req.getEndDate());
        priceList.setNote(blankToNull(req.getNote()));
        List<PriceChange> changes = req.getItems() != null ? replaceItems(priceList, req.getItems()) : List.of();

        PriceList saved = priceListRepository.save(priceList);
        priceHistoryService.record(saved, changes, actor);
        audit("UPDATE_PRICE_LIST", saved, before, summary(saved), "Sửa bảng giá " + saved.getCode(), actor);
        return toResponse(saved, true);
    }

    /**
     * Tạo phiên bản mới từ một bảng giá (thường do bảng cũ đã phát sinh đơn).
     * Trường nào không gửi thì kế thừa từ bảng gốc; không gửi items thì sao chép toàn bộ dòng giá.
     */
    @Transactional
    public PriceListResponse cloneVersion(Long id, PriceListRequest req, UserDetailsImpl actor) {
        PriceList source = findPriceList(id);
        PriceListRequest r = req != null ? req : new PriceListRequest();
        int nextVersion = (source.getVersion() != null ? source.getVersion() : 1) + 1;

        String code = StringUtils.hasText(r.getCode()) ? normalizeCode(r.getCode()) : defaultVersionCode(source.getCode(), nextVersion);
        if (priceListRepository.existsByCodeIgnoreCase(code)) {
            throw BusinessException.conflict("PRICE_LIST_CODE_EXISTS", "Mã bảng giá '" + code + "' đã tồn tại", "code");
        }
        LocalDate start = r.getStartDate() != null ? r.getStartDate() : LocalDate.now(VN_ZONE);
        LocalDate end = r.getEndDate() != null ? r.getEndDate()
                : (source.getEndDate() != null && !source.getEndDate().isBefore(start) ? source.getEndDate() : null);
        validateDates(start, end);

        PriceList copy = PriceList.builder()
                .code(code)
                .name(StringUtils.hasText(r.getName()) ? r.getName().trim() : source.getName() + " (v" + nextVersion + ")")
                .customerGroup(StringUtils.hasText(r.getCustomerGroup()) ? parseGroup(r.getCustomerGroup()) : source.getCustomerGroup())
                .startDate(start)
                .endDate(end)
                .note(StringUtils.hasText(r.getNote()) ? r.getNote().trim()
                        : "Phiên bản mới từ " + source.getCode() + " (v" + source.getVersion() + ")")
                .status(ACTIVE)
                .version(nextVersion)
                .sourcePriceList(source)
                .build();
        validateName(copy.getName());

        List<PriceChange> changes = new ArrayList<>();
        if (r.getItems() != null && !r.getItems().isEmpty()) {
            changes.addAll(replaceItems(copy, r.getItems()));
        } else {
            for (PriceListItem it : source.getItems()) {
                copy.addItem(PriceListItem.builder()
                        .product(it.getProduct())
                        .productSku(it.getProductSku())
                        .productName(it.getProductName())
                        .price(it.getPrice())
                        .floorPrice(it.getFloorPrice())
                        .build());
                changes.add(new PriceChange(it.getProduct(), PriceHistory.CREATE, null, it.getPrice(), null, it.getFloorPrice()));
            }
        }

        PriceList saved = priceListRepository.save(copy);
        priceHistoryService.record(saved, changes, actor);
        audit("CLONE_PRICE_LIST", saved, source.getCode() + " (v" + source.getVersion() + ")", summary(saved),
                "Tạo phiên bản " + saved.getVersion() + " từ " + source.getCode(), actor);
        return toResponse(saved, true);
    }

    /** Bật / tắt bảng giá. Bảng đã phát sinh đơn vẫn được tắt để ngừng áp dụng. */
    @Transactional
    public PriceListResponse changeStatus(Long id, String status, UserDetailsImpl actor) {
        String st = StringUtils.hasText(status) ? status.trim().toUpperCase() : "";
        if (!ACTIVE.equals(st) && !INACTIVE.equals(st)) {
            throw BusinessException.badRequest("INVALID_STATUS", "Trạng thái chỉ nhận ACTIVE hoặc INACTIVE");
        }
        PriceList priceList = findForUpdate(id);
        if (st.equals(priceList.getStatus())) {
            return toResponse(priceList, true);
        }
        String old = priceList.getStatus();
        priceList.setStatus(st);
        PriceList saved = priceListRepository.save(priceList);
        audit("CHANGE_PRICE_LIST_STATUS", saved, old, st, "Đổi trạng thái bảng giá " + saved.getCode(), actor);
        return toResponse(saved, true);
    }

    /** Xoá bảng giá khi chưa phát sinh đơn hàng. */
    @Transactional
    public void delete(Long id, UserDetailsImpl actor) {
        PriceList priceList = findForUpdate(id);
        if (priceList.isHasOrders()) {
            throw BusinessException.conflict("PRICE_LIST_HAS_ORDERS",
                    "Không thể xoá bảng giá đã phát sinh đơn hàng", null);
        }

        // Chặn xoá nếu đây là bảng giá hiệu lực duy nhất của nhóm và nhóm đang có đại lý
        if (ACTIVE.equals(priceList.getStatus())) {
            LocalDate today = LocalDate.now(VN_ZONE);
            List<PriceList> effectiveLists = priceListRepository.findEffectiveByCustomerGroup(priceList.getCustomerGroup(), today);
            boolean isOnlyEffective = effectiveLists.stream().allMatch(p -> p.getId().equals(priceList.getId()));
            if (isOnlyEffective) {
                long customerCount = customerRepository.countByCustomerGroup(priceList.getCustomerGroup());
                if (customerCount > 0) {
                    throw BusinessException.conflict("LAST_ACTIVE_PRICE_LIST",
                            "Không thể xoá bảng giá hiệu lực duy nhất của nhóm '" + priceList.getCustomerGroup().getLabel()
                                    + "' vì đang có " + customerCount + " đại lý thuộc nhóm này. Vui lòng tạo bảng giá mới thay thế trước khi xoá.",
                            null);
                }
            }
        }

        String before = summary(priceList);
        priceHistoryRepository.deleteByPriceList(priceList);
        priceListRepository.clearSourcePriceList(priceList.getId());
        priceListRepository.delete(priceList);
        audit("DELETE_PRICE_LIST", priceList, before, null, "Xoá bảng giá " + priceList.getCode(), actor);
    }

    /** Thêm dòng giá mới, hoặc sửa giá nếu sản phẩm đã có trong bảng. */
    @Transactional
    public PriceListResponse upsertItem(Long id, PriceListItemRequest req, UserDetailsImpl actor) {
        PriceList priceList = findForUpdate(id);
        checkEditable(priceList);
        Product product = findProduct(req.getProductSku());
        validatePrices(product.getSku(), req.getPrice(), req.getFloorPrice());

        PriceListItem item = priceList.getItems().stream()
                .filter(i -> i.getProduct().getId().equals(product.getId()))
                .findFirst()
                .orElse(null);
        String before = item == null ? null : priceText(item.getPrice(), item.getFloorPrice());
        PriceChange change;
        if (item == null) {
            item = newItem(product, req);
            priceList.addItem(item);
            change = new PriceChange(product, PriceHistory.CREATE, null, req.getPrice(), null, req.getFloorPrice());
        } else {
            change = samePrices(item, req) ? null : new PriceChange(product, PriceHistory.UPDATE,
                    item.getPrice(), req.getPrice(), item.getFloorPrice(), req.getFloorPrice());
            item.setPrice(req.getPrice());
            item.setFloorPrice(req.getFloorPrice());
        }

        PriceList saved = priceListRepository.save(priceList);
        priceHistoryService.record(saved, change == null ? List.of() : List.of(change), actor);
        auditLogService.record(
                AuditModule.PRICING,
                before == null ? "ADD_PRICE_ITEM" : "UPDATE_PRICE_ITEM",
                "PRODUCT_PRICE",
                product.getId(),
                product.getSku() + ":" + product.getName(),
                before,
                priceText(req.getPrice(), req.getFloorPrice()),
                String.format("Cập nhật giá bán SKU %s (%s) trong bảng giá %s: %s",
                        product.getSku(), product.getName(), saved.getCode(), priceText(req.getPrice(), req.getFloorPrice())),
                actor
        );
        return toResponse(saved, true);
    }

    @Transactional
    public PriceListResponse deleteItem(Long id, Long itemId, UserDetailsImpl actor) {
        PriceList priceList = findForUpdate(id);
        checkEditable(priceList);
        PriceListItem item = priceList.getItems().stream()
                .filter(i -> i.getId() != null && i.getId().equals(itemId))
                .findFirst()
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy dòng giá " + itemId + " trong bảng giá này"));
        priceList.getItems().remove(item);

        PriceList saved = priceListRepository.save(priceList);
        priceHistoryService.record(saved, List.of(new PriceChange(item.getProduct(), PriceHistory.DELETE,
                item.getPrice(), null, item.getFloorPrice(), null)), actor);
        auditLogService.record(
                AuditModule.PRICING,
                "DELETE_PRICE_ITEM",
                "PRODUCT_PRICE",
                item.getProduct().getId(),
                item.getProductSku() + ":" + item.getProduct().getName(),
                item.getProductSku() + ": " + priceText(item.getPrice(), item.getFloorPrice()),
                null,
                "Xoá dòng giá SKU " + item.getProductSku() + " (" + item.getProduct().getName() + ") khỏi bảng giá " + saved.getCode(),
                actor
        );
        return toResponse(saved, true);
    }

    // ======================= HÀM PHỤ =======================

    private PriceList findPriceList(Long id) {
        return priceListRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy bảng giá ID " + id));
    }

    private PriceList findForUpdate(Long id) {
        return priceListRepository.findByIdForUpdate(id)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy bảng giá ID " + id));
    }

    private void checkEditable(PriceList priceList) {
        if (priceList.isHasOrders()) {
            throw BusinessException.conflict("PRICE_LIST_LOCKED",
                    "Bảng giá đã phát sinh đơn hàng nên không thể chỉnh sửa. Vui lòng tạo phiên bản mới.", null);
        }
    }

    /**
     * Thay toàn bộ dòng giá: sản phẩm đã có thì sửa giá, sản phẩm mới thì thêm, sản phẩm bị bỏ thì xoá.
     * Trả về các thay đổi giá để ghi lịch sử (dòng không đổi giá thì không ghi).
     */
    private List<PriceChange> replaceItems(PriceList priceList, List<PriceListItemRequest> requests) {
        Map<Long, PriceListItemRequest> wanted = new LinkedHashMap<>();
        Map<Long, Product> products = new HashMap<>();
        for (PriceListItemRequest r : requests) {
            Product product = findProduct(r.getProductSku());
            if (wanted.containsKey(product.getId())) {
                throw BusinessException.badRequest("DUPLICATE_PRICE_ITEM",
                        "Sản phẩm '" + product.getSku() + "' đã tồn tại ở một dòng khác trong bảng giá");
            }
            validatePrices(product.getSku(), r.getPrice(), r.getFloorPrice());
            wanted.put(product.getId(), r);
            products.put(product.getId(), product);
        }

        List<PriceChange> changes = new ArrayList<>();
        for (PriceListItem removed : priceList.getItems().stream()
                .filter(i -> !wanted.containsKey(i.getProduct().getId())).toList()) {
            changes.add(new PriceChange(removed.getProduct(), PriceHistory.DELETE,
                    removed.getPrice(), null, removed.getFloorPrice(), null));
        }
        priceList.getItems().removeIf(i -> !wanted.containsKey(i.getProduct().getId()));

        for (Map.Entry<Long, PriceListItemRequest> e : wanted.entrySet()) {
            PriceListItemRequest r = e.getValue();
            Optional<PriceListItem> existing = priceList.getItems().stream()
                    .filter(i -> i.getProduct().getId().equals(e.getKey()))
                    .findFirst();
            if (existing.isPresent()) {
                PriceListItem item = existing.get();
                if (!samePrices(item, r)) {
                    changes.add(new PriceChange(item.getProduct(), PriceHistory.UPDATE,
                            item.getPrice(), r.getPrice(), item.getFloorPrice(), r.getFloorPrice()));
                }
                item.setPrice(r.getPrice());
                item.setFloorPrice(r.getFloorPrice());
            } else {
                Product product = products.get(e.getKey());
                priceList.addItem(newItem(product, r));
                changes.add(new PriceChange(product, PriceHistory.CREATE, null, r.getPrice(), null, r.getFloorPrice()));
            }
        }
        return changes;
    }

    private static boolean samePrices(PriceListItem item, PriceListItemRequest r) {
        return item.getPrice().compareTo(r.getPrice()) == 0 && item.getFloorPrice().compareTo(r.getFloorPrice()) == 0;
    }

    private PriceListItem newItem(Product product, PriceListItemRequest r) {
        return PriceListItem.builder()
                .product(product)
                .productSku(product.getSku())
                .productName(product.getName())
                .price(r.getPrice())
                .floorPrice(r.getFloorPrice())
                .build();
    }

    /** Tìm sản phẩm theo SKU (SKU trong hệ thống luôn viết hoa). */
    private Product findProduct(String sku) {
        if (!StringUtils.hasText(sku)) {
            throw BusinessException.badRequest("SKU_REQUIRED", "Mã SKU của dòng giá không được để trống");
        }
        String normalized = sku.trim().toUpperCase();
        return productRepository.findBySku(normalized)
                .or(() -> productRepository.findFirstBySkuIgnoreCaseOrderByIdAsc(normalized))
                .orElseThrow(() -> BusinessException.badRequest("PRODUCT_NOT_FOUND",
                        "Sản phẩm SKU '" + normalized + "' không tồn tại trong danh mục sản phẩm"));
    }

    private void validatePrices(String sku, BigDecimal price, BigDecimal floorPrice) {
        if (price == null || floorPrice == null) {
            throw BusinessException.badRequest("PRICE_REQUIRED",
                    "Dòng '" + sku + "': giá bán và giá sàn không được để trống");
        }
        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            throw BusinessException.badRequest("INVALID_PRICE", "Dòng '" + sku + "': giá bán phải lớn hơn 0 và không thấp hơn giá sàn");
        }
        if (floorPrice.compareTo(BigDecimal.ZERO) < 0) {
            throw BusinessException.badRequest("INVALID_FLOOR_PRICE", "Dòng '" + sku + "': giá sàn không được âm");
        }
        if (price.compareTo(MAX_PRICE) > 0 || price.scale() > 2 || floorPrice.scale() > 2) {
            throw BusinessException.badRequest("INVALID_PRICE",
                    "Dòng '" + sku + "': giá bán / giá sàn tối đa 13 chữ số phần nguyên và 2 chữ số thập phân");
        }
        if (floorPrice.compareTo(price) > 0) {
            throw BusinessException.badRequest("FLOOR_PRICE_TOO_HIGH",
                    "Dòng '" + sku + "': giá sàn (" + floorPrice.toPlainString() + ") không được lớn hơn giá bán ("
                            + price.toPlainString() + ")");
        }
    }

    /** Mã mặc định của phiên bản mới: BG-T10 -> BG-T10-V2, BG-T10-V2 -> BG-T10-V3 (cắt bớt để không quá 40 ký tự). */
    static String defaultVersionCode(String sourceCode, int version) {
        String base = sourceCode.replaceFirst("-V\\d+$", "");
        String suffix = "-V" + version;
        if (base.length() + suffix.length() > 40) {
            base = base.substring(0, 40 - suffix.length());
        }
        return base + suffix;
    }

    private String normalizeCode(String code) {
        if (!StringUtils.hasText(code)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "CODE_REQUIRED", "Mã bảng giá không được để trống", "code");
        }
        String c = code.trim().toUpperCase();
        if (!CODE_PATTERN.matcher(c).matches()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_CODE",
                    "Mã bảng giá gồm 2–40 ký tự: chữ, số, dấu - hoặc _ (không dấu cách)", "code");
        }
        return c;
    }

    private void validateName(String name) {
        if (!StringUtils.hasText(name)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "NAME_REQUIRED", "Tên bảng giá không được để trống", "name");
        }
        if (name.trim().length() > 200) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "NAME_TOO_LONG", "Tên bảng giá tối đa 200 ký tự", "name");
        }
    }

    private void validateDates(LocalDate start, LocalDate end) {
        if (start == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "START_DATE_REQUIRED",
                    "Ngày bắt đầu hiệu lực không được để trống", "startDate");
        }
        if (end != null && end.isBefore(start)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE",
                    "Ngày kết thúc hiệu lực không được trước ngày bắt đầu", "endDate");
        }
    }

    private CustomerGroup parseGroup(String value) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "CUSTOMER_GROUP_REQUIRED",
                    "Nhóm khách hàng không được để trống", "customerGroup");
        }
        try {
            return CustomerGroup.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_CUSTOMER_GROUP",
                    "Nhóm khách hàng chỉ nhận DEALER_LEVEL_1, DEALER_LEVEL_2 hoặc RETAIL", "customerGroup");
        }
    }

    private void audit(String action, PriceList p, String oldValue, String newValue, String reason, UserDetailsImpl actor) {
        auditLogService.record(AuditModule.PRICING, action, "PRICE_LIST", p.getId(), p.getCode(),
                oldValue, newValue, reason, actor);
    }

    private static String summary(PriceList p) {
        return p.getName() + " | " + p.getCustomerGroup() + " | " + p.getStartDate() + " → "
                + (p.getEndDate() != null ? p.getEndDate() : "không thời hạn") + " | " + p.getItems().size() + " dòng giá";
    }

    private static String priceText(BigDecimal price, BigDecimal floor) {
        return "giá bán " + price.toPlainString() + ", giá sàn " + floor.toPlainString();
    }

    private static boolean contains(String value, String kw) {
        return value != null && value.toLowerCase().contains(kw);
    }

    private static String blankToNull(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }

    PriceListResponse toResponse(PriceList p, boolean withItems) {
        List<PriceListItemResponse> items = withItems
                ? p.getItems().stream()
                .map(i -> new PriceListItemResponse(i.getId(), i.getProduct().getId(), i.getProductSku(), i.getProductName(),
                        i.getPrice(), i.getFloorPrice(), i.getCreatedAt(), i.getUpdatedAt()))
                .toList()
                : null;
        return new PriceListResponse(p.getId(), p.getCode(), p.getName(), p.getCustomerGroup().name(),
                p.getCustomerGroup().getLabel(), p.getStartDate(), p.getEndDate(), p.getStatus(), p.isHasOrders(),
                p.getVersion(), p.getSourcePriceList() != null ? p.getSourcePriceList().getId() : null, p.getNote(),
                p.getItems().size(), items, p.getCreatedAt(), p.getUpdatedAt());
    }
}
