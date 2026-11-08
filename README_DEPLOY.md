# Hướng dẫn triển khai Ninja School Online (Web Edition) lên Render Free + Aiven PostgreSQL Free

## 1. Kiến trúc tối ưu cho gói Free
- **Quy mô**: 4–5 người, một instance; world/session đang chạy nằm trong RAM, không chia nhiều instance.
- **RAM tiêu thụ mục tiêu**: `< 250 MB` trên Render Free (512 MB RAM). Heap giới hạn 160 MB; tổng RSS phải đo thêm trên Render, không thể suy ra chỉ từ heap.
- **PostgreSQL Pool**: Tối đa `5` kết nối (`DB_POOL_MAX=5`), tương thích hoàn toàn giới hạn 20 kết nối của Aiven Free.
- **Một cổng HTTP/WebSocket duy nhất (`PORT`)**: Phục vụ đồng thời Web Client (`/`), ảnh/bản đồ có HTTP Cache (`/assets/...`), và WebSocket (`/ws`).
- **Tối ưu ghi DB**: Dữ liệu tĩnh nạp 1 lần vào RAM; tự lưu mỗi `45` giây (cấu hình 30–60 giây) bằng snapshot trên worker riêng. Có lưu khi thoát/lên cấp/giao dịch/cường hóa; hai phía giao dịch commit trong cùng transaction.
- Snapshot có thứ tự phiên bản, tránh ghi đè trạng thái mới khi autosave và giao dịch diễn ra gần nhau.

## 2. Chuẩn bị dữ liệu (Chạy local trước khi build/push nếu cần)
Yêu cầu Python 3 + thư mục gốc `SRC NSOACE FIX` nằm cạnh `nso-web`:
```bash
python scripts/extract_data.py
python scripts/verify_source.py
```
Lệnh trên sẽ sinh:
- `data/seed_static.sql`: dữ liệu game tĩnh chuẩn PostgreSQL (đã loại bỏ các bảng nạp thẻ/Momo/quảng cáo/tin tức).
- `data/assets/maps/*.json`: 177 bản đồ đã giải mã sẵn kèm collision flags.
- `data/assets/img/...`: toàn bộ ảnh mức zoom 2 đã chuẩn hóa tên file.

## 3. Tạo PostgreSQL miễn phí trên Aiven
1. Đăng nhập [Aiven Console](https://console.aiven.io/) -> **Create service** -> chọn **PostgreSQL** -> **Free plan** (1 GB storage).
2. Vào tab **Overview**, copy **Service URI**. Project đã có CA người dùng cung cấp tại `certs/aiven-ca.pem`. URL khuyến nghị: `postgres://avnadmin:PASSWORD@host:port/defaultdb?sslmode=verify-full&sslrootcert=certs/aiven-ca.pem`. URL-encode ký tự đặc biệt trong mật khẩu. Đường dẫn CA tương đối dùng được cả ở local và Docker `/app`.
3. Tuỳ chọn: Bạn có thể chạy tay file `sql/schema.postgres.sql` rồi `data/seed_static.sql` bằng `psql`, **hoặc** để nguyên `AUTO_INIT_DB=true` để server tự động khởi tạo schema và nạp dữ liệu tĩnh ngay lần khởi động đầu tiên nếu bảng còn trống!

## 4. Deploy lên Render Free
1. Đẩy thư mục `nso-web` (bao gồm `sql/`, `data/`, `web/`, `src/`, `certs/`, `pom.xml`, `Dockerfile`, `render.yaml`) lên GitHub. Không đẩy `.env` chứa mật khẩu; file này được loại khỏi Git/Docker context. Nếu repo chứa cả thư mục cha, đặt Root Directory là `nso-web`.
2. Vào [Render Dashboard](https://dashboard.render.com/) -> **New +** -> **Blueprint** (hoặc **Web Service** chọn Docker).
3. Khai báo biến môi trường:
   - `DATABASE_URL`: dán Service URI của Aiven (`postgres://...`).
   - `DB_POOL_MAX`: `5`
   - `AUTO_INIT_DB`: `true`
   - `AUTO_SAVE_INTERVAL_SEC`: `45`
   - `AUTH_SECRET`: Blueprint tự sinh; nếu tạo Web Service thủ công, đặt chuỗi ngẫu nhiên tối thiểu 32 ký tự, giữ cố định qua các lần restart.
4. Chờ build Docker hoàn tất; Render sẽ health-check `/api/health` và mở URL HTTPS/WSS.

## 5. Cài PWA trên điện thoại
- Mở URL game bằng Chrome/Safari trên điện thoại -> chọn **"Thêm vào màn hình chính" (Add to Home Screen)** -> xoay ngang màn hình để trải nghiệm điều khiển cảm ứng đa điểm mượt mà và tự động giữ sáng màn hình khi bật **Auto**.

## 6. Chạy local và kiểm thử

Ứng dụng tự đọc `.env` cục bộ; biến môi trường hệ điều hành/Render được ưu tiên.
Thông tin Aiven thật đã nằm trong `.env`, không ghi trong tài liệu hay source code.

```powershell
mvn -B verify
java -Xms64m -Xmx160m -XX:+UseSerialGC -XX:MaxMetaspaceSize=72m -jar target/nso-web.jar
```

Hoặc chạy `powershell -File scripts/run-local.ps1`, mở [localhost:8080](http://localhost:8080), dừng bằng Ctrl+C.

Kiểm thử PostgreSQL thật (chủ động bật, khoảng 75 giây):

```powershell
mvn -B "-Dnso.test.postgres=true" "-Dtest=PostgresIntegrationTest" "-DargLine=-Xms64m -Xmx160m -XX:+UseSerialGC -XX:MaxMetaspaceSize=72m" test
```

Test tạo schema riêng `nso_review_<UUID>`, thử 5 WebSocket và tự lưu/rollback,
sau đó xóa đúng schema thử nghiệm; không tạo nhân vật trong `public`.
Test mặc định dùng H2, không tự truy cập Aiven.

## 7. Quy tắc NSO và giới hạn vận hành

- Đối chiếu nguồn `../SRC NSOACE FIX`, không chỉnh sửa nguồn gốc.
- Một tài khoản chỉ có một nhân vật online; thoát phiên cũ trước khi vào nhân vật khác.
- Giao dịch khóa thao tác túi/tiền, kiểm tra lại đúng vật phẩm và số lượng đã chào, chỉ báo thành công sau khi commit cả hai phía.
- Giá bán theo `Item.initYen()`; giới hạn bán theo `Char.saleItem()`. Dược phẩm có thể bán 0 Yên theo nguồn; đã bỏ công thức `level * 40` gây tạo tiền vô hạn.
- Giới hạn cường hóa theo `ItemTemplate.getUpMax()`: dưới cấp 10: +0; 10–19: +4; 20–39: +8; 40–49: +12; 50–59: +14; từ 60: +16.
- Map tải theo nhu cầu và tải trước tối đa 3 map cạnh; cache RAM renderer tối đa 12 map/512 ảnh. Khi thay tài nguyên, tăng `ASSET_CACHE` trong `web/sw.js`.
- Seed chạy trong transaction. DB đã seed dở từ bản cũ sẽ báo `Incomplete NSO reference data`; không tự xóa dữ liệu người chơi để sửa.
- Render Free có thể ngủ sau 15 phút không có lưu lượng vào và cần thời gian khởi động lại. Thông điệp WebSocket đang nhận được tính là hoạt động. [Render Free](https://render.com/docs/free).
- Server bind `0.0.0.0:$PORT`, browser dùng WSS trên HTTPS. [Render Web Services](https://render.com/docs/web-services), [WebSockets](https://render.com/docs/websocket).
- CA và hostname được xác minh bởi `sslmode=verify-full`. [pgJDBC SSL](https://jdbc.postgresql.org/documentation/ssl/).
- Auto/Wake Lock không bảo đảm chạy khi hệ điều hành điện thoại khóa màn hình hoặc tạm dừng tab nền.
- Kết quả kiểm chứng và các mục chưa đo trên Render: [docs/REVIEW_RENDER.md](docs/REVIEW_RENDER.md).
