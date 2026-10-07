package com.erp.backend.service;

import com.erp.backend.dto.user.UserImportPreviewResponse;
import com.erp.backend.dto.user.UserImportSummaryResponse;
import com.erp.backend.entity.Role;
import com.erp.backend.entity.RoleName;
import com.erp.backend.entity.User;
import com.erp.backend.entity.Warehouse;
import com.erp.backend.repository.RegionRepository;
import com.erp.backend.repository.RoleRepository;
import com.erp.backend.repository.UserRepository;
import com.erp.backend.repository.WarehouseRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Unit test UserExcelImportService - SCRUM-18 (S2-01) Nhập danh sách người dùng hàng loạt từ Excel")
class UserExcelImportServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private WarehouseRepository warehouseRepository;
    @Mock private RegionRepository regionRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private TempPasswordGenerator tempPasswordGenerator;
    @Mock private MailService mailService;

    @InjectMocks
    private UserExcelImportService importService;

    private Warehouse sampleWarehouse;
    private Role salesRole;
    private Role whRole;

    @BeforeEach
    void setUp() {
        sampleWarehouse = Warehouse.builder().id(1L).code("WH-MB01").name("Kho Tổng Miền Bắc").build();
        salesRole = Role.builder().id(10L).name(RoleName.ROLE_SALES_REP).build();
        whRole = Role.builder().id(11L).name(RoleName.ROLE_WAREHOUSE).build();

        lenient().when(warehouseRepository.findByCodeIgnoreCase("WH-MB01")).thenReturn(Optional.of(sampleWarehouse));
        lenient().when(roleRepository.findByName(RoleName.ROLE_SALES_REP)).thenReturn(Optional.of(salesRole));
        lenient().when(roleRepository.findByName(RoleName.ROLE_WAREHOUSE)).thenReturn(Optional.of(whRole));
        lenient().when(passwordEncoder.encode(anyString())).thenReturn("hashed_temp_pwd");
        lenient().when(tempPasswordGenerator.generate()).thenReturn("Temp@123456");
        lenient().when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(999L);
            return u;
        });
    }

    @Test
    @DisplayName("S2-01 AC1: Tải được tệp mẫu Excel có cấu trúc cột chuẩn và hướng dẫn")
    void generateTemplate_Success() throws IOException {
        byte[] bytes = importService.generateTemplate();
        assertThat(bytes).isNotEmpty();

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(2);
            Sheet s1 = wb.getSheet("DanhSachNguoiDung");
            assertThat(s1).isNotNull();
            Row header = s1.getRow(0);
            assertThat(header.getCell(1).getStringCellValue()).contains("Tên đăng nhập");
            assertThat(header.getCell(3).getStringCellValue()).contains("Email");
            assertThat(header.getCell(5).getStringCellValue()).contains("Mã vai trò");

            Sheet s2 = wb.getSheet("HuongDan_QuyDinh");
            assertThat(s2).isNotNull();
        }
    }

    @Test
    @DisplayName("S2-01 AC2: Xem trước và báo lỗi theo từng dòng trước khi nhập")
    void previewImport_ValidatesRowByRow() throws IOException {
        // Mock dữ liệu DB
        when(userRepository.existsByUsernameIgnoreCase("existing_user")).thenReturn(true);
        when(userRepository.existsByEmailIgnoreCase("dup@erp.com")).thenReturn(false);

        // Tạo file excel test gồm 3 dòng:
        // Dòng 1: Hợp lệ (sales)
        // Dòng 2: Trùng username
        // Dòng 3: Vai trò kho nhưng thiếu mã kho (vi phạm S1-09)
        byte[] excelData = createTestWorkbook(
                new Object[]{"1", "sales_new", "Nguyễn Văn Hợp Lệ", "valid@erp.com", "0912345678", "ROLE_SALES_REP", "", ""},
                new Object[]{"2", "existing_user", "Trần Trùng Lặp", "trung@erp.com", "0987654321", "ROLE_SALES_REP", "", ""},
                new Object[]{"3", "wh_staff_err", "Lê Thiếu Kho", "wh@erp.com", "0933333333", "ROLE_WAREHOUSE", "", ""}
        );

        MockMultipartFile file = new MockMultipartFile(
                "file", "test_users.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                excelData
        );

        UserImportPreviewResponse preview = importService.previewImport(file);

        assertThat(preview.getTotalRows()).isEqualTo(3);
        assertThat(preview.getValidRowsCount()).isEqualTo(1);
        assertThat(preview.getInvalidRowsCount()).isEqualTo(2);

        // Dòng 1: hợp lệ
        assertThat(preview.getRows().get(0).isValid()).isTrue();
        assertThat(preview.getRows().get(0).getErrors()).isEmpty();

        // Dòng 2: lỗi trùng username
        assertThat(preview.getRows().get(1).isValid()).isFalse();
        assertThat(preview.getRows().get(1).getErrors()).anyMatch(e -> e.contains("Tên đăng nhập 'existing_user' đã tồn tại"));

        // Dòng 3: lỗi thiếu kho cho vai trò kho
        assertThat(preview.getRows().get(2).isValid()).isFalse();
        assertThat(preview.getRows().get(2).getErrors()).anyMatch(e -> e.contains("bắt buộc phải gắn với ít nhất một mã kho"));
    }

    @Test
    @DisplayName("S2-01 AC3: Dòng lỗi bị bỏ qua, dòng hợp lệ vẫn được nhập, có báo cáo tổng kết")
    void executeImport_SkipsErrorsAndImportsValidRows() throws IOException {
        when(userRepository.existsByUsernameIgnoreCase("bad_user")).thenReturn(true);

        byte[] excelData = createTestWorkbook(
                new Object[]{"1", "good_user", "Người Dùng Đúng", "good@erp.com", "0912345678", "ROLE_SALES_REP", "", ""},
                new Object[]{"2", "bad_user", "Người Dùng Lỗi", "bad@erp.com", "0987654321", "ROLE_SALES_REP", "", ""}
        );

        MockMultipartFile file = new MockMultipartFile(
                "file", "import_users.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                excelData
        );

        UserImportSummaryResponse summary = importService.executeImport(file);

        assertThat(summary.getTotalProcessed()).isEqualTo(2);
        assertThat(summary.getSuccessCount()).isEqualTo(1);
        assertThat(summary.getFailedCount()).isEqualTo(1);

        // Chỉ lưu dòng hợp lệ
        assertThat(summary.getCreatedUsers()).hasSize(1);
        assertThat(summary.getCreatedUsers().get(0).getUsername()).isEqualTo("good_user");

        // Dòng lỗi bị đưa vào failedRows
        assertThat(summary.getFailedRows()).hasSize(1);
        assertThat(summary.getFailedRows().get(0).getUsername()).isEqualTo("bad_user");

        verify(userRepository, times(1)).save(any(User.class));
    }

    private byte[] createTestWorkbook(Object[]... dataRows) throws IOException {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Users");
            Row hRow = sheet.createRow(0);
            String[] headers = {"STT", "Username", "FullName", "Email", "Phone", "Role", "Warehouse", "Region"};
            for (int i = 0; i < headers.length; i++) {
                hRow.createCell(i).setCellValue(headers[i]);
            }

            for (int r = 0; r < dataRows.length; r++) {
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < dataRows[r].length; c++) {
                    row.createCell(c).setCellValue(String.valueOf(dataRows[r][c]));
                }
            }

            wb.write(out);
            return out.toByteArray();
        }
    }
}
