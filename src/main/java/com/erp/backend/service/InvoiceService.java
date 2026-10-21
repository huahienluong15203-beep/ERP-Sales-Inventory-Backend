package com.erp.backend.service;

import com.erp.backend.config.AuditLogInterceptor;
import com.erp.backend.dto.invoice.*;
import com.erp.backend.dto.user.PageResponse;
import com.erp.backend.entity.*;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.*;
import com.erp.backend.security.UserDetailsImpl;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
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
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * S7-01: Phát hành hoá đơn.
 * - Lập từ số lượng THỰC GIAO (không phải số đặt) của các đơn Đã giao / Đóng chưa lập hoá đơn.
 * - Gộp nhiều lần giao của cùng 1 đại lý trong 1 kỳ vào 1 hoá đơn.
 * - Số hoá đơn liên tục theo năm (HD2026-000001...), không trùng, không nhảy số: cấp từ bộ đếm bị khoá dòng
 *   trong cùng transaction với việc lưu hoá đơn; ngày hoá đơn không được lùi so với hoá đơn trước.
 * - Hoá đơn đã phát hành không sửa / xoá (không có API sửa / xoá; entity chặn xoá).
 * - Khoá các đơn (FOR UPDATE) + ràng buộc duy nhất order_line_id: 1 đơn không bị lập hoá đơn 2 lần.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InvoiceService {

    static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    static final List<String> INVOICEABLE_STATUSES = List.of(SalesOrder.STATUS_DELIVERED, SalesOrder.STATUS_CLOSED);
    static final Set<String> ISSUE_ROLES = Set.of("ROLE_ACCOUNTANT", "ROLE_ADMIN");
    static final int MAX_ORDERS = 200;
    static final long MAX_RANGE_DAYS = 366;
    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;
    static final int MAX_NOTE = 500;

    private final InvoiceRepository invoiceRepository;
    private final InvoiceLineRepository invoiceLineRepository;
    private final InvoiceSequenceRepository sequenceRepository;
    private final SalesOrderRepository orderRepository;
    private final CustomerRepository customerRepository;
    private final AuditLogService auditLogService;

    Clock clock = Clock.system(VN_ZONE);

    // ======================= CHỌN ĐƠN / XEM TRƯỚC =======================

    /** Đơn đã giao chưa lập hoá đơn của đại lý trong kỳ (mặc định từ đầu tháng đến hôm nay). */
    @Transactional(readOnly = true)
    public List<UninvoicedOrderResponse> uninvoicedOrders(Long customerId, LocalDate fromDate, LocalDate toDate,
                                                          UserDetailsImpl actor) {
        Customer customer = findCustomer(customerId, actor);
        LocalDate[] range = range(fromDate, toDate);
        List<SalesOrder> orders = orderRepository.findUninvoiced(customer.getId(), INVOICEABLE_STATUSES,
                range[0].atStartOfDay(), range[1].plusDays(1).atStartOfDay());
        List<UninvoicedOrderResponse> result = new ArrayList<>();
        for (SalesOrder o : orders) {
            BigDecimal delivered = BigDecimal.ZERO;
            boolean recorded = o.getLines().stream().allMatch(l -> l.getDeliveredQuantity() != null);
            if (recorded) {
                BigDecimal gross = BigDecimal.ZERO;
                BigDecimal discount = BigDecimal.ZERO;
                for (InvoiceLine l : buildLines(List.of(o))) {
                    gross = gross.add(l.getGrossAmount());
                    discount = discount.add(l.getDiscountAmount());
                }
                delivered = total(gross, discount);
            }
            result.add(new UninvoicedOrderResponse(o.getId(), o.getCode(), o.getStatus(), deliveredMoment(o),
                    o.getLines().size(), o.getTotalAmount(), recorded ? delivered : null));
        }
        return result;
    }

    /** Xem trước hoá đơn (không cấp số, không lưu, không khoá đơn). */
    @Transactional(readOnly = true)
    public InvoiceResponse preview(InvoiceIssueRequest request, UserDetailsImpl actor) {
        requireIssueRole(actor);
        Draft d = draft(request, actor, false);
        return toResponse(d.invoice(), d.orderCodes());
    }

    // ======================= PHÁT HÀNH =======================

    @Transactional
    public InvoiceResponse issue(InvoiceIssueRequest request, UserDetailsImpl actor) {
        requireIssueRole(actor);
        Draft d = draft(request, actor, true);
        Invoice invoice = d.invoice();

        // Cấp số hoá đơn: khoá bộ đếm của năm, người phát hành sau phải chờ -> số liên tục, không trùng
        int year = invoice.getInvoiceDate().getYear();
        sequenceRepository.ensureYear(year);
        InvoiceSequence seq = sequenceRepository.findForUpdate(year)
                .orElseThrow(() -> new IllegalStateException("Không khởi tạo được bộ đếm số hoá đơn năm " + year));
        LocalDate lastDate = invoiceRepository.maxInvoiceDate(year);
        if (lastDate != null && invoice.getInvoiceDate().isBefore(lastDate)) {
            throw new BusinessException(HttpStatus.CONFLICT, "INVOICE_DATE_BEFORE_LAST",
                    "Ngày hoá đơn phải từ " + lastDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                            + " trở đi (đã có hoá đơn ngày này, số hoá đơn phải tăng theo ngày)", "invoiceDate");
        }
        long next = (seq.getLastNumber() != null ? seq.getLastNumber() : 0L) + 1;
        seq.setLastNumber(next);
        sequenceRepository.save(seq);
        invoice.setSeqYear(year);
        invoice.setSeqNumber(next);
        invoice.setCode(invoiceCode(year, next));

        Invoice saved = invoiceRepository.saveAndFlush(invoice);
        for (SalesOrder o : d.orders()) {
            o.setInvoiceId(saved.getId());
            o.setInvoiceCode(saved.getCode());
        }
        orderRepository.saveAll(d.orders());

        // Quy tắc 9: nhật ký phát hành hoá đơn
        auditLogService.record(AuditModule.INVOICE, "ISSUE_INVOICE", "INVOICE", saved.getId(), saved.getCode(),
                null, "Tổng=" + plain(saved.getTotalAmount()) + "; Đơn=" + String.join(",", d.orderCodes()),
                cut("Phát hành hoá đơn cho đại lý " + saved.getCustomerCode()
                        + (StringUtils.hasText(saved.getNote()) ? ": " + saved.getNote() : ""), MAX_NOTE), actor);
        markAuditLogged();
        log.info("S7-01: Đã phát hành hoá đơn {} cho đại lý {} gồm {} đơn, tổng {}", saved.getCode(),
                saved.getCustomerCode(), d.orders().size(), saved.getTotalAmount());
        return toResponse(saved, d.orderCodes());
    }

    /** Hoá đơn đang dựng (chưa cấp số) + các đơn đã kiểm tra hợp lệ. */
    record Draft(Invoice invoice, List<SalesOrder> orders, List<String> orderCodes) {
    }

    private Draft draft(InvoiceIssueRequest request, UserDetailsImpl actor, boolean lock) {
        if (request == null || request.getCustomerId() == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "CUSTOMER_REQUIRED", "Vui lòng chọn đại lý", "customerId");
        }
        Customer customer = findCustomer(request.getCustomerId(), actor);
        LocalDate today = LocalDate.now(clock);
        LocalDate invoiceDate = request.getInvoiceDate() != null ? request.getInvoiceDate() : today;
        if (invoiceDate.isAfter(today)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVOICE_DATE_IN_FUTURE",
                    "Ngày hoá đơn không được ở tương lai", "invoiceDate");
        }
        String note = request.getNote() != null ? request.getNote().trim() : null;
        if (note != null && note.length() > MAX_NOTE) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "NOTE_TOO_LONG",
                    "Ghi chú tối đa " + MAX_NOTE + " ký tự", "note");
        }

        // Các đơn gộp vào hoá đơn
        List<Long> ids;
        if (request.getOrderIds() != null && !request.getOrderIds().isEmpty()) {
            ids = request.getOrderIds().stream().filter(Objects::nonNull).distinct().sorted().toList();
        } else {
            LocalDate[] range = range(request.getFromDate(), request.getToDate());
            ids = orderRepository.findUninvoicedIds(customer.getId(), INVOICEABLE_STATUSES,
                            range[0].atStartOfDay(), range[1].plusDays(1).atStartOfDay())
                    .stream().filter(Objects::nonNull).distinct().sorted().toList();
        }
        if (ids.isEmpty()) {
            throw BusinessException.conflict("NO_ORDERS_TO_INVOICE",
                    "Không có đơn đã giao nào chưa lập hoá đơn để phát hành", "orderIds");
        }
        if (ids.size() > MAX_ORDERS) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "TOO_MANY_ORDERS",
                    "Một hoá đơn gộp tối đa " + MAX_ORDERS + " đơn hàng, hãy chia kỳ nhỏ hơn", "orderIds");
        }
        List<SalesOrder> orders = new ArrayList<>(lock ? orderRepository.findAllByIdForUpdate(ids) : orderRepository.findAllById(ids));
        if (orders.size() != ids.size()) {
            throw BusinessException.notFound("Có đơn hàng không tồn tại trong danh sách lập hoá đơn");
        }
        for (SalesOrder o : orders) {
            if (o.getCustomer() == null || !Objects.equals(o.getCustomer().getId(), customer.getId())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "ORDER_CUSTOMER_MISMATCH",
                        "Đơn " + o.getCode() + " không phải của đại lý " + customer.getCode(), "orderIds");
            }
            if (!INVOICEABLE_STATUSES.contains(o.getStatus())) {
                throw BusinessException.conflict("ORDER_NOT_DELIVERED",
                        "Đơn " + o.getCode() + " chưa giao xong (trạng thái " + o.getStatus() + "), chưa lập hoá đơn được", "orderIds");
            }
            if (o.getInvoiceId() != null) {
                throw BusinessException.conflict("ORDER_ALREADY_INVOICED",
                        "Đơn " + o.getCode() + " đã được lập hoá đơn " + o.getInvoiceCode(), "orderIds");
            }
        }
        orders.sort(Comparator.comparing(InvoiceService::deliveredMoment, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(SalesOrder::getId));

        List<InvoiceLine> lines = buildLines(orders);
        if (lines.isEmpty()) {
            throw BusinessException.conflict("NOTHING_TO_INVOICE",
                    "Các đơn đã chọn không có số lượng thực giao nào để lập hoá đơn", "orderIds");
        }
        if (lock) {
            List<Long> lineIds = lines.stream().map(InvoiceLine::getOrderLineId).toList();
            if (invoiceLineRepository.existsByOrderLineIdIn(lineIds)) {
                throw BusinessException.conflict("ORDER_ALREADY_INVOICED",
                        "Có dòng hàng đã được lập hoá đơn trước đó, vui lòng tải lại danh sách", "orderIds");
            }
        }

        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal discount = BigDecimal.ZERO;
        for (InvoiceLine l : lines) {
            subtotal = subtotal.add(l.getGrossAmount());
            discount = discount.add(l.getDiscountAmount());
        }
        LocalDate periodFrom = null;
        LocalDate periodTo = null;
        for (SalesOrder o : orders) {
            LocalDateTime m = deliveredMoment(o);
            if (m == null) {
                continue;
            }
            LocalDate dte = m.toLocalDate();
            periodFrom = periodFrom == null || dte.isBefore(periodFrom) ? dte : periodFrom;
            periodTo = periodTo == null || dte.isAfter(periodTo) ? dte : periodTo;
        }

        Invoice invoice = Invoice.builder()
                .invoiceDate(invoiceDate)
                .customer(customer)
                .customerCode(customer.getCode())
                .customerName(customer.getName())
                .customerTaxCode(customer.getTaxCode())
                .customerAddress(customer.getAddress())
                .periodFrom(periodFrom)
                .periodTo(periodTo)
                .subtotal(subtotal.setScale(2, RoundingMode.HALF_UP))
                .discountTotal(discount.setScale(2, RoundingMode.HALF_UP))
                .totalAmount(total(subtotal, discount))
                .paidAmount(BigDecimal.ZERO)
                .remainingAmount(total(subtotal, discount))
                .paymentStatus(Invoice.PAYMENT_STATUS_UNPAID)
                .status(Invoice.STATUS_ISSUED)
                .note(StringUtils.hasText(note) ? note : null)
                .issuedAt(LocalDateTime.now(clock))
                .issuedById(actor != null ? actor.getId() : null)
                .issuedByUsername(actor != null ? actor.getUsername() : null)
                .build();
        lines.forEach(invoice::addLine);
        return new Draft(invoice, orders, orders.stream().map(SalesOrder::getCode).toList());
    }

    /**
     * Dòng hoá đơn từ số THỰC GIAO của từng dòng đơn (bỏ dòng giao 0). Giao đủ thì lấy nguyên tiền dòng;
     * giao thiếu thì tiền hàng / chiết khấu chia theo tỉ lệ số giao / số xuất.
     */
    static List<InvoiceLine> buildLines(List<SalesOrder> orders) {
        List<InvoiceLine> result = new ArrayList<>();
        int no = 1;
        for (SalesOrder o : orders) {
            List<SalesOrderLine> orderLines = new ArrayList<>(o.getLines());
            orderLines.sort(Comparator.comparing(SalesOrderLine::getLineNo, Comparator.nullsLast(Comparator.naturalOrder())));
            for (SalesOrderLine l : orderLines) {
                BigDecimal delivered = l.getDeliveredQuantity();
                if (delivered == null) {
                    throw BusinessException.conflict("DELIVERY_NOT_RECORDED",
                            "Đơn " + o.getCode() + " dòng " + l.getProductSku() + " chưa ghi nhận số thực giao", "orderIds");
                }
                if (delivered.signum() <= 0) {
                    continue;
                }
                BigDecimal qty = l.getQuantity();
                if (delivered.compareTo(qty) > 0) {
                    delivered = qty;
                }
                boolean full = delivered.compareTo(qty) == 0;
                BigDecimal grossAll = nz(l.getGrossAmount());
                BigDecimal discountAll = nz(l.getDiscountAmount());
                BigDecimal gross = full ? grossAll.setScale(2, RoundingMode.HALF_UP) : OrderStatusService.prorate(grossAll, delivered, qty);
                BigDecimal discount = full ? discountAll.setScale(2, RoundingMode.HALF_UP) : OrderStatusService.prorate(discountAll, delivered, qty);
                BigDecimal lineBase = InventoryService.lineBaseQuantity(l);
                BigDecimal factor = l.getConversionFactor() != null && l.getConversionFactor().signum() > 0
                        ? l.getConversionFactor() : BigDecimal.ONE;
                BigDecimal base = full ? lineBase : delivered.multiply(factor).setScale(4, RoundingMode.HALF_UP).min(lineBase);
                BigDecimal pricePerUnit = l.getPricePerUnit() != null ? l.getPricePerUnit()
                        : nz(l.getUnitPrice()).multiply(factor).setScale(2, RoundingMode.HALF_UP);
                result.add(InvoiceLine.builder()
                        .lineNo(no++)
                        .orderId(o.getId())
                        .orderCode(o.getCode())
                        .orderLineId(l.getId())
                        .product(l.getProduct())
                        .productSku(l.getProductSku())
                        .productName(l.getProductName())
                        .unitName(l.getUnitName())
                        .conversionFactor(factor)
                        .quantity(delivered)
                        .baseUnit(l.getBaseUnit())
                        .baseQuantity(base)
                        .pricePerUnit(pricePerUnit)
                        .grossAmount(gross)
                        .discountAmount(discount)
                        .netAmount(gross.subtract(discount).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP))
                        .build());
            }
        }
        return result;
    }

    // ======================= TRA CỨU =======================

    /** Danh sách hoá đơn; NV kinh doanh chỉ đại lý mình phụ trách, đại lý chỉ hoá đơn của mình. Mới nhất lên đầu. */
    @Transactional(readOnly = true)
    public PageResponse<InvoiceSummaryResponse> search(Long customerId, LocalDate fromDate, LocalDate toDate, String keyword,
                                                       int page, int size, UserDetailsImpl actor) {
        if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE",
                    "Ngày bắt đầu không được sau ngày kết thúc", "fromDate");
        }
        int safePage = Math.max(page, 0);
        int safeSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        Long restrictedCustomerId = null;
        Long restrictedRepId = null;
        if (CustomerAccess.isCustomer(actor)) {
            Customer own = CustomerAccess.resolveCustomerForActor(actor, customerRepository);
            if (own == null) {
                return PageResponse.of(Page.empty());
            }
            restrictedCustomerId = own.getId();
        } else {
            restrictedRepId = CustomerAccess.restrictedSalesRepId(actor);
        }
        String kw = StringUtils.hasText(keyword) ? keyword.trim().toLowerCase(Locale.ROOT) : null;
        Long rc = restrictedCustomerId;
        Long rr = restrictedRepId;
        Specification<Invoice> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            Join<Invoice, Customer> c = root.join("customer");
            if (rc != null) {
                ps.add(cb.equal(c.get("id"), rc));
            } else if (customerId != null) {
                ps.add(cb.equal(c.get("id"), customerId));
            }
            if (rr != null) {
                ps.add(cb.equal(c.get("salesRep").get("id"), rr));
            }
            if (fromDate != null) {
                ps.add(cb.greaterThanOrEqualTo(root.get("invoiceDate"), fromDate));
            }
            if (toDate != null) {
                ps.add(cb.lessThanOrEqualTo(root.get("invoiceDate"), toDate));
            }
            if (kw != null) {
                String like = "%" + kw + "%";
                ps.add(cb.or(cb.like(cb.lower(root.get("code")), like),
                        cb.like(cb.lower(root.get("customerCode")), like),
                        cb.like(cb.lower(root.get("customerName")), like)));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
        return PageResponse.of(invoiceRepository.findAll(spec, PageRequest.of(safePage, safeSize,
                        Sort.by(Sort.Direction.DESC, "seqYear", "seqNumber")))
                .map(i -> new InvoiceSummaryResponse(i.getId(), i.getCode(), i.getInvoiceDate(), i.getStatus(),
                        i.getCustomer().getId(), i.getCustomerCode(), i.getCustomerName(), i.getLines().size(),
                        i.getTotalAmount(),
                        i.getPaidAmount() != null ? i.getPaidAmount() : BigDecimal.ZERO,
                        i.getRemainingAmount() != null ? i.getRemainingAmount() : (i.getTotalAmount() != null ? i.getTotalAmount() : BigDecimal.ZERO),
                        i.getPaymentStatus() != null ? i.getPaymentStatus() : Invoice.PAYMENT_STATUS_UNPAID,
                        i.getIssuedAt(), i.getIssuedByUsername())));
    }

    @Transactional(readOnly = true)
    public InvoiceResponse get(Long id, UserDetailsImpl actor) {
        Invoice invoice = invoiceRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy hoá đơn"));
        CustomerAccess.checkCanAccess(actor, invoice.getCustomer(), customerRepository);
        return toResponse(invoice, null);
    }

    // ======================= TIỆN ÍCH =======================

    private Customer findCustomer(Long customerId, UserDetailsImpl actor) {
        if (customerId == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "CUSTOMER_REQUIRED", "Vui lòng chọn đại lý", "customerId");
        }
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy đại lý"));
        CustomerAccess.checkCanAccess(actor, customer, customerRepository);
        return customer;
    }

    private static void requireIssueRole(UserDetailsImpl actor) {
        boolean ok = actor != null && actor.getAuthorities() != null
                && actor.getAuthorities().stream().anyMatch(a -> ISSUE_ROLES.contains(a.getAuthority()));
        if (!ok) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "INVOICE_ISSUE_NOT_ALLOWED",
                    "Chỉ Kế toán hoặc Quản trị được lập hoá đơn", null);
        }
    }

    /** [from, to] mặc định từ đầu tháng đến hôm nay, tối đa 1 năm. */
    private LocalDate[] range(LocalDate fromDate, LocalDate toDate) {
        LocalDate today = LocalDate.now(clock);
        LocalDate to = toDate != null ? toDate : today;
        LocalDate from = fromDate != null ? fromDate : to.withDayOfMonth(1);
        if (from.isAfter(to)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE",
                    "Ngày bắt đầu không được sau ngày kết thúc", "fromDate");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "DATE_RANGE_TOO_LONG",
                    "Kỳ lập hoá đơn tối đa 1 năm", "toDate");
        }
        return new LocalDate[]{from, to};
    }

    /** Mốc giao hàng của đơn: deliveredAt (S6-08), đơn cũ chưa có thì lấy updatedAt. */
    static LocalDateTime deliveredMoment(SalesOrder o) {
        return o.getDeliveredAt() != null ? o.getDeliveredAt() : o.getUpdatedAt();
    }

    static String invoiceCode(int year, long number) {
        return String.format("HD%d-%06d", year, number);
    }

    private static BigDecimal total(BigDecimal subtotal, BigDecimal discount) {
        return subtotal.subtract(discount).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }

    InvoiceResponse toResponse(Invoice i, List<String> orderCodes) {
        List<InvoiceLineResponse> lines = i.getLines().stream()
                .sorted(Comparator.comparing(InvoiceLine::getLineNo))
                .map(l -> new InvoiceLineResponse(l.getLineNo(), l.getOrderId(), l.getOrderCode(), l.getOrderLineId(),
                        l.getProduct() != null ? l.getProduct().getId() : null, l.getProductSku(), l.getProductName(),
                        l.getUnitName(), strip(l.getQuantity()), l.getBaseUnit(), strip(l.getBaseQuantity()),
                        l.getPricePerUnit(), l.getGrossAmount(), l.getDiscountAmount(), l.getNetAmount()))
                .toList();
        List<String> codes = orderCodes != null ? orderCodes
                : i.getLines().stream().map(InvoiceLine::getOrderCode).distinct().toList();
        return new InvoiceResponse(i.getId(), i.getCode(), i.getInvoiceDate(), i.getStatus(),
                i.getCustomer() != null ? i.getCustomer().getId() : null, i.getCustomerCode(), i.getCustomerName(),
                i.getCustomerTaxCode(), i.getCustomerAddress(), i.getPeriodFrom(), i.getPeriodTo(), codes, lines,
                i.getSubtotal(), i.getDiscountTotal(), i.getTotalAmount(),
                i.getPaidAmount() != null ? i.getPaidAmount() : BigDecimal.ZERO,
                i.getRemainingAmount() != null ? i.getRemainingAmount() : (i.getTotalAmount() != null ? i.getTotalAmount() : BigDecimal.ZERO),
                i.getPaymentStatus() != null ? i.getPaymentStatus() : Invoice.PAYMENT_STATUS_UNPAID,
                i.getNote(), i.getIssuedAt(), i.getIssuedByUsername());
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

    private static String plain(BigDecimal v) {
        return v == null ? "" : v.stripTrailingZeros().toPlainString();
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static void markAuditLogged() {
        try {
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes != null && attributes.getRequest() != null) {
                attributes.getRequest().setAttribute(AuditLogInterceptor.AUDIT_LOGGED_ATTR, Boolean.TRUE);
            }
        } catch (Exception ignored) {
            // ngoài HTTP request thì bỏ qua
        }
    }
}
