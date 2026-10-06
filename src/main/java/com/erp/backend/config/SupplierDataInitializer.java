package com.erp.backend.config;

import com.erp.backend.entity.Supplier;
import com.erp.backend.repository.SupplierRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * S2-09: Nạp sẵn danh mục nhà cung cấp mẫu khi database chưa có nhà cung cấp nào,
 * để người dùng chọn / test ngay thay vì phải tự tạo mới (nhận xét của giáo viên).
 * Chỉ chạy khi bảng suppliers đang trống, không ghi đè dữ liệu đã có.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SupplierDataInitializer implements CommandLineRunner {

    private final SupplierRepository supplierRepository;

    @Override
    public void run(String... args) {
        if (supplierRepository.count() > 0) {
            return;
        }
        List<Supplier> samples = List.of(
                supplier("NCC-HABECO", "Tổng Công ty Bia - Rượu - Nước giải khát Hà Nội (HABECO)", "0100101308",
                        "Nguyễn Văn Hùng", "0912345601", "kinhdoanh@habeco.vn", "183 Hoàng Hoa Thám, Ba Đình, Hà Nội",
                        "Thanh toán 30 ngày kể từ ngày nhận hàng"),
                supplier("NCC-SABECO", "Tổng Công ty Bia - Rượu - Nước giải khát Sài Gòn (SABECO)", "0300583659",
                        "Trần Thị Mai", "0903456702", "sales@sabeco.vn", "187 Nguyễn Chí Thanh, Quận 5, TP.HCM",
                        "Thanh toán 30 ngày, chiết khấu 1% nếu trả trong 7 ngày"),
                supplier("NCC-COCACOLA", "Công ty TNHH Nước giải khát Coca-Cola Việt Nam", "0300792451",
                        "Lê Minh Tuấn", "0938567803", "order@coca-cola.vn", "485 Xa lộ Hà Nội, TP. Thủ Đức, TP.HCM",
                        "Thanh toán 15 ngày"),
                supplier("NCC-PEPSICO", "Công ty TNHH Nước giải khát Suntory PepsiCo Việt Nam", "0300816663",
                        "Phạm Quốc Bảo", "0977678904", "b2b@suntorypepsico.vn", "88 Đồng Khởi, Quận 1, TP.HCM",
                        "Thanh toán 15 ngày"),
                supplier("NCC-LAVIE", "Công ty TNHH La Vie", "1100101003",
                        "Hoàng Thu Hà", "0868789005", "sales@lavie.com.vn", "Quốc lộ 1A, Khánh Hậu, Tân An, Long An",
                        "Thanh toán ngay khi nhận hàng"),
                supplier("NCC-VINAMILK", "Công ty Cổ phần Sữa Việt Nam (Vinamilk)", "0300588569",
                        "Đỗ Thanh Tùng", "0389890106", "phanphoi@vinamilk.com.vn", "10 Tân Trào, Quận 7, TP.HCM",
                        "Thanh toán 45 ngày"),
                supplier("NCC-ACECOOK", "Công ty Cổ phần Acecook Việt Nam", "0300808687",
                        "Vũ Hải Yến", "0356901207", "kinhdoanh@acecookvietnam.com", "Lô II-3 KCN Tân Bình, TP.HCM",
                        "Thanh toán 30 ngày"),
                supplier("NCC-MASAN", "Công ty Cổ phần Hàng tiêu dùng Masan", "0302017440",
                        "Bùi Đức Long", "0789012308", "npp@masanconsumer.com", "39 Lê Duẩn, Quận 1, TP.HCM",
                        "Thanh toán 30 ngày, giao tại kho nhà phân phối"));
        supplierRepository.saveAll(samples);
        log.info("Đã nạp {} nhà cung cấp mẫu (S2-09).", samples.size());
    }

    private static Supplier supplier(String code, String name, String taxCode, String contactName, String phone,
                                     String email, String address, String paymentTerms) {
        return Supplier.builder()
                .code(code)
                .name(name)
                .taxCode(taxCode)
                .contactName(contactName)
                .phone(phone)
                .email(email)
                .address(address)
                .paymentTerms(paymentTerms)
                .note("Dữ liệu mẫu")
                .status("ACTIVE")
                .build();
    }
}
