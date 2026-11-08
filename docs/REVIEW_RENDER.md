# Kiểm tra NSO Web — 08/11/2026

Đã sửa và kiểm tra trong `E:\Ninja_School\nso-web`; không chỉnh sửa `SRC NSOACE FIX`.
Mục tiêu vận hành: một server, nhóm 4–5 người chơi, PostgreSQL pool tối đa 5 kết nối.

## Các sửa đổi chính

- Chạy tự lưu trên worker riêng, chụp trạng thái dưới khóa thế giới rồi ghi DB bên ngoài khóa. Mỗi bản lưu có phiên bản; bản lưu cũ không được ghi đè giao dịch, gia tộc hay trạng thái đã thoát.
- Giữ thứ tự gói tin WebSocket theo từng phiên, giới hạn hàng đợi và kích thước tin nhắn. Loại bỏ lệnh từ phiên cũ; một tài khoản chỉ có một nhân vật online, tránh ghi đè Lượng dùng chung.
- Giao dịch kiểm tra lại đúng vật phẩm đã chào, chặn thay đổi túi/tiền khi đang giao dịch, lưu hai phía trong một transaction rồi mới công bố thành công. Lỗi DB sẽ rollback cả hai phía.
- Sửa giá bán theo `Item.initYen()` và hạn chế bán theo `Char.saleItem()`, đóng vòng lặp mua 300 Yên/bán 400 Yên. Giới hạn cường hóa theo `ItemTemplate.getUpMax()` và tỉ lệ theo bảng `GameData`.
- Tạo gia tộc/đóng góp lưu khoản trừ tiền cùng thay đổi gia tộc trong transaction; chặn kích thành viên của gia tộc khác.
- Seed PostgreSQL có transaction và kiểm tra dữ liệu chưa đầy đủ. Lỗi lưu khi thoát được giữ để thử lại; không cho đăng nhập lại trạng thái cũ trước khi lưu thành công.
- Client xử lý gói map theo thứ tự, bỏ dữ liệu từ kết nối cũ, kết nối lại có giới hạn; nội suy theo thời gian khung hình; nhả phím khi mất focus và nhả touch khi mất pointer capture.
- Tải trước tối đa 3 map cạnh, cache renderer giới hạn 12 map/512 ảnh, HTTP/service worker cache tài nguyên. Sửa bố cục điều khiển trên điện thoại ngang.
- Cấu hình Aiven thật trong `.env`, xác minh TLS/hostname bằng CA ở `certs/aiven-ca.pem`; mật khẩu không nằm trong tài liệu hay image Docker. `AUTH_SECRET` giữ cố định khi khởi động lại.

## Bằng chứng kiểm tra

| Kiểm tra | Kết quả |
| --- | --- |
| `mvn -B verify` | Build thành công; 15 test đạt, 1 test PostgreSQL tùy chọn không chạy trong bộ mặc định |
| Test PostgreSQL chủ động | Đạt trên Aiven thật; schema thử nghiệm riêng được xóa sau test |
| 5 WebSocket trong 50 giây | 2.500 gói di chuyển, 250/250 phản hồi ping; kiểm tra tự lưu sau 45 giây và rollback giao dịch đạt |
| Ping sau khi tách tự lưu | p95: 0 ms, cao nhất: 4 ms trong lượt đo; trước thay đổi cao nhất: 944 ms |
| Trình duyệt | Đăng nhập, vào game, di chuyển, nhảy và mở túi ở bố cục điện thoại ngang; không thấy lỗi JavaScript trong phiên kiểm tra |
| JAR sau đóng gói cuối | Khởi động với Aiven trong 6,856 giây; `/api/health` đạt, trang chủ HTTP 200; heap khoảng 41 MB và Windows working set 132,6 MiB khi chưa có người chơi |
| Dữ liệu nguồn | 177 mảng tile khớp `Data/Map`; 10 bảng nâng cấp khớp Java gốc ở cả server và client; 1.145 file mob/effect/map/object khớp byte với zoom 2 |

Ping đo qua WebSocket trên máy local, DB đặt ở Aiven; đây **không phải** độ trễ Internet từ điện thoại tới Render. Bài tải 50 giây kiểm tra một chu kỳ tự lưu, không thay thế thử treo máy nhiều giờ.

Tài khoản/nhân vật tạo để thử giao diện đã được xóa; dữ liệu tĩnh 177 map/1.085 item giữ nguyên. Không còn schema thử nghiệm PostgreSQL sau kiểm tra.

Bộ test hồi quy kiểm tra phiên cũ/đăng nhập trùng, khóa túi khi giao dịch, sửa vật phẩm đã chào, lỗi ghi phía thứ hai, thử lại khi lưu thoát lỗi, giá bán, giới hạn nâng cấp, rollback seed, gia tộc và bản tự lưu đến muộn. Các test hiện hữu kiểm tra luồng lõi, cày cuốc và hoạt động nhóm.

## Giới hạn đã biết và triển khai

- Chưa deploy lên Render trong lần kiểm tra này; chưa đo RSS/FPS/độ trễ chuyển map trên Render và điện thoại thật. Không cam kết dưới 250 MB RSS hoặc mọi lần chuyển map dưới 1 giây. JVM đã cấu hình heap 160 MB; RSS còn gồm bộ nhớ ngoài heap.
- Docker daemon trên máy chưa hoạt động nên chưa chạy được image Docker để kiểm chứng môi trường Linux. JAR đã build và chạy trực tiếp với Aiven.
- Nguồn gốc thiếu ảnh icon 3599 của item 1081 (Kim tước thảo); bộ trích xuất ghi nhận thiếu ảnh này.
- Đây là bản Web chuyển thể; các luồng đã test không chứng minh mọi skill, boss, map, nhiệm vụ và hoạt động đều giống toàn bộ bản Java gốc.
- Giao dịch và một số thao tác cần xác nhận DB vẫn chờ commit. Khi DB mất kết nối, có thể gián đoạn; chưa có nhật ký lưu bền cục bộ để phục hồi mọi thay đổi nếu tiến trình bị tắt đột ngột trong lúc DB lỗi.
- Chạy một instance. Render Free có thời gian ngủ/khởi động nguội; Auto và Wake Lock phụ thuộc giới hạn tab nền của thiết bị. Chi tiết và tài liệu nhà cung cấp ở [README_DEPLOY.md](../README_DEPLOY.md).

Ảnh giao diện đã kiểm tra: [game-review.png](game-review.png).

Lệnh chạy và cấu hình Render/Aiven: [README_DEPLOY.md](../README_DEPLOY.md).
