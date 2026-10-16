package com.erp.backend.service;

import com.erp.backend.dto.trip.*;
import com.erp.backend.entity.*;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.DeliveryTripRepository;
import com.erp.backend.repository.SalesOrderRepository;
import com.erp.backend.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * S6-07: Theo dõi hàng đi đường.
 * - Kho lập chuyến giao cho các đơn đã xuất kho (cùng kho); mỗi đơn là 1 điểm giao.
 * - Danh sách chuyến đang chạy kèm số điểm đã giao / giao không thành công / còn lại, tiền hàng còn trên xe.
 * - Hàng giao không được / giao thiếu của đơn trên chuyến vẫn thuộc trách nhiệm của chuyến (không tự về kho);
 *   chỉ khi kết thúc chuyến (xe về, kho nhận lại) mới nhập lại kho, ghi thẻ kho.
 * - Chuyến đang chạy quá thời điểm dự kiến -> cảnh báo trễ.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryTripService {

    static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    static final Set<String> MANAGE_ROLES = Set.of("ROLE_ADMIN", "ROLE_WAREHOUSE", "ROLE_WH_MANAGER");
    static final List<String> RESOLVED_STATUSES = List.of(SalesOrder.STATUS_DELIVERED, SalesOrder.STATUS_CLOSED,
            SalesOrder.STATUS_DELIVERY_FAILED);
    static final int MAX_STOPS = 100;
    static final int RECENT_COMPLETED_DAYS = 30;
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final DeliveryTripRepository tripRepository;
    private final SalesOrderRepository orderRepository;
    private final InventoryService inventoryService;
    private final StockLedgerService stockLedgerService;
    private final AuditLogService auditLogService;

    Clock clock = Clock.system(VN_ZONE);

    // ======================= LẬP CHUYẾN =======================

    @Transactional
    public DeliveryTripDetailResponse create(DeliveryTripCreateRequest req, UserDetailsImpl actor) {
        requireManage(actor);
        if (req == null || req.getOrderIds() == null || req.getOrderIds().isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "ORDERS_REQUIRED", "Chọn ít nhất 1 đơn đã xuất kho", "orderIds");
        }
        List<Long> ids = req.getOrderIds().stream().filter(Objects::nonNull).distinct().sorted().toList();
        if (ids.isEmpty() || ids.size() > MAX_STOPS) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ORDER_COUNT",
                    "Một chuyến có từ 1 đến " + MAX_STOPS + " điểm giao", "orderIds");
        }
        String driver = req.getDriverName() != null ? req.getDriverName().trim() : "";
        if (!StringUtils.hasText(driver) || driver.length() > 100) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "DRIVER_REQUIRED",
                    "Vui lòng nhập tên tài xế / người giao (tối đa 100 ký tự)", "driverName");
        }
        String plate = clean(req.getVehiclePlate(), 20, "vehiclePlate");
        String phone = clean(req.getDriverPhone(), 20, "driverPhone");
        String note = clean(req.getNote(), 500, "note");
        LocalDateTime now = LocalDateTime.now(clock);
        if (req.getExpectedReturnAt() == null || !req.getExpectedReturnAt().isAfter(now)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_EXPECTED_RETURN",
                    "Thời điểm dự kiến xong chuyến phải sau thời điểm hiện tại", "expectedReturnAt");
        }

        // Khoá các đơn (theo id tăng dần) để 2 người không xếp cùng 1 đơn vào 2 chuyến
        List<SalesOrder> orders = orderRepository.findAllByIdForUpdate(ids);
        if (orders.size() != ids.size()) {
            throw BusinessException.notFound("Có đơn hàng không tồn tại trong danh sách");
        }
        Warehouse warehouse = null;
        for (SalesOrder o : orders) {
            if (!SalesOrder.STATUS_DISPATCHED.equals(o.getStatus())) {
                throw BusinessException.conflict("ORDER_NOT_DISPATCHED",
                        "Đơn " + o.getCode() + " chưa xuất kho hoặc đã giao xong (trạng thái " + o.getStatus() + ")", "orderIds");
            }
            if (o.getDeliveryTrip() != null) {
                throw BusinessException.conflict("ORDER_ALREADY_ON_TRIP",
                        "Đơn " + o.getCode() + " đã nằm trên chuyến " + o.getDeliveryTrip().getCode(), "orderIds");
            }
            if (o.getReservedWarehouse() == null) {
                throw BusinessException.conflict("ORDER_WAREHOUSE_UNKNOWN",
                        "Đơn " + o.getCode() + " chưa xác định kho xuất", "orderIds");
            }
            if (warehouse == null) {
                warehouse = o.getReservedWarehouse();
            } else if (!Objects.equals(warehouse.getId(), o.getReservedWarehouse().getId())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "ORDERS_DIFFERENT_WAREHOUSES",
                        "Các đơn trên 1 chuyến phải xuất từ cùng 1 kho", "orderIds");
            }
        }
        assertWarehouseAllowed(actor, warehouse);

        DeliveryTrip trip = tripRepository.save(DeliveryTrip.builder()
                .code(newCode(now.toLocalDate()))
                .warehouse(warehouse)
                .vehiclePlate(plate)
                .driverName(driver)
                .driverPhone(phone)
                .status(DeliveryTrip.STATUS_IN_PROGRESS)
                .departedAt(now)
                .expectedReturnAt(req.getExpectedReturnAt())
                .note(note)
                .createdByUsername(actor != null ? actor.getUsername() : null)
                .build());
        Map<Long, Integer> sequenceMap = new HashMap<>();
        if (req.getOrderIds() != null) {
            for (int i = 0; i < req.getOrderIds().size(); i++) {
                Long orderId = req.getOrderIds().get(i);
                if (orderId != null) {
                    sequenceMap.putIfAbsent(orderId, i + 1);
                }
            }
        }
        for (SalesOrder o : orders) {
            o.setDeliveryTrip(trip);
            o.setDeliveryStopSequence(sequenceMap.getOrDefault(o.getId(), 1));
        }
        orderRepository.saveAll(orders);
        auditLogService.record(AuditModule.ORDER, "CREATE_DELIVERY_TRIP", "DELIVERY_TRIP", trip.getId(), trip.getCode(),
                null, orders.size() + " điểm giao: " + String.join(",", orders.stream().map(SalesOrder::getCode).toList()),
                "Lập chuyến giao, tài xế " + driver + (plate != null ? ", xe " + plate : ""), actor);
        log.info("S6-07: Lập chuyến {} gồm {} đơn từ kho {}", trip.getCode(), orders.size(), warehouse.getCode());
        return toDetail(trip, orders, now);
    }

    // ======================= KẾT THÚC CHUYẾN =======================

    /**
     * Xe về kho: mọi điểm phải đã xử lý (đã giao / giao không thành công). Phần hàng chưa giao được của các đơn
     * trên chuyến được nhập lại kho đã xuất trong cùng transaction (ghi thẻ kho).
     */
    @Transactional
    public DeliveryTripDetailResponse complete(Long id, DeliveryTripCompleteRequest req, UserDetailsImpl actor) {
        requireManage(actor);
        String note = clean(req != null ? req.getNote() : null, 500, "note");
        DeliveryTrip trip = tripRepository.findByIdForUpdate(id)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy chuyến giao"));
        assertWarehouseAllowed(actor, trip.getWarehouse());
        if (!trip.isInProgress()) {
            throw BusinessException.conflict("TRIP_ALREADY_COMPLETED", "Chuyến " + trip.getCode() + " đã kết thúc", "status");
        }
        List<Long> orderIds = orderRepository.findIdsByDeliveryTripId(trip.getId());
        List<SalesOrder> orders = orderIds.isEmpty() ? List.of() : orderRepository.findAllByIdForUpdate(orderIds);
        List<String> pending = orders.stream()
                .filter(o -> SalesOrder.STATUS_DISPATCHED.equals(o.getStatus()))
                .map(SalesOrder::getCode).toList();
        if (!pending.isEmpty()) {
            throw BusinessException.conflict("TRIP_HAS_PENDING_STOPS",
                    "Còn " + pending.size() + " điểm chưa ghi nhận giao: " + String.join(", ", pending)
                            + ". Xác nhận giao hoặc ghi nhận giao không thành công trước khi kết thúc chuyến", "status");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        int returnedOrders = 0;
        for (SalesOrder o : orders) {
            if (o.getStockReturnedAt() != null || !RESOLVED_STATUSES.contains(o.getStatus())) {
                continue;
            }
            List<InventoryService.DispatchLine> returns = new ArrayList<>();
            for (SalesOrderLine l : o.getLines()) {
                BigDecimal delivered = l.getDeliveredQuantity() != null ? l.getDeliveredQuantity() : l.getQuantity();
                if (l.getQuantity() != null && delivered.compareTo(l.getQuantity()) < 0) {
                    returns.add(new InventoryService.DispatchLine(l, OrderDeliveryService.undeliveredBase(l)));
                }
            }
            if (!returns.isEmpty()) {
                inventoryService.returnDeliveredStock(o, returns,
                        "Chuyến " + trip.getCode() + " về kho: nhập lại hàng chưa giao được của đơn " + o.getCode(), actor);
                returnedOrders++;
            }
            o.setStockReturnedAt(now);
        }
        if (!orders.isEmpty()) {
            orderRepository.saveAll(orders);
        }
        trip.setStatus(DeliveryTrip.STATUS_COMPLETED);
        trip.setCompletedAt(now);
        trip.setCompletedByUsername(actor != null ? actor.getUsername() : null);
        if (note != null) {
            trip.setNote(note);
        }
        DeliveryTrip saved = tripRepository.save(trip);
        auditLogService.record(AuditModule.ORDER, "COMPLETE_DELIVERY_TRIP", "DELIVERY_TRIP", saved.getId(), saved.getCode(),
                DeliveryTrip.STATUS_IN_PROGRESS, DeliveryTrip.STATUS_COMPLETED,
                "Kết thúc chuyến, nhập lại kho hàng chưa giao của " + returnedOrders + " đơn"
                        + (note != null ? ". " + (note.length() > 300 ? note.substring(0, 300) : note) : ""), actor);
        log.info("S6-07: Kết thúc chuyến {}, nhập lại kho hàng chưa giao của {} đơn", saved.getCode(), returnedOrders);
        return toDetail(saved, orders, now);
    }

    // ======================= XEM =======================

    /** status = IN_PROGRESS (mặc định) | COMPLETED (30 ngày gần nhất). NV kho chỉ thấy chuyến của kho mình được gắn. */
    @Transactional(readOnly = true)
    public List<DeliveryTripSummaryResponse> list(String status, Long warehouseId, UserDetailsImpl actor) {
        String st = StringUtils.hasText(status) ? status.trim().toUpperCase(Locale.ROOT) : DeliveryTrip.STATUS_IN_PROGRESS;
        if (!DeliveryTrip.STATUS_IN_PROGRESS.equals(st) && !DeliveryTrip.STATUS_COMPLETED.equals(st)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_TRIP_STATUS",
                    "Trạng thái chuyến: IN_PROGRESS hoặc COMPLETED", "status");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        List<DeliveryTrip> trips = DeliveryTrip.STATUS_IN_PROGRESS.equals(st)
                ? tripRepository.findByStatusWithWarehouse(st)
                : tripRepository.findRecentByStatus(st, now.minusDays(RECENT_COMPLETED_DAYS));
        Set<Long> allowed = stockLedgerService.allowedWarehouseIds(actor);
        trips = trips.stream()
                .filter(t -> warehouseId == null || Objects.equals(t.getWarehouse().getId(), warehouseId))
                .filter(t -> allowed == null || allowed.contains(t.getWarehouse().getId()))
                .toList();
        Map<Long, List<SalesOrder>> byTrip = ordersByTrip(trips);
        List<DeliveryTripSummaryResponse> result = new ArrayList<>();
        for (DeliveryTrip t : trips) {
            result.add(toSummary(t, byTrip.getOrDefault(t.getId(), List.of()), now));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public DeliveryTripDetailResponse get(Long id, UserDetailsImpl actor) {
        DeliveryTrip trip = tripRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy chuyến giao"));
        Set<Long> allowed = stockLedgerService.allowedWarehouseIds(actor);
        if (allowed != null && !allowed.contains(trip.getWarehouse().getId())) {
            // Không lộ chuyến của kho khác
            throw BusinessException.notFound("Không tìm thấy chuyến giao");
        }
        return toDetail(trip, orderRepository.findByDeliveryTripIds(List.of(trip.getId())), LocalDateTime.now(clock));
    }

    /** Tổng quan: số chuyến đang chạy / trễ, số đơn và tiền hàng đang đi đường, đơn đã xuất chưa xếp chuyến. */
    @Transactional(readOnly = true)
    public InTransitSummaryResponse summary(UserDetailsImpl actor) {
        LocalDateTime now = LocalDateTime.now(clock);
        Set<Long> allowed = stockLedgerService.allowedWarehouseIds(actor);
        List<DeliveryTrip> trips = tripRepository.findByStatusWithWarehouse(DeliveryTrip.STATUS_IN_PROGRESS).stream()
                .filter(t -> allowed == null || allowed.contains(t.getWarehouse().getId()))
                .toList();
        Map<Long, List<SalesOrder>> byTrip = ordersByTrip(trips);
        int overdue = 0;
        int ordersOnRoad = 0;
        BigDecimal value = BigDecimal.ZERO;
        for (DeliveryTrip t : trips) {
            DeliveryTripSummaryResponse s = toSummary(t, byTrip.getOrDefault(t.getId(), List.of()), now);
            if (s.overdue()) {
                overdue++;
            }
            ordersOnRoad += s.remainingStops();
            value = value.add(s.valueOnRoad());
        }
        List<SalesOrder> unassigned = orderRepository.findByStatusWithoutTrip(SalesOrder.STATUS_DISPATCHED).stream()
                .filter(o -> allowed == null || (o.getReservedWarehouse() != null && allowed.contains(o.getReservedWarehouse().getId())))
                .toList();
        BigDecimal unassignedValue = BigDecimal.ZERO;
        for (SalesOrder o : unassigned) {
            unassignedValue = unassignedValue.add(nz(o.getTotalAmount()));
        }
        return new InTransitSummaryResponse(trips.size(), overdue, ordersOnRoad, money(value), unassigned.size(),
                money(unassignedValue));
    }

    // ======================= TIỆN ÍCH =======================

    /** Đơn đang thuộc 1 chuyến còn chạy: hàng giao không được chưa nhập lại kho (chờ kết thúc chuyến). */
    public static boolean onActiveTrip(SalesOrder o) {
        return o != null && o.getDeliveryTrip() != null && o.getDeliveryTrip().isInProgress();
    }

    /** Tiền hàng của đơn còn nằm trên xe. */
    static BigDecimal valueOnRoad(SalesOrder o) {
        if (SalesOrder.STATUS_DISPATCHED.equals(o.getStatus())) {
            return nz(o.getTotalAmount());
        }
        if (o.getStockReturnedAt() != null || !RESOLVED_STATUSES.contains(o.getStatus())) {
            return BigDecimal.ZERO;
        }
        BigDecimal v = BigDecimal.ZERO;
        for (SalesOrderLine l : o.getLines()) {
            BigDecimal q = l.getQuantity();
            BigDecimal d = l.getDeliveredQuantity() != null ? l.getDeliveredQuantity() : q;
            if (q != null && q.signum() > 0 && d.compareTo(q) < 0) {
                v = v.add(OrderStatusService.prorate(nz(l.getNetAmount()), q.subtract(d), q));
            }
        }
        return v;
    }

    private Map<Long, List<SalesOrder>> ordersByTrip(List<DeliveryTrip> trips) {
        Map<Long, List<SalesOrder>> byTrip = new HashMap<>();
        if (trips.isEmpty()) {
            return byTrip;
        }
        List<Long> ids = trips.stream().map(DeliveryTrip::getId).toList();
        for (SalesOrder o : orderRepository.findByDeliveryTripIds(ids)) {
            byTrip.computeIfAbsent(o.getDeliveryTrip().getId(), k -> new ArrayList<>()).add(o);
        }
        return byTrip;
    }

    DeliveryTripSummaryResponse toSummary(DeliveryTrip t, List<SalesOrder> orders, LocalDateTime now) {
        int delivered = 0;
        int failed = 0;
        int remaining = 0;
        BigDecimal value = BigDecimal.ZERO;
        for (SalesOrder o : orders) {
            String st = o.getStatus();
            if (SalesOrder.STATUS_DISPATCHED.equals(st)) {
                remaining++;
            } else if (SalesOrder.STATUS_DELIVERY_FAILED.equals(st)) {
                failed++;
            } else if (SalesOrder.STATUS_DELIVERED.equals(st) || SalesOrder.STATUS_CLOSED.equals(st)) {
                delivered++;
            }
            if (t.isInProgress()) {
                value = value.add(valueOnRoad(o));
            }
        }
        boolean overdue = t.isInProgress() && t.getExpectedReturnAt() != null && now.isAfter(t.getExpectedReturnAt());
        long overdueMinutes = overdue ? Duration.between(t.getExpectedReturnAt(), now).toMinutes() : 0;
        Warehouse w = t.getWarehouse();
        return new DeliveryTripSummaryResponse(t.getId(), t.getCode(), w != null ? w.getId() : null,
                w != null ? w.getCode() : null, w != null ? w.getName() : null, t.getVehiclePlate(), t.getDriverName(),
                t.getDriverPhone(), t.getStatus(), t.getDepartedAt(), t.getExpectedReturnAt(), t.getCompletedAt(),
                orders.size(), delivered, failed, remaining, money(value), overdue, overdueMinutes, t.getNote());
    }

    /**
     * S6-06 AC1: Lấy danh sách các đơn đã xuất kho (DISPATCHED) sẵn sàng gom chuyến theo khu vực.
     */
    @Transactional(readOnly = true)
    public List<DeliveryTripCandidateOrderResponse> getCandidateOrders(Long warehouseId, Long regionId, String search, UserDetailsImpl actor) {
        Set<Long> allowed = stockLedgerService.allowedWarehouseIds(actor);
        if (warehouseId != null && allowed != null && !allowed.contains(warehouseId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "TRIP_WAREHOUSE_NOT_ASSIGNED",
                    "Bạn không được phân công kho chỉ định", null);
        }

        List<SalesOrder> candidates = orderRepository.findCandidateOrdersWithDetails(SalesOrder.STATUS_DISPATCHED);

        // Lọc theo kho và quyền kho
        candidates = candidates.stream()
                .filter(o -> o.getReservedWarehouse() != null)
                .filter(o -> warehouseId == null || Objects.equals(o.getReservedWarehouse().getId(), warehouseId))
                .filter(o -> allowed == null || allowed.contains(o.getReservedWarehouse().getId()))
                .toList();

        // Lọc theo khu vực bán hàng (AC1: gom theo khu vực)
        if (regionId != null) {
            candidates = candidates.stream()
                    .filter(o -> o.getCustomer() != null && o.getCustomer().getRegion() != null
                            && Objects.equals(o.getCustomer().getRegion().getId(), regionId))
                    .toList();
        }

        // Lọc theo từ khóa tìm kiếm (mã đơn, khách hàng, địa chỉ, người nhận, số điện thoại)
        if (StringUtils.hasText(search)) {
            String kw = search.trim().toLowerCase(Locale.ROOT);
            candidates = candidates.stream().filter(o -> {
                String code = o.getCode() != null ? o.getCode().toLowerCase(Locale.ROOT) : "";
                Customer c = o.getCustomer();
                String cCode = c != null && c.getCode() != null ? c.getCode().toLowerCase(Locale.ROOT) : "";
                String cName = c != null && c.getName() != null ? c.getName().toLowerCase(Locale.ROOT) : "";
                CustomerDeliveryAddress a = o.getDeliveryAddress();
                String addr = a != null && a.getAddress() != null ? a.getAddress().toLowerCase(Locale.ROOT)
                        : (c != null && c.getAddress() != null ? c.getAddress().toLowerCase(Locale.ROOT) : "");
                String phone = a != null && a.getReceiverPhone() != null ? a.getReceiverPhone().toLowerCase(Locale.ROOT)
                        : (c != null && c.getPhone() != null ? c.getPhone().toLowerCase(Locale.ROOT) : "");
                String rName = a != null && a.getReceiverName() != null ? a.getReceiverName().toLowerCase(Locale.ROOT) : "";
                return code.contains(kw) || cCode.contains(kw) || cName.contains(kw) || addr.contains(kw) || phone.contains(kw) || rName.contains(kw);
            }).toList();
        }

        List<DeliveryTripCandidateOrderResponse> result = new ArrayList<>();
        for (SalesOrder o : candidates) {
            Customer c = o.getCustomer();
            CustomerDeliveryAddress a = o.getDeliveryAddress();
            Warehouse w = o.getReservedWarehouse();
            Region r = c != null ? c.getRegion() : null;

            String addr = a != null && StringUtils.hasText(a.getAddress()) ? a.getAddress() : (c != null ? c.getAddress() : "");
            String rName = StringUtils.hasText(o.getReceiverName()) ? o.getReceiverName()
                    : (a != null && StringUtils.hasText(a.getReceiverName()) ? a.getReceiverName() : (c != null ? c.getName() : ""));
            String rPhone = a != null && StringUtils.hasText(a.getReceiverPhone()) ? a.getReceiverPhone()
                    : (c != null && StringUtils.hasText(c.getPhone()) ? c.getPhone() : "");

            String district = extractDistrict(addr, r != null ? r.getName() : null);
            String province = extractProvince(addr, r != null ? r.getName() : null);

            result.add(new DeliveryTripCandidateOrderResponse(
                    o.getId(),
                    o.getCode(),
                    c != null ? c.getId() : null,
                    c != null ? c.getCode() : null,
                    c != null ? c.getName() : null,
                    r != null ? r.getId() : null,
                    r != null ? r.getName() : null,
                    district,
                    province,
                    addr,
                    rName,
                    rPhone,
                    money(o.getTotalAmount()),
                    o.getStatus(),
                    w != null ? w.getId() : null,
                    w != null ? w.getCode() : null,
                    w != null ? w.getName() : null,
                    o.getDesiredDeliveryDate(),
                    o.getDispatchedAt(),
                    o.getDispatchedByUsername()
            ));
        }
        return result;
    }

    /**
     * S6-06 AC3: In danh sách điểm giao của chuyến kèm địa chỉ và số điện thoại người nhận (Bảng kê giao hàng / Delivery Manifest).
     */
    @Transactional(readOnly = true)
    public DeliveryTripManifestResponse getManifest(Long id, UserDetailsImpl actor) {
        DeliveryTrip trip = tripRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy chuyến giao"));
        Set<Long> allowed = stockLedgerService.allowedWarehouseIds(actor);
        if (allowed != null && !allowed.contains(trip.getWarehouse().getId())) {
            throw BusinessException.notFound("Không tìm thấy chuyến giao");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        List<SalesOrder> orders = orderRepository.findByDeliveryTripIds(List.of(trip.getId()));
        DeliveryTripDetailResponse detail = toDetail(trip, orders, now);

        BigDecimal totalAmount = BigDecimal.ZERO;
        for (DeliveryTripStopResponse stop : detail.stops()) {
            totalAmount = totalAmount.add(stop.totalAmount() != null ? stop.totalAmount() : BigDecimal.ZERO);
        }

        Warehouse w = trip.getWarehouse();
        String printActor = actor != null ? actor.getUsername() : "Hệ thống";

        return new DeliveryTripManifestResponse(
                trip.getId(),
                trip.getCode(),
                w != null ? w.getId() : null,
                w != null ? w.getCode() : null,
                w != null ? w.getName() : null,
                trip.getVehiclePlate(),
                trip.getDriverName(),
                trip.getDriverPhone(),
                trip.getStatus(),
                trip.getDepartedAt(),
                trip.getExpectedReturnAt(),
                detail.stops().size(),
                money(totalAmount),
                trip.getNote(),
                detail.stops(),
                now,
                printActor
        );
    }

    private DeliveryTripDetailResponse toDetail(DeliveryTrip t, List<SalesOrder> orders, LocalDateTime now) {
        List<SalesOrder> sortedOrders = orders.stream()
                .sorted(Comparator.comparing(
                        (SalesOrder o) -> o.getDeliveryStopSequence() != null ? o.getDeliveryStopSequence() : Integer.MAX_VALUE)
                        .thenComparing(SalesOrder::getId))
                .toList();

        List<DeliveryTripStopResponse> stops = new ArrayList<>();
        for (int idx = 0; idx < sortedOrders.size(); idx++) {
            SalesOrder o = sortedOrders.get(idx);
            Customer c = o.getCustomer();
            CustomerDeliveryAddress a = o.getDeliveryAddress();
            Region r = c != null ? c.getRegion() : null;

            String addr = a != null && StringUtils.hasText(a.getAddress()) ? a.getAddress() : (c != null ? c.getAddress() : null);
            String rName = StringUtils.hasText(o.getReceiverName()) ? o.getReceiverName()
                    : (a != null && StringUtils.hasText(a.getReceiverName()) ? a.getReceiverName() : (c != null ? c.getName() : null));
            String rPhone = a != null && StringUtils.hasText(a.getReceiverPhone()) ? a.getReceiverPhone()
                    : (c != null && StringUtils.hasText(c.getPhone()) ? c.getPhone() : null);

            int seq = o.getDeliveryStopSequence() != null ? o.getDeliveryStopSequence() : (idx + 1);
            String district = extractDistrict(addr, r != null ? r.getName() : null);
            String province = extractProvince(addr, r != null ? r.getName() : null);

            stops.add(new DeliveryTripStopResponse(
                    o.getId(),
                    o.getCode(),
                    c != null ? c.getId() : null,
                    c != null ? c.getCode() : null,
                    c != null ? c.getName() : null,
                    addr,
                    rName,
                    rPhone,
                    district,
                    province,
                    seq,
                    o.getStatus(),
                    o.getTotalAmount(),
                    money(t.isInProgress() ? valueOnRoad(o) : BigDecimal.ZERO),
                    o.getDeliveredAt(),
                    o.getDeliveryFailedReason(),
                    o.getStockReturnedAt() != null
            ));
        }
        return new DeliveryTripDetailResponse(toSummary(t, orders, now), stops);
    }

    static String extractDistrict(String addr, String fallbackRegion) {
        if (StringUtils.hasText(addr)) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(Quận|Huyện|Thị xã)\\s+([^,]+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(addr);
            if (m.find()) {
                return m.group(0).trim();
            }
        }
        return StringUtils.hasText(fallbackRegion) ? fallbackRegion.trim() : "Nội thành";
    }

    static String extractProvince(String addr, String fallbackRegion) {
        if (StringUtils.hasText(addr)) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(Hà Nội|TP\\.?\\s*Hồ Chí Minh|Hồ Chí Minh|Sài Gòn|Đà Nẵng|Hải Phòng|Cần Thơ|Bình Dương|Đồng Nai)", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(addr);
            if (m.find()) {
                return m.group(0).trim();
            }
        }
        return "Hà Nội";
    }

    private void assertWarehouseAllowed(UserDetailsImpl actor, Warehouse warehouse) {
        Set<Long> allowed = stockLedgerService.allowedWarehouseIds(actor);
        if (warehouse != null && allowed != null && !allowed.contains(warehouse.getId())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "TRIP_WAREHOUSE_NOT_ASSIGNED",
                    "Bạn không được phân công kho " + warehouse.getName() + " nên không thao tác chuyến giao của kho này", null);
        }
    }

    private static void requireManage(UserDetailsImpl actor) {
        boolean ok = actor != null && actor.getAuthorities() != null
                && actor.getAuthorities().stream().anyMatch(a -> MANAGE_ROLES.contains(a.getAuthority()));
        if (!ok) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "TRIP_NOT_ALLOWED",
                    "Chỉ bộ phận kho được lập / kết thúc chuyến giao", null);
        }
    }

    private String newCode(LocalDate date) {
        String prefix = "CG" + date.format(DateTimeFormatter.ofPattern("yyMMdd")) + "-";
        for (int attempt = 0; attempt < 20; attempt++) {
            StringBuilder sb = new StringBuilder(prefix);
            for (int i = 0; i < 4; i++) {
                sb.append(CODE_CHARS.charAt(RANDOM.nextInt(CODE_CHARS.length())));
            }
            if (!tripRepository.existsByCode(sb.toString())) {
                return sb.toString();
            }
        }
        throw new IllegalStateException("Không sinh được mã chuyến giao, vui lòng thử lại");
    }

    private static String clean(String s, int max, String field) {
        if (!StringUtils.hasText(s)) {
            return null;
        }
        String t = s.trim();
        if (t.length() > max) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "TEXT_TOO_LONG", "Tối đa " + max + " ký tự", field);
        }
        return t;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal money(BigDecimal v) {
        return nz(v).setScale(2, RoundingMode.HALF_UP);
    }
}
