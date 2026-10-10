# Tích hợp hồ sơ đăng ký và các module thuê bao đã chốt

## Nguồn tích hợp

Nhánh đích được khảo sát tại `dangpn/subscription` commit `61f11c2f66785c2c2773c403e74ac8258c95f1f0`.
Phần nghiệp vụ gói, kỳ thuê bao, receipt, phí ngoài gói và adapter payment được kế thừa từ
`dangpn/fee_policy` commit `79a307e8123cfa5a4b062a5af9124a5fad2639ad`.

Đây là tích hợp theo phụ thuộc mã nguồn, không merge toàn bộ hai nhánh. Bảng, dữ liệu và
API của module hồ sơ member đã bổ sung được giữ lại; service hồ sơ được xây dựng lại để
gọi nghiệp vụ thuê bao. Các service/calculator tính phí không nằm trong module hồ sơ.

Nguồn quyết định nghiệp vụ:

- [Hợp đồng thuê bao Java/JPA](vehicle-subscription-jpa.md).
- [Luồng QR, intent và thanh toán đã xác minh](vehicle-subscription-payment.md).
- [Schema thuê bao](schema/vehicle-subscription-jpa.dbml).
- [Tính phí phiên ngoài phạm vi gói](parking-fee-jpa.md).

## Hai lịch sử Flyway phải được phân biệt

| Nguồn | Lịch sử |
| --- | --- |
| `dangpn/subscription` | V47 tạo hồ sơ, quan hệ và bảng kỳ; V50 tạo gói/lịch và đổi ID kỳ thành BIGINT. |
| `dangpn/fee_policy` | V54 tạo mới cả ba bảng gói/lịch/kỳ cùng receipt; V55 chuyển kiểm tra nghiệp vụ sang Java/JPA. |

V54 không thể chạy nguyên bản sau V50 vì ba bảng đã tồn tại. Không sửa V47/V48/V50 đã
áp dụng, cũng không phát hành một file V54 có nội dung khác checksum gốc.

Nhánh đích sử dụng **V51__bridge_subscription_branch_schema.sql** để bổ sung hợp đồng
cấu trúc còn thiếu cho các bảng hiện hữu và tạo `vehicle_subscription_receipt`.
V52/V53/V55/V56 được giữ nguyên nội dung nguồn. V54 không nằm trong vị trí migration
production của nhánh này; đây là một lịch sử nâng cấp có chủ đích, không phải file bị thất lạc.

V51 giữ ID và các dòng hiện có; chỉ cấp khóa kỹ thuật `legacy-subscription-{id}` cho đơn cũ.
Không tạo receipt, không đặt hạn trả tiền, không đổi trạng thái đã thu tiền dựa trên suy đoán.
Migration dừng và rollback nếu gặp trạng thái cũ chưa ánh xạ, bằng chứng thanh toán inline,
hạn thanh toán thiếu, gia hạn chưa ánh xạ kỳ trước đã trả tiền, điều khoản đã chốt không hợp lệ hoặc lịch không biểu diễn được bằng
quy tắc đồng hồ đã chốt. Dữ liệu đó cần được đối soát và ánh xạ rõ trước khi chạy lại.
FK/UNIQUE cũng làm migration dừng nếu dữ liệu cũ bị trùng hoặc tham chiếu không đúng phạm vi.

`end_day_offset` của V50 được giữ lại để tra cứu dữ liệu cũ. Nghiệp vụ mới suy ra ngày kết
thúc bằng `end_time <= start_time`; không đọc hoặc ghi trường legacy này.

V50 gốc chứa `TRUNCATE vehicle_subscription CASCADE`. Bản thay đổi này không sửa lịch sử
đó và V51 không xóa dữ liệu. Với môi trường có dữ liệu trước V50 chưa áp dụng, cần giải quyết
riêng thao tác destructive sẵn có của V50 trước khi nâng cấp; không tự chạy chuỗi này lên DB
production hoặc dùng Flyway repair để che khác biệt.

Nếu tiếp tục hợp nhất hai nhánh, phải xử lý lịch sử migration như trên thay vì kéo thêm V54
CREATE TABLE vào migration production. Không sửa checksum hay giả ghi lịch sử đã áp dụng.

## API tra cứu hiện hữu

`GET /api/vehicle-subscriptions` được giữ, có giới hạn `size` từ 1 tới 200. Truy vấn phân
trang trên đơn trước rồi tải nhãn xe/chủ/bãi/gói theo từng batch; quan hệ nhiều người lái
không nhân bản đơn hoặc làm sai tổng bản ghi. Tên chủ hồ sơ là tên đăng ký; xe legacy
chưa có owner dùng danh sách người được gán ACTIVE làm thông tin hiển thị dự phòng.

Lọc tên người dùng `EXISTS`; ký tự `%` và `_` trong từ khóa được coi là ký tự nhập thật.
Thứ tự `created_at DESC, id DESC` xác định ổn định. Không tải collection lịch gói hoặc receipt
cho danh sách này. Số query bị chặn theo batch, không tăng theo số dòng trong page.

Hai tham số ngày `LocalDateTime` của API cũ giữ cách hiểu theo timezone server, cũng như
giờ hiển thị của response cũ. Các API kỳ mới dùng `Instant`. Không tự giả định timezone bãi
vì schema hiện tại chưa có contract timezone cho API danh sách này.

Danh sách xe theo chủ giữ projection cũ, chuyển enum thành tên trạng thái và `Instant`
thành `OffsetDateTime` UTC. Truy vấn kỳ mới nhất mỗi xe chỉ đọc scalar, không tải graph.

`/api/dangpnOwnerAccount` và alias `/api/owner-accounts` cùng nằm trong scoped security
của thuê bao, dùng cấu hình xác thực và quyền view/edit hiện hữu. Các endpoint payment
callback giữ cơ chế xác minh gateway riêng; không lấy browser làm bằng chứng thu tiền.

## Phạm vi kiểm chứng

Hai nhóm fixture được phân biệt để tránh báo nhầm kết quả:

1. Các test nghiệp vụ thuê bao/phí kế thừa chạy lịch sử canonical V52–V56 tại
   `src/test/resources/db/subscription-history`, vị trí chỉ dành cho test. Chúng kiểm chứng
   hợp đồng Java/JPA, khóa, receipt, phép tính và hồi quy gateway.
2. `SubscriptionBridgeMigrationPostgresIntegrationTest` chạy các file production thật
   V47/V48/V50/V51/V55 trong schema PostgreSQL dùng một lần. Test xác minh giữ dòng
   chưa thanh toán, FK/UNIQUE, JPA mapping và rollback khi dữ liệu legacy không thể ánh xạ.
   Các test đăng ký hồ sơ kiểm chứng V57/V58 và toàn luồng đăng ký mới.

`VehicleSubscriptionListingPostgresIntegrationTest` thực thi truy vấn thật với nhiều người
lái trên một xe, kiểm tra tổng/page, ký tự wildcard và projection API cũ. Mock không được
dùng thay bằng chứng cho transaction PostgreSQL hoặc tính đúng của phân trang.

Lịch sử test V54 không được dùng làm migration baseline production. Test chạy fixture
Hibernate không chứng minh toàn bộ V1–V50 legacy đều có thể chạy trên một DB trắng;
khả năng khởi tạo DB từ lịch sử đầy đủ phải được kiểm tra độc lập trước rollout.
