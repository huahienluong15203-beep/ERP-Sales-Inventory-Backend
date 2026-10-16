package com.erp.backend.service;

import com.erp.backend.dto.inventory.picking.*;
import com.erp.backend.dto.user.PageResponse;
import com.erp.backend.entity.*;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.*;
import com.erp.backend.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service xử lý nghiệp vụ Gom đơn, Gộp mặt hàng & Sắp xếp theo đường đi trong kho (S6-03 / SCRUM-88).
 * Tuân thủ quy chuẩn Backend Engineering: không hardcode mã nhân viên, quản lý transaction chuẩn ACID.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PickingListService {

    private static final String CODE_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PickingListRepository pickingListRepository;
    private final PickingListItemRepository pickingListItemRepository;
    private final SalesOrderRepository salesOrderRepository;
    private final WarehouseRepository warehouseRepository;
    private final WarehouseLocationRepository locationRepository;
    private final ProductLotRepository productLotRepository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;

    // =========================================================================
    // 1. DANH SÁCH ĐƠN KHẢ DỤNG & XEM TRƯỚC (PREVIEW) GOM ĐƠN (S6-03)
    // =========================================================================

    /**
     * Lấy danh sách các đơn hàng đã duyệt tại kho đang chờ được gom vào đợt soạn hàng.
     */
    @Transactional(readOnly = true)
    public List<AvailablePickingOrderResponse> getAvailableOrdersForPicking(Long warehouseId, UserDetailsImpl actor) {
        assertWarehouseAccess(warehouseId, actor);

        Warehouse warehouse = warehouseRepository.findById(warehouseId)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy kho với ID: " + warehouseId));

        // Lấy các đơn hàng có trạng thái APPROVED hoặc PICKING (chờ soạn hàng) thuộc kho này
        List<SalesOrder> candidateOrders = salesOrderRepository.findAll((root, query, cb) -> cb.and(
                cb.equal(root.get("reservedWarehouse").get("id"), warehouseId),
                root.get("status").in(SalesOrder.STATUS_APPROVED, SalesOrder.STATUS_PICKING)
        ), Sort.by(Sort.Direction.ASC, "approvedAt"));

        if (candidateOrders.isEmpty()) {
            return Collections.emptyList();
        }

        // Lọc bỏ các đơn đã nằm trong phiếu soạn hàng đang hoạt động (ASSIGNED, IN_PROGRESS)
        List<Long> orderIds = candidateOrders.stream().map(SalesOrder::getId).collect(Collectors.toList());
        List<Long> activeOrderIds = pickingListRepository.findOrderIdsInActivePickingList(
                orderIds, List.of(PickingList.STATUS_ASSIGNED, PickingList.STATUS_IN_PROGRESS));
        Set<Long> busyOrderIds = new HashSet<>(activeOrderIds);

        return candidateOrders.stream()
                .filter(o -> !busyOrderIds.contains(o.getId()))
                .map(o -> AvailablePickingOrderResponse.builder()
                        .orderId(o.getId())
                        .orderCode(o.getCode())
                        .customerId(o.getCustomer() != null ? o.getCustomer().getId() : null)
                        .customerCode(o.getCustomer() != null ? o.getCustomer().getCode() : null)
                        .customerName(o.getCustomer() != null ? o.getCustomer().getName() : null)
                        .desiredDeliveryDate(o.getDesiredDeliveryDate())
                        .totalLines(o.getLines() != null ? o.getLines().size() : 0)
                        .totalAmount(o.getTotalAmount())
                        .approvedAt(o.getApprovedAt())
                        .status(o.getStatus())
                        .warehouseId(warehouse.getId())
                        .warehouseName(warehouse.getName())
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * Xem trước (Preview) kết quả gom đơn, gộp mặt hàng và sắp xếp theo đường đi trong kho.
     */
    @Transactional(readOnly = true)
    public PickingListPreviewResponse previewPickingList(PickingListPreviewRequest request, UserDetailsImpl actor) {
        assertWarehouseAccess(request.getWarehouseId(), actor);

        Warehouse warehouse = warehouseRepository.findById(request.getWarehouseId())
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy kho với ID: " + request.getWarehouseId()));

        List<SalesOrder> orders = loadAndValidateOrders(request.getOrderIds(), warehouse.getId());
        List<GroupedItemHolder> groupedHolders = aggregateAndRouteItems(orders, warehouse.getId());

        BigDecimal totalQuantity = BigDecimal.ZERO;
        List<PickingListItemResponse> itemResponses = new ArrayList<>();

        for (GroupedItemHolder holder : groupedHolders) {
            totalQuantity = totalQuantity.add(holder.totalQuantity);
            itemResponses.add(holder.toResponse());
        }

        List<String> orderCodes = orders.stream().map(SalesOrder::getCode).collect(Collectors.toList());

        return PickingListPreviewResponse.builder()
                .warehouseId(warehouse.getId())
                .warehouseCode(warehouse.getCode())
                .warehouseName(warehouse.getName())
                .totalOrders(orders.size())
                .totalItems(itemResponses.size())
                .totalQuantity(totalQuantity)
                .orderCodes(orderCodes)
                .items(itemResponses)
                .build();
    }

    // =========================================================================
    // 2. TẠO MỚI PHIẾU SOẠN HÀNG (S6-03)
    // =========================================================================

    /**
     * Tạo mới phiếu soạn hàng từ các đơn đã duyệt, gộp mặt hàng và sắp xếp theo đường đi.
     */
    @Transactional(rollbackFor = Exception.class)
    public PickingListResponse createPickingList(PickingListCreateRequest request, UserDetailsImpl actor) {
        assertWarehouseAccess(request.getWarehouseId(), actor);

        Warehouse warehouse = warehouseRepository.findById(request.getWarehouseId())
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy kho với ID: " + request.getWarehouseId()));

        List<SalesOrder> orders = loadAndValidateOrders(request.getOrderIds(), warehouse.getId());

        // Kiểm tra xem có đơn nào đã nằm trong phiếu soạn hàng đang hoạt động không
        List<Long> orderIds = orders.stream().map(SalesOrder::getId).collect(Collectors.toList());
        List<Long> busyIds = pickingListRepository.findOrderIdsInActivePickingList(
                orderIds, List.of(PickingList.STATUS_ASSIGNED, PickingList.STATUS_IN_PROGRESS));
        if (!busyIds.isEmpty()) {
            throw BusinessException.conflict("ORDER_ALREADY_IN_PICKING_LIST",
                    "Một hoặc nhiều đơn hàng được chọn đã nằm trong một đợt soạn hàng khác đang hoạt động.", "orderIds");
        }

        User picker = null;
        String pickerName = null;
        if (request.getPickerId() != null) {
            picker = userRepository.findById(request.getPickerId())
                    .orElseThrow(() -> BusinessException.notFound("Không tìm thấy nhân viên soạn hàng với ID: " + request.getPickerId()));
            pickerName = picker.getFullName();
        } else if (actor != null) {
            pickerName = actor.getFullName();
        }

        String code = generatePickingCode();

        PickingList pickingList = PickingList.builder()
                .code(code)
                .warehouse(warehouse)
                .status(PickingList.STATUS_ASSIGNED)
                .picker(picker)
                .pickerName(pickerName)
                .note(request.getNote())
                .totalOrders(orders.size())
                .startedAt(LocalDateTime.now())
                .createdById(actor != null ? actor.getId() : null)
                .createdByUsername(actor != null ? actor.getUsername() : null)
                .build();

        // 1. Thêm các đơn hàng được gom
        for (SalesOrder o : orders) {
            PickingListOrder plo = PickingListOrder.builder()
                    .order(o)
                    .orderCode(o.getCode())
                    .customerName(o.getCustomer() != null ? o.getCustomer().getName() : null)
                    .totalLines(o.getLines() != null ? o.getLines().size() : 0)
                    .totalAmount(o.getTotalAmount())
                    .build();
            pickingList.addOrder(plo);

            // Chuyển trạng thái đơn hàng sang PICKING nếu đang APPROVED
            if (SalesOrder.STATUS_APPROVED.equals(o.getStatus())) {
                o.setStatus(SalesOrder.STATUS_PICKING);
                salesOrderRepository.save(o);
                log.info("Transitioned order {} to status PICKING for picking list {}", o.getCode(), code);
            }
        }

        // 2. Gộp mặt hàng & sắp xếp theo đường đi trong kho
        List<GroupedItemHolder> groupedHolders = aggregateAndRouteItems(orders, warehouse.getId());

        BigDecimal totalRequestedQty = BigDecimal.ZERO;
        for (GroupedItemHolder holder : groupedHolders) {
            totalRequestedQty = totalRequestedQty.add(holder.totalQuantity);
            PickingListItem item = holder.toEntity(pickingList);
            pickingList.addItem(item);
        }

        pickingList.setTotalItems(groupedHolders.size());
        pickingList.setTotalRequestedQuantity(totalRequestedQty);
        pickingList.setTotalPickedQuantity(BigDecimal.ZERO);

        PickingList saved = pickingListRepository.save(pickingList);

        if (auditLogService != null) {
            auditLogService.record(AuditModule.INVENTORY, "CREATE_PICKING_LIST", "PICKING_LIST", saved.getId(),
                    saved.getCode(), null, "Created picking list with " + orders.size() + " orders",
                    "Tạo phiếu soạn hàng gộp đơn theo đường đi kho", actor);
        }

        return mapToResponse(saved);
    }

    // =========================================================================
    // 3. TRA CỨU & XEM CHI TIẾT PHIẾU SOẠN HÀNG (S6-03)
    // =========================================================================

    /**
     * Tra cứu danh sách phiếu soạn hàng có phân trang và bộ lọc.
     */
    @Transactional(readOnly = true)
    public PageResponse<PickingListResponse> searchPickingLists(
            Long warehouseId,
            String status,
            Long pickerId,
            String keyword,
            Pageable pageable,
            UserDetailsImpl actor) {

        assertWarehouseAccess(warehouseId, actor);
        Set<Long> allowedWarehouses = allowedWarehouseIds(actor);

        Specification<PickingList> spec = PickingListSpecifications.filter(
                allowedWarehouses,
                warehouseId,
                status,
                pickerId,
                keyword
        );

        int pageSize = Math.min(pageable.getPageSize(), 100);
        Pageable safePageable = PageRequest.of(pageable.getPageNumber(), pageSize, pageable.getSort());

        Page<PickingList> pageResult = pickingListRepository.findAll(spec, safePageable);
        List<PickingListResponse> content = pageResult.getContent().stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());

        return new PageResponse<>(content, pageResult.getNumber(), pageResult.getSize(),
                pageResult.getTotalElements(), pageResult.getTotalPages());
    }

    /**
     * Xem chi tiết một phiếu soạn hàng theo ID.
     */
    @Transactional(readOnly = true)
    public PickingListResponse getPickingListById(Long id, UserDetailsImpl actor) {
        PickingList pickingList = pickingListRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy phiếu soạn hàng với ID: " + id));

        assertWarehouseAccess(pickingList.getWarehouse().getId(), actor);
        return mapToResponse(pickingList);
    }

    // =========================================================================
    // 4. CẬP NHẬT TIẾN ĐỘ SOẠN HÀNG & RÀNG BUỘC SOẠN THIẾU (S6-03)
    // =========================================================================

    /**
     * Cập nhật tiến độ soạn cho một dòng hàng cụ thể (tối ưu cho thao tác trên Mobile).
     * BẮT BUỘC có lý do nếu số lượng thực soạn < số lượng yêu cầu.
     */
    @Transactional(rollbackFor = Exception.class)
    public PickingListResponse updateItemProgress(
            Long pickingListId,
            Long itemId,
            PickingItemUpdateRequest request,
            UserDetailsImpl actor) {

        PickingList pickingList = pickingListRepository.findById(pickingListId)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy phiếu soạn hàng với ID: " + pickingListId));

        assertWarehouseAccess(pickingList.getWarehouse().getId(), actor);

        if (PickingList.STATUS_COMPLETED.equals(pickingList.getStatus())) {
            throw BusinessException.badRequest("ALREADY_COMPLETED", "Phiếu soạn hàng đã hoàn thành, không thể chỉnh sửa.");
        }
        if (PickingList.STATUS_CANCELLED.equals(pickingList.getStatus())) {
            throw BusinessException.badRequest("ALREADY_CANCELLED", "Phiếu soạn hàng đã bị hủy, không thể chỉnh sửa.");
        }

        PickingListItem item = pickingList.getItems().stream()
                .filter(i -> i.getId().equals(itemId))
                .findFirst()
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy dòng hàng với ID " + itemId + " trong phiếu soạn hàng này."));

        BigDecimal pickedQty = request.getPickedQuantity();
        BigDecimal requestedQty = item.getRequestedQuantity();

        // RÀNG BUỘC S6-03: Soạn dư bị chặn
        if (pickedQty.compareTo(requestedQty) > 0) {
            throw BusinessException.badRequest("PICKED_EXCEEDS_REQUESTED",
                    "Số lượng thực soạn (" + pickedQty + ") không được vượt quá số lượng yêu cầu (" + requestedQty
                            + ") cho mặt hàng '" + item.getProductName() + "'.");
        }

        // RÀNG BUỘC S6-03: Soạn thiếu bắt buộc nhập lý do
        if (pickedQty.compareTo(requestedQty) < 0) {
            if (!StringUtils.hasText(request.getShortageReason()) || request.getShortageReason().trim().length() < 5) {
                throw BusinessException.badRequest("SHORTAGE_REASON_REQUIRED",
                        "Số lượng thực soạn (" + pickedQty + ") nhỏ hơn số lượng yêu cầu (" + requestedQty
                                + ") cho mặt hàng '" + item.getProductName() + "'. Bắt buộc nhập lý do soạn thiếu (tối thiểu 5 ký tự).");
            }
            item.setShortageReason(request.getShortageReason().trim());
        } else {
            item.setShortageReason(null);
        }

        item.setPickedQuantity(pickedQty);
        item.setIsPicked(Boolean.TRUE.equals(request.getIsPicked()) || pickedQty.compareTo(BigDecimal.ZERO) > 0);
        if (StringUtils.hasText(request.getActualBatchNumber())) {
            validateAndApplyBatchNumber(item, pickingList.getWarehouse(), request.getActualBatchNumber(), request.getLotChangeReason());
        }
        if (request.getNotes() != null) {
            item.setNotes(request.getNotes().trim());
        }

        // Tự động phân bổ số lượng thực soạn về các đơn con theo thứ tự
        distributePickedQuantityToAllocations(item);

        // Chuyển trạng thái phiếu sang IN_PROGRESS nếu đang ASSIGNED
        if (PickingList.STATUS_ASSIGNED.equals(pickingList.getStatus())) {
            pickingList.setStatus(PickingList.STATUS_IN_PROGRESS);
        }

        recalculateTotals(pickingList);
        PickingList saved = pickingListRepository.save(pickingList);

        return mapToResponse(saved);
    }

    /**
     * Cập nhật tiến độ hàng loạt cho nhiều dòng hàng trong đợt soạn.
     */
    @Transactional(rollbackFor = Exception.class)
    public PickingListResponse updateProgress(
            Long pickingListId,
            PickingProgressUpdateRequest request,
            UserDetailsImpl actor) {

        PickingList pickingList = pickingListRepository.findById(pickingListId)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy phiếu soạn hàng với ID: " + pickingListId));

        assertWarehouseAccess(pickingList.getWarehouse().getId(), actor);

        if (PickingList.STATUS_COMPLETED.equals(pickingList.getStatus()) || PickingList.STATUS_CANCELLED.equals(pickingList.getStatus())) {
            throw BusinessException.badRequest("INVALID_STATUS", "Không thể cập nhật phiếu soạn hàng ở trạng thái " + pickingList.getStatus());
        }

        Map<Long, PickingListItem> itemMap = pickingList.getItems().stream()
                .collect(Collectors.toMap(PickingListItem::getId, i -> i));

        for (PickingProgressUpdateRequest.ItemProgress progress : request.getItems()) {
            PickingListItem item = itemMap.get(progress.getItemId());
            if (item == null) {
                throw BusinessException.notFound("Không tìm thấy dòng hàng với ID " + progress.getItemId() + " trong phiếu soạn hàng này.");
            }

            if (progress.getPickedQuantity() != null && progress.getPickedQuantity().signum() < 0) {
                throw BusinessException.badRequest("INVALID_PICKED_QUANTITY",
                        "Số lượng thực soạn không được âm cho mặt hàng '" + item.getProductName() + "'.");
            }

            BigDecimal picked = progress.getPickedQuantity() != null ? progress.getPickedQuantity() : BigDecimal.ZERO;
            if (picked.compareTo(item.getRequestedQuantity()) > 0) {
                throw BusinessException.badRequest("PICKED_EXCEEDS_REQUESTED",
                        "Mặt hàng '" + item.getProductName() + "' soạn vượt quá số lượng yêu cầu (" + picked
                                + " / " + item.getRequestedQuantity() + ").");
            }

            if (picked.compareTo(item.getRequestedQuantity()) < 0) {
                if (!StringUtils.hasText(progress.getShortageReason()) || progress.getShortageReason().trim().length() < 5) {
                    throw BusinessException.badRequest("SHORTAGE_REASON_REQUIRED",
                            "Mặt hàng '" + item.getProductName() + "' soạn thiếu (" + picked + " / "
                                    + item.getRequestedQuantity() + "). Bắt buộc nhập lý do soạn thiếu (tối thiểu 5 ký tự).");
                }
                item.setShortageReason(progress.getShortageReason().trim());
            } else {
                item.setShortageReason(null);
            }

            item.setPickedQuantity(picked);
            item.setIsPicked(Boolean.TRUE.equals(progress.getIsPicked()) || picked.compareTo(BigDecimal.ZERO) > 0);
            if (StringUtils.hasText(progress.getActualBatchNumber())) {
                validateAndApplyBatchNumber(item, pickingList.getWarehouse(), progress.getActualBatchNumber(), progress.getLotChangeReason());
            }
            if (progress.getNotes() != null) {
                item.setNotes(progress.getNotes().trim());
            }

            distributePickedQuantityToAllocations(item);
        }

        if (PickingList.STATUS_ASSIGNED.equals(pickingList.getStatus())) {
            pickingList.setStatus(PickingList.STATUS_IN_PROGRESS);
        }

        recalculateTotals(pickingList);

        // Tự động chốt hoàn tất nếu yêu cầu và tất cả dòng đã soạn xong
        if (Boolean.TRUE.equals(request.getCompleteIfAllPicked())) {
            boolean allPicked = pickingList.getItems().stream().allMatch(i -> Boolean.TRUE.equals(i.getIsPicked()));
            if (allPicked) {
                applyPickingCompletion(pickingList, actor, "Hoàn thành phiếu soạn hàng (tự động qua cập nhật hàng loạt)");
            }
        }

        PickingList saved = pickingListRepository.save(pickingList);
        return mapToResponse(saved);
    }

    /**
     * Chốt hoàn thành phiếu soạn hàng (COMPLETED).
     */
    @Transactional(rollbackFor = Exception.class)
    public PickingListResponse completePickingList(Long id, UserDetailsImpl actor) {
        PickingList pickingList = pickingListRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy phiếu soạn hàng với ID: " + id));

        assertWarehouseAccess(pickingList.getWarehouse().getId(), actor);

        if (PickingList.STATUS_COMPLETED.equals(pickingList.getStatus())) {
            return mapToResponse(pickingList);
        }
        if (PickingList.STATUS_CANCELLED.equals(pickingList.getStatus())) {
            throw BusinessException.badRequest("ALREADY_CANCELLED", "Không thể hoàn thành phiếu đã bị hủy.");
        }

        applyPickingCompletion(pickingList, actor, "Hoàn thành phiếu soạn hàng");
        PickingList saved = pickingListRepository.save(pickingList);
        return mapToResponse(saved);
    }

    /**
     * S6-03 & S6-04: Xử lý nghiệp vụ hoàn tất phiếu soạn hàng:
     * - Kiểm tra lý do soạn thiếu cho các dòng chưa đủ số lượng
     * - Đồng bộ số lô thực xuất và HSD thực tế về các dòng đơn hàng
     * - Cập nhật trạng thái phiếu COMPLETED và ghi nhận audit log
     */
    private void applyPickingCompletion(PickingList pickingList, UserDetailsImpl actor, String auditNote) {
        // Kiểm tra tất cả các dòng: nếu dòng nào soạn thiếu thì bắt buộc đã có lý do
        for (PickingListItem item : pickingList.getItems()) {
            if (item.getPickedQuantity().compareTo(item.getRequestedQuantity()) < 0) {
                if (!StringUtils.hasText(item.getShortageReason()) || item.getShortageReason().trim().length() < 5) {
                    throw BusinessException.badRequest("SHORTAGE_REASON_REQUIRED",
                            "Mặt hàng '" + item.getProductName() + "' soạn thiếu. Vui lòng bổ sung lý do soạn thiếu trước khi hoàn tất phiếu.");
                }
            }
            item.setIsPicked(true);
        }

        // S6-04: Đồng bộ số lô thực xuất và lý do đổi lô về các dòng đơn hàng
        for (PickingListItem item : pickingList.getItems()) {
            String chosenBatch = StringUtils.hasText(item.getActualBatchNumber())
                    ? item.getActualBatchNumber().trim()
                    : item.getSuggestedBatchNumber();

            if (StringUtils.hasText(chosenBatch) && item.getAllocations() != null) {
                LocalDate chosenExpiredDate = item.getSuggestedExpiredDate();
                if (StringUtils.hasText(item.getActualBatchNumber())
                        && !item.getActualBatchNumber().equalsIgnoreCase(item.getSuggestedBatchNumber())
                        && item.getProduct() != null) {
                    chosenExpiredDate = productLotRepository
                            .findByProduct_IdAndWarehouse_IdAndBatchNumber(item.getProduct().getId(), pickingList.getWarehouse().getId(), item.getActualBatchNumber().trim())
                            .map(ProductLot::getExpiredDate)
                            .orElse(item.getSuggestedExpiredDate());
                }

                for (PickingListItemAllocation alloc : item.getAllocations()) {
                    SalesOrder order = alloc.getOrder();
                    if (order != null && order.getLines() != null) {
                        for (SalesOrderLine line : order.getLines()) {
                            if (line.getProduct() != null && line.getProduct().getId().equals(item.getProduct().getId())) {
                                line.setBatchNumber(chosenBatch);
                                line.setSuggestedBatchNumber(item.getSuggestedBatchNumber());
                                line.setLotChangeReason(item.getLotChangeReason());
                                line.setLotExpiredDate(chosenExpiredDate);
                                salesOrderRepository.save(order);
                            }
                        }
                    }
                }
            }
        }

        String oldStatus = pickingList.getStatus();
        pickingList.setStatus(PickingList.STATUS_COMPLETED);
        pickingList.setCompletedAt(LocalDateTime.now());
        recalculateTotals(pickingList);

        if (auditLogService != null) {
            auditLogService.record(AuditModule.INVENTORY, "COMPLETE_PICKING_LIST", "PICKING_LIST", pickingList.getId(),
                    pickingList.getCode(), oldStatus, "COMPLETED",
                    auditNote, actor);
        }
    }

    /**
     * Hủy phiếu soạn hàng (CANCELLED) kèm lý do hủy.
     */
    @Transactional(rollbackFor = Exception.class)
    public PickingListResponse cancelPickingList(Long id, String reason, UserDetailsImpl actor) {
        PickingList pickingList = pickingListRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy phiếu soạn hàng với ID: " + id));

        assertWarehouseAccess(pickingList.getWarehouse().getId(), actor);

        if (PickingList.STATUS_COMPLETED.equals(pickingList.getStatus())) {
            throw BusinessException.badRequest("ALREADY_COMPLETED", "Không thể hủy phiếu soạn hàng đã hoàn thành.");
        }
        if (PickingList.STATUS_CANCELLED.equals(pickingList.getStatus())) {
            return mapToResponse(pickingList);
        }

        if (!StringUtils.hasText(reason) || reason.trim().length() < 5) {
            throw BusinessException.badRequest("REASON_REQUIRED", "Cần nhập lý do hủy phiếu soạn hàng (tối thiểu 5 ký tự).");
        }

        pickingList.setStatus(PickingList.STATUS_CANCELLED);
        pickingList.setCancelReason(reason.trim());
        pickingList.setCancelledAt(LocalDateTime.now());

        // Trả trạng thái các đơn hàng về APPROVED nếu đơn chưa xuất kho
        for (PickingListOrder plo : pickingList.getOrders()) {
            SalesOrder o = plo.getOrder();
            if (SalesOrder.STATUS_PICKING.equals(o.getStatus())) {
                o.setStatus(SalesOrder.STATUS_APPROVED);
                salesOrderRepository.save(o);
                log.info("Reverted order {} to status APPROVED due to picking list cancellation", o.getCode());
            }
        }

        PickingList saved = pickingListRepository.save(pickingList);

        if (auditLogService != null) {
            auditLogService.record(AuditModule.INVENTORY, "CANCEL_PICKING_LIST", "PICKING_LIST", saved.getId(),
                    saved.getCode(), "ACTIVE", "CANCELLED",
                    "Hủy phiếu soạn hàng: " + reason.trim(), actor);
        }

        return mapToResponse(saved);
    }

    // =========================================================================
    // HELPER & LOGIC NỘI BỘ (GỘP HÀNG & SẮP XẾP ĐƯỜNG ĐI)
    // =========================================================================

    private List<SalesOrder> loadAndValidateOrders(List<Long> orderIds, Long warehouseId) {
        if (orderIds == null || orderIds.isEmpty()) {
            throw BusinessException.badRequest("EMPTY_ORDERS", "Danh sách đơn hàng không được để trống.");
        }

        List<SalesOrder> orders = salesOrderRepository.findAllById(orderIds);
        if (orders.size() != orderIds.size()) {
            throw BusinessException.notFound("Một hoặc nhiều đơn hàng không tồn tại trong hệ thống.");
        }

        for (SalesOrder o : orders) {
            Long orderWhId = o.getReservedWarehouse() != null ? o.getReservedWarehouse().getId() : null;
            if (orderWhId == null || !orderWhId.equals(warehouseId)) {
                throw BusinessException.badRequest("WAREHOUSE_MISMATCH",
                        "Đơn hàng " + o.getCode() + " không thuộc kho xuất hàng chỉ định.");
            }
            if (!SalesOrder.STATUS_APPROVED.equals(o.getStatus()) && !SalesOrder.STATUS_PICKING.equals(o.getStatus())) {
                throw BusinessException.badRequest("INVALID_ORDER_STATUS",
                        "Đơn hàng " + o.getCode() + " đang ở trạng thái " + o.getStatus()
                                + ". Chỉ có thể soạn hàng cho đơn đã duyệt (APPROVED).");
            }
        }

        return orders;
    }

    /**
     * Thuật toán Gộp mặt hàng từ nhiều đơn và Sắp xếp theo đường đi trong kho:
     * 1. Gom các dòng có cùng product_id.
     * 2. Tìm vị trí lưu kho: ưu tiên defaultLocation của sản phẩm, nếu không có thì lấy vị trí của lô có hàng, hoặc vị trí kho.
     * 3. Gợi ý số lô theo nguyên tắc FEFO (hạn gần nhất lên trước).
     * 4. Sắp xếp thứ tự lấy hàng (Route Sequence) theo mã vị trí (Zone, Rack, Shelf, Bin) để thủ kho đi 1 vòng khép kín.
     */
    private List<GroupedItemHolder> aggregateAndRouteItems(List<SalesOrder> orders, Long warehouseId) {
        Map<Long, GroupedItemHolder> map = new LinkedHashMap<>();
        LocalDate today = LocalDate.now();

        for (SalesOrder o : orders) {
            if (o.getLines() == null) continue;
            for (SalesOrderLine line : o.getLines()) {
                Product p = line.getProduct();
                if (p == null) continue;

                BigDecimal lineBaseQty = line.getBaseQuantity() != null ? line.getBaseQuantity()
                        : (line.getQuantity() != null ? line.getQuantity() : BigDecimal.ZERO);

                GroupedItemHolder holder = map.computeIfAbsent(p.getId(), pid -> {
                    GroupedItemHolder h = new GroupedItemHolder();
                    h.product = p;
                    h.totalQuantity = BigDecimal.ZERO;
                    h.allocations = new ArrayList<>();

                    // 1. Xác định vị trí lưu kho: ưu tiên vị trí mặc định của sản phẩm
                    h.location = p.getDefaultLocation();

                    return h;
                });

                holder.totalQuantity = holder.totalQuantity.add(lineBaseQty);

                // Chi tiết phân bổ cho đơn này
                AllocationHolder ah = new AllocationHolder();
                ah.order = o;
                ah.requestedQuantity = lineBaseQty;
                holder.allocations.add(ah);
            }
        }

        // 2. Gợi ý số lô theo FEFO (S6-04 AC1: Mặc định gợi ý lô có hạn sử dụng gần nhất còn đủ số lượng)
        for (GroupedItemHolder h : map.values()) {
            Product p = h.product;
            if (Boolean.TRUE.equals(p.getHasBatchManagement())) {
                List<ProductLot> validLots = productLotRepository
                        .findByProduct_IdAndWarehouse_IdOrderByExpiredDateAsc(p.getId(), warehouseId).stream()
                        .filter(lot -> lot.getQuantity() != null && lot.getQuantity().compareTo(BigDecimal.ZERO) > 0)
                        .filter(lot -> lot.getExpiredDate() == null || !lot.getExpiredDate().isBefore(today))
                        .filter(lot -> !"LOCKED".equalsIgnoreCase(lot.getStatus()))
                        .collect(Collectors.toList());

                if (!validLots.isEmpty()) {
                    // S6-04 AC1: Mặc định gợi ý lô có hạn sử dụng gần nhất còn đủ số lượng
                    ProductLot bestLot = validLots.stream()
                            .filter(lot -> lot.getQuantity().compareTo(h.totalQuantity) >= 0)
                            .findFirst()
                            .orElse(validLots.get(0));

                    h.suggestedBatchNumber = bestLot.getBatchNumber();
                    h.suggestedExpiredDate = bestLot.getExpiredDate();
                    if (h.location == null && bestLot.getLocation() != null) {
                        h.location = bestLot.getLocation();
                    }
                }
            }
        }

        // 3. Sắp xếp các mặt hàng theo thứ tự đường đi trong kho (theo mã vị trí lưu kho tăng dần)
        List<GroupedItemHolder> sortedList = new ArrayList<>(map.values());
        sortedList.sort((a, b) -> {
            String codeA = a.location != null && a.location.getCode() != null ? a.location.getCode() : "ZZZZ";
            String codeB = b.location != null && b.location.getCode() != null ? b.location.getCode() : "ZZZZ";
            int locComp = codeA.compareToIgnoreCase(codeB);
            if (locComp != 0) {
                return locComp;
            }
            return a.product.getSku().compareToIgnoreCase(b.product.getSku());
        });

        // Đánh số thứ tự đường đi (routeSequence: 1, 2, 3...)
        int seq = 1;
        for (GroupedItemHolder h : sortedList) {
            h.routeSequence = seq++;
        }

        return sortedList;
    }

    private void distributePickedQuantityToAllocations(PickingListItem item) {
        if (item.getAllocations() == null || item.getAllocations().isEmpty()) {
            return;
        }

        BigDecimal remainingPicked = item.getPickedQuantity() != null ? item.getPickedQuantity() : BigDecimal.ZERO;

        for (PickingListItemAllocation alloc : item.getAllocations()) {
            BigDecimal req = alloc.getRequestedQuantity();
            if (remainingPicked.compareTo(BigDecimal.ZERO) <= 0) {
                alloc.setPickedQuantity(BigDecimal.ZERO);
                alloc.setShortageReason(item.getShortageReason());
            } else if (remainingPicked.compareTo(req) >= 0) {
                alloc.setPickedQuantity(req);
                alloc.setShortageReason(null);
                remainingPicked = remainingPicked.subtract(req);
            } else {
                alloc.setPickedQuantity(remainingPicked);
                alloc.setShortageReason(item.getShortageReason());
                remainingPicked = BigDecimal.ZERO;
            }
        }
    }

    private void validateAndApplyBatchNumber(
            PickingListItem item,
            Warehouse warehouse,
            String actualBatch,
            String lotChangeReason) {

        if (!StringUtils.hasText(actualBatch)) {
            return;
        }
        String cleanActual = actualBatch.trim();
        String suggested = item.getSuggestedBatchNumber();
        boolean isChanged = suggested != null && !suggested.equalsIgnoreCase(cleanActual);

        if (isChanged) {
            if (!StringUtils.hasText(lotChangeReason) || lotChangeReason.trim().length() < 5) {
                throw BusinessException.badRequest("LOT_CHANGE_REASON_REQUIRED",
                        "Thủ kho chọn lô thực tế ('" + cleanActual + "') khác với lô gợi ý FEFO ('" + suggested
                                + "') cho mặt hàng '" + item.getProductName() + "'. Bắt buộc nhập lý do đổi lô (tối thiểu 5 ký tự).");
            }
            item.setLotChangeReason(lotChangeReason.trim());
        } else {
            item.setLotChangeReason(null);
        }

        Product product = item.getProduct();
        if (product != null && Boolean.TRUE.equals(product.getHasBatchManagement())) {
            ProductLot lot = productLotRepository
                    .findByProduct_IdAndWarehouse_IdAndBatchNumber(product.getId(), warehouse.getId(), cleanActual)
                    .orElseThrow(() -> BusinessException.notFound("Không tìm thấy lô '" + cleanActual
                            + "' của sản phẩm '" + product.getSku() + "' tại kho " + warehouse.getName()));

            if ("LOCKED".equalsIgnoreCase(lot.getStatus())) {
                throw BusinessException.badRequest("LOT_LOCKED",
                        "Lô hàng '" + cleanActual + "' đang bị khóa (LOCKED), không được phép lấy để xuất.");
            }
            if (lot.getExpiredDate() != null && lot.getExpiredDate().isBefore(LocalDate.now())) {
                throw BusinessException.badRequest("LOT_EXPIRED",
                        "Lô hàng '" + cleanActual + "' đã hết hạn sử dụng (" + lot.getExpiredDate() + ").");
            }
        }

        item.setActualBatchNumber(cleanActual);
    }

    private void recalculateTotals(PickingList pickingList) {
        BigDecimal totalPicked = BigDecimal.ZERO;
        if (pickingList.getItems() != null) {
            for (PickingListItem item : pickingList.getItems()) {
                if (item.getPickedQuantity() != null) {
                    totalPicked = totalPicked.add(item.getPickedQuantity());
                }
            }
        }
        pickingList.setTotalPickedQuantity(totalPicked);
    }

    private String generatePickingCode() {
        String prefix = "PKL-" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyMMdd")) + "-";
        for (int attempt = 0; attempt < 20; attempt++) {
            StringBuilder sb = new StringBuilder(prefix);
            for (int i = 0; i < 4; i++) {
                sb.append(CODE_CHARS.charAt(RANDOM.nextInt(CODE_CHARS.length())));
            }
            if (!pickingListRepository.existsByCode(sb.toString())) {
                return sb.toString();
            }
        }
        throw new IllegalStateException("Không sinh được mã phiếu soạn hàng, vui lòng thử lại");
    }

    public PickingListResponse mapToResponse(PickingList p) {
        if (p == null) return null;

        BigDecimal completionPercentage = BigDecimal.ZERO;
        if (p.getTotalRequestedQuantity() != null && p.getTotalRequestedQuantity().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal picked = p.getTotalPickedQuantity() != null ? p.getTotalPickedQuantity() : BigDecimal.ZERO;
            completionPercentage = picked.multiply(BigDecimal.valueOf(100))
                    .divide(p.getTotalRequestedQuantity(), 1, RoundingMode.HALF_UP);
            if (completionPercentage.compareTo(BigDecimal.valueOf(100)) > 0) {
                completionPercentage = BigDecimal.valueOf(100);
            }
        }

        List<PickingListOrderResponse> orderResponses = p.getOrders() != null
                ? p.getOrders().stream().map(this::mapOrderToResponse).collect(Collectors.toList())
                : Collections.emptyList();

        List<PickingListItemResponse> itemResponses = p.getItems() != null
                ? p.getItems().stream().map(this::mapItemToResponse).collect(Collectors.toList())
                : Collections.emptyList();

        return PickingListResponse.builder()
                .id(p.getId())
                .code(p.getCode())
                .warehouseId(p.getWarehouse() != null ? p.getWarehouse().getId() : null)
                .warehouseCode(p.getWarehouse() != null ? p.getWarehouse().getCode() : null)
                .warehouseName(p.getWarehouse() != null ? p.getWarehouse().getName() : null)
                .status(p.getStatus())
                .pickerId(p.getPicker() != null ? p.getPicker().getId() : null)
                .pickerName(p.getPickerName())
                .note(p.getNote())
                .cancelReason(p.getCancelReason())
                .totalOrders(p.getTotalOrders())
                .totalItems(p.getTotalItems())
                .totalRequestedQuantity(p.getTotalRequestedQuantity())
                .totalPickedQuantity(p.getTotalPickedQuantity())
                .completionPercentage(completionPercentage)
                .startedAt(p.getStartedAt())
                .completedAt(p.getCompletedAt())
                .cancelledAt(p.getCancelledAt())
                .createdById(p.getCreatedById())
                .createdByUsername(p.getCreatedByUsername())
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .orders(orderResponses)
                .items(itemResponses)
                .build();
    }

    private PickingListOrderResponse mapOrderToResponse(PickingListOrder plo) {
        return PickingListOrderResponse.builder()
                .id(plo.getId())
                .orderId(plo.getOrder() != null ? plo.getOrder().getId() : null)
                .orderCode(plo.getOrderCode())
                .customerName(plo.getCustomerName())
                .totalLines(plo.getTotalLines())
                .totalAmount(plo.getTotalAmount())
                .orderStatus(plo.getOrder() != null ? plo.getOrder().getStatus() : null)
                .createdAt(plo.getCreatedAt())
                .build();
    }

    private PickingListItemResponse mapItemToResponse(PickingListItem item) {
        List<PickingItemAllocationResponse> allocs = item.getAllocations() != null
                ? item.getAllocations().stream().map(this::mapAllocationToResponse).collect(Collectors.toList())
                : Collections.emptyList();

        return PickingListItemResponse.builder()
                .id(item.getId())
                .productId(item.getProduct() != null ? item.getProduct().getId() : null)
                .productSku(item.getProductSku())
                .productName(item.getProductName())
                .baseUnit(item.getBaseUnit())
                .locationId(item.getLocation() != null ? item.getLocation().getId() : null)
                .locationCode(item.getLocationCode())
                .locationName(StringUtils.hasText(item.getLocationName()) ? item.getLocationName() : "Chưa xác định vị trí")
                .zone(item.getZone())
                .shelf(item.getShelf())
                .routeSequence(item.getRouteSequence())
                .suggestedBatchNumber(item.getSuggestedBatchNumber())
                .suggestedExpiredDate(item.getSuggestedExpiredDate())
                .actualBatchNumber(item.getActualBatchNumber())
                .lotChangeReason(item.getLotChangeReason())
                .requestedQuantity(item.getRequestedQuantity())
                .pickedQuantity(item.getPickedQuantity())
                .isPicked(item.getIsPicked())
                .shortageReason(item.getShortageReason())
                .notes(item.getNotes())
                .allocations(allocs)
                .build();
    }

    private PickingItemAllocationResponse mapAllocationToResponse(PickingListItemAllocation a) {
        return PickingItemAllocationResponse.builder()
                .id(a.getId())
                .orderId(a.getOrder() != null ? a.getOrder().getId() : null)
                .orderCode(a.getOrderCode())
                .customerName(a.getCustomerName())
                .requestedQuantity(a.getRequestedQuantity())
                .pickedQuantity(a.getPickedQuantity())
                .shortageReason(a.getShortageReason())
                .build();
    }

    private void assertWarehouseAccess(Long warehouseId, UserDetailsImpl actor) {
        if (warehouseId == null) return;
        Set<Long> allowed = allowedWarehouseIds(actor);
        if (allowed != null && !allowed.contains(warehouseId)) {
            throw BusinessException.forbidden("ACCESS_DENIED", "Bạn không có quyền thao tác trên kho này");
        }
    }

    Set<Long> allowedWarehouseIds(UserDetailsImpl actor) {
        if (actor == null || actor.getAuthorities() == null) {
            return Set.of();
        }
        Set<String> roles = new HashSet<>();
        actor.getAuthorities().forEach(a -> roles.add(a.getAuthority()));
        boolean broad = roles.contains("ROLE_ADMIN")
                || roles.contains("ROLE_ACCOUNTANT") || roles.contains("ROLE_SALES_MANAGER");
        if (broad) {
            return null; // xem tất cả kho
        }
        if (roles.contains("ROLE_WAREHOUSE") || roles.contains("ROLE_WH_MANAGER")) {
            Set<Long> ids = userRepository.findById(actor.getId())
                    .map(u -> {
                        Set<Long> s = new HashSet<>();
                        u.getWarehouses().forEach(w -> s.add(w.getId()));
                        return s;
                    })
                    .orElse(Set.of());
            if (!ids.isEmpty()) {
                return ids;
            }
            Set<Long> fallback = new HashSet<>();
            warehouseRepository.findByCodeIgnoreCase("WH-MB01")
                    .ifPresent(wh -> fallback.add(wh.getId()));
            return fallback.isEmpty() ? null : fallback;
        }
        return null;
    }

    // =========================================================================
    // NỘI BỘ STRUCTS
    // =========================================================================

    private static class GroupedItemHolder {
        Product product;
        WarehouseLocation location;
        Integer routeSequence;
        String suggestedBatchNumber;
        LocalDate suggestedExpiredDate;
        BigDecimal totalQuantity;
        List<AllocationHolder> allocations;

        PickingListItemResponse toResponse() {
            List<PickingItemAllocationResponse> allocResponses = allocations.stream().map(a ->
                    PickingItemAllocationResponse.builder()
                            .orderId(a.order.getId())
                            .orderCode(a.order.getCode())
                            .customerName(a.order.getCustomer() != null ? a.order.getCustomer().getName() : null)
                            .requestedQuantity(a.requestedQuantity)
                            .pickedQuantity(BigDecimal.ZERO)
                            .build()
            ).collect(Collectors.toList());

            return PickingListItemResponse.builder()
                    .productId(product.getId())
                    .productSku(product.getSku())
                    .productName(product.getName())
                    .baseUnit(product.getBaseUnit())
                    .locationId(location != null ? location.getId() : null)
                    .locationCode(location != null ? location.getCode() : null)
                    .locationName(location != null && StringUtils.hasText(location.getName()) ? location.getName() : "Chưa xác định vị trí")
                    .zone(location != null ? location.getZone() : null)
                    .shelf(location != null ? location.getShelf() : null)
                    .routeSequence(routeSequence)
                    .suggestedBatchNumber(suggestedBatchNumber)
                    .suggestedExpiredDate(suggestedExpiredDate)
                    .requestedQuantity(totalQuantity)
                    .pickedQuantity(BigDecimal.ZERO)
                    .isPicked(false)
                    .allocations(allocResponses)
                    .build();
        }

        PickingListItem toEntity(PickingList pickingList) {
            PickingListItem item = PickingListItem.builder()
                    .pickingList(pickingList)
                    .product(product)
                    .productSku(product.getSku())
                    .productName(product.getName())
                    .baseUnit(product.getBaseUnit())
                    .location(location)
                    .locationCode(location != null ? location.getCode() : null)
                    .locationName(location != null && StringUtils.hasText(location.getName()) ? location.getName() : "Chưa xác định vị trí")
                    .zone(location != null ? location.getZone() : null)
                    .shelf(location != null ? location.getShelf() : null)
                    .routeSequence(routeSequence)
                    .suggestedBatchNumber(suggestedBatchNumber)
                    .suggestedExpiredDate(suggestedExpiredDate)
                    .requestedQuantity(totalQuantity)
                    .pickedQuantity(BigDecimal.ZERO)
                    .isPicked(false)
                    .build();

            for (AllocationHolder ah : allocations) {
                PickingListItemAllocation alloc = PickingListItemAllocation.builder()
                        .pickingListItem(item)
                        .order(ah.order)
                        .orderCode(ah.order.getCode())
                        .customerName(ah.order.getCustomer() != null ? ah.order.getCustomer().getName() : null)
                        .requestedQuantity(ah.requestedQuantity)
                        .pickedQuantity(BigDecimal.ZERO)
                        .build();
                item.addAllocation(alloc);
            }

            return item;
        }
    }

    private static class AllocationHolder {
        SalesOrder order;
        BigDecimal requestedQuantity;
    }
}
