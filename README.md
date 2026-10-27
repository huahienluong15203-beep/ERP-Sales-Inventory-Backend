# ERP Sales & Inventory System

Dự án Hệ thống Quản trị Bán hàng & Quản lý Kho (ERP Sales & Inventory System).

## 📁 Cấu trúc Quản lý Dự án (2 Repository Độc lập)

Thư mục này đóng vai trò là workspace làm việc chung. Dự án được chia tách thành **2 kho lưu trữ (repository) riêng biệt** để đảm bảo độc lập khi phát triển:

| Phân hệ | Công nghệ | Repository GitHub |
| :--- | :--- | :--- |
| **Backend** | Spring Boot 4.x / Java 17, PostgreSQL, JWT, Spring Security | [ERP-Sales-Inventory-Backend](https://github.com/huahienluong15203-beep/ERP-Sales-Inventory-Backend.git) |
| **Frontend** | React 19, TypeScript, Vite, Tailwind/CSS | [ERP-Sales-Inventory-Frontend](https://github.com/huahienluong15203-beep/ERP-Sales-Inventory-Frontend.git) |
| **QA_Testing** | Kịch bản kiểm thử, tài liệu test case | Lưu trữ tài liệu test |

---

## 🚀 Hướng dẫn cho Thành viên Mới / Khởi tạo Môi trường Làm việc

### Cách 1: Clone riêng từng repository để phát triển độc lập
- **Thành viên làm Backend:**
  ```bash
  git clone https://github.com/huahienluong15203-beep/ERP-Sales-Inventory-Backend.git Backend
  cd Backend
  ./mvnw spring-boot:run
  ```
- **Thành viên làm Frontend:**
  ```bash
  git clone https://github.com/huahienluong15203-beep/ERP-Sales-Inventory-Frontend.git Frontend
  cd Frontend
  npm install
  npm run dev
  ```

### Cách 2: Thiết lập môi trường đầy đủ (Full Workspace)
Nếu muốn mở cả 2 dự án trong cùng 1 cửa sổ IDE như workspace hiện tại:
1. Tạo thư mục `ERP-Sales-Inventory`:
   ```bash
   mkdir ERP-Sales-Inventory
   cd ERP-Sales-Inventory
   ```
2. Clone Backend và Frontend vào 2 thư mục con tương ứng:
   ```bash
   git clone https://github.com/huahienluong15203-beep/ERP-Sales-Inventory-Backend.git Backend
   git clone https://github.com/huahienluong15203-beep/ERP-Sales-Inventory-Frontend.git Frontend
   ```
3. Mở thư mục gốc `ERP-Sales-Inventory` bằng VS Code / IDE. Trình biên tập sẽ tự động nhận diện cả 2 Git repositories độc lập.

---

## 🐳 Chạy toàn bộ hệ thống bằng Docker

Chỉ cần cài **Docker Desktop**, không cần cài Java, Node hay PostgreSQL. Một lệnh dựng đủ 3 phần:
PostgreSQL, Backend (Spring Boot) và Frontend (React, phục vụ bằng nginx).

### 1. Chuẩn bị
Đặt 2 repo **cạnh nhau** trong cùng một thư mục:
```
D:\Projects\ERP-Sales-Inventory-Backend     <- có file docker-compose.yml
D:\Projects\ERP-Sales-Inventory-Frontend
```
(Để repo frontend ở chỗ khác thì copy `.env.example` thành `.env` và sửa `FRONTEND_DIR`.)

### 2. Chạy
```bash
cd ERP-Sales-Inventory-Backend
docker compose up -d --build
```
Lần đầu mất khoảng 5–10 phút để tải image và thư viện; các lần sau nhanh hơn nhiều nhờ cache.

| Thành phần | Địa chỉ |
| :--- | :--- |
| Giao diện web | http://localhost:3000 |
| API + Swagger | http://localhost:8080/swagger-ui/index.html |
| PostgreSQL | `localhost:5433` (user `postgres`, mật khẩu `123456`, DB `erp_db`) |

PostgreSQL trong Docker dùng cổng **5433** để không đụng PostgreSQL đã cài sẵn trên máy (5432).
Tài khoản mẫu được tạo tự động khi backend khởi động lần đầu (vd: `admin / admin123`).

### 3. Lệnh hay dùng
| Việc cần làm | Lệnh |
| :--- | :--- |
| Xem trạng thái | `docker compose ps` |
| Xem log backend | `docker compose logs -f backend` |
| Build lại sau khi kéo code mới | `docker compose up -d --build` |
| Tắt hệ thống (giữ dữ liệu) | `docker compose down` |
| Tắt và **xoá sạch dữ liệu** (DB + ảnh tải lên) | `docker compose down -v` |

Dữ liệu nằm trong 2 volume `db-data` (database) và `uploads` (ảnh đại diện, ảnh biên bản giao hàng,
ảnh biên lai), nên tắt / build lại container không bị mất.

### 4. Cấu hình (tuỳ chọn)
Copy `.env.example` thành `.env` để đổi cổng, mật khẩu DB, địa chỉ trong link email, JWT secret, tài khoản gửi mail.
File `.env` đã nằm trong `.gitignore`, không bị đẩy lên GitHub.

### 5. Gặp lỗi
- `port is already allocated`: cổng 3000 / 8080 / 5433 đang bị chương trình khác dùng
  (vd: đang chạy `mvnw spring-boot:run` hoặc `npm run dev`). Tắt chương trình đó hoặc đổi cổng trong `.env`.
- Giao diện báo không kết nối được máy chủ: chờ backend khởi động xong
  (`docker compose logs -f backend` thấy dòng `Started BackendApplication`).

---

## 🌿 Quy định Nhánh (Branching Strategy)
- `main`: Nhánh production ổn định.
- `develop`: Nhánh tích hợp tính năng chung của team.
- `feature/<tên-tính-năng>`: Nhánh làm việc cá nhân được tách ra từ `develop`. Sau khi hoàn thành tạo Pull Request vào `develop`.
