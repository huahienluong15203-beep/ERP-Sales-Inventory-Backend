package com.erp.backend.service;

import com.erp.backend.dto.inventory.StockInfoDto;
import com.erp.backend.entity.*;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.InventoryRepository;
import com.erp.backend.repository.WarehouseRepository;
import com.erp.backend.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.LocalDateTime;
import java.time.Clock;
import java.math.RoundingMode;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryService {

    private final InventoryRepository inventoryRepository;
    private final WarehouseRepository warehouseRepository;
    private final StockMovementService stockMovementService;

    // S5-06: số ngày giữ chỗ tối đa trước khi tự nhả (application.properties: erp.order.reservation-days)
    @Value("${erp.order.reservation-days:7}")
    int reservationDays = 7;

    // Cho phép test cố định thời gian
    Clock clock = Clock.system(ZoneId.of("Asia/Ho_Chi_Minh"));

    /**
     * S4-03 AC1: Xác định kho hàng phục vụ đại lý theo địa bàn / khu vực hoạt động.
     * Thứ tự ưu tiên:
     * 1. Mã/Tên khu vực (MB, MT, MN)
     * 2. Địa chỉ trụ sở đại lý
     * 3. Kho mặc định hoạt động đầu tiên trong hệ thống
     */
    @Transactional(readOnly = true)
    public Warehouse resolveWarehouseForCustomer(Customer customer) {
        if (customer == null) {
            return getDefaultWarehouse();
        }

        // S5-03: Ưu tiên kho phục vụ mặc định đã được gán trực tiếp cho đại lý
        if (customer.getDefaultWarehouse() != null
                && "ACTIVE".equalsIgnoreCase(customer.getDefaultWarehouse().getStatus())) {
            return customer.getDefaultWarehouse();
        }

        // Khu vực đã gán cho đại lý (MB / MT / MN) quyết định kho; chỉ khi chưa gán khu vực mới đoán theo địa chỉ.
        // Không dò chuỗi con (vd "Nam Từ Liêm", "Trung Kính" là địa danh Hà Nội, không phải miền Nam / miền Trung).
        String zone = zoneOfRegion(customer.getRegion());
        if (zone == null) {
            zone = zoneOfAddress(customer.getAddress());
        }

        // 1. Miền Trung
        if (ZONE_CENTRAL.equals(zone)) {
            Optional<Warehouse> wh = activeWarehouseByCode("WH-MT01")
                    .or(() -> findFirstActiveWarehouseMatching("MT", "Trung"));
            if (wh.isPresent()) return wh.get();
        }

        // 2. Miền Nam
        if (ZONE_SOUTH.equals(zone)) {
            Optional<Warehouse> wh = activeWarehouseByCode("WH-MN01")
                    .or(() -> activeWarehouseByCode("KHO-HCM"))
                    .or(() -> findFirstActiveWarehouseMatching("MN", "Nam", "TP.HCM"));
            if (wh.isPresent()) return wh.get();
        }

        // 3. Miền Bắc (mặc định)
        Optional<Warehouse> northWh = activeWarehouseByCode("WH-MB01")
                .or(() -> activeWarehouseByCode("KHO-HN"))
                .or(() -> findFirstActiveWarehouseMatching("MB", "Bắc", "Hà Nội"));

        return northWh.orElseGet(this::getDefaultWarehouse);
    }

    /** S5-03: chỉ lấy kho đang hoạt động; kho đã ngừng thì bỏ qua để chọn kho khác, không giữ chỗ vào kho ngừng. */
    private Optional<Warehouse> activeWarehouseByCode(String code) {
        return warehouseRepository.findByCodeIgnoreCase(code)
                .filter(w -> "ACTIVE".equalsIgnoreCase(w.getStatus()));
    }

    static final String ZONE_NORTH = "MB";
    static final String ZONE_CENTRAL = "MT";
    static final String ZONE_SOUTH = "MN";

    /** Địa danh rõ ràng thuộc miền Bắc: có thì giữ miền Bắc dù địa chỉ có tên đường trùng tỉnh miền khác (vd phố Đà Nẵng, Hải Phòng). */
    private static final List<String> NORTH_PLACES = List.of("mien bac", "ha noi", "hai phong", "bac ninh", "hai duong",
            "hung yen", "quang ninh", "thai nguyen", "vinh phuc", "nam dinh", "ha nam", "thai binh", "ninh binh");
    /** Tỉnh / thành miền Trung (viết không dấu, so khớp nguyên cụm từ). */
    private static final List<String> CENTRAL_PLACES = List.of(
            "mien trung", "da nang", "tp hue", "thanh pho hue", "thua thien", "quang nam", "quang ngai", "quang binh", "quang tri",
            "binh dinh", "quy nhon", "phu yen", "khanh hoa", "nha trang", "ninh thuan", "binh thuan", "phan thiet",
            "nghe an", "ha tinh", "thanh hoa", "kon tum", "gia lai", "pleiku", "dak lak", "dak nong",
            "buon ma thuot", "lam dong", "da lat");
    /** Tỉnh / thành miền Nam (viết không dấu, so khớp nguyên cụm từ). */
    private static final List<String> SOUTH_PLACES = List.of(
            "mien nam", "ho chi minh", "tp hcm", "tphcm", "hcm", "sai gon", "binh duong", "dong nai", "ba ria",
            "vung tau", "tay ninh", "binh phuoc", "long an", "tien giang", "ben tre", "tra vinh", "vinh long",
            "dong thap", "an giang", "kien giang", "can tho", "hau giang", "soc trang", "bac lieu", "ca mau");

    /** Mã khu vực MB / MT / MN, hoặc tên khu vực "Miền Bắc / Trung / Nam"; không xác định được thì null. */
    static String zoneOfRegion(Region region) {
        if (region == null) {
            return null;
        }
        String code = region.getCode() != null ? region.getCode().trim().toUpperCase(Locale.ROOT) : "";
        if (ZONE_NORTH.equals(code) || ZONE_CENTRAL.equals(code) || ZONE_SOUTH.equals(code)) {
            return code;
        }
        String name = normalize(region.getName());
        if (containsPhrase(name, "mien bac")) return ZONE_NORTH;
        if (containsPhrase(name, "mien trung")) return ZONE_CENTRAL;
        if (containsPhrase(name, "mien nam")) return ZONE_SOUTH;
        return null;
    }

    /** Đoán miền theo tỉnh / thành trong địa chỉ (so khớp nguyên cụm từ, không dấu); không thấy thì null. */
    static String zoneOfAddress(String address) {
        String text = normalize(address);
        if (text.isEmpty()) {
            return null;
        }
        if (NORTH_PLACES.stream().anyMatch(p -> containsPhrase(text, p))) return ZONE_NORTH;
        if (SOUTH_PLACES.stream().anyMatch(p -> containsPhrase(text, p))) return ZONE_SOUTH;
        if (CENTRAL_PLACES.stream().anyMatch(p -> containsPhrase(text, p))) return ZONE_CENTRAL;
        return null;
    }

    /** Chữ thường, bỏ dấu tiếng Việt, đổi ký tự không phải chữ / số thành khoảng trắng. */
    static String normalize(String s) {
        if (s == null) {
            return "";
        }
        String noMarks = java.text.Normalizer.normalize(s.toLowerCase(Locale.ROOT).replace('đ', 'd'),
                java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return " " + noMarks.replaceAll("[^a-z0-9]+", " ").trim() + " ";
    }

    private static boolean containsPhrase(String normalizedText, String phrase) {
        return normalizedText.contains(" " + phrase + " ");
    }

    private Optional<Warehouse> findFirstActiveWarehouseMatching(String... keywords) {
        List<Warehouse> activeWarehouses = warehouseRepository.findByStatusOrderByNameAsc("ACTIVE");
        for (Warehouse w : activeWarehouses) {
            String combined = (w.getCode() + " " + w.getName()).toLowerCase();
            for (String kw : keywords) {
                if (combined.contains(kw.toLowerCase())) {
                    return Optional.of(w);
                }
            }
        }
        return Optional.empty();
    }

    private Warehouse getDefaultWarehouse() {
        return warehouseRepository.findByStatusOrderByNameAsc("ACTIVE").stream()
                .findFirst()
                .orElseGet(() -> warehouseRepository.findAll().stream().findFirst()
                        .orElseThrow(() -> BusinessException.notFound("Không tìm thấy kho hàng nào trong hệ thống")));
    }

    /**
     * S4-03 AC2: Tồn khả dụng = Tồn thực tế - Tồn đang giữ chỗ cho đơn khác.
     */
    @Transactional(readOnly = true)
    public StockInfoDto getStockInfo(Warehouse warehouse, Product product) {
        if (warehouse == null || product == null || warehouse.getId() == null || product.getId() == null) {
            return StockInfoDto.of(
                    warehouse != null ? warehouse.getCode() : "WH-MB01",
                    warehouse != null ? warehouse.getName() : "Kho Tổng Miền Bắc",
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO
            );
        }

        Optional<Inventory> opt = inventoryRepository.findByWarehouse_IdAndProduct_Id(warehouse.getId(), product.getId());
        if (opt.isPresent()) {
            Inventory inv = opt.get();
            return StockInfoDto.of(
                    warehouse.getCode(),
                    warehouse.getName(),
                    inv.getPhysicalStock(),
                    inv.getReservedStock(),
                    inv.getAvailableStock()
            );
        }

        return StockInfoDto.of(warehouse.getCode(), warehouse.getName(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /**
     * S4-03 AC3 & AC4: Giữ chỗ hàng tồn kho khi chốt đơn hàng (SUBMIT).
     * Áp dụng KHÓA BI QUAN (Pessimistic Write Lock) trên từng dòng tồn kho.
     * Sắp xếp theo productId để chống Deadlock giữa 2 giao dịch đồng thời.
     */
    @Transactional
    public void checkAndReserveStock(SalesOrder order) {
        if (order == null || order.getLines() == null || order.getLines().isEmpty()) {
            return;
        }

        Warehouse warehouse = resolveWarehouseForCustomer(order.getCustomer());

        // Sắp xếp các dòng hàng theo Product ID tăng dần để ngăn ngừa Deadlock đa bảng
        List<SalesOrderLine> sortedLines = new ArrayList<>(order.getLines());
        sortedLines.sort(Comparator.comparing(l -> l.getProduct().getId()));

        for (SalesOrderLine line : sortedLines) {
            Product product = line.getProduct();
            BigDecimal baseQty = line.getBaseQuantity() != null
                    ? line.getBaseQuantity()
                    : line.getQuantity().multiply(line.getConversionFactor()).setScale(4, RoundingMode.HALF_UP);

            // Khoá bi quan dòng tồn kho của mặt hàng tại kho này
            Inventory inventory = inventoryRepository.findByWarehouseIdAndProductIdForUpdate(warehouse.getId(), product.getId())
                    .orElse(null);

            BigDecimal available = inventory != null ? inventory.getAvailableStock() : BigDecimal.ZERO;
            if (inventory == null || baseQty.compareTo(available) > 0) {
                BigDecimal factor = line.getConversionFactor() != null && line.getConversionFactor().signum() > 0
                        ? line.getConversionFactor()
                        : BigDecimal.ONE;
                BigDecimal maxAllowedInUnit = available.compareTo(BigDecimal.ZERO) > 0
                        ? available.divide(factor, 0, RoundingMode.FLOOR)
                        : BigDecimal.ZERO;

                throw new BusinessException(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK",
                        String.format("Mặt hàng '%s' (%s) tại %s không đủ tồn khả dụng (còn %s %s, yêu cầu %s %s). Vui lòng điều chỉnh lại số lượng.",
                                line.getProductName(),
                                line.getProductSku(),
                                warehouse.getName(),
                                maxAllowedInUnit.toPlainString(),
                                line.getUnitName(),
                                line.getQuantity().stripTrailingZeros().toPlainString(),
                                line.getUnitName()),
                        "lines");
            }

            // Tăng lượng giữ chỗ tương ứng
            inventory.setReservedStock(inventory.getReservedStock().add(baseQty));
            inventoryRepository.save(inventory);
            log.info("S4-03: Đã giữ chỗ {} {} (tổng giữ: {}) cho SKU {} tại kho {}",
                    baseQty, product.getBaseUnit(), inventory.getReservedStock(), product.getSku(), warehouse.getCode());
        }

        // S5-06: ghi nhận kho, thời điểm và hạn giữ chỗ trên đơn (cùng transaction với việc tăng giữ chỗ)
        LocalDateTime now = LocalDateTime.now(clock);
        order.setReservedWarehouse(warehouse);
        order.setReservationStatus(SalesOrder.RESERVATION_RESERVED);
        order.setReservedAt(now);
        order.setReservationExpiresAt(now.plusDays(reservationDays));
    }

    /** S5-06: đơn vừa được duyệt -> gia hạn giữ chỗ thêm N ngày để kho kịp soạn hàng. */
    public void renewReservation(SalesOrder order) {
        if (order != null && SalesOrder.RESERVATION_RESERVED.equals(order.getReservationStatus())) {
            order.setReservationExpiresAt(LocalDateTime.now(clock).plusDays(reservationDays));
        }
    }

    /**
     * S5-06: kho dùng để nhả / xuất: kho đã lưu lúc giữ chỗ; đơn cũ trước S5-06 chưa lưu thì xác định lại theo đại lý.
     */
    private Warehouse reservationWarehouse(SalesOrder order) {
        return order.getReservedWarehouse() != null
                ? order.getReservedWarehouse()
                : resolveWarehouseForCustomer(order.getCustomer());
    }

    /** S6-02: diễn giải dòng thẻ kho khi xuất bán. */
    private static String dispatchNote(SalesOrder order) {
        Customer c = order.getCustomer();
        return c != null && StringUtils.hasText(c.getName()) ? "Xuất bán cho đại lý " + c.getName() : "Xuất bán";
    }

    /** S5-06: đơn đã nhả hoặc đã xuất kho thì không trừ giữ chỗ lần nữa. */
    private static boolean reservationClosed(SalesOrder order) {
        return SalesOrder.RESERVATION_RELEASED.equals(order.getReservationStatus())
                || SalesOrder.RESERVATION_DISPATCHED.equals(order.getReservationStatus());
    }

    /**
     * S4-03: Giải phóng hàng giữ chỗ khi đơn hàng bị Từ chối (REJECTED), Trả lại sửa (RETURNED TO DRAFT), hoặc Hủy.
     */
    @Transactional
    public void releaseReservedStock(SalesOrder order) {
        if (order == null || order.getLines() == null || order.getLines().isEmpty()) {
            return;
        }
        if (reservationClosed(order)) {
            log.info("S5-06: Đơn {} đã nhả / đã xuất kho trước đó, bỏ qua nhả giữ chỗ lần nữa", order.getCode());
            return;
        }

        Warehouse warehouse = reservationWarehouse(order);

        List<SalesOrderLine> sortedLines = new ArrayList<>(order.getLines());
        sortedLines.sort(Comparator.comparing(l -> l.getProduct().getId()));

        for (SalesOrderLine line : sortedLines) {
            Product product = line.getProduct();
            BigDecimal baseQty = line.getBaseQuantity() != null
                    ? line.getBaseQuantity()
                    : line.getQuantity().multiply(line.getConversionFactor()).setScale(4, RoundingMode.HALF_UP);

            Inventory inventory = inventoryRepository.findByWarehouseIdAndProductIdForUpdate(warehouse.getId(), product.getId())
                    .orElse(null);

            if (inventory != null) {
                BigDecimal currentReserved = inventory.getReservedStock() != null ? inventory.getReservedStock() : BigDecimal.ZERO;
                BigDecimal updated = currentReserved.subtract(baseQty);
                if (updated.compareTo(BigDecimal.ZERO) < 0) {
                    updated = BigDecimal.ZERO;
                }
                inventory.setReservedStock(updated);
                inventoryRepository.save(inventory);
                log.info("S4-03: Đã giải phóng giữ chỗ {} {} (còn giữ: {}) cho SKU {} tại kho {}",
                        baseQty, product.getBaseUnit(), updated, product.getSku(), warehouse.getCode());
            }
        }
        order.setReservationStatus(SalesOrder.RESERVATION_RELEASED);
        order.setReservationExpiresAt(null);
    }

    /**
     * S4-06: Xuất kho đơn hàng (DISPATCHED).
     * Trừ lượng tồn thực tế (physicalStock) và giải phóng lượng tồn đang giữ chỗ (reservedStock).
     */
    @Transactional
    public void dispatchReservedStock(SalesOrder order) {
        dispatchReservedStock(order, null);
    }

    /** S6-02: như trên, ghi thẻ kho (Xuất bán) kèm người thực hiện. Xuất đủ mọi dòng. */
    @Transactional
    public void dispatchReservedStock(SalesOrder order, UserDetailsImpl actor) {
        dispatchStock(order, null, actor);
    }

    /** S6-05: số lượng thực xuất (ĐVT cơ sở) của 1 dòng đơn. */
    public record DispatchLine(SalesOrderLine line, BigDecimal baseQuantity) {
    }

    /**
     * S6-05: Xuất kho đơn hàng, cho phép xuất thiếu.
     * - dispatchLines: số thực xuất từng dòng (ĐVT cơ sở); null = xuất đủ mọi dòng. Dòng không có trong danh sách = xuất đủ.
     * - Trừ tồn thực tế đúng số thực xuất; giải phóng TOÀN BỘ phần giữ chỗ của đơn (phần còn nợ không giữ chỗ nữa,
     *   đơn nợ hàng sẽ kiểm tồn khả dụng lúc xuất).
     * - Đơn không còn giữ chỗ (đã nhả / đơn nợ hàng) thì phải đủ tồn KHẢ DỤNG, không lấy phần đang giữ cho đơn khác.
     * - Gọi TRƯỚC khi tách dòng (cần baseQuantity gốc của dòng = phần đang giữ chỗ).
     * Khoá dòng tồn theo thứ tự productId (tránh deadlock), kiểm tra hết rồi mới trừ.
     */
    @Transactional
    public void dispatchStock(SalesOrder order, List<DispatchLine> dispatchLines, UserDetailsImpl actor) {
        if (order == null || order.getLines() == null || order.getLines().isEmpty()) {
            return;
        }
        if (SalesOrder.RESERVATION_DISPATCHED.equals(order.getReservationStatus())) {
            return;
        }
        boolean wasReleased = SalesOrder.RESERVATION_RELEASED.equals(order.getReservationStatus());

        Warehouse warehouse = reservationWarehouse(order);

        // Gộp theo sản phẩm (một sản phẩm có thể nằm ở nhiều dòng): số xuất và số đang giữ chỗ
        Map<Long, BigDecimal> dispatchByProduct = new TreeMap<>();
        Map<Long, BigDecimal> reservedByProduct = new TreeMap<>();
        Map<Long, SalesOrderLine> sampleLine = new HashMap<>();
        for (SalesOrderLine line : order.getLines()) {
            Long productId = line.getProduct().getId();
            BigDecimal lineBase = lineBaseQuantity(line);
            BigDecimal dispatchBase = lineBase;
            if (dispatchLines != null) {
                for (DispatchLine d : dispatchLines) {
                    if (d.line() == line) {
                        dispatchBase = d.baseQuantity();
                    }
                }
            }
            if (dispatchBase.signum() < 0 || dispatchBase.compareTo(lineBase) > 0) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_DISPATCH_QUANTITY",
                        "Dòng " + line.getProductSku() + ": số thực xuất phải từ 0 đến số lượng đặt", "lines");
            }
            dispatchByProduct.merge(productId, dispatchBase, BigDecimal::add);
            reservedByProduct.merge(productId, lineBase, BigDecimal::add);
            sampleLine.putIfAbsent(productId, line);
        }

        // Bước 1: khoá mọi dòng tồn và kiểm tra đủ tồn trước khi trừ (thiếu -> báo lỗi, không trừ âm rồi ép về 0)
        List<Inventory> inventories = new ArrayList<>();
        List<Long> productIds = new ArrayList<>(dispatchByProduct.keySet());
        for (Long productId : productIds) {
            SalesOrderLine line = sampleLine.get(productId);
            Product product = line.getProduct();
            BigDecimal qty = dispatchByProduct.get(productId);

            Inventory inventory = inventoryRepository.findByWarehouseIdAndProductIdForUpdate(warehouse.getId(), productId)
                    .orElseThrow(() -> BusinessException.conflict("INVENTORY_NOT_FOUND",
                            "Mặt hàng '" + line.getProductName() + "' (" + line.getProductSku() + ") chưa có dòng tồn tại "
                                    + warehouse.getName() + ", không xuất kho được", "lines"));
            BigDecimal currentPhysical = inventory.getPhysicalStock() != null ? inventory.getPhysicalStock() : BigDecimal.ZERO;
            if (currentPhysical.compareTo(qty) < 0) {
                throw BusinessException.conflict("INSUFFICIENT_PHYSICAL_STOCK",
                        "Mặt hàng '" + line.getProductName() + "' (" + line.getProductSku() + ") tại " + warehouse.getName()
                                + " chỉ còn " + plain(currentPhysical) + " " + product.getBaseUnit()
                                + " thực tế, cần xuất " + plain(qty) + " " + product.getBaseUnit()
                                + ". Kiểm tra lại kiểm kê / nhập kho, hoặc xác nhận xuất thiếu.", "lines");
            }
            if (wasReleased) {
                BigDecimal available = inventory.getAvailableStock();
                if (available.compareTo(qty) < 0) {
                    throw BusinessException.conflict("INSUFFICIENT_AVAILABLE_STOCK",
                            "Mặt hàng '" + line.getProductName() + "' (" + line.getProductSku() + ") tại " + warehouse.getName()
                                    + " chỉ còn " + plain(available.max(BigDecimal.ZERO)) + " " + product.getBaseUnit()
                                    + " khả dụng (phần còn lại đang giữ cho đơn khác), cần xuất " + plain(qty) + " "
                                    + product.getBaseUnit() + ". Hãy xuất thiếu hoặc chờ nhập thêm hàng.", "lines");
                }
            }
            inventories.add(inventory);
        }

        // Bước 2: trừ tồn thực tế và giải phóng giữ chỗ
        for (int i = 0; i < productIds.size(); i++) {
            Long productId = productIds.get(i);
            Product product = sampleLine.get(productId).getProduct();
            Inventory inventory = inventories.get(i);
            BigDecimal qty = dispatchByProduct.get(productId);

            BigDecimal updatedPhysical = inventory.getPhysicalStock().subtract(qty);
            inventory.setPhysicalStock(updatedPhysical);

            // Giải phóng giữ chỗ (đơn đã nhả trước đó thì không trừ lần nữa)
            BigDecimal currentReserved = inventory.getReservedStock() != null ? inventory.getReservedStock() : BigDecimal.ZERO;
            BigDecimal updatedReserved = wasReleased ? currentReserved : currentReserved.subtract(reservedByProduct.get(productId));
            if (updatedReserved.compareTo(BigDecimal.ZERO) < 0) {
                updatedReserved = BigDecimal.ZERO;
            }
            inventory.setReservedStock(updatedReserved);

            inventoryRepository.save(inventory);
            stockMovementService.record(inventory, StockMovement.TYPE_SALES_DISPATCH, qty.negate(),
                    StockMovement.DOC_SALES_ORDER, order.getId(), order.getCode(),
                    dispatchNote(order), actor);
            log.info("S4-06: Đã xuất kho đơn hàng {}: trừ tồn thực tế {} {} (còn {}), giải phóng giữ chỗ (còn {}) cho SKU {} tại kho {}",
                    order.getCode(), qty, product.getBaseUnit(), updatedPhysical, updatedReserved, product.getSku(), warehouse.getCode());
        }
        order.setReservationStatus(SalesOrder.RESERVATION_DISPATCHED);
        order.setReservationExpiresAt(null);
    }

    /**
     * S6-08: Hàng giao không thành công / đại lý không nhận được chở về -> cộng lại tồn thực tế của kho đã xuất.
     * returnLines: số lượng nhập lại theo ĐVT cơ sở từng dòng (bỏ qua dòng 0). Không đụng giữ chỗ.
     */
    @Transactional
    public void returnDeliveredStock(SalesOrder order, List<DispatchLine> returnLines, String note, UserDetailsImpl actor) {
        if (order == null || returnLines == null || returnLines.isEmpty()) {
            return;
        }
        Warehouse warehouse = reservationWarehouse(order);
        Map<Long, BigDecimal> byProduct = new TreeMap<>();
        Map<Long, Product> products = new HashMap<>();
        for (DispatchLine d : returnLines) {
            if (d.baseQuantity() == null || d.baseQuantity().signum() <= 0) {
                continue;
            }
            Product p = d.line().getProduct();
            byProduct.merge(p.getId(), d.baseQuantity(), BigDecimal::add);
            products.putIfAbsent(p.getId(), p);
        }
        for (Map.Entry<Long, BigDecimal> e : byProduct.entrySet()) {
            Product product = products.get(e.getKey());
            Inventory inventory = inventoryRepository.findByWarehouseIdAndProductIdForUpdate(warehouse.getId(), e.getKey())
                    .orElseGet(() -> inventoryRepository.save(Inventory.builder()
                            .warehouse(warehouse)
                            .product(product)
                            .physicalStock(BigDecimal.ZERO)
                            .reservedStock(BigDecimal.ZERO)
                            .build()));
            BigDecimal current = inventory.getPhysicalStock() != null ? inventory.getPhysicalStock() : BigDecimal.ZERO;
            inventory.setPhysicalStock(current.add(e.getValue()));
            inventoryRepository.save(inventory);
            stockMovementService.record(inventory, StockMovement.TYPE_DELIVERY_RETURN, e.getValue(),
                    StockMovement.DOC_SALES_ORDER, order.getId(), order.getCode(), note, actor);
            log.info("S6-08: Nhập lại kho {} {} {} của đơn {} (giao không thành công)", warehouse.getCode(),
                    e.getValue(), product.getBaseUnit(), order.getCode());
        }
    }

    /** Số lượng ĐVT cơ sở của dòng (đơn cũ thiếu baseQuantity thì quy đổi lại). */
    static BigDecimal lineBaseQuantity(SalesOrderLine line) {
        return line.getBaseQuantity() != null
                ? line.getBaseQuantity()
                : line.getQuantity().multiply(line.getConversionFactor()).setScale(4, RoundingMode.HALF_UP);
    }

    private static String plain(BigDecimal v) {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }
}
