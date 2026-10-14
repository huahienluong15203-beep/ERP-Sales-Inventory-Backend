BEGIN;

DELETE FROM sales_order_lines WHERE order_id IN (SELECT id FROM sales_orders WHERE code = 'DH-TEST-S501');
DELETE FROM sales_orders WHERE code = 'DH-TEST-S501';

INSERT INTO sales_orders (
    code, customer_id, status, subtotal, discount_total, total_amount, version,
    created_at, updated_at, desired_delivery_date, source, note
) VALUES (
    'DH-TEST-S501', 1, 'DELIVERED', 7000000.00, 0.00, 7000000.00, 0,
    NOW() - INTERVAL '1 day', NOW(), CURRENT_DATE, 'PORTAL', 'Đơn hàng mẫu test tính năng theo dõi & cảnh báo giao thiếu S5-01'
);

INSERT INTO sales_order_lines (
    order_id, line_no, product_id, product_sku, product_name, unit_name,
    quantity, base_quantity, base_unit, conversion_factor, unit_price, floor_price,
    gross_amount, discount_amount, net_amount, delivered_quantity, shortage_reason
) VALUES 
(
    (SELECT id FROM sales_orders WHERE code = 'DH-TEST-S501'),
    1, 43, 'BIA-0043', 'Sản phẩm Bia & Đồ uống có cồn #0043 đóng gói Thùng 24 lon', 'Thùng',
    10.0000, 240.0000, 'Lon', 24.0000, 350000.00, 300000.00,
    3500000.00, 0.00, 3500000.00, 8.0000, 'Giao thiếu 2 thùng so với số lượng đặt theo biên bản giao hàng'
),
(
    (SELECT id FROM sales_orders WHERE code = 'DH-TEST-S501'),
    2, 5013, 'COCA-320', 'Nước ngọt Coca-Cola Lon 320ml', 'Thùng',
    10.0000, 240.0000, 'Lon', 24.0000, 350000.00, 300000.00,
    3500000.00, 0.00, 3500000.00, 10.0000, NULL
);

COMMIT;
