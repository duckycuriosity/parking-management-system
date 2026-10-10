# Thuê bao: hợp đồng Java/JPA và quản trị phiên bản gói

Tài liệu này mô tả implementation sau `V55__move_subscription_business_rules_to_jpa.sql`. Nghiệp vụ kỳ thuê bao và API frontend hiện có nằm tại [vehicle-subscription.md](vehicle-subscription.md); tích hợp gateway nằm tại [vehicle-subscription-payment.md](vehicle-subscription-payment.md), báo cáo tiền tại [payment.md](payment.md). DBML cập nhật nằm tại [schema/vehicle-subscription-jpa.dbml](schema/vehicle-subscription-jpa.dbml).

## 1. Phạm vi và nguồn dữ liệu

Sau V56, [module phí phiên bằng JPA](parking-fee-jpa.md) sử dụng các hook PAID/khóa đã mô tả ở đây để tính coverage và chốt phí; không ghi thu phí phiên vào `vehicle_subscription_receipt`. Đây là tích hợp qua API/payment scoped; rollout tự động vào làn và chính sách ra sau thanh toán còn cần chốt.

Ứng dụng truy cập nhóm bảng thuê bao bằng Spring Data JPA, JPQL, entity mapping và `EntityManager`. Không thêm JDBC/JdbcTemplate hoặc đường truy cập DB riêng. SQL trong Flyway và fixture PostgreSQL dùng để quản lý schema/kiểm thử, không phải truy vấn nghiệp vụ lúc ứng dụng chạy.

| Dữ liệu | Chủ sở hữu và ý nghĩa |
| --- | --- |
| `subscription_package`, `subscription_package_schedule` | `SubscriptionPackageService`: phiên bản gói, giá/số ngày/lịch và hiệu lực **bán**. |
| `vehicle_subscription` | `VehicleSubscriptionService`: đơn/kỳ của xe, giá chốt và khoảng `[starts_at, ends_at)`. |
| `payment` | Module payment: invoice, giá yêu cầu, QR/link, gateway và `SUBSCRIPTION` callback context. Không đổi bảng thành bản ghi cha–con. |
| `vehicle_subscription_receipt` | Bằng chứng từng giao dịch tiền thực nhận của thuê bao. Một payment có nhiều receipt; receipt APPLIED chỉ cấp một kỳ khớp đơn. |

`payment.amount` và `vehicle_subscription.price_vnd` là giá yêu cầu. `receipt.amount_vnd` là tiền thật của một giao dịch. Không lấy giá yêu cầu thay tiền thực nhận, không cộng cả payment và receipt của cùng khoản.

## 2. Năm quy tắc V54 đã chuyển về Java

V54 giữ nguyên để bảo toàn lịch sử Flyway. V55 gỡ các trigger/function dưới đây cùng CHECK/EXCLUDE nghiệp vụ của bốn bảng thuê bao. PK/FK/UNIQUE/NOT NULL/index vẫn tồn tại; UNIQUE `payment.invoice_no` được bổ sung để invoice xác định một intent. Dữ liệu invoice trùng cũ phải được đối soát trước khi triển khai V55; migration không tự xóa hoặc gộp giao dịch.

| Function cũ | Thay thế trong Java/JPA |
| --- | --- |
| `enforce_subscription_package_contract` | `SubscriptionPackageService`: cùng code giữ bãi/loại xe, cấp version dưới khóa family, kiểm tra scalar/hiệu lực, từ chối overlap của các phiên bản PUBLISHED. `SubscriptionPackageEntity` có snapshot/lifecycle guard giữ code/version/bãi/loại xe và hợp đồng PUBLISHED bất biến. |
| `enforce_subscription_schedule_immutability` | Service chỉ thay lịch khi DRAFT; `SubscriptionPackageScheduleEntity` chặn thêm/sửa/xóa lịch của gói PUBLISHED và không cho chuyển dòng lịch sang gói khác. Validate lịch toàn tuần trước publish. |
| `enforce_vehicle_subscription_contract` | `VehicleSubscriptionService` chốt giá/số ngày theo phiên bản đã khóa, xác minh kỳ trước PAID khi gia hạn, kiểm tra overlap dưới khóa xe. Lifecycle của `VehicleSubscriptionEntity` giữ các trường đã chốt/PAID bất biến. |
| `enforce_subscription_receipt_immutability` | Repository receipt chỉ có đọc/tạo; lifecycle `VehicleSubscriptionReceiptEntity` chặn update/delete và kiểm tra các dữ kiện receipt. Không có API sửa/xóa/duyệt receipt. |
| `ensure_applied_subscription_receipt_grants_period` | `confirmVerifiedPayment()` ghi receipt/payment/kỳ trong một transaction, flush và kiểm tra postcondition: APPLIED trỏ chính đơn PAID, cùng giá/paid_at/payment/invoice; không còn deferred business trigger tại commit. |

Java bảo vệ những đường ghi đi qua application. Lệnh SQL trực tiếp hoặc JPQL bulk update bỏ qua lifecycle/service không được coi là đường ghi hợp lệ sau V55. Các repository của nhóm package/subscription/receipt không cung cấp bulk update/delete tùy ý.

## 3. Protocol khóa

| Luồng | Thứ tự khóa |
| --- | --- |
| Đăng ký | `vehicle` WRITE → `subscription_package` READ. |
| Gia hạn | `vehicle` WRITE → kỳ trước WRITE → phiên bản gói READ. |
| Áp dụng thanh toán | `vehicle` WRITE → `vehicle_subscription` WRITE → `payment` WRITE. |
| Quản trị gói | Phiên bản sớm nhất của `package_code` WRITE → phiên bản cần sửa WRITE, nếu khác anchor. |
| Hook chốt phí | Gọi trong write transaction có sẵn; giữ khóa `vehicle` WRITE trong quá trình đọc quyền và chốt phí. |

Các khóa dùng `@Lock(PESSIMISTIC_WRITE/PESSIMISTIC_READ)` trên truy vấn entity JPA. Sau khi chờ khóa, service refresh dữ liệu dùng để quyết định; chỉ gọi lại `findById()` có thể trả object cũ từ persistence context.

Phiên bản sớm nhất là anchor ổn định của family: code/version/bãi/loại xe không sửa, service không có đường xóa version. Cách này dùng được với dữ liệu lịch sử bắt đầu bằng version khác 1. Family mới bắt đầu bằng version 1; khi chưa có dòng để khóa, UNIQUE `(package_code, version_no)` chọn request tạo đầu tiên. Request thua trả conflict và rollback; không query tiếp trong transaction đã lỗi.

Khóa lịch/catalog được tách khỏi việc fetch schedules. Query kiểm tra chồng kỳ chỉ hỏi tồn tại; lịch chỉ được fetch khi response/quyền lợi cần nó. Catalog/history của gói dùng query riêng có lịch, không EntityGraph mặc định trên mọi `findById`.

## 4. API quản trị gói

Các API giữ envelope `Result<T>` và công tắc `app.vehicle-subscription.auth-enabled`. Khi bật auth: đọc cần quyền xem, ghi cần quyền sửa. Công tắc phát triển không bỏ xác minh gateway.

| Method/path | Chức năng |
| --- | --- |
| `GET /api/subscription-packages?parkingLotId=33&vehicleType=CAR` | Catalog đang mở bán, giữ contract cũ. |
| `POST /api/subscription-packages` | Tạo DRAFT; backend cấp `versionNo`. Cùng code thì tạo version kế tiếp. |
| `PUT /api/subscription-packages/{id}/draft` | Thay các điều khoản/lịch của DRAFT. Không nhận lại code/version/bãi/loại xe. |
| `POST /api/subscription-packages/{id}/publish` | Kiểm tra và công bố. Gọi lại version đã publish trả version hiện có. |
| `PUT /api/subscription-packages/{id}/sale` | Chỉ đổi `saleEnabled` và `effectiveTo` của PUBLISHED. |
| `GET /api/subscription-packages/{id}` | Chi tiết version, gồm status/lịch. |
| `GET /api/subscription-packages/history?packageCode=OFFICE_CAR` | Các version của code, theo thứ tự version. |

Không có API unpublish/delete. Giá, số ngày, coverage, lịch, tên và `effectiveFrom` của PUBLISHED không sửa đè. Muốn đổi quyền lợi tạo version khác. Tắt bán không thu hồi các kỳ đã mua. Các version PUBLISHED cùng code không được chồng hiệu lực, kể cả version đang tắt bán; service không tự đóng version trước.

### Tạo gói WEEKLY

Thay bãi/ID/ngày theo môi trường thật. Không có số ngày hoặc lịch giờ hành chính mặc định.

```json
{
  "packageCode": "OFFICE_CAR",
  "name": "Ô tô giờ hành chính",
  "parkingLotId": 33,
  "vehicleType": "CAR",
  "priceVnd": 300000,
  "durationDays": 30,
  "coverageMode": "WEEKLY",
  "effectiveFrom": "2026-10-10T00:00:00+07:00",
  "effectiveTo": null,
  "saleEnabled": true,
  "schedules": [
    {"weekday": 1, "startTime": "08:00:00", "endTime": "17:00:00"},
    {"weekday": 2, "startTime": "08:00:00", "endTime": "17:00:00"},
    {"weekday": 3, "startTime": "08:00:00", "endTime": "17:00:00"},
    {"weekday": 4, "startTime": "08:00:00", "endTime": "17:00:00"},
    {"weekday": 5, "startTime": "08:00:00", "endTime": "17:00:00"}
  ]
}
```

Đáp ứng trả DRAFT và ID version; gọi `POST /api/subscription-packages/{id}/publish` không cần body để công bố. Gói `ALL_DAY` gửi `"coverageMode":"ALL_DAY"` và `"schedules":[]`. WEEKLY DRAFT có thể chưa có lịch; publish bắt buộc có ít nhất một lịch hợp lệ.

`weekday` là ngày bắt đầu: 1 Thứ Hai đến 7 Chủ nhật. Không có `end_day_offset`: end > start kết thúc cùng ngày; end < start kết thúc ngày sau; end = start là 24 giờ. Khoảng nửa mở cho phép hai khung tiếp giáp. Chủ nhật 22:00–02:00 chồng Thứ Hai 01:00–03:00; Thứ Hai 02:00–03:00 tiếp giáp nên hợp lệ. Dữ liệu timestamp/clock nhận tối đa microsecond để giữ đúng độ chính xác lưu PostgreSQL.

`PUT /{id}/draft` gửi toàn bộ điều khoản như ví dụ nhưng bỏ `packageCode`, `parkingLotId`, `vehicleType`. `schedules` là thay toàn bộ danh sách, không phải patch từng dòng. Service flush xóa lịch cũ trước thêm lại để tránh UNIQUE khi giữ nguyên một cửa sổ.

### Đóng hiệu lực bán hoặc tắt bán

```json
{
  "saleEnabled": false,
  "effectiveTo": "2026-11-01T00:00:00+07:00"
}
```

Gửi tới `PUT /api/subscription-packages/{id}/sale`. Hai trường biểu diễn cấu hình mới; `effectiveTo:null` nghĩa là không giới hạn cuối, nên frontend phải gửi mốc cuối muốn giữ khi chỉ đổi `saleEnabled`. Mốc cuối phải sau `effectiveFrom` và không làm chồng version khác. Version kế tiếp có thể bắt đầu đúng mốc cuối vì các khoảng là `[from,to)`.

## 5. Đăng ký, gia hạn và thời gian

Đăng ký dùng contract hiện có:

```json
{
  "vehicleId": 12,
  "parkingLotId": 33,
  "packageId": 41,
  "requestKey": "registration-12-20261010-001",
  "startsAt": "2026-10-10T09:00:00+07:00",
  "paymentDeadlineAt": "2026-10-10T10:00:00+07:00"
}
```

`POST /api/vehicle-subscriptions/register` lấy giá và số ngày từ package đã khóa; `ends_at = starts_at + duration_days × 86400 giây`. Không nhận giá/ngày kết thúc/trạng thái paid do frontend chốt. Gói giá 0 có thể có trong catalog, nhưng đăng ký/gia hạn mới yêu cầu giá dương tới khi có cơ chế miễn phí được duyệt. Hạn thanh toán phải được cung cấp rõ và còn trong tương lai khi tạo đơn; không có TTL mặc định.

Gửi lại cùng requestKey/nội dung trả đơn cũ trước khi kiểm tra lại điều kiện bán mới. Cùng key nhưng khác nội dung trả 409. Các trường đã chốt không đổi khi admin tắt bán hoặc tạo version giá mới.

Gia hạn gọi `POST /api/vehicle-subscriptions/renew`:

```json
{
  "previousSubscriptionId": 51,
  "packageId": 42,
  "requestKey": "renewal-51-20261010-001",
  "paymentDeadlineAt": "2026-10-10T10:00:00+07:00"
}
```

Kỳ trước phải PAID. Service tạo dòng RENEW mới, bắt đầu đúng `previous.ends_at`; không sửa kỳ cũ hoặc lấy `max(now, previous.ends_at)`. Hai kỳ tiếp giáp không chồng. Thanh toán trạng thái PAID giữ nguyên khi hết hạn; `periodStatus` là NONE/UPCOMING/IN_TERM/EXPIRED theo thời gian server. IN_TERM chưa kết luận được lịch WEEKLY đang bao phủ.

## 6. Tiền thực nhận, replay và cấp quyền

Chỉ backend adapter đã xác minh gateway được gọi `confirmVerifiedPayment()`. Chế độ dev không cho frontend tự xác nhận PAID.

| Tình huống | Kết quả |
| --- | --- |
| Đúng giá/VND, thời điểm trả đáng tin <= deadline, đơn pending, kỳ chưa hết và không chồng PAID | Receipt APPLIED, kỳ PAID liên kết đúng receipt. |
| Callback tới sau deadline nhưng tiền đã trả đúng hạn, kỳ vẫn chưa hết | Có thể APPLIED; giờ callback không thay giờ trả tiền. |
| Thiếu/thừa tiền, tiền tệ sai, thiếu/không hợp lệ thời điểm, trả sau deadline hoặc kỳ đã hết | Receipt REVIEW_REQUIRED có reason; pending chuyển PAYMENT_EXCEPTION; chưa cấp quyền. |
| Cùng source/reference và cùng dữ kiện quan trọng | Replay, trả kết quả cũ; không thêm receipt, cấp kỳ hoặc phát event. |
| Cùng source/reference nhưng khác đơn/invoice/payment/tiền/paid_at | Conflict, không sửa receipt cũ. |
| Giao dịch khác tới sau khi đã PAID | Lưu receipt REVIEW_REQUIRED/SECOND_SETTLEMENT, giữ nguyên quyền đã cấp. |

`paid_at` là giờ trả tiền được gateway xác minh, không thay bằng `now()`. `received_at` là giờ backend tiếp nhận. Đúng tại deadline vẫn hợp lệ; tại `ends_at` kỳ đã hết. Hiện MegaPay chưa có format/timezone timestamp trả tiền được xác nhận trong contract; adapter giữ `paidAt=null`, vì vậy giao dịch đúng tiền vẫn vào PAYMENT_TIME_UNKNOWN cần đối soát. SePay dùng thời điểm từ inquiry đã xác thực có offset; không có thời điểm đáng tin cũng đối soát.

Ví dụ invoice yêu cầu 300.000 đồng: TX001 chuyển 300.000 → APPLIED; callback TX001 gửi lại → giữ một receipt; TX002 chuyển thêm 300.000 → receipt thứ hai SECOND_SETTLEMENT. Một payment, hai receipt, một kỳ được cấp, 600.000 tiền thực nhận; chỉ 300.000 đã áp dụng. V1 không cộng các lần chuyển thiếu để cấp kỳ, tự hoàn tiền, đổi REVIEW_REQUIRED sang APPLIED hoặc tạo giao dịch giả để kích hoạt gói 0 đồng.

Lỗi kỹ thuật rollback receipt/payment/kỳ; REVIEW_REQUIRED là kết quả phải lưu. UNIQUE là lớp cấu trúc cuối cho requestKey/source-reference. Khi UNIQUE thua race, service rollback transaction và trả conflict; không bắt lỗi rồi đọc tiếp transaction đã rollback-only.

## 7. Event và phần chưa tích hợp

Sau cấp kỳ thành công, service đăng ký `TransactionSynchronization.afterCommit()` rồi phát `SubscriptionEntitlementsChanged` sau commit. Replay/đối soát không phát event. Delivery là best effort: lỗi listener được log, không đảo ngược quyền/tiền đã commit. Hiện chưa có listener, MQTT delivery, queue/outbox hoặc durable retry. Consumer thông thường có thể dùng `@EventListener`; không giả định sự kiện tự tính lại tiền.

`lockPaidPeriodsForSettlement()` là hook backend MANDATORY trong write transaction, không phải API frontend. Nó khóa xe, đọc/refresh các kỳ PAID giao khoảng và giữ khóa tới commit để caller có thể tính/chốt phí nhất quán với thanh toán thuê bao. `SubscriptionCoverageCalculator`, `ParkingFeeService` và các calculator phí còn là file khung; chưa có luồng checkout gọi hook, giao lịch hoặc tính phí ngoài gói. Luồng barrier legacy vẫn dựa trên ticket, chưa được chuyển sang quyền thuê bao mới.

## 8. Kiểm chứng và triển khai

Chạy bộ check module theo hướng dẫn [vehicle-subscription.md](vehicle-subscription.md). Các test PostgreSQL dùng schema riêng, không gateway/profile/DB vận hành. Test package service sau V55 kiểm tra lịch tuần/replace lịch, publish/immutable, chồng hiệu lực bằng Java dưới khóa và hai request tạo version/công bố đồng thời. Test subscription service kiểm tra replay, chuyển lần hai, khóa, atomic rollback và post-commit notification.

Trước triển khai: review V55 cùng code Java; đối soát invoice trùng lịch sử; kiểm tra migration trên bản sao DB; không chỉnh checksum V54; không trộn phiên bản application cũ vẫn phụ thuộc trigger với schema đã gỡ trigger. Kết quả local/CI và giới hạn môi trường được báo riêng, không mặc định kiểm thử fixture tương đương chạy toàn bộ migration legacy hoặc tích hợp hiện trường.

## 9. Kết quả kiểm chứng lần bàn giao này

JDK 21, Gradle 9.3.0 và PostgreSQL 16.15 tạm: toàn bộ production source biên dịch thành công. Lệnh module `./gradlew --init-script gradle/subscription-tests.init.gradle test` đạt **270 test, 0 failure, 0 error, 0 skipped**. Database được dựng mới cho từng lần chạy, ngoài thư mục đồng bộ; không sử dụng DB vận hành.

| Bộ kiểm thử PostgreSQL | Số test đạt |
| --- | ---: |
| Migration V54 lịch sử | 12 |
| Service thuê bao sau V55 | 16 |
| Quản trị gói sau V55 | 7 |
| Tổng hợp tiền và truy vấn payment | 3 |
| Tổng | 38 |

DBML được parser `@dbml/core 10.3.0` chấp nhận: 8 bảng, 77 trường, 10 FK, không lệch kiểu khóa tham chiếu. `git diff --check` đạt; V54 không thay đổi. Production code thuê bao mới không thêm truy cập JDBC hoặc native SQL nghiệp vụ.

Lệnh test mặc định toàn repository (`./gradlew test`) vẫn dừng tại `compileTestJava`: 18 lỗi cú pháp sẵn có trong `src/test/java/vio/barie/core/repository/ParkingLotRepositoryTest.java` (bắt đầu dòng 149). File này không được thay đổi trong công việc này. Vì vậy kết quả 270 test chỉ áp dụng bộ biểu phí/thuê bao đã chọn, không được diễn giải thành toàn bộ test project đạt. Workflow module kiểm tra riêng cả bốn bộ PostgreSQL và yêu cầu không có test bỏ qua.
