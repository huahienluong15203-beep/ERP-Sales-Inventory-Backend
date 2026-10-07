package com.erp.backend.service;

import com.erp.backend.dto.discount.*;
import com.erp.backend.dto.discount.DiscountCalculationResponse.Candidate;
import com.erp.backend.entity.*;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.DiscountPolicyRepository;
import com.erp.backend.repository.DiscountPolicySpecifications;
import com.erp.backend.dto.user.PageResponse;
import org.springframework.data.domain.PageRequest;
import com.erp.backend.repository.ProductCategoryRepository;
import com.erp.backend.repository.ProductRepository;
import com.erp.backend.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Pattern;

/**
 * S3-01: Chính sách chiết khấu theo sản lượng.
 *
 * QUY TẮC TÍNH (xem thêm docs/quy-tac-chiet-khau.md):
 * 1. Chính sách áp cho dòng hàng nếu: đang ACTIVE, ngày đặt nằm trong thời gian hiệu lực, và áp đúng SKU
 *    hoặc áp cho nhóm hàng chứa sản phẩm (kể cả nhóm cha, ông...).
 * 2. Trong mỗi chính sách, lấy bậc cao nhất có minQuantity <= số lượng mua (theo đơn vị cơ sở).
 * 3. Chiết khấu trên 1 đơn vị: PERCENT = đơn giá x % ; AMOUNT_PER_UNIT = số tiền đó (không vượt đơn giá).
 * 4. Nhiều chính sách cùng áp dụng: CHỌN MỘT chính sách cho tổng tiền chiết khấu lớn nhất (có lợi nhất cho khách),
 *    không cộng dồn. Bằng nhau thì ưu tiên chính sách theo SKU, rồi chính sách có id nhỏ hơn.
 * 5. Tiền làm tròn 2 chữ số thập phân, HALF_UP.
 */
@Service
@RequiredArgsConstructor
public class DiscountPolicyService {

    static final String ACTIVE = "ACTIVE";
    static final String INACTIVE = "INACTIVE";
    static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;
    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{1,39}$");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final DiscountPolicyRepository policyRepository;
    private final ProductRepository productRepository;
    private final ProductCategoryRepository categoryRepository;
    private final AuditLogService auditLogService;

    // ======================= XEM =======================

    @Transactional(readOnly = true)
    public PageResponse<DiscountPolicyResponse> search(String status, String scope, String keyword, int page, int size) {
        if (StringUtils.hasText(status) && !List.of("ACTIVE", "INACTIVE", "EXPIRED").contains(status.trim().toUpperCase())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_STATUS",
                    "Trạng thái lọc chỉ nhận ACTIVE, INACTIVE hoặc EXPIRED", "status");
        }
        if (StringUtils.hasText(scope) && !List.of(DiscountPolicy.SCOPE_PRODUCT, DiscountPolicy.SCOPE_CATEGORY).contains(scope.trim().toUpperCase())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_SCOPE",
                    "Phạm vi lọc chỉ nhận PRODUCT hoặc CATEGORY", "scope");
        }
        int safePage = Math.max(page, 0);
        int safeSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        return PageResponse.of(policyRepository.findAll(
                DiscountPolicySpecifications.filter(status, scope, keyword, LocalDate.now(VN_ZONE)),
                PageRequest.of(safePage, safeSize, Sort.by("startDate").descending().and(Sort.by("id").descending())))
                .map(this::toResponse));
    }

    /** Số liệu cho các thẻ thống kê (toàn bộ dữ liệu, không phụ thuộc trang đang xem). */
    @Transactional(readOnly = true)
    public DiscountPolicyStatsResponse stats() {
        return new DiscountPolicyStatsResponse(
                policyRepository.count(),
                policyRepository.countActive(LocalDate.now(VN_ZONE)),
                policyRepository.countByScope(DiscountPolicy.SCOPE_PRODUCT),
                policyRepository.countByScope(DiscountPolicy.SCOPE_CATEGORY));
    }

    @Transactional(readOnly = true)
    public DiscountPolicyResponse getById(Long id) {
        return toResponse(findPolicy(id));
    }

    // ======================= TẠO / SỬA =======================

    @Transactional
    public DiscountPolicyResponse create(DiscountPolicyRequest req, UserDetailsImpl actor) {
        String code = normalizeCode(req.getCode());
        if (policyRepository.existsByCodeIgnoreCase(code)) {
            throw BusinessException.conflict("DISCOUNT_CODE_EXISTS", "Mã chính sách '" + code + "' đã tồn tại", "code");
        }
        DiscountPolicy policy = DiscountPolicy.builder().code(code).status(ACTIVE).build();
        apply(policy, req);

        DiscountPolicy saved = policyRepository.save(policy);
        audit("CREATE_DISCOUNT_POLICY", saved, null, summary(saved), actor);
        return toResponse(saved);
    }

    @Transactional
    public DiscountPolicyResponse update(Long id, DiscountPolicyRequest req, UserDetailsImpl actor) {
        DiscountPolicy policy = findPolicy(id);
        String before = summary(policy);
        String code = normalizeCode(req.getCode());
        if (policyRepository.existsByCodeIgnoreCaseAndIdNot(code, id)) {
            throw BusinessException.conflict("DISCOUNT_CODE_EXISTS", "Mã chính sách '" + code + "' đã tồn tại", "code");
        }
        policy.setCode(code);
        apply(policy, req);

        DiscountPolicy saved = policyRepository.save(policy);
        audit("UPDATE_DISCOUNT_POLICY", saved, before, summary(saved), actor);
        return toResponse(saved);
    }

    /** Không xoá chính sách: chỉ ngừng áp dụng (INACTIVE) hoặc áp dụng lại (ACTIVE). */
    @Transactional
    public DiscountPolicyResponse changeStatus(Long id, String status, UserDetailsImpl actor) {
        String st = StringUtils.hasText(status) ? status.trim().toUpperCase() : "";
        if (!ACTIVE.equals(st) && !INACTIVE.equals(st)) {
            throw BusinessException.badRequest("INVALID_STATUS", "Trạng thái chỉ nhận ACTIVE hoặc INACTIVE");
        }
        DiscountPolicy policy = findPolicy(id);
        if (st.equals(policy.getStatus())) {
            return toResponse(policy);
        }
        String old = policy.getStatus();
        policy.setStatus(st);
        DiscountPolicy saved = policyRepository.save(policy);
        audit("CHANGE_DISCOUNT_STATUS", saved, old, st, actor);
        return toResponse(saved);
    }

    // ======================= TÍNH CHIẾT KHẤU =======================

    @Transactional(readOnly = true)
    public DiscountCalculationResponse calculate(DiscountCalculationRequest req) {
        Product product = findProduct(req.getProductSku());
        if (req.getQuantity() == null || req.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_QUANTITY", "Số lượng phải lớn hơn 0", "quantity");
        }
        if (req.getUnitPrice() == null || req.getUnitPrice().compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_UNIT_PRICE", "Đơn giá không được để trống hoặc âm", "unitPrice");
        }
        return calculate(product, req.getQuantity(), req.getUnitPrice(),
                req.getDate() != null ? req.getDate() : LocalDate.now(VN_ZONE));
    }

    /** Dùng lại khi khởi tạo đơn hàng (S3-09). */
    @Transactional(readOnly = true)
    public DiscountCalculationResponse calculate(Product product, BigDecimal quantity, BigDecimal unitPrice, LocalDate date) {
        List<DiscountPolicy> policies = policyRepository.findEffective(product.getId(), categoryAncestors(product), date);

        List<Candidate> candidates = new ArrayList<>();
        for (DiscountPolicy p : policies) {
            Optional<DiscountTier> tier = p.getTiers().stream()
                    .filter(t -> t.getMinQuantity().compareTo(quantity) <= 0)
                    .max(Comparator.comparing(DiscountTier::getMinQuantity));
            if (tier.isEmpty()) {
                continue;
            }
            BigDecimal perUnit = DiscountPolicy.TYPE_PERCENT.equals(p.getDiscountType())
                    ? unitPrice.multiply(tier.get().getDiscountValue()).divide(HUNDRED, 2, RoundingMode.HALF_UP)
                    : tier.get().getDiscountValue().min(unitPrice);
            BigDecimal amount = perUnit.multiply(quantity).setScale(2, RoundingMode.HALF_UP);
            candidates.add(new Candidate(p.getId(), p.getCode(), p.getName(), p.getScope(), p.getDiscountType(),
                    tier.get().getMinQuantity(), tier.get().getDiscountValue(), perUnit, amount));
        }

        candidates.sort(Comparator.comparing(Candidate::discountAmount).reversed()
                .thenComparing(c -> DiscountPolicy.SCOPE_PRODUCT.equals(c.scope()) ? 0 : 1)
                .thenComparing(Candidate::policyId));
        Candidate best = candidates.isEmpty() ? null : candidates.get(0);

        BigDecimal gross = unitPrice.multiply(quantity).setScale(2, RoundingMode.HALF_UP);
        BigDecimal discount = best == null ? BigDecimal.ZERO.setScale(2) : best.discountAmount();
        return new DiscountCalculationResponse(product.getId(), product.getSku(), quantity, unitPrice,
                gross, discount, gross.subtract(discount), best, candidates);
    }

    // ======================= HÀM PHỤ =======================

    private void apply(DiscountPolicy policy, DiscountPolicyRequest req) {
        if (!StringUtils.hasText(req.getName())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "NAME_REQUIRED", "Tên chính sách không được để trống", "name");
        }
        if (req.getName().trim().length() > 200) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "NAME_TOO_LONG", "Tên chính sách tối đa 200 ký tự", "name");
        }
        String scope = upper(req.getScope());
        if (DiscountPolicy.SCOPE_PRODUCT.equals(scope)) {
            policy.setProduct(findProduct(req.getProductSku()));
            policy.setCategory(null);
        } else if (DiscountPolicy.SCOPE_CATEGORY.equals(scope)) {
            if (req.getCategoryId() == null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "CATEGORY_REQUIRED",
                        "Chính sách theo nhóm hàng phải chọn nhóm hàng", "categoryId");
            }
            policy.setCategory(categoryRepository.findById(req.getCategoryId())
                    .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "CATEGORY_NOT_FOUND",
                            "Không tìm thấy nhóm hàng ID " + req.getCategoryId(), "categoryId")));
            policy.setProduct(null);
        } else {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_SCOPE",
                    "Phạm vi áp dụng chỉ nhận PRODUCT (theo SKU) hoặc CATEGORY (theo nhóm hàng)", "scope");
        }

        String type = upper(req.getDiscountType());
        if (!DiscountPolicy.TYPE_PERCENT.equals(type) && !DiscountPolicy.TYPE_AMOUNT.equals(type)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_DISCOUNT_TYPE",
                    "Kiểu chiết khấu chỉ nhận PERCENT (phần trăm) hoặc AMOUNT_PER_UNIT (số tiền trên đơn vị)", "discountType");
        }
        if (req.getStartDate() == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "START_DATE_REQUIRED",
                    "Ngày bắt đầu áp dụng không được để trống", "startDate");
        }
        if (req.getEndDate() != null && req.getEndDate().isBefore(req.getStartDate())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE",
                    "Ngày kết thúc không được trước ngày bắt đầu", "endDate");
        }

        List<DiscountTierRequest> tiers = validateTiers(req.getTiers(), type);
        policy.setName(req.getName().trim());
        policy.setScope(scope);
        policy.setDiscountType(type);
        policy.setStartDate(req.getStartDate());
        policy.setEndDate(req.getEndDate());
        policy.setNote(StringUtils.hasText(req.getNote()) ? req.getNote().trim() : null);
        policy.getTiers().clear();
        tiers.forEach(t -> policy.addTier(DiscountTier.builder()
                .minQuantity(t.getMinQuantity())
                .discountValue(t.getDiscountValue())
                .build()));
    }

    /**
     * Bậc phải có ít nhất 1; số lượng tối thiểu > 0 và không trùng; mức chiết khấu > 0 (phần trăm tối đa 100);
     * mua nhiều hơn thì mức chiết khấu không được thấp hơn bậc dưới.
     */
    private List<DiscountTierRequest> validateTiers(List<DiscountTierRequest> tiers, String type) {
        if (tiers == null || tiers.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "TIERS_REQUIRED", "Phải khai báo ít nhất một bậc chiết khấu", "tiers");
        }
        for (DiscountTierRequest t : tiers) {
            if (t.getMinQuantity() == null || t.getMinQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_TIER_QUANTITY",
                        "Số lượng tối thiểu của mỗi bậc phải lớn hơn 0", "tiers");
            }
            if (t.getDiscountValue() == null || t.getDiscountValue().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_TIER_VALUE",
                        "Mức chiết khấu của mỗi bậc phải lớn hơn 0", "tiers");
            }
            if (DiscountPolicy.TYPE_PERCENT.equals(type) && t.getDiscountValue().compareTo(HUNDRED) > 0) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_TIER_VALUE",
                        "Chiết khấu phần trăm không được vượt quá 100%", "tiers");
            }
            if (t.getMinQuantity().scale() > 4 || t.getDiscountValue().scale() > 2) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_TIER_SCALE",
                        "Số lượng tối đa 4 chữ số thập phân, mức chiết khấu tối đa 2 chữ số thập phân", "tiers");
            }
        }
        List<DiscountTierRequest> sorted = tiers.stream()
                .sorted(Comparator.comparing(DiscountTierRequest::getMinQuantity))
                .toList();
        for (int i = 1; i < sorted.size(); i++) {
            DiscountTierRequest prev = sorted.get(i - 1);
            DiscountTierRequest cur = sorted.get(i);
            if (cur.getMinQuantity().compareTo(prev.getMinQuantity()) == 0) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "DUPLICATE_TIER",
                        "Hai bậc chiết khấu trùng số lượng tối thiểu " + cur.getMinQuantity().toPlainString(), "tiers");
            }
            if (cur.getDiscountValue().compareTo(prev.getDiscountValue()) < 0) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "TIER_VALUE_DECREASING",
                        "Mua nhiều hơn thì mức chiết khấu không được thấp hơn bậc dưới (bậc từ "
                                + cur.getMinQuantity().toPlainString() + ")", "tiers");
            }
        }
        return sorted;
    }

    /** id nhóm hàng của sản phẩm và toàn bộ nhóm cha, ông... (chính sách cho nhóm cha cũng áp cho nhóm con). */
    private List<Long> categoryAncestors(Product product) {
        List<Long> ids = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        ProductCategory c = product.getProductCategory();
        while (c != null && seen.add(c.getId())) {
            ids.add(c.getId());
            c = c.getParent();
        }
        if (ids.isEmpty()) {
            ids.add(-1L); // tránh câu IN rỗng
        }
        return ids;
    }

    private Product findProduct(String sku) {
        if (!StringUtils.hasText(sku)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "SKU_REQUIRED", "Mã SKU không được để trống", "productSku");
        }
        String normalized = sku.trim().toUpperCase();
        return productRepository.findBySku(normalized)
                .or(() -> productRepository.findFirstBySkuIgnoreCaseOrderByIdAsc(normalized))
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "PRODUCT_NOT_FOUND",
                        "Sản phẩm SKU '" + normalized + "' không tồn tại trong danh mục sản phẩm", "productSku"));
    }

    private DiscountPolicy findPolicy(Long id) {
        return policyRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy chính sách chiết khấu ID " + id));
    }

    private String normalizeCode(String code) {
        if (!StringUtils.hasText(code)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "CODE_REQUIRED", "Mã chính sách không được để trống", "code");
        }
        String c = code.trim().toUpperCase();
        if (!CODE_PATTERN.matcher(c).matches()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_CODE",
                    "Mã chính sách gồm 2–40 ký tự: chữ, số, dấu - hoặc _ (không dấu cách)", "code");
        }
        return c;
    }

    private static String upper(String s) {
        return s == null ? "" : s.trim().toUpperCase();
    }

    private void audit(String action, DiscountPolicy p, String oldValue, String newValue, UserDetailsImpl actor) {
        auditLogService.record(AuditModule.PRICING, action, "DISCOUNT_POLICY", p.getId(), p.getCode(),
                oldValue, newValue, "Chính sách chiết khấu " + p.getCode(), actor);
    }

    private static String summary(DiscountPolicy p) {
        StringBuilder sb = new StringBuilder();
        sb.append(p.getName()).append(" | ").append(p.getScope()).append(" | ").append(p.getDiscountType())
                .append(" | ").append(p.getStartDate()).append(" → ").append(p.getEndDate() != null ? p.getEndDate() : "không thời hạn")
                .append(" | bậc:");
        p.getTiers().forEach(t -> sb.append(" ≥").append(t.getMinQuantity().stripTrailingZeros().toPlainString())
                .append("=").append(t.getDiscountValue().stripTrailingZeros().toPlainString()));
        return sb.toString();
    }

    DiscountPolicyResponse toResponse(DiscountPolicy p) {
        Product product = p.getProduct();
        ProductCategory category = p.getCategory();
        return new DiscountPolicyResponse(p.getId(), p.getCode(), p.getName(), p.getScope(),
                product != null ? product.getId() : null, product != null ? product.getSku() : null,
                product != null ? product.getName() : null,
                category != null ? category.getId() : null, category != null ? category.getName() : null,
                p.getDiscountType(), p.getStartDate(), p.getEndDate(), p.getStatus(), p.getNote(),
                p.getTiers().stream().map(t -> new DiscountTierResponse(t.getId(), t.getMinQuantity(), t.getDiscountValue())).toList(),
                p.getCreatedAt(), p.getUpdatedAt());
    }
}
