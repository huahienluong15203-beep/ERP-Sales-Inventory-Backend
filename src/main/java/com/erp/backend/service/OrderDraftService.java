package com.erp.backend.service;

import com.erp.backend.dto.customer.CreditStatusResponse;
import com.erp.backend.dto.discount.DiscountCalculationResponse;
import com.erp.backend.dto.inventory.StockInfoDto;
import com.erp.backend.dto.order.*;
import com.erp.backend.entity.*;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.*;
import com.erp.backend.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
    private final InventoryService inventoryService;

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

    /**
     * Gợi ý / hiển thị sản phẩm theo bảng giá của cấp đại lý (tự động load, kèm tìm kiếm SKU/tên nếu có).
     */
    @Transactional(readOnly = true)
    public List<ProductOptionResponse> productOptions(Long customerId, String keyword, UserDetailsImpl actor) {
        Customer customer = findCustomer(customerId, actor);
        String kw = StringUtils.hasText(keyword) ? keyword.trim() : "";
        LocalDate today = LocalDate.now(VN_ZONE);
        Warehouse warehouse = inventoryService != null ? inventoryService.resolveWarehouseForCustomer(customer) : null;

        // 1. Tìm bảng giá đang có hiệu lực của nhóm khách hàng mà đại lý thuộc về
        List<PriceList> effectivePriceLists = priceListRepository.findEffectiveByCustomerGroup(
                customer.getCustomerGroup(), today);

        List<ProductOptionResponse> result = new ArrayList<>();

        if (!effectivePriceLists.isEmpty()) {
            PriceList activePriceList = effectivePriceLists.get(0);
            List<PriceListItem> items = priceItemRepository.findByPriceListIdAndKeyword(
                    activePriceList.getId(), kw, PageRequest.of(0, 100));

            Set<String> pricedSkus = new HashSet<>();
            for (PriceListItem item : items) {
                if (item.getProduct() != null && "ACTIVE".equalsIgnoreCase(item.getProduct().getStatus())) {
                    Product p = item.getProduct();
                    pricedSkus.add(item.getProductSku().toUpperCase());
                    StockInfoDto stock = (inventoryService != null && warehouse != null)
                            ? inventoryService.getStockInfo(warehouse, p) : null;
                    result.add(new ProductOptionResponse(
                            p.getId(),
                            item.getProductSku(),
                            item.getProductName(),
                            p.getBaseUnit(),
                            units(p),
                            true,
                            item.getPrice(),
                            item.getFloorPrice(),
                            activePriceList.getCode(),
                            null,
                            warehouse != null ? warehouse.getCode() : null,
                            warehouse != null ? warehouse.getName() : null,
                            stock != null ? stock.physicalStock() : BigDecimal.ZERO,
                            stock != null ? stock.reservedStock() : BigDecimal.ZERO,
                            stock != null ? stock.availableStock() : BigDecimal.ZERO));
                }
            }

            // S4-01: Nếu người dùng tìm kiếm theo từ khóa, hiển thị cả sản phẩm chưa có giá trong bảng giá kèm lý do chặn
            if (StringUtils.hasText(kw)) {
                List<Product> catalogMatches = productRepository.findByNameContainingIgnoreCaseOrSkuContainingIgnoreCase(
                        kw, kw, PageRequest.of(0, 50, Sort.by("sku"))).getContent();
                for (Product p : catalogMatches) {
                    if ("ACTIVE".equalsIgnoreCase(p.getStatus()) && !pricedSkus.contains(p.getSku().toUpperCase())) {
                        StockInfoDto stock = (inventoryService != null && warehouse != null)
                                ? inventoryService.getStockInfo(warehouse, p) : null;
                        result.add(new ProductOptionResponse(
                                p.getId(),
                                p.getSku(),
                                p.getName(),
                                p.getBaseUnit(),
                                units(p),
                                false,
                                null,
                                null,
                                null,
                                noPriceMessage(customer, p),
                                warehouse != null ? warehouse.getCode() : null,
                                warehouse != null ? warehouse.getName() : null,
                                stock != null ? stock.physicalStock() : BigDecimal.ZERO,
                                stock != null ? stock.reservedStock() : BigDecimal.ZERO,
                                stock != null ? stock.availableStock() : BigDecimal.ZERO));
                    }
                }
            }

            return result;
        }

        // 2. Đại lý chưa có bảng giá hiệu lực: tìm kiếm theo danh mục chung nếu có từ khóa
        if (!StringUtils.hasText(kw)) {
            return List.of();
        }
        return productRepository.findByNameContainingIgnoreCaseOrSkuContainingIgnoreCase(kw, kw,
                        PageRequest.of(0, 50, Sort.by("sku"))).stream()
                .filter(p -> "ACTIVE".equalsIgnoreCase(p.getStatus()))
                .map(p -> {
                    Optional<PriceListItem> price = findPrice(customer, p, today);
                    StockInfoDto stock = (inventoryService != null && warehouse != null)
                            ? inventoryService.getStockInfo(warehouse, p) : null;
                    return new ProductOptionResponse(p.getId(), p.getSku(), p.getName(), p.getBaseUnit(), units(p),
                            price.isPresent(), price.map(PriceListItem::getPrice).orElse(null),
                            price.map(PriceListItem::getFloorPrice).orElse(null),
                            price.map(i -> i.getPriceList().getCode()).orElse(null),
                            price.isPresent() ? null : noPriceMessage(customer, p),
                            warehouse != null ? warehouse.getCode() : null,
                            warehouse != null ? warehouse.getName() : null,
                            stock != null ? stock.physicalStock() : BigDecimal.ZERO,
                            stock != null ? stock.reservedStock() : BigDecimal.ZERO,
                            stock != null ? stock.availableStock() : BigDecimal.ZERO);
                })
                .toList();
    }

    /**
     * S4-05: Chốt đơn: tính lại giá / chiết khấu theo bảng giá hôm nay từ các dòng đã lưu,
     * đồng thời kiểm tra lại đại lý (khoá giao dịch, ngừng giao dịch, nợ quá hạn) như tạo đơn mới.
     * S4-01: Giữ nguyên đơn giá đã sửa thủ công để kiểm tra vi phạm giá sàn lúc chốt đơn.
     */
    void recalculateForSubmit(SalesOrder order, UserDetailsImpl actor) {
        OrderDraftRequest req = new OrderDraftRequest();
        req.setCustomerId(order.getCustomer().getId());
        req.setDeliveryAddressId(order.getDeliveryAddress() != null ? order.getDeliveryAddress().getId() : null);
        req.setDesiredDeliveryDate(order.getDesiredDeliveryDate());
        req.setNote(order.getNote());
        List<OrderLineRequest> lines = new ArrayList<>();
        for (SalesOrderLine l : order.getLines()) {
            OrderLineRequest r = new OrderLineRequest();
            r.setProductSku(l.getProductSku());
            r.setUnitName(l.getUnitName());
            r.setQuantity(l.getQuantity());
            if (Boolean.TRUE.equals(l.getIsCustomPrice()) && l.getPricePerUnit() != null) {
                r.setUnitPrice(l.getPricePerUnit());
                r.setIsCustomPrice(true);
            }
            lines.add(r);
        }
        req.setLines(lines);
        fill(order, req, actor, null);
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
        updateBelowFloorViolations(order);
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
        if (r.getUnitPrice() != null && r.getUnitPrice().compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_UNIT_PRICE",
                    prefix + "đơn giá không được âm", "lines");
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

        BigDecimal catalogBasePrice = price.getPrice();
        BigDecimal catalogPricePerUnit = catalogBasePrice.multiply(factor).setScale(2, RoundingMode.HALF_UP);
        BigDecimal floorPrice = price.getFloorPrice() != null ? price.getFloorPrice() : BigDecimal.ZERO;

        boolean isCustom;
        BigDecimal pricePerUnit;
        BigDecimal unitPrice;
        if (Boolean.TRUE.equals(r.getIsCustomPrice())) {
            isCustom = true;
            pricePerUnit = r.getUnitPrice() != null ? r.getUnitPrice().setScale(2, RoundingMode.HALF_UP) : catalogPricePerUnit;
            unitPrice = pricePerUnit.divide(factor, 2, RoundingMode.HALF_UP);
        } else if (r.getUnitPrice() != null && r.getUnitPrice().compareTo(catalogPricePerUnit) != 0 && !Boolean.FALSE.equals(r.getIsCustomPrice())) {
            isCustom = true;
            pricePerUnit = r.getUnitPrice().setScale(2, RoundingMode.HALF_UP);
            unitPrice = pricePerUnit.divide(factor, 2, RoundingMode.HALF_UP);
        } else {
            isCustom = false;
            pricePerUnit = catalogPricePerUnit;
            unitPrice = catalogBasePrice;
        }

        BigDecimal grossAmount = r.getQuantity().multiply(pricePerUnit).setScale(2, RoundingMode.HALF_UP);
        BigDecimal effectiveBasePrice = pricePerUnit.divide(factor, 4, RoundingMode.HALF_UP);
        DiscountCalculationResponse discount = discountPolicyService.calculate(
                customer.getCustomerGroup(), product, baseQuantity, effectiveBasePrice, today);
        BigDecimal discountAmount = discount.discountAmount();
        BigDecimal netAmount = grossAmount.subtract(discountAmount).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);

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
                .unitPrice(unitPrice)
                .pricePerUnit(pricePerUnit)
                .isCustomPrice(isCustom)
                .floorPrice(floorPrice)
                .grossAmount(grossAmount)
                .discountPolicyCode(discount.applied() != null ? discount.applied().policyCode() : null)
                .discountAmount(discountAmount)
                .netAmount(netAmount)
                .build();
    }

    /**
     * S4-01 / S4-05: Cập nhật mức độ vi phạm bán dưới giá sàn cho các dòng hàng của đơn.
     */
    public static void updateBelowFloorViolations(SalesOrder order) {
        int count = 0;
        BigDecimal shortfall = BigDecimal.ZERO;
        BigDecimal maxPercent = null;
        BigDecimal hundred = new BigDecimal("100");
        for (SalesOrderLine l : order.getLines()) {
            BigDecimal floor = l.getFloorPrice();
            if (floor == null || floor.signum() <= 0 || l.getBaseQuantity() == null || l.getBaseQuantity().signum() <= 0
                    || l.getNetAmount() == null) {
                continue;
            }
            // Giá bán thực tế / đơn vị cơ sở sau chiết khấu
            BigDecimal netUnit = l.getNetAmount().divide(l.getBaseQuantity(), 4, RoundingMode.HALF_UP);
            if (netUnit.compareTo(floor) < 0) {
                count++;
                shortfall = shortfall.add(floor.subtract(netUnit).multiply(l.getBaseQuantity()));
                BigDecimal pct = floor.subtract(netUnit).multiply(hundred).divide(floor, 2, RoundingMode.HALF_UP);
                maxPercent = maxPercent == null || pct.compareTo(maxPercent) > 0 ? pct : maxPercent;
            }
        }
        order.setBelowFloorLineCount(count > 0 ? count : null);
        order.setBelowFloorAmount(count > 0 ? shortfall.setScale(2, RoundingMode.HALF_UP) : null);
        order.setBelowFloorMaxPercent(maxPercent);
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
        Warehouse warehouse = inventoryService != null ? inventoryService.resolveWarehouseForCustomer(c) : null;

        List<OrderLineResponse> lines = o.getLines().stream()
                .map(l -> {
                    BigDecimal pricePerUnit = l.getPricePerUnit() != null ? l.getPricePerUnit()
                            : l.getUnitPrice().multiply(l.getConversionFactor()).setScale(2, RoundingMode.HALF_UP);
                    boolean isBelow = false;
                    if (l.getFloorPrice() != null && l.getFloorPrice().signum() > 0
                            && l.getBaseQuantity() != null && l.getBaseQuantity().signum() > 0
                            && l.getNetAmount() != null) {
                        BigDecimal netUnit = l.getNetAmount().divide(l.getBaseQuantity(), 4, RoundingMode.HALF_UP);
                        isBelow = netUnit.compareTo(l.getFloorPrice()) < 0;
                    }

                    StockInfoDto stock = (inventoryService != null && warehouse != null && l.getProduct() != null)
                            ? inventoryService.getStockInfo(warehouse, l.getProduct()) : null;
                    BigDecimal available = stock != null ? stock.availableStock() : BigDecimal.ZERO;
                    BigDecimal baseQty = l.getBaseQuantity() != null ? l.getBaseQuantity() : BigDecimal.ZERO;
                    boolean isOverStock = baseQty.compareTo(available) > 0;
                    BigDecimal factor = l.getConversionFactor() != null && l.getConversionFactor().signum() > 0
                            ? l.getConversionFactor() : BigDecimal.ONE;
                    BigDecimal maxAllowed = available.compareTo(BigDecimal.ZERO) > 0
                            ? available.divide(factor, 0, RoundingMode.FLOOR) : BigDecimal.ZERO;

                    return new OrderLineResponse(
                            l.getId(), l.getLineNo(), l.getProduct().getId(), l.getProductSku(),
                            l.getProductName(), l.getUnitName(), l.getConversionFactor(), l.getQuantity(), l.getBaseUnit(),
                            l.getBaseQuantity(), l.getPriceList() != null ? l.getPriceList().getCode() : null, l.getUnitPrice(),
                            pricePerUnit, l.getFloorPrice(), l.getGrossAmount(), l.getDiscountPolicyCode(), l.getDiscountAmount(),
                            l.getNetAmount(), Boolean.TRUE.equals(l.getIsCustomPrice()), isBelow,
                            warehouse != null ? warehouse.getCode() : null,
                            warehouse != null ? warehouse.getName() : null,
                            stock != null ? stock.physicalStock() : BigDecimal.ZERO,
                            stock != null ? stock.reservedStock() : BigDecimal.ZERO,
                            available,
                            isOverStock,
                            maxAllowed);
                })
                .toList();
        // Đơn đã duyệt thì tiền đơn đã nằm trong công nợ hiện tại, chỉ tính cho đơn nháp
        CreditStatusResponse credit = SalesOrder.STATUS_DRAFT.equals(o.getStatus())
                ? creditService.evaluate(c, o.getTotalAmount()) : null;
        return new OrderResponse(o.getId(), o.getCode(), o.getStatus(), c.getId(), c.getCode(), c.getName(),
                c.getCustomerGroup().name(), c.getCustomerGroup().getLabel(),
                a == null ? null : new OrderResponse.DeliveryAddressInfo(a.getId(), a.getLabel(), a.getAddress(),
                        a.getReceiverName(), a.getReceiverPhone()),
                o.getDesiredDeliveryDate(), o.getNote(), lines, o.getSubtotal(), o.getDiscountTotal(), o.getTotalAmount(),
                o.getCreatedByUsername(), o.getCreatedAt(), o.getUpdatedAt(), warnings(c, credit, o, lines), credit,
                OrderApprovalReasons.of(o), o.getLastApprovalComment(), o.getSubmittedAt(), o.getApprovedAt(),
                o.getApprovedByUsername(), o.getCancelReason(), o.getCancelledAt(), o.getCancelledByUsername());
    }

    /** S3-07 AC3: cảnh báo khi đại lý của đơn đang bị khoá giao dịch. S4-02: cảnh báo vượt hạn mức / nợ quá hạn. S4-01: cảnh báo bán dưới giá sàn. S4-03: cảnh báo vượt tồn kho khả dụng. */
    private static List<String> warnings(Customer c, CreditStatusResponse credit, SalesOrder o, List<OrderLineResponse> lines) {
        List<String> warnings = new ArrayList<>();
        if (c.isTransactionLocked()) {
            String reason = c.getTransactionLockReason() != null ? ": " + c.getTransactionLockReason() : "";
            warnings.add("Đại lý " + c.getName() + " (" + c.getCode() + ") đang bị khoá giao dịch" + reason
                    + ". Đơn nháp này vẫn xử lý tiếp được nhưng không tạo được đơn mới.");
        }
        if (credit != null && credit.message() != null) {
            warnings.add(credit.message());
        }
        if (o != null && o.getBelowFloorLineCount() != null && o.getBelowFloorLineCount() > 0) {
            warnings.add("Đơn hàng có " + o.getBelowFloorLineCount() + " mặt hàng bán dưới giá sàn quy định. Khi chốt đơn sẽ chuyển sang trạng thái Chờ duyệt.");
        }
        if (lines != null) {
            for (OrderLineResponse line : lines) {
                if (Boolean.TRUE.equals(line.isOverStock())) {
                    warnings.add(String.format("Mặt hàng '%s' (%s) vượt quá tồn khả dụng tại %s (Kho còn %s %s, yêu cầu %s %s). Vui lòng điều chỉnh trước khi chốt đơn.",
                            line.productName(), line.productSku(),
                            line.warehouseName() != null ? line.warehouseName() : "kho",
                            line.maxAllowedQuantity() != null ? line.maxAllowedQuantity().stripTrailingZeros().toPlainString() : "0",
                            line.unitName(),
                            line.quantity() != null ? line.quantity().stripTrailingZeros().toPlainString() : "0",
                            line.unitName()));
                }
            }
        }
        return List.copyOf(warnings);
    }
}
