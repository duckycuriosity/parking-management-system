# Đăng ký thuê bao: bản sửa giữ nguyên DB

Bản sửa xây dựng trên `dangpn/subscription@61f11c2`. Không nhập schema V51–V58 của PR #74,
không tạo/xóa bảng hoặc cột, không đổi kiểu khóa, NOT NULL, UNIQUE, index hay FK.
`SubscriptionPackageEntity` chỉ ánh xạ các cột bảng `subscription_package` đã có từ V50.
`VehicleEntity` bỏ trường Java id trùng với id kế thừa; khóa DB giữ nguyên.

## Luồng và trách nhiệm

1. FE xác nhận các lựa chọn chủ, xe, người lái và gói. Đối tượng có sẵn dùng ID; sửa thông tin là API riêng.
2. BE kiểm tra đầu vào và phạm vi liên kết. Người mới được gửi tên + ảnh qua nguyên trạng `AiFaceApiClient.createProfile`.
   Không thêm hệ thống trạng thái, token, job đồng bộ AI. Người đã có không bị gọi tạo AI lại.
3. Một transaction lưu chủ, xe, người, quan hệ và đơn PENDING_PAYMENT. Service thuê bao đọc gói PUBLISHED đang bán,
   chốt giá, tính endsAt = startsAt + durationDays × 86400 giây. Gia hạn tạo đơn mới, không kéo dài dòng PAID cũ.
4. FE gọi payment-link bằng ownerId/subscriptionId, không gửi giá. Invoice và context commit trước khi gọi gateway.
   QR còn hạn được dùng lại; kết quả gateway chưa rõ không tạo invoice khác.
5. Adapter xác minh callback mới áp dụng thanh toán. Payment và kỳ được cập nhật trong cùng transaction,
   kiểm tra tiền tệ, số tiền, thời điểm, deadline và chồng kỳ. Tiền cần đối soát được lưu, chưa cấp kỳ.

AI ID lưu ở `parking_customer.ai_profile_id` hiện hữu; UUID phản hồi ở `ai_identity_id` hiện hữu.
Nếu chủ cũng lái xe, dùng ownerDriver hoặc chọn rõ parking_customer đã có và relationType OWNER.
Schema nhánh gốc không có owner_account.ai_profile_id, nên không lưu thêm ID AI riêng cho owner.
Không nhét mã AI vào image_url/note. ownerImage chỉ dùng khi khai báo chủ đồng thời là người lái.

## Request và API

Ví dụ tạo chủ đồng thời lái xe; gửi JSON sau ở multipart part `request`, ảnh ở `ownerImage`.
ID bãi/gói và thời gian cần thay bằng dữ liệu thực, không phải giá trị mặc định.

```json
{
  "requestKey": "registration-20261011-001",
  "owner": { "fullName": "Nguyễn Văn A", "phone": "0901234567" },
  "vehicles": [{
    "plate": "30A12345",
    "vehicleType": "CAR",
    "customers": [{ "ownerDriver": true }],
    "subscription": {
      "parkingLotId": 33,
      "packageId": 41,
      "startsAt": "2026-10-12T09:00:00+07:00",
      "paymentDeadlineAt": "2026-10-11T18:00:00+07:00"
    }
  }]
}
```

- Tạo người lái khác: name, phone tùy chọn, imageIndexes trỏ vào multipart customerImages, relationType tùy chọn.
- Chọn người có sẵn: customer chỉ có id và relationType. clientId cho phép dùng cùng một người mới trên nhiều xe trong request.
- Chọn chủ có sẵn: owner chỉ có id. Chọn xe có sẵn: vehicle dùng id; không gửi lại plate/vehicleType.
  Có thể bỏ customers để giữ các quyền hiện hữu; đăng ký gói vẫn kiểm tra có người lái ACTIVE.
- Không nhận priceVnd, endsAt, trạng thái đơn, paidAt, mã AI hoặc kết quả gateway từ request hồ sơ.
- paymentDeadlineAt là thời điểm chọn rõ theo contract trước đó; không đặt TTL hoặc tự bắt đầu kỳ vào ngày nhận tiền.
- Request thiếu đối tượng/ảnh hoặc trường ngoài contract trả 400; ID khác chủ trả 404; xung đột trả 409.
  Success dùng envelope Result; thao tác tạo trả HTTP 201.

| API | Mục đích |
| --- | --- |
| POST /api/dangpnOwnerAccount/profiles/with-images | Xác nhận tạo/chọn hồ sơ và đơn; upload ảnh cho người mới |
| POST /api/dangpnOwnerAccount/profiles | Xác nhận bằng JSON khi không cần tạo khuôn mặt mới |
| POST /api/dangpnOwnerAccount/subscription-registrants/{ownerId}/vehicles | Thêm xe và người; multipart |
| PATCH /api/dangpnOwnerAccount/subscription-registrants/{ownerId}/customers/{customerId} | Sửa thông tin người thuộc chủ |
| POST /api/dangpnOwnerAccount/vehicles/{vehicleId}/users | Gán người đã có vào xe |
| DELETE /api/dangpnOwnerAccount/vehicles/{vehicleId}/users/{customerId} | Thu hồi quyền rõ ràng |
| POST /api/owner-accounts/{ownerId}/subscriptions/{subscriptionId}/payment-link | Tạo/tái dùng QR; không body amount |
| GET /api/vehicle-subscriptions | Danh sách đơn, trạng thái; phân trang rồi đọc nhãn theo batch |

Các API hồ sơ/payment-link dùng JWT và quyền CAN_VIEW/CAN_EDIT hiện hữu.
Các route ngoài nhóm này giữ security hiện hữu. Callback gateway giữ xác minh riêng.
FE cần chuyển DTO và đường PATCH có ownerId; request mới không dùng response làm payload lưu.

## Giữ nguyên schema và giới hạn

V50 chưa khai báo FK cho subscription_package và vehicle_subscription; target cũng chưa có
vehicle_subscription_receipt. Không có FK hiện hữu cần xóa ở ba bảng được yêu cầu rà soát.
Bỏ FK không tự loại JOIN trong truy vấn SELECT.

Evidence sử dụng `vehicle_subscription.payment_receipt` JSONB hiện hữu:
`transactions` chứa mỗi quan sát tiền đã xác minh; dữ liệu inline cũ giữ trong `legacyReceipt`.
Không tạo bảng receipt mới. Callback context hiện hữu giữ subscriptionId và thông tin QR.
Chống trùng giao dịch kiểm tra JSON hiện hữu dưới advisory lock; chưa có index mới cho lịch sử này.

Chống gửi lại đăng ký dùng StringRedisTemplate đã có. Cùng requestKey/nội dung/ảnh trả response đã lưu,
khác nội dung trả 409. Validation trước side effect thất bại sẽ bỏ claim. Lần đã bắt đầu gọi ngoài/ghi DB
mà chưa có kết quả hoàn tất không tự chạy lại. Redis cần lưu bền các khóa này; mất khóa Redis làm mất
bảo đảm chống trùng. Crash giữa DB commit và lưu response cần đối soát. Không tuyên bố exactly-once
giữa Redis, PostgreSQL và AI. Response replay là snapshot đăng ký; xem danh sách đơn để đọc trạng thái hiện tại.

Transaction DB không rollback tác vụ đã thành công trên Core AI. TICKET/SESSION và cách sinh invoice cũ giữ nguyên;
invoice thuê bao mới dùng Payment.id hiện hữu. QR timeout không đồng nghĩa đã thất bại.
MegaPay không có thời điểm thanh toán tin cậy thì giữ đối soát. Chưa có luồng kích hoạt gói miễn phí;
không dựng giao dịch ngân hàng giả cho giá 0. Chưa triển khai lên DB/gateway/AI vận hành.

## Kiểm chứng

```sh
bash gradlew -I gradle/lean-subscription-tests.init.gradle test bootJar
```

JDK 21, Gradle 9.3. Init script chỉ chọn các test module mới và thiết lập Mockito agent,
không thay production dependencies. Test cũ ngoài phạm vi vẫn có lỗi compile sẵn; không coi là đã đạt.
Lần kiểm chứng cuối: 62 test thuộc 8 bộ đạt, không lỗi hoặc bỏ qua; production compile và bootJar thành công.
Các test kiểm tra contract, lựa chọn ID, phạm vi, AI trước transaction, replay, giá/kỳ,
invoice trước gateway, callback và mapping/HQL thực bằng Hibernate.

Môi trường kiểm chứng chưa khởi tạo được PostgreSQL riêng. Test Hibernate không kết nối DB;
chưa chứng minh rollback, JSONB/advisory lock và cạnh tranh trên PostgreSQL thật.
Cần kiểm chứng những phần đó trên DB thử nghiệm trước khi triển khai.
