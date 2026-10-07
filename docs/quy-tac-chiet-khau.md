# Quy tắc tính chiết khấu theo sản lượng (S3-01)

## Khai báo chính sách
- **Phạm vi**: một SKU (`PRODUCT`) hoặc một nhóm hàng (`CATEGORY`). Chính sách theo nhóm hàng áp cho mọi sản phẩm trong nhóm đó **và các nhóm con, cháu**.
- **Kiểu chiết khấu**:
  - `PERCENT`: phần trăm trên đơn giá (0 < x ≤ 100).
  - `AMOUNT_PER_UNIT`: số tiền (VND) giảm trên **mỗi đơn vị tính cơ sở** (lon, chai...).
- **Bậc**: mỗi bậc gồm *số lượng tối thiểu* (theo đơn vị cơ sở) và *mức chiết khấu*. Không trùng số lượng; mua nhiều hơn thì mức chiết khấu không được thấp hơn bậc dưới.
- **Thời gian hiệu lực**: ngày bắt đầu bắt buộc, ngày kết thúc có thể để trống (không thời hạn).
- Không xoá chính sách, chỉ **ngừng áp dụng** (`INACTIVE`).

## Cách tính cho một dòng hàng
1. Lấy các chính sách đang `ACTIVE`, ngày đặt hàng nằm trong thời gian hiệu lực, áp đúng SKU hoặc áp cho nhóm hàng chứa sản phẩm.
2. Trong mỗi chính sách, chọn **bậc cao nhất** có số lượng tối thiểu ≤ số lượng mua.
3. Chiết khấu trên 1 đơn vị:
   - `PERCENT`: đơn giá × % (làm tròn 2 chữ số thập phân).
   - `AMOUNT_PER_UNIT`: số tiền khai báo, **không vượt quá đơn giá**.
4. Tiền chiết khấu = chiết khấu trên 1 đơn vị × số lượng.

## Khi nhiều chính sách cùng áp dụng
- **Chỉ áp một chính sách**, chọn chính sách cho **tổng tiền chiết khấu lớn nhất** (có lợi nhất cho khách). Các chính sách **không cộng dồn**.
- Nếu bằng nhau: ưu tiên chính sách theo SKU, sau đó chính sách tạo trước (id nhỏ hơn).
- Kết quả tính trả về cả danh sách các chính sách đạt bậc (`candidates`) để giải thích cho đại lý vì sao chọn chính sách đó.

## Ví dụ
Đơn giá 10.000 ₫/lon, mua 120 lon Coca:
- Chính sách A (SKU Coca, `PERCENT`): từ 48 lon giảm 3%, từ 96 lon giảm 5% → đạt bậc 96: 500 ₫/lon → **60.000 ₫**.
- Chính sách B (nhóm Nước ngọt, `AMOUNT_PER_UNIT`): từ 100 lon giảm 400 ₫/lon → **48.000 ₫**.
- Áp dụng **A** (60.000 ₫). Thành tiền: 1.200.000 − 60.000 = 1.140.000 ₫.
