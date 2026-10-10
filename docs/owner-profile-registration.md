# Hồ sơ chủ thuê bao và điều phối đăng ký

## Trách nhiệm và luồng hoàn tất

OwnerAccountProfileService chỉ điều phối dữ liệu hồ sơ, xe, người lái, quan hệ sử dụng
và gọi VehicleSubscriptionService để lập đơn. Nó không gọi AI/gateway, không tự ghi PAID
và không tính phí phiên gửi.

Luồng UI: nhập/chọn chủ → nhập/chọn xe → nhập/chọn người lái → chọn gói → xác nhận.
POST /api/owner-accounts/profiles lưu lệnh trong transaction DB, trả ID và đơn chờ trả tiền.
Sau commit, UI thực hiện đồng bộ ảnh qua các endpoint face và yêu cầu thanh toán qua
endpoint payment-link. Một nút xác nhận trên UI có thể điều phối những request này;
không cần màn hình riêng cho từng transaction.

Giá, phiên bản gói, kỳ và receipt do module thuê bao sở hữu. payment-link chỉ nhận
subscriptionId; module payment lấy giá đã chốt, lưu invoice/mapping trước khi làm việc
với nhà cung cấp. QR chưa phải bằng chứng đã trả tiền. Chỉ đường callback đã xác minh
mới ghi receipt và cấp kỳ. Xem [thanh toán](vehicle-subscription-payment.md).

## Contract đăng ký tối thiểu

Ví dụ các ID/ngày dưới đây cần thay theo môi trường; không phải giá trị mặc định:

```json
{
  "requestKey": "registration-20261010-001",
  "owner": { "fullName": "Nguyễn Văn A", "phone": "0901234567" },
  "vehicles": [
    {
      "clientId": "car-a",
      "plate": "30A-123.45",
      "vehicleType": "CAR",
      "customers": [
        { "ownerAsDriver": true, "relationType": "OWNER" }
      ],
      "subscription": {
        "parkingLotId": 33,
        "packageId": 41,
        "startsAt": "2026-10-10T09:00:00+07:00",
        "paymentDeadlineAt": "2026-10-10T10:00:00+07:00"
      }
    }
  ]
}
```

Chọn chủ đã có: owner chỉ chứa id. Chọn xe đã có: vehicle chứa id và những hành động
customers/subscription cần thực hiện; không gửi lại plate/vehicleType. Chọn người đã có:
customer chứa id và relationType. Dữ liệu tạo mới trộn với ID bị từ chối; sửa thông tin
là thao tác riêng, không phải tác dụng phụ của việc chọn đối tượng.

Người mới khác chủ được mô tả bằng name, phone tùy chọn, relationType và clientId tùy chọn.
Giữ cùng clientId và cùng thông tin để dùng một người mới cho nhiều xe. Hai tên/số điện
thoại giống nhau không được tự xem là cùng người. ownerAsDriver là khai báo tường minh;
liên kết owner.driverCustomerId giữ cùng người và cùng hồ sơ AI cho các xe tiếp theo.

owner không bắt buộc identityNumber. vehicles có thể vắng để chỉ tạo hồ sơ; xe không có
subscription là xe danh mục, chưa có đơn mua gói. Lưu người trước AI được phép; nhận diện
chỉ dùng người SYNCED. Đăng ký gói yêu cầu ít nhất một quan hệ người lái ACTIVE tới người
chưa bị INACTIVE. ACTIVE và PAID không thay thế điều kiện khuôn mặt.

Request là allowlist. FE không gửi priceVnd, endsAt, status/paidAt của đơn, invoice,
paymentReference, aiProfileId/profileId, imageUrl hoặc kết quả gateway. Trường ngoài
contract trả 400. Status mới ACTIVE được backend cấp; các thao tác quản trị trạng thái
ở request cập nhật riêng.

Response giữ envelope Result, chứa:

- Chủ: id, aiProfileId, aiSyncState, driverCustomerId và thông tin hồ sơ.
- vehicles: id, clientId, plate, vehicleType, status, subscription của từng xe.
- customers: id, clientId, aiProfileId, aiSyncState và thông tin người.
- vehicleCustomers: id, vehicleId, customerId, relationType, status.

profileId dạng chuỗi trong response người được giữ tạm để đọc tương thích. Đây không phải
trường command. subscription trả giá/kỳ/trạng thái do service thuê bao chốt; không có
invoice tổng cho nhiều xe.

## Idempotency, cập nhật và phân quyền

owner_profile_registration lưu requestKey, hash những lựa chọn và các ID kết quả, commit
cùng toàn bộ graph. Cùng key và nội dung trả lại cùng định danh; dữ liệu hiển thị và trạng
thái thanh toán được đọc hiện tại. Cùng key khác nội dung trả 409. Replay được xử lý trước
khi xét lại điều kiện bán mới của gói.

Nếu hai request cùng key tới đồng thời, một request có thể nhận 409
PROFILE_REQUEST_CONCURRENT. Retry nguyên request trong transaction mới để lấy kết quả
đã commit. Không đọc tiếp transaction bị lỗi UNIQUE, không tạo thêm hồ sơ hoặc đơn.
Gói bị từ chối/lỗi kỹ thuật rollback cả graph và ledger.

Hai key khác nhau cùng tạo một số điện thoại/số định danh/biển số vẫn bị UNIQUE của DB
chặn. Các xung đột nghiệp vụ đã biết trả 409 và rollback toàn bộ lệnh thua; không tiếp
tục đọc transaction lỗi hoặc tự chọn một chủ/xe theo số điện thoại/biển số.

Các thao tác chọn ID kiểm tra thuộc cùng owner; quan hệ không thể chuyển chủ bằng payload.
HTTP và method security dùng app.vehicle-subscription.auth-enabled cùng quyền CAN_VIEW/
CAN_EDIT hiện hữu. Đây là module người vận hành quản trị hồ sơ; không thêm tenant hay
mô hình chủ hồ sơ tự đăng nhập chưa được phê duyệt.

Omission không xóa/vô hiệu hóa người hoặc quan hệ. Gỡ người khỏi xe cập nhật REVOKED bằng
endpoint riêng; không xóa lịch sử thuê bao/receipt.

Với xe có owner, nhận diện FACE_PLATE ở lượt vào kiểm tra người thuộc đúng owner,
quan hệ sử dụng còn ACTIVE và chủ/xe/người chưa bị INACTIVE. Thu hồi quan hệ có hiệu
lực ở lượt vào. Lượt ra giữ quy tắc đóng phiên đang mở; không giữ xe lại chỉ vì quan
hệ vừa bị thu hồi. Luồng xe legacy chưa có owner được giữ tương thích.

DELETE chủ có dữ liệu liên kết trả 409 OWNER_HAS_LINKED_DATA. Cập nhật status INACTIVE
để ngừng sử dụng; không xóa lịch sử hồ sơ, đơn và thanh toán.

## Endpoint

| Method/path (sau /api/owner-accounts) | Hành động |
| --- | --- |
| POST /profiles | Tạo/chọn graph và lập đơn tùy chọn; chỉ DB |
| GET /{ownerId} | Thông tin cơ bản + trạng thái AI |
| POST /list | Danh sách phân trang; count xe theo batch |
| PUT /{ownerId} | Cập nhật hồ sơ cơ bản, không sửa mã/ảnh AI |
| DELETE /{ownerId} | Chỉ xóa hồ sơ chưa được tham chiếu; có liên kết trả 409 |
| POST /{ownerId}/vehicles | Thêm xe; requestKey, plate, vehicleType, customers và subscription tùy chọn |
| GET /{ownerId}/vehicles | Xe đã đăng ký, nhãn kỳ và người ACTIVE theo batch |
| POST /{ownerId}/vehicles/{vehicleId}/users | Cấp quan hệ cho người mới/chọn bằng customerId; requestKey |
| DELETE /{ownerId}/vehicles/{vehicleId}/users/{customerId} | Thu hồi quan hệ |
| PATCH /{ownerId}/customers/{customerId} | Sửa name/phone/status của người thuộc chủ |
| POST /{ownerId}/face | Multipart images, đồng bộ ảnh chủ đã lưu |
| POST /{ownerId}/customers/{customerId}/face | Multipart images, đồng bộ ảnh người lái |
| POST /{ownerId}/subscriptions/{subscriptionId}/payment-link | Không body; kiểm tra phạm vi rồi tạo/tái dùng QR |

Alias /api/dangpnOwnerAccount được giữ và bảo vệ bằng cùng security chain.
Các URL danh sách xe cũ /subscription-registrants/{ownerId}/vehicles vẫn có alias.

FE cần chuyển contract:

- Bổ sung requestKey; dùng ID khi chọn sẵn, không gửi nguyên graph response để lưu.
- Bỏ các trường do backend sở hữu; chuyển users/newUsers cũ sang customers.
- PATCH người có ownerId trong URL.
- Upload ảnh qua face sau khi nhận ID. Transport /profiles/with-images cũ chỉ còn nhận
  lệnh không có ảnh; có upload thì trả lỗi rõ trước mọi DB write. Không bỏ ảnh âm thầm.
- Dùng subscription.id của từng xe để gọi payment-link và theo dõi trạng thái/receipt.

## AI, timeout và vận hành

Xem [AI identity](owner-ai-identity.md): client hiện hữu AiFaceApiClient được tái sử dụng;
hợp đồng ngoài vẫn profileId. Core AI chưa có contract idempotency/correlation đã được
chứng minh, nên CREATE timeout không biết ID hoặc crash ở IN_PROGRESS cần đối soát;
không tự POST lại hay cho FE gắn mã AI thô.

Payment UNKNOWN/CREATING cũng không tự tạo invoice mới khi kết quả chưa rõ. Đơn và
invoice đã commit vẫn tìm được để ghi nhận callback. Xem tài liệu payment về các trạng
thái link, retry và khoản chuyển thêm.

MegaPay vẫn cần nguồn paidAt tin cậy để tự cấp quyền. Tiền đã xác minh nhưng thiếu dữ
kiện cấp kỳ được lưu REVIEW_REQUIRED; không dựng thời gian server để lấp chỗ trống.

## Schema và kiểm chứng

V57 thêm ledger và FK/UNIQUE quan hệ đã thiếu; V58 thêm định danh/trạng thái AI và liên
kết chủ-người lái. Không thay PK/FK bằng mã AI. Xem
[đối chiếu lịch sử nhánh](subscription-branch-integration.md) về V51 và các dữ liệu cũ
cần đối soát trước migration; không chạy ứng dụng lên DB vận hành để thử.

Test đơn vị kiểm tra allowlist cả Jackson2/Jackson3, selection-vs-creation, quyền phạm vi
và replay. PostgreSQL dùng schema riêng để kiểm tra mapping, rollback cả graph, cạnh
tranh, liên kết cùng người, UNIQUE và migration duplicate-ID. Bộ kiểm chứng kết hợp:
`./gradlew --init-script gradle/owner-profile-tests.init.gradle test`.
Chạy PostgreSQL độc lập và cấu hình SUBSCRIPTION_TEST_JDBC_URL/USER/PASSWORD theo fixture;
không dùng application-prod để kiểm thử.
