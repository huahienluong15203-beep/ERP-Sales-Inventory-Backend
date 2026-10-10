package com.erp.backend.service;

import com.erp.backend.entity.*;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.InventoryRepository;
import com.erp.backend.repository.WarehouseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** S5-06: Giữ chỗ tồn ghi nhận kho + hạn giữ chỗ, nhả đúng kho, không nhả 2 lần, lỗi giữa chừng thì không ghi nhận. */
@ExtendWith(MockitoExtension.class)
class InventoryReservationTest {

    @Mock private InventoryRepository inventoryRepository;
    @Mock private WarehouseRepository warehouseRepository;

    @InjectMocks private InventoryService service;

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 10, 9, 0);
    private final Warehouse north = Warehouse.builder().id(1L).code("WH-MB01").name("Kho Tổng Miền Bắc").status("ACTIVE").build();
    private final Warehouse south = Warehouse.builder().id(3L).code("WH-MN01").name("Kho Tổng Miền Nam").status("ACTIVE").build();
    private final Product beer = Product.builder().id(10L).sku("BIA-HN-01").name("Bia Hà Nội").baseUnit("Lon").status("ACTIVE").build();
    private final Product coca = Product.builder().id(11L).sku("SP-COCA").name("Coca").baseUnit("Lon").status("ACTIVE").build();
    private final Customer hanoi = Customer.builder().id(100L).address("Hà Nội").build();

    @BeforeEach
    void setUp() {
        service.clock = Clock.fixed(NOW.atZone(VN).toInstant(), VN);
        service.reservationDays = 7;
    }

    private SalesOrder order(Product p, String qty) {
        SalesOrder o = SalesOrder.builder().id(1001L).code("DH261010-AAAA").customer(hanoi).build();
        o.addLine(line(p, qty));
        return o;
    }

    private static SalesOrderLine line(Product p, String qty) {
        return SalesOrderLine.builder().product(p).productSku(p.getSku()).productName(p.getName())
                .unitName("Lon").conversionFactor(BigDecimal.ONE)
                .quantity(new BigDecimal(qty)).baseQuantity(new BigDecimal(qty)).build();
    }

    private Inventory inv(Warehouse w, Product p, String physical, String reserved) {
        return Inventory.builder().id(p.getId() * 10 + w.getId()).warehouse(w).product(p)
                .physicalStock(new BigDecimal(physical)).reservedStock(new BigDecimal(reserved)).build();
    }

    @Test
    @DisplayName("S5-06: Giữ chỗ ghi nhận kho, thời điểm và hạn giữ chỗ 7 ngày trên đơn")
    void reserve_recordsWarehouseAndExpiry() {
        Inventory stock = inv(north, beer, "100", "0");
        when(warehouseRepository.findByCodeIgnoreCase("WH-MB01")).thenReturn(Optional.of(north));
        when(inventoryRepository.findByWarehouseIdAndProductIdForUpdate(1L, 10L)).thenReturn(Optional.of(stock));
        SalesOrder o = order(beer, "30");

        service.checkAndReserveStock(o);

        assertThat(stock.getReservedStock()).isEqualByComparingTo("30");
        assertThat(o.getReservedWarehouse()).isSameAs(north);
        assertThat(o.getReservationStatus()).isEqualTo(SalesOrder.RESERVATION_RESERVED);
        assertThat(o.getReservedAt()).isEqualTo(NOW);
        assertThat(o.getReservationExpiresAt()).isEqualTo(NOW.plusDays(7));
    }

    @Test
    @DisplayName("S5-06: Dòng thứ 2 thiếu tồn -> ném lỗi, đơn không được ghi nhận là đang giữ chỗ (transaction hoàn lại)")
    void reserve_secondLineFails_noReservationRecorded() {
        when(warehouseRepository.findByCodeIgnoreCase("WH-MB01")).thenReturn(Optional.of(north));
        when(inventoryRepository.findByWarehouseIdAndProductIdForUpdate(1L, 10L)).thenReturn(Optional.of(inv(north, beer, "100", "0")));
        when(inventoryRepository.findByWarehouseIdAndProductIdForUpdate(1L, 11L)).thenReturn(Optional.of(inv(north, coca, "5", "5")));
        SalesOrder o = order(beer, "10");
        o.addLine(line(coca, "1"));

        assertThatThrownBy(() -> service.checkAndReserveStock(o))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "INSUFFICIENT_STOCK");
        assertThat(o.getReservationStatus()).isNull();
        assertThat(o.getReservedWarehouse()).isNull();
    }

    @Test
    @DisplayName("S5-06: Nhả giữ chỗ dùng đúng kho đã giữ dù đại lý đã đổi sang khu vực khác")
    void release_usesStoredWarehouse() {
        Inventory southStock = inv(south, beer, "50", "20");
        when(inventoryRepository.findByWarehouseIdAndProductIdForUpdate(3L, 10L)).thenReturn(Optional.of(southStock));
        SalesOrder o = order(beer, "15");
        o.setReservedWarehouse(south);
        o.setReservationStatus(SalesOrder.RESERVATION_RESERVED);

        service.releaseReservedStock(o);

        assertThat(southStock.getReservedStock()).isEqualByComparingTo("5");
        assertThat(o.getReservationStatus()).isEqualTo(SalesOrder.RESERVATION_RELEASED);
        assertThat(o.getReservationExpiresAt()).isNull();
        verifyNoInteractions(warehouseRepository);
    }

    @Test
    @DisplayName("S5-06: Đơn đã nhả rồi thì không nhả lần 2 (không làm lệch số giữ chỗ của đơn khác)")
    void release_twice_noop() {
        SalesOrder o = order(beer, "15");
        o.setReservedWarehouse(north);
        o.setReservationStatus(SalesOrder.RESERVATION_RELEASED);

        service.releaseReservedStock(o);

        verifyNoInteractions(inventoryRepository, warehouseRepository);
    }

    @Test
    @DisplayName("S5-06: Xuất kho đơn đã bị nhả giữ chỗ -> chỉ trừ tồn thực tế, không trừ giữ chỗ lần nữa")
    void dispatch_afterRelease_doesNotReduceReservedAgain() {
        Inventory stock = inv(north, beer, "50", "20");
        when(inventoryRepository.findByWarehouseIdAndProductIdForUpdate(1L, 10L)).thenReturn(Optional.of(stock));
        SalesOrder o = order(beer, "15");
        o.setReservedWarehouse(north);
        o.setReservationStatus(SalesOrder.RESERVATION_RELEASED);

        service.dispatchReservedStock(o);

        assertThat(stock.getPhysicalStock()).isEqualByComparingTo("35");
        assertThat(stock.getReservedStock()).isEqualByComparingTo("20");
        assertThat(o.getReservationStatus()).isEqualTo(SalesOrder.RESERVATION_DISPATCHED);
    }

    @Test
    @DisplayName("S5-06: Đơn được duyệt -> gia hạn giữ chỗ thêm 7 ngày tính từ lúc duyệt")
    void renew_onApproval() {
        SalesOrder o = order(beer, "1");
        o.setReservationStatus(SalesOrder.RESERVATION_RESERVED);
        o.setReservationExpiresAt(NOW.minusDays(1));

        service.renewReservation(o);

        assertThat(o.getReservationExpiresAt()).isEqualTo(NOW.plusDays(7));
        verify(inventoryRepository, never()).save(any());
    }
}
