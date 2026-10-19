-- ====================================================================
-- SEED DATA DÀNH RIÊNG ĐỂ TEST SPRINT 5 (QUẢN LÝ KHO & TỒN KHO)
-- ====================================================================

BEGIN;

-- 1. Phân quyền gắn kho cho Quản lý kho (wh_manager - id = 5)
-- Đảm bảo wh_manager phụ trách cả 3 kho: WH-MB01, WH-MT01, WH-MN01
INSERT INTO user_warehouses (user_id, warehouse_id)
VALUES (5, 3), (5, 4), (5, 5)
ON CONFLICT DO NOTHING;

-- 2. Dữ liệu Vị trí lưu kho (Warehouse Locations) cho WH-MB01 (id = 3)
INSERT INTO warehouse_locations (warehouse_id, code, name, location_type, zone, shelf, description, status, created_at, updated_at)
VALUES
(3, 'ZONE-MB-01', 'Phân khu Hàng Khô & Tiêu Dùng', 'ZONE', 'Khu A', NULL, 'Khu bảo quản nhiệt độ thường', 'ACTIVE', NOW(), NOW()),
(3, 'ZONE-MB-02', 'Phân khu Đồ Uống & Nước Giải Khát', 'ZONE', 'Khu B', NULL, 'Khu bia lon, nước ngọt đóng két', 'ACTIVE', NOW(), NOW()),
(3, 'RACK-MB-A1', 'Kệ Hàng Tầng Cao A1', 'RACK', 'Khu A', NULL, 'Kệ chứa hàng bánh kẹo ngũ cốc', 'ACTIVE', NOW(), NOW()),
(3, 'RACK-MB-B1', 'Kệ Hàng Đồ Uống B1', 'RACK', 'Khu B', NULL, 'Kệ pallet tải trọng nặng', 'ACTIVE', NOW(), NOW()),
(3, 'SHELF-MB-A1-T1', 'Tầng 1 - Kệ A1', 'SHELF', 'Khu A', 'Tầng 1', 'Chiều cao pallet 1.5m', 'ACTIVE', NOW(), NOW()),
(3, 'SHELF-MB-A1-T2', 'Tầng 2 - Kệ A1', 'SHELF', 'Khu A', 'Tầng 2', 'Chiều cao pallet 1.5m', 'ACTIVE', NOW(), NOW()),
(3, 'SHELF-MB-B1-T1', 'Tầng 1 - Kệ B1', 'SHELF', 'Khu B', 'Tầng 1', 'Để két bia chai', 'ACTIVE', NOW(), NOW()),
(3, 'BIN-MB-A1-01', 'Ô Chứa A1-01', 'BIN', 'Khu A', 'Tầng 1', 'Ô chứa mặt hàng snack gói', 'ACTIVE', NOW(), NOW()),
(3, 'BIN-MB-A1-02', 'Ô Chứa A1-02', 'BIN', 'Khu A', 'Tầng 1', 'Ô chứa mặt hàng bánh quy', 'ACTIVE', NOW(), NOW()),
(3, 'BIN-MB-B1-01', 'Ô Chứa B1-01 (Bia Lon)', 'BIN', 'Khu B', 'Tầng 1', 'Ô chứa bia lon Thùng 24', 'ACTIVE', NOW(), NOW())
ON CONFLICT (warehouse_id, code) DO NOTHING;

-- Vị trí lưu kho cho WH-MN01 (id = 5)
INSERT INTO warehouse_locations (warehouse_id, code, name, location_type, zone, shelf, description, status, created_at, updated_at)
VALUES
(5, 'ZONE-MN-01', 'Phân khu Tổng Hợp Phía Nam', 'ZONE', 'Khu Trung Tâm', NULL, 'Khu vực xuất nhập nhanh', 'ACTIVE', NOW(), NOW()),
(5, 'RACK-MN-01', 'Kệ Hàng MN-01', 'RACK', 'Khu Trung Tâm', NULL, 'Kệ pallet hàng giải khát', 'ACTIVE', NOW(), NOW()),
(5, 'SHELF-MN-01-T1', 'Tầng 1 - Kệ MN-01', 'SHELF', 'Khu Trung Tâm', 'Tầng 1', 'Pallet tầng trệt', 'ACTIVE', NOW(), NOW()),
(5, 'BIN-MN-01-01', 'Ô Chứa MN-01-01', 'BIN', 'Khu Trung Tâm', 'Tầng 1', 'Ô chứa nước ngọt lon', 'ACTIVE', NOW(), NOW())
ON CONFLICT (warehouse_id, code) DO NOTHING;

-- 3. Cấu hình định mức tồn tối thiểu (Min Stock Thresholds)
-- Xoá các threshold test cũ của các sản phẩm này nếu có để nạp chuẩn
DELETE FROM min_stock_thresholds WHERE warehouse_id = 3 AND product_id IN (5013, 43, 36, 4993, 4991);

INSERT INTO min_stock_thresholds (warehouse_id, product_id, min_threshold, created_at, updated_at)
VALUES
-- COCA-320 (Tồn 1000) -> min 200 -> ĐẠT AN TOÀN (SAFE)
(3, 5013, 200.0000, NOW(), NOW()),
-- BIA-0043 (Tồn 1000) -> min 1500 -> DƯỚI ĐỊNH MỨC (LOW - Báo cam và kích hoạt banner Dashboard!)
(3, 43, 1500.0000, NOW(), NOW()),
-- BIA-0036 (Tồn 1000) -> min 500 -> ĐẠT AN TOÀN (SAFE)
(3, 36, 500.0000, NOW(), NOW()),
-- NGK-4993 (Tồn 1000) -> min 300 -> ĐẠT AN TOÀN (SAFE)
(3, 4993, 300.0000, NOW(), NOW()),
-- SNK-4991 (Tồn 1000) -> min 1800 -> DƯỚI ĐỊNH MỨC (LOW)
(3, 4991, 1800.0000, NOW(), NOW());

-- 4. Phiếu nhập kho (Goods Receipts)
DELETE FROM goods_receipt_lines WHERE goods_receipt_id IN (
    SELECT id FROM goods_receipts WHERE code IN ('PNK-202610-TEST01', 'PNK-202610-TEST02')
);
DELETE FROM goods_receipts WHERE code IN ('PNK-202610-TEST01', 'PNK-202610-TEST02');

-- Phiếu nhập 1: PENDING (Chờ nhập kho) - để user test bấm nút "Xác nhận nhập kho"
INSERT INTO goods_receipts (
    code, document_number, receipt_date, warehouse_id, warehouse_code, warehouse_name,
    supplier_id, supplier_code, supplier_name, status, total_lines, total_base_quantity,
    total_amount, driver_name, vehicle_plate, note, created_by_id, created_by_username, created_at, updated_at
) VALUES (
    'PNK-202610-TEST01', 'HD-HABECO-8899', CURRENT_DATE, 3, 'WH-MB01', 'Kho Tổng Miền Bắc',
    1, 'NCC-HABECO', 'Tổng Công ty Bia - Rượu - Nước giải khát Hà Nội (HABECO)', 'PENDING', 2, 7200.0000,
    102000000.00, 'Nguyễn Văn Tài', '29C-888.99', 'Phiếu nhập mẫu chờ xác nhận nhập kho (Sprint 5)',
    5, 'wh_manager', NOW(), NOW()
);

INSERT INTO goods_receipt_lines (
    goods_receipt_id, product_id, product_sku, product_name, category, selected_unit,
    conversion_factor, base_unit, quantity, base_quantity, unit_price, total_amount,
    batch_number, expired_date, has_batch_management, note, created_at
) VALUES 
(
    (SELECT id FROM goods_receipts WHERE code = 'PNK-202610-TEST01'),
    43, 'BIA-0043', 'Sản phẩm Bia & Đồ uống có cồn #0043 đóng gói Thùng 24 lon', 'Bia lon', 'Thùng',
    24.0000, 'Lon', 200.0000, 4800.0000, 350000.00, 70000000.00,
    'LOT-HABECO-202610A', CURRENT_DATE + INTERVAL '365 days', TRUE, 'Nhập bổ sung đợt 1', NOW()
),
(
    (SELECT id FROM goods_receipts WHERE code = 'PNK-202610-TEST01'),
    36, 'BIA-0036', 'Sản phẩm Bia & Đồ uống có cồn #0036 đóng gói Két 24 chai', 'Bia chai & Rượu nhẹ', 'Két',
    24.0000, 'Chai', 100.0000, 2400.0000, 320000.00, 32000000.00,
    'LOT-HABECO-202610B', CURRENT_DATE + INTERVAL '270 days', TRUE, 'Nhập bổ sung đợt 1', NOW()
);

-- Phiếu nhập 2: COMPLETED (Đã hoàn thành) - Xem lịch sử phiếu đã khóa
INSERT INTO goods_receipts (
    code, document_number, receipt_date, warehouse_id, warehouse_code, warehouse_name,
    supplier_id, supplier_code, supplier_name, status, total_lines, total_base_quantity,
    total_amount, driver_name, vehicle_plate, note, created_by_id, created_by_username,
    confirmed_by_id, confirmed_by_username, confirmed_at, created_at, updated_at
) VALUES (
    'PNK-202610-TEST02', 'HD-COCA-9988', CURRENT_DATE - INTERVAL '2 days', 3, 'WH-MB01', 'Kho Tổng Miền Bắc',
    3, 'NCC-COCACOLA', 'Công ty TNHH Nước giải khát Coca-Cola Việt Nam', 'COMPLETED', 1, 1200.0000,
    14400000.00, 'Trần Văn Lái', '29B-123.45', 'Phiếu nhập hoàn tất đã khóa',
    5, 'wh_manager', 5, 'wh_manager', NOW() - INTERVAL '2 days', NOW() - INTERVAL '2 days', NOW() - INTERVAL '2 days'
);

INSERT INTO goods_receipt_lines (
    goods_receipt_id, product_id, product_sku, product_name, category, selected_unit,
    conversion_factor, base_unit, quantity, base_quantity, unit_price, total_amount,
    batch_number, expired_date, has_batch_management, note, created_at
) VALUES (
    (SELECT id FROM goods_receipts WHERE code = 'PNK-202610-TEST02'),
    5013, 'COCA-320', 'Nước ngọt Coca-Cola Lon 320ml', 'Nước ngọt chai PET', 'Thùng',
    24.0000, 'Lon', 50.0000, 1200.0000, 288000.00, 14400000.00,
    'LOT-COCA-202610', CURRENT_DATE + INTERVAL '500 days', TRUE, 'Đã nhập kho đủ', NOW() - INTERVAL '2 days'
);

-- 5. Phiếu chuyển kho nội bộ (Stock Transfers)
DELETE FROM stock_transfer_lines WHERE stock_transfer_id IN (
    SELECT id FROM stock_transfers WHERE code IN ('TRF-202610-TEST01', 'TRF-202610-TEST02', 'TRF-202610-TEST03')
);
DELETE FROM stock_transfers WHERE code IN ('TRF-202610-TEST01', 'TRF-202610-TEST02', 'TRF-202610-TEST03');

-- Phiếu chuyển 1: DRAFT (Chờ chuyển đi) - Để user bấm "Xuất chuyển đi"
INSERT INTO stock_transfers (
    code, transfer_date, expected_receive_date, source_warehouse_id, source_warehouse_code, source_warehouse_name,
    dest_warehouse_id, dest_warehouse_code, dest_warehouse_name, transporter_name, vehicle_plate,
    status, note, created_by_id, created_by_username, created_at, updated_at
) VALUES (
    'TRF-202610-TEST01', CURRENT_DATE, CURRENT_DATE + INTERVAL '2 days',
    3, 'WH-MB01', 'Kho Tổng Miền Bắc',
    5, 'WH-MN01', 'Kho Tổng Miền Nam',
    'Công ty Vận Tải Bắc Nam', '29C-999.88',
    'DRAFT', 'Phiếu chờ xuất chuyển điều phối kho Miền Nam (Sprint 5)',
    5, 'wh_manager', NOW(), NOW()
);

INSERT INTO stock_transfer_lines (
    stock_transfer_id, product_id, product_sku, product_name, category, unit,
    source_available_stock, transfer_quantity, created_at
) VALUES (
    (SELECT id FROM stock_transfers WHERE code = 'TRF-202610-TEST01'),
    5013, 'COCA-320', 'Nước ngọt Coca-Cola Lon 320ml', 'Nước ngọt chai PET', 'Lon',
    1000.0000, 100.0000, NOW()
);

-- Phiếu chuyển 2: IN_TRANSIT (Đang vận chuyển) - Để user test bấm "Xác nhận nhận hàng" (và test nhập chênh lệch)
INSERT INTO stock_transfers (
    code, transfer_date, expected_receive_date, source_warehouse_id, source_warehouse_code, source_warehouse_name,
    dest_warehouse_id, dest_warehouse_code, dest_warehouse_name, transporter_name, vehicle_plate,
    status, note, dispatched_at, created_by_id, created_by_username, created_at, updated_at
) VALUES (
    'TRF-202610-TEST02', CURRENT_DATE - INTERVAL '1 day', CURRENT_DATE + INTERVAL '1 day',
    3, 'WH-MB01', 'Kho Tổng Miền Bắc',
    4, 'WH-MT01', 'Kho Miền Trung',
    'Nhà xe Viettel Post', '43B-555.66',
    'IN_TRANSIT', 'Xe đang trên đường giao vào Đà Nẵng, chuẩn bị nhận kho',
    NOW() - INTERVAL '1 day', 5, 'wh_manager', NOW() - INTERVAL '1 day', NOW() - INTERVAL '1 day'
);

INSERT INTO stock_transfer_lines (
    stock_transfer_id, product_id, product_sku, product_name, category, unit,
    source_available_stock, transfer_quantity, created_at
) VALUES (
    (SELECT id FROM stock_transfers WHERE code = 'TRF-202610-TEST02'),
    43, 'BIA-0043', 'Sản phẩm Bia & Đồ uống có cồn #0043 đóng gói Thùng 24 lon', 'Bia lon', 'Lon',
    1000.0000, 50.0000, NOW() - INTERVAL '1 day'
);

-- Phiếu chuyển 3: COMPLETED (Đã nhận đủ) - Lịch sử hoàn tất
INSERT INTO stock_transfers (
    code, transfer_date, expected_receive_date, source_warehouse_id, source_warehouse_code, source_warehouse_name,
    dest_warehouse_id, dest_warehouse_code, dest_warehouse_name, transporter_name, vehicle_plate,
    status, note, dispatched_at, received_at, created_by_id, created_by_username, created_at, updated_at
) VALUES (
    'TRF-202610-TEST03', CURRENT_DATE - INTERVAL '3 days', CURRENT_DATE - INTERVAL '1 day',
    3, 'WH-MB01', 'Kho Tổng Miền Bắc',
    5, 'WH-MN01', 'Kho Tổng Miền Nam',
    'Vận Tải Phương Trang', '51D-777.99',
    'COMPLETED', 'Đã nhận đủ toàn bộ mặt hàng tại kho Sài Gòn',
    NOW() - INTERVAL '3 days', NOW() - INTERVAL '1 day', 5, 'wh_manager', NOW() - INTERVAL '3 days', NOW() - INTERVAL '1 day'
);

INSERT INTO stock_transfer_lines (
    stock_transfer_id, product_id, product_sku, product_name, category, unit,
    source_available_stock, transfer_quantity, received_quantity, difference_quantity, created_at
) VALUES (
    (SELECT id FROM stock_transfers WHERE code = 'TRF-202610-TEST03'),
    36, 'BIA-0036', 'Sản phẩm Bia & Đồ uống có cồn #0036 đóng gói Két 24 chai', 'Bia chai & Rượu nhẹ', 'Chai',
    1000.0000, 48.0000, 48.0000, 0.0000, NOW() - INTERVAL '3 days'
);

-- 6. Phiếu kiểm kê kho (Stock Takes)
DELETE FROM stock_take_lines WHERE stock_take_id IN (
    SELECT id FROM stock_takes WHERE code IN ('STK-202610-TEST01', 'STK-202610-TEST02')
);
DELETE FROM stock_takes WHERE code IN ('STK-202610-TEST01', 'STK-202610-TEST02');

-- Phiếu kiểm kê 1: DRAFT (Đang kiểm kê) - Có sẵn số đếm lệch thiếu, lệch thừa -> Để user bấm "Chốt kiểm kê & Cân bằng tồn"
INSERT INTO stock_takes (
    code, warehouse_id, status, version, note, created_by_id, created_by_username, created_at
) VALUES (
    'STK-202610-TEST01', 3, 'DRAFT', 0, 'Phiếu kiểm kê mẫu tháng 10/2026 tại Kho Tổng Miền Bắc',
    5, 'wh_manager', NOW()
);

INSERT INTO stock_take_lines (
    stock_take_id, product_id, sku, product_name, base_unit, system_quantity, counted_quantity, difference, note
) VALUES 
(
    (SELECT id FROM stock_takes WHERE code = 'STK-202610-TEST01'),
    5013, 'COCA-320', 'Nước ngọt Coca-Cola Lon 320ml', 'Lon',
    1000.0000, 980.0000, -20.0000, 'Hao hụt 20 lon méo bẹp khi xếp kho'
),
(
    (SELECT id FROM stock_takes WHERE code = 'STK-202610-TEST01'),
    43, 'BIA-0043', 'Sản phẩm Bia & Đồ uống có cồn #0043 đóng gói Thùng 24 lon', 'Lon',
    1000.0000, 1050.0000, 50.0000, 'Thừa 50 lon do đợt trước nhập chưa ghi nhận'
),
(
    (SELECT id FROM stock_takes WHERE code = 'STK-202610-TEST01'),
    4993, 'NGK-4993', 'Sản phẩm Nước giải khát có gas #4993 đóng gói Thùng 24 lon', 'Lon',
    1000.0000, 1000.0000, 0.0000, 'Số lượng khớp hoàn toàn với sổ sách'
);

-- Phiếu kiểm kê 2: CLOSED (Đã chốt) - Xem lịch sử phiếu kiểm kê đã hoàn tất và lý do chốt
INSERT INTO stock_takes (
    code, warehouse_id, status, version, note, close_reason,
    created_by_id, created_by_username, closed_by_username, closed_at, created_at
) VALUES (
    'STK-202610-TEST02', 5, 'CLOSED', 1, 'Kiểm kê định kỳ Quý 3/2026 tại Kho Miền Nam',
    'Đã điều chỉnh cân bằng kho theo biên bản kiểm kê số 89/BB-KK',
    5, 'wh_manager', 'wh_manager', NOW() - INTERVAL '5 days', NOW() - INTERVAL '5 days'
);

INSERT INTO stock_take_lines (
    stock_take_id, product_id, sku, product_name, base_unit, system_quantity, counted_quantity, difference, note
) VALUES (
    (SELECT id FROM stock_takes WHERE code = 'STK-202610-TEST02'),
    36, 'BIA-0036', 'Sản phẩm Bia & Đồ uống có cồn #0036 đóng gói Két 24 chai', 'Chai',
    500.0000, 500.0000, 0.0000, 'Khớp sổ sách'
);

-- =========================================================================
-- 6. Dữ liệu Vị trí lưu kho chuẩn cho tất cả các kho và gán vị trí cho sản phẩm
-- =========================================================================

-- Kho Tổng Miền Bắc (id = 3)
INSERT INTO warehouse_locations (warehouse_id, code, name, location_type, zone, shelf, description, status, created_at, updated_at)
VALUES
    (3, 'ZONE-MB-03', 'Phân khu Lương Thực & Gạo Nông Sản', 'ZONE', 'Khu C', NULL, 'Khu bảo quản gạo ST25 và nông sản khô', 'ACTIVE', NOW(), NOW()),
    (3, 'ZONE-MB-04', 'Phân khu Sữa & Chế Phẩm Sữa', 'ZONE', 'Khu D', NULL, 'Khu bảo quản sữa tươi, bơ sữa nhiệt độ mát', 'ACTIVE', NOW(), NOW()),
    (3, 'RACK-MB-B2', 'Kệ Hàng Bia Chai & Rượu Nhẹ B2', 'RACK', 'Khu B', 'Kệ B2', 'Dãy kệ chuyên xếp két bia chai 24 chai', 'ACTIVE', NOW(), NOW()),
    (3, 'RACK-MB-C1', 'Kệ Hàng Gạo ST25 & Nông Sản C1', 'RACK', 'Khu C', 'Kệ C1', 'Dãy kệ pallet chứa bao gạo 5kg - 25kg', 'ACTIVE', NOW(), NOW()),
    (3, 'RACK-MB-D1', 'Kệ Hàng Sữa & Bơ Phô Mai D1', 'RACK', 'Khu D', 'Kệ D1', 'Dãy kệ sữa thùng và chế phẩm bơ sữa', 'ACTIVE', NOW(), NOW()),
    (3, 'SHELF-MB-B2-T1', 'Tầng 1 - Kệ B2', 'SHELF', 'Khu B', 'Kệ B2', 'Tầng 1 kệ B2 bia chai két', 'ACTIVE', NOW(), NOW()),
    (3, 'SHELF-MB-C1-T1', 'Tầng 1 - Kệ C1', 'SHELF', 'Khu C', 'Kệ C1', 'Tầng 1 kệ C1 gạo ST25', 'ACTIVE', NOW(), NOW()),
    (3, 'SHELF-MB-D1-T1', 'Tầng 1 - Kệ D1', 'SHELF', 'Khu D', 'Kệ D1', 'Tầng 1 kệ D1 sữa hộp', 'ACTIVE', NOW(), NOW()),
    (3, 'BIN-MB-B1-02', 'Ô Chứa B1-02 (Nước Ngọt Lon & Chai)', 'BIN', 'Khu B', 'Tầng 1', 'Ô chứa Coca, Pepsi, 7Up các loại', 'ACTIVE', NOW(), NOW()),
    (3, 'BIN-MB-B1-03', 'Ô Chứa B1-03 (Nước Đóng Chai & Nước Khoáng)', 'BIN', 'Khu B', 'Tầng 1', 'Ô chứa nước khoáng, nước tinh khiết đóng chai', 'ACTIVE', NOW(), NOW()),
    (3, 'BIN-MB-B2-01', 'Ô Chứa B2-01 (Bia Chai & Rượu Nhẹ)', 'BIN', 'Khu B', 'Kệ B2', 'Ô chứa két bia chai Heineken, Saigon, Tiger', 'ACTIVE', NOW(), NOW()),
    (3, 'BIN-MB-C1-01', 'Ô Chứa C1-01 (Gạo ST25 & Thực Phẩm Khô)', 'BIN', 'Khu C', 'Kệ C1', 'Ô chứa túi gạo ST25 Ông Cua 5kg', 'ACTIVE', NOW(), NOW()),
    (3, 'BIN-MB-D1-01', 'Ô Chứa D1-01 (Sữa Tươi & Sữa Chua)', 'BIN', 'Khu D', 'Kệ D1', 'Ô chứa sữa tươi hộp 180ml, 1L và sữa chua', 'ACTIVE', NOW(), NOW())
ON CONFLICT (warehouse_id, code) DO NOTHING;

-- Kho Miền Trung (id = 4)
INSERT INTO warehouse_locations (warehouse_id, code, name, location_type, zone, shelf, description, status, created_at, updated_at)
VALUES
    (4, 'ZONE-MT-01', 'Phân khu A - Hàng Tiêu Dùng Nhanh', 'ZONE', 'Khu A', NULL, 'Khu tập kết cà phê, bánh kẹo, thực phẩm khô', 'ACTIVE', NOW(), NOW()),
    (4, 'ZONE-MT-02', 'Phân khu B - Đồ Uống & Nước Giải Khát', 'ZONE', 'Khu B', NULL, 'Khu chứa bia lon, nước ngọt đóng chai', 'ACTIVE', NOW(), NOW()),
    (4, 'ZONE-MT-03', 'Phân khu C - Lương Thực & Gạo Nông Sản', 'ZONE', 'Khu C', NULL, 'Khu bảo quản gạo ST25 bao túi thương mại', 'ACTIVE', NOW(), NOW()),
    (4, 'RACK-MT-A1', 'Kệ Hàng Tiêu Dùng A1', 'RACK', 'Khu A', 'Kệ A1', 'Dãy kệ A1 chứa cà phê G7, bánh kẹo', 'ACTIVE', NOW(), NOW()),
    (4, 'RACK-MT-B1', 'Kệ Hàng Bia & Nước Giải Khát B1', 'RACK', 'Khu B', 'Kệ B1', 'Dãy kệ B1 chứa bia và nước ngọt', 'ACTIVE', NOW(), NOW()),
    (4, 'RACK-MT-C1', 'Kệ Hàng Gạo & Nông Sản C1', 'RACK', 'Khu C', 'Kệ C1', 'Dãy kệ C1 chứa gạo ST25', 'ACTIVE', NOW(), NOW()),
    (4, 'SHELF-MT-A1-T1', 'Tầng 1 - Kệ A1', 'SHELF', 'Khu A', 'Kệ A1', 'Tầng 1 kệ A1 hàng tiêu dùng', 'ACTIVE', NOW(), NOW()),
    (4, 'SHELF-MT-B1-T1', 'Tầng 1 - Kệ B1', 'SHELF', 'Khu B', 'Kệ B1', 'Tầng 1 kệ B1 đồ uống', 'ACTIVE', NOW(), NOW()),
    (4, 'SHELF-MT-C1-T1', 'Tầng 1 - Kệ C1', 'SHELF', 'Khu C', 'Kệ C1', 'Tầng 1 kệ C1 gạo', 'ACTIVE', NOW(), NOW()),
    (4, 'BIN-MT-A1-01', 'Ô Chứa A1-01 (Cà phê G7 & Bánh kẹo)', 'BIN', 'Khu A', 'Kệ A1', 'Ô nhặt hàng cà phê G7 3in1 và bánh kẹo hộp', 'ACTIVE', NOW(), NOW()),
    (4, 'BIN-MT-B1-01', 'Ô Chứa B1-01 (Bia Lon & Nước Ngọt)', 'BIN', 'Khu B', 'Kệ B1', 'Ô nhặt hàng bia thùng và nước ngọt lon', 'ACTIVE', NOW(), NOW()),
    (4, 'BIN-MT-C1-01', 'Ô Chứa C1-01 (Gạo ST25 Ông Cua 5kg)', 'BIN', 'Khu C', 'Kệ C1', 'Vị trí lưu trữ gạo ST25 Ông Cua chính hãng', 'ACTIVE', NOW(), NOW())
ON CONFLICT (warehouse_id, code) DO NOTHING;

-- Kho Tổng Hà Nội (id = 1)
INSERT INTO warehouse_locations (warehouse_id, code, name, location_type, zone, shelf, description, status, created_at, updated_at)
VALUES
    (1, 'ZONE-HN-01', 'Phân khu A - Hàng Tiêu Dùng Nhanh', 'ZONE', 'Khu A', NULL, 'Khu tập kết cà phê, bánh kẹo, thực phẩm khô', 'ACTIVE', NOW(), NOW()),
    (1, 'ZONE-HN-02', 'Phân khu B - Đồ Uống & Nước Giải Khát', 'ZONE', 'Khu B', NULL, 'Khu chứa bia lon, nước ngọt đóng chai', 'ACTIVE', NOW(), NOW()),
    (1, 'ZONE-HN-03', 'Phân khu C - Lương Thực & Gạo Nông Sản', 'ZONE', 'Khu C', NULL, 'Khu chứa gạo ST25 và ngũ cốc', 'ACTIVE', NOW(), NOW()),
    (1, 'RACK-HN-A1', 'Kệ Hàng Tiêu Dùng A1', 'RACK', 'Khu A', 'Kệ A1', 'Dãy kệ A1 chứa cà phê G7, bánh kẹo', 'ACTIVE', NOW(), NOW()),
    (1, 'RACK-HN-B1', 'Kệ Hàng Bia & Nước Giải Khát B1', 'RACK', 'Khu B', 'Kệ B1', 'Dãy kệ B1 chứa bia và nước ngọt lon', 'ACTIVE', NOW(), NOW()),
    (1, 'RACK-HN-C1', 'Kệ Hàng Gạo & Lương Thực C1', 'RACK', 'Khu C', 'Kệ C1', 'Dãy kệ C1 chứa gạo ST25', 'ACTIVE', NOW(), NOW()),
    (1, 'SHELF-HN-A1-T1', 'Tầng 1 - Kệ A1', 'SHELF', 'Khu A', 'Kệ A1', 'Tầng 1 kệ A1 hàng khô', 'ACTIVE', NOW(), NOW()),
    (1, 'SHELF-HN-B1-T1', 'Tầng 1 - Kệ B1', 'SHELF', 'Khu B', 'Kệ B1', 'Tầng 1 kệ B1 đồ uống', 'ACTIVE', NOW(), NOW()),
    (1, 'SHELF-HN-C1-T1', 'Tầng 1 - Kệ C1', 'SHELF', 'Khu C', 'Kệ C1', 'Tầng 1 kệ C1 gạo', 'ACTIVE', NOW(), NOW()),
    (1, 'BIN-HN-A1-01', 'Ô Chứa A1-01 (Cà phê G7 & Bánh kẹo)', 'BIN', 'Khu A', 'Kệ A1', 'Ô nhặt hàng cà phê G7 3in1 và bánh kẹo hộp', 'ACTIVE', NOW(), NOW()),
    (1, 'BIN-HN-B1-01', 'Ô Chứa B1-01 (Bia & Nước Ngọt Pepsi)', 'BIN', 'Khu B', 'Kệ B1', 'Ô nhặt hàng Pepsi lon và nước ngọt', 'ACTIVE', NOW(), NOW()),
    (1, 'BIN-HN-C1-01', 'Ô Chứa C1-01 (Gạo ST25 Ông Cua 5kg)', 'BIN', 'Khu C', 'Kệ C1', 'Ô nhặt hàng gạo ST25 túi 5kg', 'ACTIVE', NOW(), NOW())
ON CONFLICT (warehouse_id, code) DO NOTHING;

-- Kho Tổng TP.HCM (id = 2)
INSERT INTO warehouse_locations (warehouse_id, code, name, location_type, zone, shelf, description, status, created_at, updated_at)
VALUES
    (2, 'ZONE-HCM-01', 'Phân khu A - Hàng Tiêu Dùng Nhanh', 'ZONE', 'Khu A', NULL, 'Khu tập kết cà phê, bánh kẹo, thực phẩm khô', 'ACTIVE', NOW(), NOW()),
    (2, 'ZONE-HCM-02', 'Phân khu B - Đồ Uống & Nước Giải Khát', 'ZONE', 'Khu B', NULL, 'Khu chứa bia lon, nước ngọt đóng chai', 'ACTIVE', NOW(), NOW()),
    (2, 'ZONE-HCM-03', 'Phân khu C - Lương Thực & Gạo Nông Sản', 'ZONE', 'Khu C', NULL, 'Khu bảo quản gạo ST25 và nông sản', 'ACTIVE', NOW(), NOW()),
    (2, 'RACK-HCM-A1', 'Kệ Hàng Tiêu Dùng A1', 'RACK', 'Khu A', 'Kệ A1', 'Dãy kệ A1 chứa cà phê G7, bánh kẹo', 'ACTIVE', NOW(), NOW()),
    (2, 'RACK-HCM-B1', 'Kệ Hàng Bia & Nước Giải Khát B1', 'RACK', 'Khu B', 'Kệ B1', 'Dãy kệ B1 chứa bia lon và nước ngọt', 'ACTIVE', NOW(), NOW()),
    (2, 'RACK-HCM-C1', 'Kệ Hàng Gạo & Nông Sản C1', 'RACK', 'Khu C', 'Kệ C1', 'Dãy kệ C1 chứa gạo ST25 túi 5kg', 'ACTIVE', NOW(), NOW()),
    (2, 'SHELF-HCM-A1-T1', 'Tầng 1 - Kệ A1', 'SHELF', 'Khu A', 'Kệ A1', 'Tầng 1 kệ A1 tiêu dùng', 'ACTIVE', NOW(), NOW()),
    (2, 'SHELF-HCM-B1-T1', 'Tầng 1 - Kệ B1', 'SHELF', 'Khu B', 'Kệ B1', 'Tầng 1 kệ B1 đồ uống', 'ACTIVE', NOW(), NOW()),
    (2, 'SHELF-HCM-C1-T1', 'Tầng 1 - Kệ C1', 'SHELF', 'Khu C', 'Kệ C1', 'Tầng 1 kệ C1 gạo', 'ACTIVE', NOW(), NOW()),
    (2, 'BIN-HCM-A1-01', 'Ô Chứa A1-01 (Cà phê G7 & Bánh kẹo)', 'BIN', 'Khu A', 'Kệ A1', 'Ô nhặt hàng cà phê G7 3in1 và bánh kẹo hộp', 'ACTIVE', NOW(), NOW()),
    (2, 'BIN-HCM-B1-01', 'Ô Chứa B1-01 (Bia & Nước Ngọt Pepsi)', 'BIN', 'Khu B', 'Kệ B1', 'Ô nhặt hàng Pepsi lon và nước ngọt', 'ACTIVE', NOW(), NOW()),
    (2, 'BIN-HCM-C1-01', 'Ô Chứa C1-01 (Gạo ST25 Ông Cua 5kg)', 'BIN', 'Khu C', 'Kệ C1', 'Ô nhặt hàng gạo ST25', 'ACTIVE', NOW(), NOW())
ON CONFLICT (warehouse_id, code) DO NOTHING;

-- Kho Tổng Miền Nam (id = 5)
INSERT INTO warehouse_locations (warehouse_id, code, name, location_type, zone, shelf, description, status, created_at, updated_at)
VALUES
    (5, 'ZONE-MN-02', 'Phân khu B - Đồ Uống & Nước Giải Khát', 'ZONE', 'Khu B', NULL, 'Khu bia lon và nước giải khát', 'ACTIVE', NOW(), NOW()),
    (5, 'RACK-MN-02', 'Kệ Hàng Đồ Uống MN-B1', 'RACK', 'Khu B', 'Kệ B1', 'Dãy kệ bia lon và nước giải khát', 'ACTIVE', NOW(), NOW()),
    (5, 'BIN-MN-01-02', 'Ô Chứa MN-01-02 (Cà phê & Bánh kẹo)', 'BIN', 'Khu Trung Tâm', 'Kệ MN-01', 'Ô nhặt hàng FMCG', 'ACTIVE', NOW(), NOW()),
    (5, 'BIN-MN-02-01', 'Ô Chứa MN-02-01 (Bia & Nước Ngọt)', 'BIN', 'Khu B', 'Kệ B1', 'Ô nhặt hàng đồ uống', 'ACTIVE', NOW(), NOW())
ON CONFLICT (warehouse_id, code) DO NOTHING;

-- Gán vị trí mặc định cho sản phẩm
UPDATE products SET default_location_id = (SELECT id FROM warehouse_locations WHERE warehouse_id = 3 AND code = 'BIN-MB-B1-01')
WHERE category ILIKE '%Bia lon%' OR sku ILIKE 'BIA-%-LON' OR (sku ILIKE 'BIA-%' AND packaging ILIKE '%lon%');

UPDATE products SET default_location_id = (SELECT id FROM warehouse_locations WHERE warehouse_id = 3 AND code = 'BIN-MB-B2-01')
WHERE category ILIKE '%Bia chai%' OR packaging ILIKE '%chai%' OR sku ILIKE '%CHAI%';

UPDATE products SET default_location_id = (SELECT id FROM warehouse_locations WHERE warehouse_id = 3 AND code = 'BIN-MB-B1-02')
WHERE category ILIKE '%Nước ngọt%' OR sku IN ('PEPSI-LON-330', 'COCA-320', '7UP-CHAI-390');

UPDATE products SET default_location_id = (SELECT id FROM warehouse_locations WHERE warehouse_id = 3 AND code = 'BIN-MB-B1-03')
WHERE category ILIKE '%Nước bình%' OR category ILIKE '%500ml%';

UPDATE products SET default_location_id = (SELECT id FROM warehouse_locations WHERE warehouse_id = 3 AND code = 'BIN-MB-C1-01')
WHERE category ILIKE '%Gạo%' OR sku = 'GAO-ST25-5K' OR name ILIKE '%Gạo ST25%';

UPDATE products SET default_location_id = (SELECT id FROM warehouse_locations WHERE warehouse_id = 3 AND code = 'BIN-MB-A1-01')
WHERE category ILIKE '%Đồ hộp%' OR sku = 'CAFE-G7-3IN1' OR name ILIKE '%Cà phê%' OR sku = 'SD';

UPDATE products SET default_location_id = (SELECT id FROM warehouse_locations WHERE warehouse_id = 3 AND code = 'BIN-MB-A1-02')
WHERE category ILIKE '%Mì%' OR category ILIKE '%Snack%' OR category ILIKE '%Kẹo%' OR category ILIKE '%Bánh%' OR category ILIKE '%Socola%';

UPDATE products SET default_location_id = (SELECT id FROM warehouse_locations WHERE warehouse_id = 3 AND code = 'BIN-MB-D1-01')
WHERE category ILIKE '%Sữa%' OR category ILIKE '%Phô mai%' OR category ILIKE '%Bơ thực vật%';

UPDATE products SET default_location_id = (SELECT id FROM warehouse_locations WHERE warehouse_id = 3 AND code = 'RACK-MB-A1')
WHERE category ILIKE '%Dầu ăn%' OR category ILIKE '%Nước mắm%' OR category ILIKE '%gia vị%';

UPDATE products SET default_location_id = (SELECT id FROM warehouse_locations WHERE warehouse_id = 3 AND code = 'BIN-MB-A1-01')
WHERE default_location_id IS NULL;

COMMIT;

