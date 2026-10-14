package com.erp.backend.service;

import com.erp.backend.dto.customer.CustomerResponse;
import com.erp.backend.dto.user.RefItem;
import com.erp.backend.dto.warehouse.*;
import com.erp.backend.entity.User;
import com.erp.backend.entity.Warehouse;
import com.erp.backend.entity.WarehouseLocation;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.CustomerRepository;
import com.erp.backend.repository.InventoryRepository;
import com.erp.backend.repository.GoodsReceiptRepository;
import com.erp.backend.repository.StockTransferRepository;
import com.erp.backend.repository.SalesOrderRepository;
import com.erp.backend.repository.UserRepository;
import com.erp.backend.repository.WarehouseLocationRepository;
import com.erp.backend.repository.WarehouseRepository;
import com.erp.backend.security.UserDetailsImpl;
import com.erp.backend.entity.AuditModule;
import com.erp.backend.entity.StockTake;
import com.erp.backend.repository.MinStockThresholdRepository;
import com.erp.backend.repository.StockTakeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.*;

/**
 * S5-03 / SCRUM-161: Dịch vụ quản lý đa kho và vị trí lưu trữ (Khu / Kệ / Ô).
 * - Khai báo kho hàng (mã, tên, địa chỉ, người phụ trách, trạng thái)
 * - Khai báo vị trí lưu trong kho (ở mức kệ hoặc khu)
 * - Gán kho phục vụ mặc định cho đại lý
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WarehouseService {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_INACTIVE = "INACTIVE";

    private final WarehouseRepository warehouseRepository;
    private final WarehouseLocationRepository locationRepository;
    private final UserRepository userRepository;
    private final CustomerRepository customerRepository;
    private final CustomerService customerService;
    private final InventoryRepository inventoryRepository;
    private final GoodsReceiptRepository goodsReceiptRepository;
    private final StockTransferRepository stockTransferRepository;
    private final SalesOrderRepository salesOrderRepository;
    private final StockTakeRepository stockTakeRepository;
    private final MinStockThresholdRepository minStockThresholdRepository;
    private final AuditLogService auditLogService;

    // ==============================================================
    // 1. QUẢN LÝ KHO HÀNG (WAREHOUSE CRUD)
    // ==============================================================

    @Transactional(readOnly = true)
    public List<WarehouseResponse> getAll(String status, String keyword, UserDetailsImpl actor) {
        List<Warehouse> list;
        if (StringUtils.hasText(status)) {
            list = warehouseRepository.findByStatusIgnoreCaseOrderByNameAsc(status.trim());
        } else {
            list = warehouseRepository.findAll(org.springframework.data.domain.Sort.by("name").ascending());
        }

        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim().toLowerCase();
            list = list.stream().filter(w ->
                    (w.getCode() != null && w.getCode().toLowerCase().contains(kw))
                            || (w.getName() != null && w.getName().toLowerCase().contains(kw))
                            || (w.getAddress() != null && w.getAddress().toLowerCase().contains(kw))
            ).toList();
        }

        return list.stream().map(w -> toResponse(w, false)).toList();
    }

    @Transactional(readOnly = true)
    public WarehouseResponse getById(Long id, UserDetailsImpl actor) {
        Warehouse warehouse = findWarehouse(id);
        return toResponse(warehouse, true);
    }

    @Transactional
    public WarehouseResponse create(WarehouseCreateRequest req, UserDetailsImpl actor) {
        String cleanCode = req.getCode() != null ? req.getCode().trim().toUpperCase() : "";
        if (!StringUtils.hasText(cleanCode)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "CODE_REQUIRED",
                    "Mã kho không được để trống", "code");
        }

        if (warehouseRepository.existsByCodeIgnoreCase(cleanCode)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "DUPLICATE_WAREHOUSE_CODE",
                    "Mã kho '" + cleanCode + "' đã tồn tại trong hệ thống", "code");
        }

        String cleanName = req.getName() != null ? req.getName().trim() : "";
        if (!StringUtils.hasText(cleanName)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "NAME_REQUIRED",
                    "Tên kho không được để trống", "name");
        }

        User manager = null;
        if (req.getManagerId() != null) {
            manager = loadUser(req.getManagerId(), "managerId");
        }

        String status = StringUtils.hasText(req.getStatus())
                ? req.getStatus().trim().toUpperCase()
                : STATUS_ACTIVE;
        validateStatus(status);

        Warehouse warehouse = Warehouse.builder()
                .code(cleanCode)
                .name(cleanName)
                .address(StringUtils.hasText(req.getAddress()) ? req.getAddress().trim() : null)
                .manager(manager)
                .status(status)
                .build();

        Warehouse saved = warehouseRepository.save(warehouse);
        log.info("S5-03: Đã tạo kho hàng mới code='{}', name='{}', actor='{}'",
                saved.getCode(), saved.getName(), actor != null ? actor.getUsername() : "system");

        return toResponse(saved, false);
    }

    @Transactional
    public WarehouseResponse update(Long id, WarehouseUpdateRequest req, UserDetailsImpl actor) {
        Warehouse warehouse = findWarehouse(id);

        String cleanName = req.getName() != null ? req.getName().trim() : "";
        if (!StringUtils.hasText(cleanName)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "NAME_REQUIRED",
                    "Tên kho không được để trống", "name");
        }

        warehouse.setName(cleanName);
        if (req.getAddress() != null) {
            warehouse.setAddress(StringUtils.hasText(req.getAddress()) ? req.getAddress().trim() : null);
        }

        if (req.getManagerId() != null) {
            warehouse.setManager(loadUser(req.getManagerId(), "managerId"));
        } else {
            warehouse.setManager(null);
        }

        if (StringUtils.hasText(req.getStatus())) {
            String status = req.getStatus().trim().toUpperCase();
            validateStatus(status);
            warehouse.setStatus(status);
        }

        Warehouse saved = warehouseRepository.save(warehouse);
        log.info("S5-03: Đã cập nhật kho hàng id={}, code='{}', actor='{}'",
                saved.getId(), saved.getCode(), actor != null ? actor.getUsername() : "system");

        return toResponse(saved, false);
    }

    @Transactional
    public void delete(Long id, UserDetailsImpl actor) {
        delete(id, "Xóa kho hàng", actor);
    }

    @Transactional
    public void delete(Long id, String reason, UserDetailsImpl actor) {
        String cleanReason = StringUtils.hasText(reason) ? reason.trim() : null;
        if (cleanReason == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "REASON_REQUIRED",
                    "Vui lòng nhập lý do xóa kho hàng", "reason");
        }

        Warehouse warehouse = findWarehouse(id);

        long locCount = locationRepository.countByWarehouse_Id(id);
        long customerCount = customerRepository.findAll().stream()
                .filter(c -> c.getDefaultWarehouse() != null && id.equals(c.getDefaultWarehouse().getId()))
                .count();

        boolean hasCommercialTransactions = (goodsReceiptRepository != null && goodsReceiptRepository.existsByWarehouse_Id(id))
                || (salesOrderRepository != null && salesOrderRepository.existsByReservedWarehouse_Id(id))
                || (stockTransferRepository != null && stockTransferRepository.existsBySourceWarehouse_IdOrDestWarehouse_Id(id, id))
                || (stockTakeRepository != null && stockTakeRepository.existsByWarehouse_IdAndStatus(id, StockTake.STATUS_CLOSED));

        boolean hasAssociatedData = locCount > 0 || customerCount > 0 || hasTransactions(id) || hasCommercialTransactions;

        if (STATUS_ACTIVE.equalsIgnoreCase(warehouse.getStatus()) && hasAssociatedData) {
            // Kho đang hoạt động nhưng có dữ liệu ràng buộc -> Chuyển sang INACTIVE (Soft Delete theo Mục 12)
            warehouse.setStatus(STATUS_INACTIVE);
            warehouseRepository.save(warehouse);
            log.info("S5-03: Kho id={} có dữ liệu ràng buộc, đã chuyển sang INACTIVE. Lý do: {}", id, cleanReason);

            if (auditLogService != null) {
                auditLogService.record(
                        AuditModule.INVENTORY,
                        "DEACTIVATE_WAREHOUSE",
                        "WAREHOUSE",
                        id,
                        warehouse.getCode(),
                        "{\"status\":\"ACTIVE\"}",
                        "{\"status\":\"INACTIVE\"}",
                        cleanReason,
                        actor);
            }
            return;
        }

        // Nếu kho đã ở trạng thái INACTIVE hoặc hoàn toàn trống:
        if (hasCommercialTransactions) {
            throw new BusinessException(HttpStatus.CONFLICT, "WAREHOUSE_HAS_TRANSACTIONS",
                    "Kho " + warehouse.getCode() + " đã phát sinh chứng từ xuất nhập/đơn hàng thực tế, không thể xóa vĩnh viễn khỏi cơ sở dữ liệu để bảo toàn lịch sử sổ sách kế toán.", null);
        }

        // Không có chứng từ giao dịch thương mại/nhập xuất thực tế -> cho phép dọn dẹp dữ liệu khởi tạo/nháp và xóa hoàn toàn
        if (stockTakeRepository != null) {
            List<StockTake> stockTakes = stockTakeRepository.findByWarehouse_Id(id);
            if (stockTakes != null && !stockTakes.isEmpty()) {
                stockTakeRepository.deleteAll(stockTakes);
            }
        }

        if (minStockThresholdRepository != null) {
            minStockThresholdRepository.deleteByWarehouse_Id(id);
        }

        if (inventoryRepository != null) {
            inventoryRepository.deleteByWarehouse_Id(id);
        }

        if (locationRepository != null) {
            locationRepository.deleteByWarehouse_Id(id);
        }

        if (customerRepository != null) {
            customerRepository.findAll().stream()
                    .filter(c -> c.getDefaultWarehouse() != null && id.equals(c.getDefaultWarehouse().getId()))
                    .forEach(c -> {
                        c.setDefaultWarehouse(null);
                        customerRepository.save(c);
                    });
        }

        if (userRepository != null) {
            userRepository.findAll().stream()
                    .filter(u -> u.getWarehouses() != null && u.getWarehouses().stream().anyMatch(w -> id.equals(w.getId())))
                    .forEach(u -> {
                        u.getWarehouses().removeIf(w -> id.equals(w.getId()));
                        userRepository.save(u);
                    });
        }

        String oldValue = String.format("{\"code\":\"%s\",\"name\":\"%s\",\"status\":\"%s\"}",
                warehouse.getCode(), warehouse.getName(), warehouse.getStatus());
        warehouseRepository.delete(warehouse);
        log.info("S5-03: Đã xóa hoàn toàn kho id={}, code='{}'. Lý do: {}", id, warehouse.getCode(), cleanReason);

        if (auditLogService != null) {
            auditLogService.record(
                    AuditModule.INVENTORY,
                    "DELETE_WAREHOUSE",
                    "WAREHOUSE",
                    id,
                    warehouse.getCode(),
                    oldValue,
                    null,
                    cleanReason,
                    actor);
        }
    }

    /**
     * Góp ý GV mục 12 + quy tắc 8: kho đã có tồn, phiếu nhập, phiếu chuyển, đơn giữ chỗ hoặc nhân viên được gắn
     * thì không xoá cứng (trước đây xoá -> lỗi khoá ngoại 500), chỉ chuyển Ngừng hoạt động.
     */
    private boolean hasTransactions(Long id) {
        return inventoryRepository.existsByWarehouse_Id(id)
                || goodsReceiptRepository.existsByWarehouse_Id(id)
                || stockTransferRepository.existsBySourceWarehouse_IdOrDestWarehouse_Id(id, id)
                || salesOrderRepository.existsByReservedWarehouse_Id(id)
                || userRepository.existsByWarehouses_Id(id);
    }

    // ==============================================================
    // 2. QUẢN LÝ VỊ TRÍ LƯU TRONG KHO (LOCATION / SHELF / ZONE CRUD)
    // ==============================================================

    @Transactional(readOnly = true)
    public List<WarehouseLocationResponse> getLocations(Long warehouseId, String locationType,
                                                        String status, String keyword, UserDetailsImpl actor) {
        findWarehouse(warehouseId);

        List<WarehouseLocation> list;
        if (StringUtils.hasText(status) && StringUtils.hasText(locationType)) {
            list = locationRepository.findByWarehouse_IdAndStatusIgnoreCaseAndLocationTypeIgnoreCaseOrderByCodeAsc(
                    warehouseId, status.trim(), locationType.trim());
        } else if (StringUtils.hasText(status)) {
            list = locationRepository.findByWarehouse_IdAndStatusIgnoreCaseOrderByCodeAsc(warehouseId, status.trim());
        } else if (StringUtils.hasText(locationType)) {
            list = locationRepository.findByWarehouse_IdAndLocationTypeIgnoreCaseOrderByCodeAsc(warehouseId, locationType.trim());
        } else {
            list = locationRepository.findByWarehouse_IdOrderByCodeAsc(warehouseId);
        }

        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim().toLowerCase();
            list = list.stream().filter(l ->
                    (l.getCode() != null && l.getCode().toLowerCase().contains(kw))
                            || (l.getName() != null && l.getName().toLowerCase().contains(kw))
                            || (l.getZone() != null && l.getZone().toLowerCase().contains(kw))
                            || (l.getShelf() != null && l.getShelf().toLowerCase().contains(kw))
            ).toList();
        }

        return list.stream().map(this::toLocationResponse).toList();
    }

    @Transactional(readOnly = true)
    public WarehouseLocationResponse getLocationById(Long warehouseId, Long locationId, UserDetailsImpl actor) {
        findWarehouse(warehouseId);
        WarehouseLocation location = locationRepository.findByWarehouse_IdAndId(warehouseId, locationId)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy vị trí lưu trữ trong kho này"));
        return toLocationResponse(location);
    }

    @Transactional
    public WarehouseLocationResponse createLocation(Long warehouseId, WarehouseLocationCreateRequest req,
                                                    UserDetailsImpl actor) {
        Warehouse warehouse = findWarehouse(warehouseId);

        String cleanCode = req.getCode() != null ? req.getCode().trim().toUpperCase() : "";
        if (!StringUtils.hasText(cleanCode)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "CODE_REQUIRED",
                    "Mã vị trí không được để trống", "code");
        }

        if (locationRepository.existsByWarehouse_IdAndCodeIgnoreCase(warehouseId, cleanCode)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "DUPLICATE_LOCATION_CODE",
                    "Mã vị trí '" + cleanCode + "' đã tồn tại trong kho " + warehouse.getName(), "code");
        }

        String cleanName = req.getName() != null ? req.getName().trim() : "";
        if (!StringUtils.hasText(cleanName)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "NAME_REQUIRED",
                    "Tên vị trí không được để trống", "name");
        }

        String locationType = StringUtils.hasText(req.getLocationType())
                ? req.getLocationType().trim().toUpperCase()
                : "RACK";

        String status = StringUtils.hasText(req.getStatus())
                ? req.getStatus().trim().toUpperCase()
                : STATUS_ACTIVE;
        validateStatus(status);

        WarehouseLocation location = WarehouseLocation.builder()
                .warehouse(warehouse)
                .code(cleanCode)
                .name(cleanName)
                .locationType(locationType)
                .zone(StringUtils.hasText(req.getZone()) ? req.getZone().trim() : null)
                .shelf(StringUtils.hasText(req.getShelf()) ? req.getShelf().trim() : null)
                .description(StringUtils.hasText(req.getDescription()) ? req.getDescription().trim() : null)
                .status(status)
                .build();

        WarehouseLocation saved = locationRepository.save(location);
        log.info("S5-03: Đã tạo vị trí lưu kho mới id={}, code='{}', kho='{}'",
                saved.getId(), saved.getCode(), warehouse.getCode());

        return toLocationResponse(saved);
    }

    @Transactional
    public WarehouseLocationResponse updateLocation(Long warehouseId, Long locationId,
                                                    WarehouseLocationUpdateRequest req, UserDetailsImpl actor) {
        findWarehouse(warehouseId);
        WarehouseLocation location = locationRepository.findByWarehouse_IdAndId(warehouseId, locationId)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy vị trí lưu trữ trong kho này"));

        String cleanName = req.getName() != null ? req.getName().trim() : "";
        if (!StringUtils.hasText(cleanName)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "NAME_REQUIRED",
                    "Tên vị trí không được để trống", "name");
        }
        location.setName(cleanName);

        if (StringUtils.hasText(req.getLocationType())) {
            location.setLocationType(req.getLocationType().trim().toUpperCase());
        }

        location.setZone(StringUtils.hasText(req.getZone()) ? req.getZone().trim() : null);
        location.setShelf(StringUtils.hasText(req.getShelf()) ? req.getShelf().trim() : null);
        location.setDescription(StringUtils.hasText(req.getDescription()) ? req.getDescription().trim() : null);

        if (StringUtils.hasText(req.getStatus())) {
            String status = req.getStatus().trim().toUpperCase();
            validateStatus(status);
            location.setStatus(status);
        }

        WarehouseLocation saved = locationRepository.save(location);
        log.info("S5-03: Đã cập nhật vị trí lưu kho id={}, code='{}'", saved.getId(), saved.getCode());

        return toLocationResponse(saved);
    }

    @Transactional
    public void deleteLocation(Long warehouseId, Long locationId, UserDetailsImpl actor) {
        deleteLocation(warehouseId, locationId, "Xóa vị trí lưu kho", actor);
    }

    @Transactional
    public void deleteLocation(Long warehouseId, Long locationId, String reason, UserDetailsImpl actor) {
        findWarehouse(warehouseId);
        WarehouseLocation location = locationRepository.findByWarehouse_IdAndId(warehouseId, locationId)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy vị trí lưu trữ trong kho này"));

        locationRepository.delete(location);
        log.info("S5-03: Đã xóa vị trí lưu kho id={}, code='{}'. Lý do: {}", locationId, location.getCode(), reason);

        if (auditLogService != null) {
            auditLogService.record(
                    AuditModule.INVENTORY,
                    "DELETE_LOCATION",
                    "WAREHOUSE_LOCATION",
                    locationId,
                    location.getCode(),
                    location.getName(),
                    null,
                    reason,
                    actor);
        }
    }

    // ==============================================================
    // 3. GÁN KHO PHỤC VỤ MẶC ĐỊNH CHO ĐẠI LÝ
    // ==============================================================

    @Transactional
    public CustomerResponse assignDefaultWarehouse(Long customerId, Long warehouseId, UserDetailsImpl actor) {
        return customerService.assignDefaultWarehouse(customerId, warehouseId, actor);
    }

    // ==============================================================
    // HELPER METHODS
    // ==============================================================

    public Warehouse findWarehouse(Long id) {
        if (id == null) {
            throw BusinessException.badRequest("WAREHOUSE_ID_REQUIRED", "ID kho không được để trống");
        }
        return warehouseRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy kho hàng với ID: " + id));
    }

    private User loadUser(Long userId, String field) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "USER_NOT_FOUND",
                        "Không tìm thấy người phụ trách với ID: " + userId, field));
    }

    private void validateStatus(String status) {
        if (!STATUS_ACTIVE.equals(status) && !STATUS_INACTIVE.equals(status)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_STATUS",
                    "Trạng thái kho chỉ được là ACTIVE hoặc INACTIVE", "status");
        }
    }

    public WarehouseResponse toResponse(Warehouse w, boolean includeLocations) {
        long locCount = locationRepository.countByWarehouse_Id(w.getId());
        long activeLocCount = locationRepository.countByWarehouse_IdAndStatusIgnoreCase(w.getId(), STATUS_ACTIVE);

        User mgr = w.getManager();
        RefItem mgrRef = mgr != null ? new RefItem(mgr.getId(), mgr.getUsername(), mgr.getFullName()) : null;
        String mgrPhone = mgr != null ? mgr.getPhone() : null;
        String mgrEmail = mgr != null ? mgr.getEmail() : null;

        List<WarehouseLocationResponse> locations = null;
        if (includeLocations) {
            locations = locationRepository.findByWarehouse_IdOrderByCodeAsc(w.getId()).stream()
                    .map(this::toLocationResponse)
                    .toList();
        }

        return WarehouseResponse.builder()
                .id(w.getId())
                .code(w.getCode())
                .name(w.getName())
                .address(w.getAddress())
                .status(w.getStatus())
                .manager(mgrRef)
                .managerPhone(mgrPhone)
                .managerEmail(mgrEmail)
                .locationCount(locCount)
                .activeLocationCount(activeLocCount)
                .locations(locations)
                .createdAt(w.getCreatedAt())
                .updatedAt(w.getUpdatedAt())
                .build();
    }

    public WarehouseLocationResponse toLocationResponse(WarehouseLocation l) {
        return WarehouseLocationResponse.builder()
                .id(l.getId())
                .warehouseId(l.getWarehouse() != null ? l.getWarehouse().getId() : null)
                .warehouseCode(l.getWarehouse() != null ? l.getWarehouse().getCode() : null)
                .warehouseName(l.getWarehouse() != null ? l.getWarehouse().getName() : null)
                .code(l.getCode())
                .name(l.getName())
                .locationType(l.getLocationType())
                .zone(l.getZone())
                .shelf(l.getShelf())
                .description(l.getDescription())
                .status(l.getStatus())
                .createdAt(l.getCreatedAt())
                .updatedAt(l.getUpdatedAt())
                .build();
    }
}
