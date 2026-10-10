package com.erp.backend.service;

import com.erp.backend.dto.customer.CreditStatusResponse;
import com.erp.backend.dto.customer.DeliveryAddressResponse;
import com.erp.backend.dto.order.*;
import com.erp.backend.dto.portal.*;
import com.erp.backend.dto.user.PageResponse;
import com.erp.backend.entity.Customer;
import com.erp.backend.entity.SalesOrder;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.CustomerRepository;
import com.erp.backend.repository.SalesOrderRepository;
import com.erp.backend.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * S4-10: Cổng đại lý đặt hàng.
 * - Đại lý luôn xác định từ tài khoản đăng nhập (Customer.portalUser), KHÔNG nhận customerId từ client.
 * - Chỉ thấy sản phẩm và giá theo bảng giá nhóm khách của mình; không thấy giá sàn, giá vốn.
 * - Giỏ hàng nằm ở client; xem trước tính tiền + công nợ còn lại; đặt đơn thì đơn luôn ở trạng thái Chờ duyệt
 *   (lý do "Đại lý tự đặt") để nhân viên phụ trách xác nhận. Giá luôn theo bảng giá hiện hành, bỏ qua giá client gửi.
 * Tái sử dụng logic tính đơn / chốt đơn / giữ chỗ tồn của nhân viên (OrderDraftService, OrderApprovalService).
 */
@Service
@RequiredArgsConstructor
public class PortalService {

    static final int MAX_NOTE = 400;
    static final String NOTE_PREFIX = "[Đại lý tự đặt qua cổng] ";
    static final Map<String, String> STATUS_LABELS = Map.ofEntries(
            Map.entry(SalesOrder.STATUS_DRAFT, "Nháp"),
            Map.entry(SalesOrder.STATUS_PENDING_APPROVAL, "Chờ xác nhận"),
            Map.entry(SalesOrder.STATUS_APPROVED, "Đã xác nhận"),
            Map.entry(SalesOrder.STATUS_PICKING, "Đang soạn hàng"),
            Map.entry(SalesOrder.STATUS_DISPATCHED, "Đã xuất kho"),
            Map.entry(SalesOrder.STATUS_DELIVERED, "Đã giao"),
            Map.entry(SalesOrder.STATUS_CLOSED, "Hoàn tất"),
            Map.entry(SalesOrder.STATUS_REJECTED, "Bị từ chối"),
            Map.entry(SalesOrder.STATUS_CANCELLED, "Đã huỷ"));

    private final CustomerRepository customerRepository;
    private final SalesOrderRepository orderRepository;
    private final OrderDraftService orderDraftService;
    private final OrderApprovalService orderApprovalService;
    private final OrderQueryService orderQueryService;
    private final CustomerCreditService creditService;
    private final CustomerDeliveryAddressService deliveryAddressService;

    @Transactional(readOnly = true)
    public PortalMeResponse me(UserDetailsImpl actor) {
        Customer c = myCustomer(actor);
        return new PortalMeResponse(c.getId(), c.getCode(), c.getName(),
                c.getCustomerGroup() != null ? c.getCustomerGroup().name() : null,
                c.getCustomerGroup() != null ? c.getCustomerGroup().getLabel() : null,
                c.getSalesRep() != null ? c.getSalesRep().getFullName() : null,
                c.getRegion() != null ? c.getRegion().getName() : null,
                c.getPhone(), c.getAddress(), c.isTransactionLocked(), c.getStatus());
    }

    @Transactional(readOnly = true)
    public List<PortalProductResponse> products(String keyword, UserDetailsImpl actor) {
        Customer c = myCustomer(actor);
        return orderDraftService.productOptions(c.getId(), keyword, actor).stream()
                .map(p -> new PortalProductResponse(p.productId(), p.sku(), p.name(), p.baseUnit(),
                        p.units() == null ? List.of() : p.units().stream()
                                .map(u -> new PortalProductResponse.Unit(u.unitName(), u.conversionFactor())).toList(),
                        p.priceAvailable(), p.unitPrice(), p.message(), p.availableStock()))
                .toList();
    }

    @Transactional(readOnly = true)
    public CreditStatusResponse creditStatus(BigDecimal orderAmount, UserDetailsImpl actor) {
        return creditService.getStatus(myCustomer(actor).getId(), orderAmount, actor);
    }

    @Transactional(readOnly = true)
    public List<DeliveryAddressResponse> deliveryAddresses(UserDetailsImpl actor) {
        return deliveryAddressService.list(myCustomer(actor).getId(), actor).stream()
                .filter(a -> "ACTIVE".equalsIgnoreCase(a.status()))
                .toList();
    }

    /** Tính tiền giỏ hàng (không lưu): tổng tiền, chiết khấu, công nợ còn lại sau đơn. */
    @Transactional(readOnly = true)
    public PortalOrderResponse preview(PortalOrderRequest req, UserDetailsImpl actor) {
        Customer c = myCustomer(actor);
        return toPortal(orderDraftService.preview(toDraftRequest(c, req, false), actor));
    }

    /** Đặt đơn: tạo đơn và gửi luôn, đơn ở trạng thái Chờ duyệt chờ nhân viên phụ trách xác nhận. */
    @Transactional
    public PortalOrderResponse placeOrder(PortalOrderRequest req, UserDetailsImpl actor) {
        Customer c = myCustomer(actor);
        OrderDraftRequest draftReq = toDraftRequest(c, req, true);
        OrderResponse draft = orderDraftService.createDraft(draftReq, actor);
        SalesOrder order = orderRepository.findById(draft.id())
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy đơn hàng"));
        order.setSource(SalesOrder.SOURCE_PORTAL);
        return toPortal(orderApprovalService.submit(order.getId(), actor));
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderSummaryResponse> orders(String status, int page, int size, UserDetailsImpl actor) {
        Customer c = myCustomer(actor);
        List<String> statuses = StringUtils.hasText(status) ? List.of(status) : null;
        return orderQueryService.search(new OrderSearchCriteria(statuses, c.getId(), null, null, null, null, null),
                page, size, actor);
    }

    @Transactional(readOnly = true)
    public PortalOrderResponse order(Long id, UserDetailsImpl actor) {
        return toPortal(myOrder(id, actor));
    }

    // ======================= HÀM PHỤ =======================

    /** Đại lý gắn với tài khoản đang đăng nhập; chưa gắn thì báo rõ để Admin gắn (409, không dùng 403). */
    Customer myCustomer(UserDetailsImpl actor) {
        if (actor == null || actor.getId() == null) {
            throw BusinessException.conflict("PORTAL_ACCOUNT_NOT_LINKED",
                    "Tài khoản chưa được gắn với đại lý nào. Vui lòng liên hệ quản trị viên.", null);
        }
        return customerRepository.findByPortalUser_Id(actor.getId())
                .orElseThrow(() -> BusinessException.conflict("PORTAL_ACCOUNT_NOT_LINKED",
                        "Tài khoản chưa được gắn với đại lý nào. Vui lòng liên hệ quản trị viên.", null));
    }

    /** Đơn của chính đại lý; đơn đại lý khác -> 404 như không tồn tại. */
    OrderResponse myOrder(Long id, UserDetailsImpl actor) {
        Customer c = myCustomer(actor);
        SalesOrder order = orderRepository.findById(id)
                .filter(o -> o.getCustomer() != null && c.getId().equals(o.getCustomer().getId()))
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy đơn hàng"));
        return orderDraftService.getById(order.getId(), actor);
    }

    /** Giỏ hàng -> yêu cầu tính đơn; đại lý lấy theo tài khoản, bỏ mọi giá client gửi (không cho sửa giá). */
    OrderDraftRequest toDraftRequest(Customer c, PortalOrderRequest req, boolean placing) {
        if (req == null) {
            throw BusinessException.badRequest("ORDER_EMPTY", "Giỏ hàng đang trống");
        }
        List<PortalOrderLineRequest> lines = req.getLines() != null ? req.getLines() : List.of();
        if (placing && lines.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "ORDER_EMPTY", "Giỏ hàng đang trống", "lines");
        }
        String note = StringUtils.hasText(req.getNote()) ? req.getNote().trim() : "";
        if (note.length() > MAX_NOTE) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "NOTE_TOO_LONG",
                    "Ghi chú tối đa " + MAX_NOTE + " ký tự", "note");
        }
        OrderDraftRequest r = new OrderDraftRequest();
        r.setCustomerId(c.getId());
        r.setDeliveryAddressId(req.getDeliveryAddressId());
        r.setDesiredDeliveryDate(req.getDesiredDeliveryDate());
        r.setNote(NOTE_PREFIX + note);
        List<OrderLineRequest> out = new ArrayList<>();
        for (PortalOrderLineRequest l : lines) {
            OrderLineRequest o = new OrderLineRequest();
            o.setProductSku(l != null ? l.getProductSku() : null);
            o.setUnitName(l != null ? l.getUnitName() : null);
            o.setQuantity(l != null ? l.getQuantity() : null);
            o.setUnitPrice(null);
            o.setIsCustomPrice(false);
            out.add(o);
        }
        r.setLines(out);
        return r;
    }

    static PortalOrderResponse toPortal(OrderResponse o) {
        List<PortalOrderResponse.Line> lines = o.lines() == null ? List.of() : o.lines().stream()
                .map(l -> new PortalOrderResponse.Line(l.lineNo(), l.productId(), l.productSku(), l.productName(),
                        l.unitName(), l.conversionFactor(), l.quantity(), l.pricePerUnit(), l.grossAmount(),
                        l.discountAmount(), l.netAmount(), l.availableStock(), l.isOverStock(), l.maxAllowedQuantity()))
                .toList();
        return new PortalOrderResponse(o.id(), o.code(), o.status(), STATUS_LABELS.getOrDefault(o.status(), o.status()),
                o.deliveryAddress(), o.desiredDeliveryDate(), o.note(), lines, o.subtotal(), o.discountTotal(),
                o.totalAmount(), o.createdAt(), o.submittedAt(), o.approvedAt(), o.lastApprovalComment(),
                o.cancelReason(),
                // Không cho đại lý thấy cảnh báo nội bộ về giá sàn
                o.warnings() == null ? List.of() : o.warnings().stream().filter(w -> !w.contains("giá sàn")).toList(),
                o.credit());
    }
}
