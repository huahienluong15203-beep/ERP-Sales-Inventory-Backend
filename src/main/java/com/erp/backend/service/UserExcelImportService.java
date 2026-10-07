package com.erp.backend.service;

import com.erp.backend.dto.user.UserImportPreviewResponse;
import com.erp.backend.dto.user.UserImportRowDto;
import com.erp.backend.dto.user.UserImportSummaryResponse;
import com.erp.backend.entity.*;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.RegionRepository;
import com.erp.backend.repository.RoleRepository;
import com.erp.backend.repository.UserRepository;
import com.erp.backend.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Service xử lý Nhập danh sách người dùng hàng loạt từ Excel (SCRUM-18 / S2-01).
 * Tuân thủ:
 * - Tải tệp mẫu chuẩn (.xlsx) có hướng dẫn rõ ràng.
 * - Xem trước (Preview) và báo lỗi chi tiết theo từng dòng trước khi nhập.
 * - Dòng lỗi bị bỏ qua, dòng hợp lệ vẫn được nhập, có báo cáo tổng kết chi tiết.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserExcelImportService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final WarehouseRepository warehouseRepository;
    private final RegionRepository regionRepository;
    private final PasswordEncoder passwordEncoder;
    private final TempPasswordGenerator tempPasswordGenerator;
    private final MailService mailService;

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9._-]{3,50}$");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9+_.-]+@(.+)$");
    private static final Pattern PHONE_PATTERN = Pattern.compile("^(0|\\+84)(3|5|7|8|9)\\d{8}$");

    /**
     * S2-01: Tạo tệp Excel mẫu chứa bảng dữ liệu mẫu và sheet hướng dẫn.
     */
    public byte[] generateTemplate() {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            // Kiểu font và style header
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());

            CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);
            headerStyle.setBorderBottom(BorderStyle.THIN);

            CellStyle textStyle = workbook.createCellStyle();
            DataFormat format = workbook.createDataFormat();
            textStyle.setDataFormat(format.getFormat("@")); // Định dạng text để không bị mất số 0 ở đầu SĐT

            // 1. Sheet DanhSachNguoiDung
            Sheet sheet1 = workbook.createSheet("DanhSachNguoiDung");
            String[] headers = {
                    "STT",
                    "Tên đăng nhập (*)",
                    "Họ và tên (*)",
                    "Email (*)",
                    "Số điện thoại",
                    "Mã vai trò (*)",
                    "Mã kho hàng",
                    "Mã địa bàn"
            };

            Row headerRow = sheet1.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            // Dữ liệu mẫu (sử dụng tài khoản mới chưa seed, và có dòng để trống số điện thoại làm mẫu)
            Object[][] sampleData = {
                    {1, "sales_north_01", "Phan Văn Nam", "nam.pv@erp.com", "0981112233", "ROLE_SALES_REP", "", "MB"},
                    {2, "sales_north_02", "Lê Thị Bích", "bich.lt@erp.com", "", "ROLE_SALES_REP", "", "MB"},
                    {3, "wh_staff_dn01", "Trần Đình Trọng", "trong.td@erp.com", "0982223344", "ROLE_WAREHOUSE", "WH-MT01", "MT"},
                    {4, "acc_south_01", "Hoàng Kim Oanh", "oanh.hk@erp.com", "", "ROLE_ACCOUNTANT", "", ""}
            };

            for (int r = 0; r < sampleData.length; r++) {
                Row row = sheet1.createRow(r + 1);
                for (int c = 0; c < sampleData[r].length; c++) {
                    Cell cell = row.createCell(c);
                    cell.setCellStyle(textStyle);
                    Object val = sampleData[r][c];
                    if (val instanceof Number) {
                        cell.setCellValue(((Number) val).intValue());
                    } else {
                        cell.setCellValue(val != null ? val.toString() : "");
                    }
                }
            }

            for (int i = 0; i < headers.length; i++) {
                sheet1.setColumnWidth(i, 20 * 256);
            }

            // 2. Sheet HuongDan_QuyDinh
            Sheet sheet2 = workbook.createSheet("HuongDan_QuyDinh");
            Row guideHeader = sheet2.createRow(0);
            Cell gCell = guideHeader.createCell(0);
            gCell.setCellValue("HƯỚNG DẪN QUY CHUẨN NHẬP DỮ LIỆU NGƯỜI DÙNG TỪ EXCEL");
            gCell.setCellStyle(headerStyle);

            List<String> guidelines = List.of(
                    "1. Các cột có dấu (*) là bắt buộc phải nhập dữ liệu.",
                    "2. Tên đăng nhập: Từ 3 - 50 ký tự, viết liền không dấu, chỉ gồm chữ cái, số, dấu chấm (.) hoặc gạch ngang/dưới.",
                    "3. Email: Phải đúng định dạng chuẩn (vd: ten@domain.com) và chưa từng được sử dụng trong hệ thống.",
                    "4. Số điện thoại: Tùy chọn (cho phép để trống để nhân viên tự cập nhật sau trong Hồ sơ cá nhân). Nếu nhập thì phải đủ 10 số (đầu 03, 05, 07, 08, 09) và không trùng với tài khoản khác.",
                    "5. Mã vai trò hợp lệ (nhiều vai trò thì cách nhau bằng dấu phẩy):",
                    "   - ROLE_ADMIN: Quản trị hệ thống",
                    "   - ROLE_SALES_MANAGER: Quản lý kinh doanh",
                    "   - ROLE_SALES_REP: Nhân viên kinh doanh",
                    "   - ROLE_WAREHOUSE: Nhân viên kho (BẮT BUỘC gắn với ít nhất một mã kho hợp lệ)",
                    "   - ROLE_WH_MANAGER: Quản lý kho (BẮT BUỘC gắn với ít nhất một mã kho hợp lệ)",
                    "   - ROLE_ACCOUNTANT: Kế toán công nợ",
                    "   - ROLE_CUSTOMER: Đại lý",
                    "6. Ràng buộc kho (S1-09): Tài khoản thuộc vai trò Nhân viên kho hoặc Quản lý kho bắt buộc phải nhập Mã kho.",
                    "7. Dòng có lỗi sẽ tự động được hệ thống đánh dấu và bỏ qua, các dòng hợp lệ vẫn sẽ được nhập thành công."
            );

            for (int i = 0; i < guidelines.size(); i++) {
                Row row = sheet2.createRow(i + 2);
                row.createCell(0).setCellValue(guidelines.get(i));
            }

            // Danh sách kho hiện có trong hệ thống
            int rowIdx = guidelines.size() + 4;
            Row whHeader = sheet2.createRow(rowIdx++);
            whHeader.createCell(0).setCellValue("DANH SÁCH MÃ KHO HIỆN CÓ");
            whHeader.createCell(1).setCellValue("TÊN KHO HÀNG");

            List<Warehouse> warehouses = warehouseRepository.findAll();
            for (Warehouse w : warehouses) {
                Row row = sheet2.createRow(rowIdx++);
                row.createCell(0).setCellValue(w.getCode());
                row.createCell(1).setCellValue(w.getName());
            }

            // Danh sách địa bàn hiện có trong hệ thống
            rowIdx += 2;
            Row regHeader = sheet2.createRow(rowIdx++);
            regHeader.createCell(0).setCellValue("DANH SÁCH MÃ ĐỊA BÀN HIỆN CÓ");
            regHeader.createCell(1).setCellValue("TÊN ĐỊA BÀN / VÙNG");

            List<Region> regions = regionRepository.findAll();
            for (Region r : regions) {
                Row row = sheet2.createRow(rowIdx++);
                row.createCell(0).setCellValue(r.getCode());
                row.createCell(1).setCellValue(r.getName());
            }

            sheet2.setColumnWidth(0, 30 * 256);
            sheet2.setColumnWidth(1, 40 * 256);

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            log.error("Lỗi khi sinh tệp mẫu Excel người dùng", e);
            throw BusinessException.badRequest("TEMPLATE_ERROR", "Không thể tạo tệp mẫu Excel: " + e.getMessage());
        }
    }

    /**
     * S2-01: Xem trước dữ liệu và kiểm tra tính hợp lệ từng dòng từ tệp Excel.
     */
    @Transactional(readOnly = true)
    public UserImportPreviewResponse previewImport(MultipartFile file) {
        validateFile(file);
        List<UserImportRowDto> parsedRows = parseAndValidateRows(file);

        int validCount = (int) parsedRows.stream().filter(UserImportRowDto::isValid).count();
        int invalidCount = parsedRows.size() - validCount;

        return UserImportPreviewResponse.builder()
                .fileName(file.getOriginalFilename())
                .totalRows(parsedRows.size())
                .validRowsCount(validCount)
                .invalidRowsCount(invalidCount)
                .rows(parsedRows)
                .build();
    }

    /**
     * S2-01: Thực thi nhập danh sách người dùng hàng loạt.
     * Bỏ qua dòng lỗi, nhập các dòng hợp lệ và trả về báo cáo tổng kết.
     */
    @Transactional
    public UserImportSummaryResponse executeImport(MultipartFile file) {
        validateFile(file);
        List<UserImportRowDto> rows = parseAndValidateRows(file);

        List<UserImportSummaryResponse.CreatedUserItem> createdUsers = new ArrayList<>();
        List<UserImportSummaryResponse.FailedUserRow> failedRows = new ArrayList<>();

        for (UserImportRowDto row : rows) {
            if (!row.isValid()) {
                failedRows.add(UserImportSummaryResponse.FailedUserRow.builder()
                        .rowNumber(row.getRowNumber())
                        .username(row.getUsername())
                        .email(row.getEmail())
                        .reasons(row.getErrors())
                        .build());
                continue;
            }

            try {
                // Tạo tài khoản người dùng từ dòng hợp lệ
                String tempPassword = tempPasswordGenerator.generate();
                User user = User.builder()
                        .username(row.getUsername())
                        .fullName(row.getFullName())
                        .email(row.getEmail())
                        .phone(row.getPhone())
                        .password(passwordEncoder.encode(tempPassword))
                        .status("ACTIVE")
                        .failedLoginAttempts(0)
                        .mustChangePassword(true)
                        .build();

                // Gán vai trò
                Set<Role> roles = new HashSet<>();
                for (String roleStr : row.getRoles()) {
                    RoleName rName = RoleName.valueOf(roleStr);
                    roleRepository.findByName(rName).ifPresent(roles::add);
                }
                user.setRoles(roles);

                // Gán kho
                if (row.getWarehouseCodes() != null && !row.getWarehouseCodes().isEmpty()) {
                    Set<Warehouse> warehouses = new HashSet<>();
                    for (String wCode : row.getWarehouseCodes()) {
                        warehouseRepository.findByCodeIgnoreCase(wCode).ifPresent(warehouses::add);
                    }
                    user.setWarehouses(warehouses);
                }

                // Gán địa bàn
                if (row.getRegionCodes() != null && !row.getRegionCodes().isEmpty()) {
                    Set<Region> regions = new HashSet<>();
                    for (String rCode : row.getRegionCodes()) {
                        regionRepository.findByCodeIgnoreCase(rCode).ifPresent(regions::add);
                    }
                    user.setRegions(regions);
                }

                User saved = userRepository.save(user);

                // Gửi email kích hoạt tài khoản bất đồng bộ sau commit
                String toEmail = saved.getEmail();
                String fullName = saved.getFullName();
                String savedUsername = saved.getUsername();
                AfterCommit.run(() -> mailService.sendAccountCreatedEmail(toEmail, fullName, savedUsername, tempPassword));

                createdUsers.add(UserImportSummaryResponse.CreatedUserItem.builder()
                        .id(saved.getId())
                        .username(saved.getUsername())
                        .fullName(saved.getFullName())
                        .email(saved.getEmail())
                        .phone(saved.getPhone())
                        .roles(row.getRoles())
                        .build());

            } catch (Exception ex) {
                log.error("Lỗi khi lưu người dùng dòng {}: {}", row.getRowNumber(), ex.getMessage(), ex);
                failedRows.add(UserImportSummaryResponse.FailedUserRow.builder()
                        .rowNumber(row.getRowNumber())
                        .username(row.getUsername())
                        .email(row.getEmail())
                        .reasons(List.of("Lỗi hệ thống khi lưu: " + ex.getMessage()))
                        .build());
            }
        }

        return UserImportSummaryResponse.builder()
                .totalProcessed(rows.size())
                .successCount(createdUsers.size())
                .failedCount(failedRows.size())
                .createdUsers(createdUsers)
                .failedRows(failedRows)
                .build();
    }

    /**
     * Đọc tệp Excel và kiểm tra validation từng dòng một.
     */
    private List<UserImportRowDto> parseAndValidateRows(MultipartFile file) {
        List<UserImportRowDto> result = new ArrayList<>();
        DataFormatter formatter = new DataFormatter();

        Set<String> seenUsernames = new HashSet<>();
        Set<String> seenEmails = new HashSet<>();
        Set<String> seenPhones = new HashSet<>();

        try (InputStream is = file.getInputStream(); Workbook workbook = new XSSFWorkbook(is)) {
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null) {
                throw BusinessException.badRequest("EMPTY_SHEET", "Tệp Excel không chứa sheet dữ liệu nào.");
            }

            int rowCount = sheet.getLastRowNum();
            for (int r = 1; r <= rowCount; r++) { // Dòng 0 là header
                Row row = sheet.getRow(r);
                if (row == null || isRowEmpty(row, formatter)) {
                    continue; // Bỏ qua dòng trống hoàn toàn
                }

                int rowNumber = r + 1; // Số dòng hiển thị người dùng (1-based)
                List<String> errors = new ArrayList<>();

                String username = clean(formatter.formatCellValue(row.getCell(1)));
                String fullName = clean(formatter.formatCellValue(row.getCell(2)));
                String email = clean(formatter.formatCellValue(row.getCell(3)));
                String rawPhone = clean(formatter.formatCellValue(row.getCell(4)));
                String rawRoles = clean(formatter.formatCellValue(row.getCell(5)));
                String rawWarehouses = clean(formatter.formatCellValue(row.getCell(6)));
                String rawRegions = clean(formatter.formatCellValue(row.getCell(7)));

                // 1. Kiểm tra Username
                if (!StringUtils.hasText(username)) {
                    errors.add("Tên đăng nhập không được để trống.");
                } else {
                    username = username.toLowerCase();
                    if (!USERNAME_PATTERN.matcher(username).matches()) {
                        errors.add("Tên đăng nhập phải từ 3 đến 50 ký tự, chỉ gồm chữ cái, số, dấu chấm (.) hoặc gạch ngang (-).");
                    } else if (seenUsernames.contains(username)) {
                        errors.add("Tên đăng nhập '" + username + "' bị trùng lặp với dòng khác trong tệp.");
                    } else if (userRepository.existsByUsernameIgnoreCase(username)) {
                        errors.add("Tên đăng nhập '" + username + "' đã tồn tại trong hệ thống.");
                    } else {
                        seenUsernames.add(username);
                    }
                }

                // 2. Kiểm tra Họ tên
                if (!StringUtils.hasText(fullName)) {
                    errors.add("Họ và tên không được để trống.");
                } else if (fullName.length() > 100) {
                    errors.add("Họ và tên không được vượt quá 100 ký tự.");
                }

                // 3. Kiểm tra Email
                if (!StringUtils.hasText(email)) {
                    errors.add("Email không được để trống.");
                } else {
                    email = email.toLowerCase();
                    if (!EMAIL_PATTERN.matcher(email).matches() || email.length() > 100) {
                        errors.add("Email '" + email + "' không đúng định dạng hợp lệ.");
                    } else if (seenEmails.contains(email)) {
                        errors.add("Email '" + email + "' bị trùng lặp với dòng khác trong tệp.");
                    } else if (userRepository.existsByEmailIgnoreCase(email)) {
                        errors.add("Email '" + email + "' đã được sử dụng trong hệ thống.");
                    } else {
                        seenEmails.add(email);
                    }
                }

                // 4. Kiểm tra Số điện thoại
                String normalizedPhone = null;
                if (StringUtils.hasText(rawPhone)) {
                    normalizedPhone = rawPhone.replaceAll("\\s+", "");
                    if (normalizedPhone.startsWith("+84")) {
                        normalizedPhone = "0" + normalizedPhone.substring(3);
                    }
                    if (!PHONE_PATTERN.matcher(normalizedPhone).matches()) {
                        errors.add("Số điện thoại '" + rawPhone + "' không hợp lệ (phải đủ 10 số, đầu 03/05/07/08/09).");
                    } else if (seenPhones.contains(normalizedPhone)) {
                        errors.add("Số điện thoại '" + normalizedPhone + "' bị trùng lặp với dòng khác trong tệp.");
                    } else if (userRepository.existsByPhone(normalizedPhone)) {
                        errors.add("Số điện thoại '" + normalizedPhone + "' đã được sử dụng trong hệ thống.");
                    } else {
                        seenPhones.add(normalizedPhone);
                    }
                }

                // 5. Kiểm tra Vai trò
                List<String> roleList = new ArrayList<>();
                boolean hasWarehouseRole = false;
                if (!StringUtils.hasText(rawRoles)) {
                    errors.add("Bắt buộc chỉ định ít nhất một vai trò hợp lệ.");
                } else {
                    String[] tokens = rawRoles.split("[,;]");
                    for (String t : tokens) {
                        String rName = t.trim().toUpperCase();
                        if (rName.isEmpty()) continue;
                        if (!rName.startsWith("ROLE_")) {
                            rName = "ROLE_" + rName;
                        }
                        try {
                            RoleName parsedRole = RoleName.valueOf(rName);
                            roleList.add(parsedRole.name());
                            if (parsedRole == RoleName.ROLE_WAREHOUSE || parsedRole == RoleName.ROLE_WH_MANAGER) {
                                hasWarehouseRole = true;
                            }
                        } catch (IllegalArgumentException ex) {
                            errors.add("Mã vai trò '" + t.trim() + "' không tồn tại trong hệ thống.");
                        }
                    }
                }

                // 6. Kiểm tra Kho hàng & Ràng buộc S1-09
                List<String> warehouseCodes = new ArrayList<>();
                if (StringUtils.hasText(rawWarehouses)) {
                    String[] tokens = rawWarehouses.split("[,;]");
                    for (String t : tokens) {
                        String code = t.trim();
                        if (code.isEmpty()) continue;
                        Optional<Warehouse> wh = warehouseRepository.findByCodeIgnoreCase(code);
                        if (wh.isEmpty()) {
                            errors.add("Mã kho hàng '" + code + "' không tồn tại trong hệ thống.");
                        } else {
                            warehouseCodes.add(wh.get().getCode());
                        }
                    }
                }

                if (hasWarehouseRole && warehouseCodes.isEmpty()) {
                    errors.add("Vai trò Nhân viên kho / Quản lý kho bắt buộc phải gắn với ít nhất một mã kho hợp lệ (S1-09).");
                }

                // 7. Kiểm tra Địa bàn
                List<String> regionCodes = new ArrayList<>();
                if (StringUtils.hasText(rawRegions)) {
                    String[] tokens = rawRegions.split("[,;]");
                    for (String t : tokens) {
                        String code = t.trim();
                        if (code.isEmpty()) continue;
                        Optional<Region> reg = regionRepository.findByCodeIgnoreCase(code);
                        if (reg.isEmpty()) {
                            errors.add("Mã địa bàn '" + code + "' không tồn tại trong hệ thống.");
                        } else {
                            regionCodes.add(reg.get().getCode());
                        }
                    }
                }

                boolean valid = errors.isEmpty();

                result.add(UserImportRowDto.builder()
                        .rowNumber(rowNumber)
                        .username(username)
                        .fullName(fullName)
                        .email(email)
                        .phone(normalizedPhone)
                        .roles(roleList)
                        .warehouseCodes(warehouseCodes)
                        .regionCodes(regionCodes)
                        .valid(valid)
                        .errors(errors)
                        .build());
            }

        } catch (Exception e) {
            log.error("Lỗi khi đọc file Excel", e);
            throw BusinessException.badRequest("INVALID_EXCEL_FORMAT", "File không đúng định dạng Excel (.xlsx) hoặc bị hư hỏng: " + e.getMessage());
        }

        return result;
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw BusinessException.badRequest("FILE_EMPTY", "Vui lòng chọn tệp Excel để tải lên.");
        }
        String fileName = file.getOriginalFilename();
        if (fileName == null || (!fileName.toLowerCase().endsWith(".xlsx") && !fileName.toLowerCase().endsWith(".xls"))) {
            throw BusinessException.badRequest("INVALID_FILE_FORMAT", "Hệ thống chỉ hỗ trợ tệp định dạng Excel (.xlsx).");
        }
    }

    private boolean isRowEmpty(Row row, DataFormatter formatter) {
        for (int c = 1; c <= 7; c++) {
            Cell cell = row.getCell(c);
            if (cell != null && StringUtils.hasText(formatter.formatCellValue(cell))) {
                return false;
            }
        }
        return true;
    }

    private String clean(String text) {
        return text != null ? text.trim() : "";
    }
}
