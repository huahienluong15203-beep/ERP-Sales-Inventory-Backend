package com.erp.backend.service;

import com.erp.backend.dto.customer.CreditStatusResponse;
import com.erp.backend.dto.discount.DiscountCalculationResponse;
import com.erp.backend.dto.order.*;
import com.erp.backend.dto.user.PageResponse;
import com.erp.backend.entity.*;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.*;
import com.erp.backend.security.UserDetailsImpl;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * S3-09: Khởi tạo đơn hàng (đơn nháp).
 * - Chọn đại lý (NV kinh doanh chỉ chọn được đại lý mình phụ trách), điểm giao, ngày giao mong muốn.
 * - Thêm dòng hàng theo SKU, chọn đơn vị tính (thùng, lốc...) và số lượng; số lượng quy về đơn vị cơ sở.
 * - Giá lấy từ bảng giá đang hiệu lực của nhóm khách hàng (S2-10); chưa có giá thì chặn thêm dòng.
 * - Chiết khấu theo sản lượng tính tự động (S3-01).
 * - Tính tổng tiền hàng, chiết khấu, tổng phải thu; xem trước không lưu, lưu nháp và mở lại gõ tiếp.
 * Đơn nháp không giữ tồn, không khoá bảng giá; việc đó làm khi chốt đơn (Sprint 4).
 * S4-02: kèm tình trạng công nợ; tiền đơn + công nợ hiện tại > hạn mức thì đơn "cần duyệt",
 * đại lý có nợ quá hạn thì chặn tạo đơn mới (trong customerService.assertCanCreateOrder).
 */
@Service
@RequiredArgsConstructor
public class OrderDraftService {

    static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    static final int MAX_LINES = 200;
    static final BigDecimal MAX_QUANTITY = new BigDecimal("1000000");
    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SalesOrderRepository orderRepository;
    private final CustomerRepository customerRepository;
    private final CustomerDeliveryAddressRepository addressRepository;
    private final ProductRepository productRepository;
    private final PriceListItemRepository priceItemRepository;
    private final PriceListRepository priceListRepository;
    private final DiscountPolicyService discountPolicyService;
    private final CustomerService customerService;
    private final CustomerCreditService creditService;

    // ======================= XEM TRƯỚC / LƯU NHÁP =======================

    /** Tính tiền ngay khi thêm / sửa dòng hàng, không lưu gì. */
    @Transactional(readOnly = true)
    public OrderResponse preview(OrderDraftRequest req, UserDetailsImpl actor) {
        // S3-07 AC3: đang sửa dở một đơn nháp có sẵn -> cho xem trước dù đại lý vừa bị khoá
        Long continuingCustomerId = null;
        if (req.getDraftId() != null) {
            SalesOrder draft = findOrder(req.getDraftId(), actor);
            if (SalesOrder.STATUS_DRAFT.equals(draft.getStatus())) {
                continuingCustomerId = draft.getCustomer().getId();
            }
        }
        SalesOrder order = new SalesOrder();
        fill(order, req, actor, continuingCustomerId);
        return toResponse(order);
    }

    @Transactional
    public OrderResponse createDraft(OrderDraftRequest req, UserDetailsImpl actor) {
        SalesOrder order = SalesOrder.builder()
                .code(generateCode())
                .status(SalesOrder.STATUS_DRAFT)
                .createdById(actor != null ? actor.getId() : null)
                .createdByUsername(actor != null ? actor.getUsername() : null)
                .build();
        fill(order, req, actor, null);
        return toResponse(orderRepository.saveAndFlush(order));
    }

    /** Mở lại đơn nháp và lưu tiếp: thay toàn bộ thông tin và dòng hàng, giá và chiết khấu tính lại theo hôm nay. */
    @Transactional
    public OrderResponse updateDraft(Long id, OrderDraftRequest req, UserDetailsImpl actor) {
        SalesOrder order = findOrder(id, actor);
        if (!SalesOrder.STATUS_DRAFT.equals(order.getStatus())) {
            throw BusinessException.conflict("ORDER_NOT_DRAFT", "Chỉ sửa được đơn đang ở trạng thái nháp", null);
        }
        // S3-07 AC3: đơn đang dở của đại lý bị khoá vẫn xử lý tiếp được (kèm cảnh báo trong warnings)
        fill(order, req, actor, order.getCustomer().getId());
        return toResponse(orderRepository.saveAndFlush(order));
    }

    @Transactional(readOnly = true)
    public OrderResponse getById(Long id, UserDetailsImpl actor) {
        return toResponse(findOrder(id, actor));
    }

    /** NV kinh doanh chỉ thấy đơn của đại lý mình phụ trách. */
    @Transactional(readOnly = true)
    public PageResponse<OrderSummaryResponse> search(String status, Long customerId, String keyword,
                                                     int page, int size, UserDetailsImpl actor) {
        Long restrictedSalesRepId = CustomerAccess.restrictedSalesRepId(actor);
        int safePage = Math.max(page, 0);
        int safeSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);

        Specification<SalesOrder> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            Join<SalesOrder, Customer> customer = root.join("customer");
            if (StringUtils.hasText(status)) {
                ps.add(cb.equal(root.get("status"), status.trim().toUpperCase()));
            }
            if (customerId != null) {
                ps.add(cb.equal(customer.get("id"), customerId));
            }
            if (restrictedSalesRepId != null) {
                ps.add(cb.equal(customer.get("salesRep").get("id"), restrictedSalesRepId));
            }
            if (StringUtils.hasText(keyword)) {
                String like = "%" + keyword.trim().toLowerCase() + "%";
                ps.add(cb.or(cb.like(cb.lower(root.get("code")), like),
                        cb.like(cb.lower(customer.get("code")), like),
                        cb.like(cb.lower(customer.get("name")), like)));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };

        return PageResponse.of(orderRepository.findAll(spec,
                        PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "updatedAt").and(Sort.by("id").descending())))
                .map(o -> new OrderSummaryResponse(o.getId(), o.getCode(), o.getStatus(), o.getCustomer().getId(),
                        o.getCustomer().getCode(), o.getCustomer().getName(), o.getDesiredDeliveryDate(),
                        o.getLines().size(), o.getTotalAmount(), o.getCreatedByUsername(), o.getUpdatedAt())));
    }

    /**
     * Gợi ý / hiển thị sản phẩm theo bảng giá của cấp đại lý (tự động load, kèm tìm kiếm SKU/tên nếu có).
     */
    @Transactional(readOnly = true)
    public List<ProductOptionResponse> productOptions(Long customerId, String keyword, UserDetailsImpl actor) {
        Customer customer = findCustomer(customerId, actor);
        String kw = StringUtils.hasText(keyword) ? keyword.trim() : "";
        LocalDate today = LocalDate.now(VN_ZONE);

        // 1. Tìm bảng giá đang có hiệu lực của nhóm khách hàng mà đại lý thuộc về
        List<PriceList> effectivePriceLists = priceListRepository.findEffectiveByCustomerGroup(
                customer.getCustomerGroup(), today);

        if (!effectivePriceLists.isEmpty()) {
            PriceList activePriceList = effectivePriceLists.get(0);
            List<PriceListItem> items = priceItemRepository.findByPriceListIdAndKeyword(
                    activePriceList.getId(), kw, PageRequest.of(0, 100));

            return items.stream()
                    .filter(item -> item.getProduct() != null && "ACTIVE".equalsIgnoreCase(item.getProduct().getStatus()))
                    .map(item -> {
                        Product p = item.getProduct();
                        return new ProductOptionResponse(
                                p.getId(),
                                item.getProductSku(),
                                item.getProductName(),
                                p.getBaseUnit(),
                                units(p),
                                true,
                                item.getPrice(),
                                activePriceList.getCode(),
                                null);
                    })
                    .toList();
        }

        // 2. Fallback nếu đại lý chưa có bảng giá hiệu lực: tìm kiếm theo danh mục chung nếu có từ khóa
        if (!StringUtils.hasText(kw)) {
            return List.of();
        }
        return productRepository.findByNameContainingIgnoreCaseOrSkuContainingIgnoreCase(kw, kw,
                        PageRequest.of(0, 50, Sort.by("sku"))).stream()
                .filter(p -> "ACTIVE".equalsIgnoreCase(p.getStatus()))
                .map(p -> {
                    Optional<PriceListItem> price = findPrice(customer, p, today);
                    return new ProductOptionResponse(p.getId(), p.getSku(), p.getName(), p.getBaseUnit(), units(p),
                            price.isPresent(), price.map(PriceListItem::getPrice).orElse(null),
                            price.map(i -> i.getPriceList().getCode()).orElse(null),
                            price.isPresent() ? null : noPriceMessage(customer, p));
                })
                .toList();
    }

    // ======================= TÍNH ĐƠN =======================

    /**
     * @param continuingCustomerId đại lý của đơn nháp đang sửa dở (null khi tạo đơn mới).
     *        Đại lý này bị khoá giao dịch sau khi đã có đơn nháp thì vẫn cho xử lý tiếp (S3-07 AC3);
     *        đổi sang đại lý khác đang bị khoá thì vẫn chặn như tạo đơn mới.
     */
    private void fill(SalesOrder order, OrderDraftRequest req, UserDetailsImpl actor, Long continuingCustomerId) {
        Customer customer = findCustomer(req.getCustomerId(), actor);
        boolean continuingLockedDraft = continuingCustomerId != null
                && continuingCustomerId.equals(customer.getId()) && customer.isTransactionLocked();
        if (continuingLockedDraft) {
            if (!"ACTIVE".equalsIgnoreCase(customer.getStatus())) {
                throw new BusinessException(HttpStatus.CONFLICT, "CUSTOMER_INACTIVE",
                        "Đại lý " + customer.getName() + " (" + customer.getCode() + ") đã ngừng giao dịch.", "customerId");
            }
        } else {
            try {
                customerService.assertCanCreateOrder(customer);
            } catch (BusinessException e) {
                // Trả 409 thay vì 403: Frontend tự đăng xuất khi gặp 403, trong khi đây là lỗi nghiệp vụ của đại lý
                throw new BusinessException(HttpStatus.CONFLICT, e.getCode(), e.getMessage(), "customerId");
            }
        }
        LocalDate today = LocalDate.now(VN_ZONE);

        if (req.getDesiredDeliveryDate() != null && req.getDesiredDeliveryDate().isBefore(today)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_DELIVERY_DATE",
                    "Ngày giao mong muốn không được trước hôm nay", "desiredDeliveryDate");
        }
        List<OrderLineRequest> lineReqs = req.getLines() != null ? req.getLines() : List.of();
        if (lineReqs.size() > MAX_LINES) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "TOO_MANY_LINES",
                    "Mỗi đơn tối đa " + MAX_LINES + " dòng hàng", "lines");
        }

        order.setCustomer(customer);
        order.setDeliveryAddress(resolveAddress(customer, req.getDeliveryAddressId()));
        order.setDesiredDeliveryDate(req.getDesiredDeliveryDate());
        order.setNote(StringUtils.hasText(req.getNote()) ? req.getNote().trim() : null);
        order.getLines().clear();

        Set<Long> seenProducts = new HashSet<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal discountTotal = BigDecimal.ZERO;
        int lineNo = 1;
        for (OrderLineRequest r : lineReqs) {
            SalesOrderLine line = buildLine(customer, r, lineNo, today);
            if (!seenProducts.add(line.getProduct().getId())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "DUPLICATE_ORDER_LINE",
                        "Dòng " + lineNo + ": sản phẩm '" + line.getProductSku()
                                + "' đã có trong đơn, hãy sửa số lượng ở dòng cũ", "lines");
            }
            order.addLine(line);
            subtotal = subtotal.add(line.getGrossAmount());
            discountTotal = discountTotal.add(line.getDiscountAmount());
            lineNo++;
        }
        order.setSubtotal(subtotal.setScale(2, RoundingMode.HALF_UP));
        order.setDiscountTotal(discountTotal.setScale(2, RoundingMode.HALF_UP));
        order.setTotalAmount(subtotal.subtract(discountTotal).setScale(2, RoundingMode.HALF_UP));
    }

    private SalesOrderLine buildLine(Customer customer, OrderLineRequest r, int lineNo, LocalDate today) {
        String prefix = "Dòng " + lineNo + ": ";
        Product product = findProduct(r.getProductSku(), prefix);
        if (!"ACTIVE".equalsIgnoreCase(product.getStatus())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "PRODUCT_INACTIVE",
                    prefix + "sản phẩm '" + product.getSku() + "' đã ngừng kinh doanh", "lines");
        }
        if (r.getQuantity() == null || r.getQuantity().compareTo(BigDecimal.ZERO) <= 0
                || r.getQuantity().compareTo(MAX_QUANTITY) > 0 || r.getQuantity().stripTrailingZeros().scale() > 4) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_QUANTITY",
                    prefix + "số lượng phải lớn hơn 0, tối đa 1.000.000 và tối đa 4 chữ số thập phân", "lines");
        }

        String unitName = product.getBaseUnit();
        BigDecimal factor = BigDecimal.ONE;
        if (StringUtils.hasText(r.getUnitName()) && !r.getUnitName().trim().equalsIgnoreCase(product.getBaseUnit())) {
            ProductUnitConversion conv = product.getUnitConversions().stream()
                    .filter(c -> c.getUnitName().equalsIgnoreCase(r.getUnitName().trim()))
                    .filter(c -> !"INACTIVE".equalsIgnoreCase(c.getStatus()))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "UNIT_NOT_FOUND",
                            prefix + "đơn vị '" + r.getUnitName().trim() + "' chưa được khai báo cho SKU '"
                                    + product.getSku() + "'", "lines"));
            unitName = conv.getUnitName();
            factor = conv.getConversionFactor();
        }
        BigDecimal baseQuantity = r.getQuantity().multiply(factor).setScale(4, RoundingMode.HALF_UP);

        PriceListItem price = findPrice(customer, product, today)
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "NO_EFFECTIVE_PRICE",
                        prefix + noPriceMessage(customer, product), "lines"));
        DiscountCalculationResponse discount = discountPolicyService.calculate(customer.getCustomerGroup(), product, baseQuantity, price.getPrice(), today);

        return SalesOrderLine.builder()
                .lineNo(lineNo)
                .product(product)
                .productSku(product.getSku())
                .productName(product.getName())
                .unitName(unitName)
                .conversionFactor(factor)
                .quantity(r.getQuantity())
                .baseUnit(product.getBaseUnit())
                .baseQuantity(baseQuantity)
                .priceList(price.getPriceList())
                .unitPrice(price.getPrice())
                .floorPrice(price.getFloorPrice())
                .grossAmount(discount.grossAmount())
                .discountPolicyCode(discount.applied() != null ? discount.applied().policyCode() : null)
                .discountAmount(discount.discountAmount())
                .netAmount(discount.netAmount())
                .build();
    }

    // ======================= HÀM PHỤ =======================

    private Optional<PriceListItem> findPrice(Customer customer, Product product, LocalDate date) {
        return priceItemRepository.findEffective(customer.getCustomerGroup(), product.getSku(), date, PageRequest.of(0, 1))
                .stream().findFirst();
    }

    private static String noPriceMessage(Customer customer, Product product) {
        return "SKU '" + product.getSku() + "' chưa có giá trong bảng giá đang hiệu lực của nhóm "
                + customer.getCustomerGroup().getLabel() + ", không thêm được vào đơn";
    }

    private static List<ProductOptionResponse.UnitOption> units(Product p) {
        List<ProductOptionResponse.UnitOption> units = new ArrayList<>();
        units.add(new ProductOptionResponse.UnitOption(p.getBaseUnit(), BigDecimal.ONE));
        p.getUnitConversions().stream()
                .filter(c -> !"INACTIVE".equalsIgnoreCase(c.getStatus()))
                .sorted(Comparator.comparing(ProductUnitConversion::getConversionFactor))
                .forEach(c -> units.add(new ProductOptionResponse.UnitOption(c.getUnitName(), c.getConversionFactor())));
        return units;
    }

    private Customer findCustomer(Long customerId, UserDetailsImpl actor) {
        if (customerId == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "CUSTOMER_REQUIRED", "Vui lòng chọn đại lý", "customerId");
        }
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy đại lý"));
        CustomerAccess.checkCanAccess(actor, customer);
        return customer;
    }

    /** Điểm giao phải thuộc đại lý và đang dùng; không chọn thì lấy điểm giao mặc định (nếu có). */
    private CustomerDeliveryAddress resolveAddress(Customer customer, Long addressId) {
        if (addressId == null) {
            return addressRepository.findByCustomer_IdAndStatusOrderByDefaultAddressDescIdAsc(customer.getId(), "ACTIVE")
                    .stream().findFirst().orElse(null);
        }
        return addressRepository.findByIdAndCustomer_Id(addressId, customer.getId())
                .filter(a -> "ACTIVE".equals(a.getStatus()))
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_DELIVERY_ADDRESS",
                        "Điểm giao hàng không thuộc đại lý này hoặc đã ngừng sử dụng", "deliveryAddressId"));
    }

    private Product findProduct(String sku, String prefix) {
        if (!StringUtils.hasText(sku)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "SKU_REQUIRED", prefix + "mã SKU không được để trống", "lines");
        }
        String normalized = sku.trim().toUpperCase();
        return productRepository.findBySku(normalized)
                .or(() -> productRepository.findFirstBySkuIgnoreCaseOrderByIdAsc(normalized))
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "PRODUCT_NOT_FOUND",
                        prefix + "không tìm thấy sản phẩm SKU '" + normalized + "'", "lines"));
    }

    /** Tìm đơn và kiểm tra quyền xem theo đại lý (NV kinh doanh mở đơn của đại lý người khác -> 404). */
    private SalesOrder findOrder(Long id, UserDetailsImpl actor) {
        SalesOrder order = orderRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy đơn hàng"));
        CustomerAccess.checkCanAccess(actor, order.getCustomer());
        return order;
    }

    /** Mã đơn: DH + ngày (yyMMdd) + 4 ký tự ngẫu nhiên, vd DH261004-7KQ2. */
    private String generateCode() {
        String prefix = "DH" + LocalDate.now(VN_ZONE).format(DateTimeFormatter.ofPattern("yyMMdd")) + "-";
        for (int attempt = 0; attempt < 20; attempt++) {
            StringBuilder sb = new StringBuilder(prefix);
            for (int i = 0; i < 4; i++) {
                sb.append(CODE_CHARS.charAt(RANDOM.nextInt(CODE_CHARS.length())));
            }
            String code = sb.toString();
            if (!orderRepository.existsByCode(code)) {
                return code;
            }
        }
        throw new IllegalStateException("Không sinh được mã đơn hàng, vui lòng thử lại");
    }

    OrderResponse toResponse(SalesOrder o) {
        Customer c = o.getCustomer();
        CustomerDeliveryAddress a = o.getDeliveryAddress();
        List<OrderLineResponse> lines = o.getLines().stream()
                .map(l -> new OrderLineResponse(l.getId(), l.getLineNo(), l.getProduct().getId(), l.getProductSku(),
                        l.getProductName(), l.getUnitName(), l.getConversionFactor(), l.getQuantity(), l.getBaseUnit(),
                        l.getBaseQuantity(), l.getPriceList() != null ? l.getPriceList().getCode() : null, l.getUnitPrice(),
                        l.getUnitPrice().multiply(l.getConversionFactor()).setScale(2, RoundingMode.HALF_UP),
                        l.getFloorPrice(), l.getGrossAmount(), l.getDiscountPolicyCode(), l.getDiscountAmount(),
                        l.getNetAmount()))
                .toList();
        // Đơn đã duyệt thì tiền đơn đã nằm trong công nợ hiện tại, chỉ tính cho đơn nháp
        CreditStatusResponse credit = SalesOrder.STATUS_DRAFT.equals(o.getStatus())
                ? creditService.evaluate(c, o.getTotalAmount()) : null;
        return new OrderResponse(o.getId(), o.getCode(), o.getStatus(), c.getId(), c.getCode(), c.getName(),
                c.getCustomerGroup().name(), c.getCustomerGroup().getLabel(),
                a == null ? null : new OrderResponse.DeliveryAddressInfo(a.getId(), a.getLabel(), a.getAddress(),
                        a.getReceiverName(), a.getReceiverPhone()),
                o.getDesiredDeliveryDate(), o.getNote(), lines, o.getSubtotal(), o.getDiscountTotal(), o.getTotalAmount(),
                o.getCreatedByUsername(), o.getCreatedAt(), o.getUpdatedAt(), warnings(c, credit), credit);
    }

    /** S3-07 AC3: cảnh báo khi đại lý của đơn đang bị khoá giao dịch. S4-02: cảnh báo vượt hạn mức / nợ quá hạn. */
    private static List<String> warnings(Customer c, CreditStatusResponse credit) {
        List<String> warnings = new ArrayList<>();
        if (c.isTransactionLocked()) {
            String reason = c.getTransactionLockReason() != null ? ": " + c.getTransactionLockReason() : "";
            warnings.add("Đại lý " + c.getName() + " (" + c.getCode() + ") đang bị khoá giao dịch" + reason
                    + ". Đơn nháp này vẫn xử lý tiếp được nhưng không tạo được đơn mới.");
        }
        if (credit != null && credit.message() != null) {
            warnings.add(credit.message());
        }
        return List.copyOf(warnings);
    }
}
