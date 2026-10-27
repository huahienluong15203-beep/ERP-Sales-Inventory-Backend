# =============================================================================
# Backend ERP Sales & Inventory - Spring Boot 4 / Java 17
# Build nhiều tầng: tầng 1 build file jar bằng Maven, tầng 2 chỉ chứa JRE + jar
# (image nhỏ, không chứa mã nguồn, chạy bằng user thường thay vì root).
# =============================================================================

# ---------- Tầng 1: build ----------
# Dùng image Maven chính thức (Maven 3.9 + JDK 17) để không phụ thuộc vào việc Maven Wrapper tự tải Maven
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace

# Copy pom trước để Docker cache phần tải thư viện:
# chỉ sửa code trong src/ thì lần build sau không phải tải lại thư viện.
COPY pom.xml ./
# go-offline chỉ để tăng tốc; nếu một plugin không tải trước được thì bước package vẫn tự tải
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline || true

COPY src/ src/
# Test chạy riêng bằng "mvnw test"; khi build image bỏ qua cả biên dịch test để build nhanh, không phụ thuộc test
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -Dmaven.test.skip=true package \
    && cp target/*.jar /workspace/app.jar

# ---------- Tầng 2: chạy ----------
FROM eclipse-temurin:17-jre-jammy AS runtime

ENV TZ=Asia/Ho_Chi_Minh \
    LANG=C.UTF-8 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Duser.timezone=Asia/Ho_Chi_Minh -Dfile.encoding=UTF-8"

WORKDIR /app

# User thường (không chạy bằng root). Thư mục uploads chứa ảnh đại diện, ảnh biên bản giao hàng, ảnh biên lai
RUN groupadd --system --gid 1001 spring \
    && useradd --system --uid 1001 --gid spring --home-dir /app --shell /usr/sbin/nologin spring \
    && mkdir -p /app/uploads/avatars /app/uploads/delivery-proofs /app/uploads/receipt-images \
    && chown -R spring:spring /app

COPY --from=build --chown=spring:spring /workspace/app.jar /app/app.jar

USER spring
EXPOSE 8080
VOLUME ["/app/uploads"]

# Kiểm tra cổng 8080 đã mở (dùng bash có sẵn, không cần cài thêm curl)
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=5 \
    CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080' || exit 1

# exec để Java nhận tín hiệu dừng từ Docker (tắt êm, không bị kill cứng)
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
