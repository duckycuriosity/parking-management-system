# Đăng ký và gia hạn thuê bao phương tiện

## Phạm vi đã triển khai

`VehicleSubscriptionService` quản lý việc mua kỳ thuê bao cho **xe đã tồn tại** tại **bãi đã tồn tại**. Một kỳ chốt phiên bản gói, giá, thời hạn và lịch được hưởng. Module có đăng ký mới, gia hạn, tra cứu và xác nhận bằng chứng thanh toán đã được gateway xác minh. `VehicleSubscriptionPaymentService` tạo hoặc tái sử dụng QR theo giá đã chốt.

Module không tạo chủ hồ sơ, người sử dụng, xe, khuôn mặt hoặc hồ sơ AI. Dữ liệu `vehicle`, `parking_lot`, `payment` dùng ID `BIGINT` đang có trong repository, không thay bằng UUID. Không thêm multi tenant.

Migration `V54__create_vehicle_subscription.sql` bổ sung bốn bảng và được giữ nguyên. Migration tiếp theo `V55__move_subscription_business_rules_to_jpa.sql` bỏ năm business trigger/function cùng CHECK/EXCLUDE nghiệp vụ của nhóm thuê bao; Java/JPA thay thế các kiểm tra này. PK, FK, UNIQUE, NOT NULL và index được giữ; V55 bổ sung UNIQUE cho `payment.invoice_no`. Flyway vẫn chạy SQL để quản lý schema, còn ứng dụng đọc/ghi qua JPA. Không chạy riêng V54/V55 khi thiếu bảng cha. Chi tiết chuyển đổi và API quản trị gói tại [vehicle-subscription-jpa.md](vehicle-subscription-jpa.md).

## Nghiệp vụ và dữ liệu

| Bảng | Vai trò và trường chính |
| --- | --- |
| `subscription_package` | Mỗi dòng là một phiên bản gói. `id` chọn chính xác phiên bản; `package_code` nối các phiên bản của cùng gói; `version_no` phân biệt phiên bản. `parking_lot_id`, `vehicle_type` giới hạn bãi/loại xe. `price_vnd`, `duration_days`, `coverage_mode` là quyền lợi thương mại. `effective_from/to` là thời gian được bán phiên bản, không phải thời hạn của từng xe. `sale_enabled` ngừng bán nhưng không thu hồi quyền đã mua. |
| `subscription_package_schedule` | Lịch tuần của phiên bản `WEEKLY`. `weekday`: 1 thứ Hai, 2 thứ Ba, …, 7 Chủ nhật. `end_time > start_time`: kết thúc cùng ngày; nhỏ hơn: kết thúc ngày kế tiếp; bằng nhau: 24 giờ **từ giờ bắt đầu của ngày đã chọn**. Không có `end_day_offset`. |
| `vehicle_subscription` | Đơn mua và kỳ của xe. `request_key` chống ghi trùng khi frontend gửi lại. `package_id` giữ phiên bản đã chốt; `price_vnd` giữ giá báo cho đơn. `starts_at/ends_at` là khoảng `[bắt đầu, kết thúc)`. `previous_subscription_id` nối kỳ gia hạn. `payment_deadline_at` là hạn trả tiền do người vận hành chọn. `status`, `paid_at`, `paid_receipt_id` thể hiện kết quả cấp quyền. |
| `vehicle_subscription_receipt` | Nhật ký khoản tiền đã được xác minh. `payment_id` nối intent; `source + reference` nhận diện giao dịch thật và chống callback lặp. `amount_vnd`, `currency`, `paid_at` lưu bằng chứng thực, không lấy giá đơn thay tiền đã chuyển. `received_at` là giờ backend nhận/ghi nhận bằng chứng. `outcome`, `reason_code` phân biệt cấp quyền và cần đối soát. Không cập nhật/xóa receipt. |

`subscription_package` là gói thuê bao; `fee_policy` là biểu phí vãng lai. Chúng không dùng chung giá. Module này chưa sử dụng `fee_policy.pricing_rules`.

### Đăng ký mới

1. Frontend giữ bản nháp; khi người vận hành gửi đơn thực, gọi API đăng ký với `startsAt` và `paymentDeadlineAt` được chọn rõ ràng. Backend không tự chọn ngày bắt đầu hoặc số phút giữ giá.
2. Khóa dòng xe để hai yêu cầu của cùng xe được xử lý tuần tự. Nếu `requestKey` đã tồn tại với cùng nội dung, trả đơn cũ; khác nội dung trả HTTP 409.
3. Kiểm tra xe, bãi và phiên bản gói: đúng bãi, đúng loại xe, đã phát hành, đang cho bán, nằm trong lịch hiệu lực bán. Khóa chia sẻ phiên bản và refresh dữ liệu để không dùng trạng thái bán đã cũ sau khi chờ khóa.
4. Chốt `price_vnd` từ gói; tính `ends_at = starts_at + duration_days × 86400 giây`. Không dùng tháng lịch, không làm tròn giá gói như phí phiên gửi xe.
5. Kiểm tra không chồng kỳ đã thanh toán; lưu `PENDING_PAYMENT`. Đơn này **chưa cấp quyền**, kể cả ngày bắt đầu đã tới. Các đơn pending khác không được xem là quyền hoặc tự giữ chỗ.

### Gia hạn

1. Chọn một kỳ đã `PAID`, phiên bản gói đang được bán và hạn thanh toán rõ ràng.
2. Khóa xe rồi khóa kỳ trước; refresh sau khóa để không đọc trạng thái cũ từ cache JPA.
3. Tạo dòng mới: `starts_at = kỳ_trước.ends_at`, `ends_at = starts_at + N × 86400 giây`. Không sửa kỳ cũ và không lấy `max(ngày hết hạn, giờ hiện tại)`.
4. Gia hạn sớm được tạo thành kỳ tương lai; sau thanh toán hiển thị `UPCOMING`. Gia hạn muộn vẫn nối đúng cuối kỳ cũ. Nếu toàn bộ kỳ mới đã qua khi nhận thanh toán, lưu đối soát, không tự quyết định cách xử lý tiền.
5. Nếu đã có kỳ paid tiếp theo chồng khoảng mới, trả 409 khi tạo đơn hoặc ghi nhận `PAID_PERIOD_OVERLAP` nếu phát hiện tại bước nhận tiền.

### Xác nhận thanh toán

1. Gateway xác minh thông báo rồi gửi bằng chứng nội bộ. Không có API frontend truyền trạng thái đã trả tiền.
2. Core khóa **xe → kỳ thuê bao → payment**, refresh các đối tượng có thể đã được đọc trước khi chờ khóa; xác nhận invoice/gateway/context đúng đơn.
3. Nếu `source + reference` đã được ghi với cùng bằng chứng, trả kết quả hiện có. Không tạo receipt hoặc cấp quyền lần hai. Dữ liệu khác cho cùng mã giao dịch bị từ chối.
4. Đối chiếu tiền tệ, số tiền thực với giá đã chốt, ngày giờ trả tiền thực, hạn thanh toán, thời hạn kỳ và chồng lấn paid. Giờ nhận callback không thay thế giờ thực trả tiền. Callback tới sau hạn vẫn có thể được áp dụng nếu bằng chứng cho thấy trả đúng hạn và kỳ chưa hết.
5. Lưu receipt trước. Đúng điều kiện: `APPLIED`, đổi kỳ thành `PAID`, liên kết receipt, ghi `payment.status = PAID`. Không đạt: `REVIEW_REQUIRED`, kỳ pending đổi thành `PAYMENT_EXCEPTION`, payment intent đổi thành `REVIEW_REQUIRED`. Giá báo của intent được giữ; tiền thực nằm trong receipt. Cách này tránh báo cáo cũ cộng giá báo thành tiền đã thu khi khách chuyển thiếu/thừa.
6. Receipt, payment và kỳ thuê bao được ghi trong cùng transaction. Lỗi kỹ thuật rollback toàn bộ; đối soát là kết quả nghiệp vụ được lưu. Java kiểm tra chồng kỳ dưới khóa xe, bảo vệ bất biến qua service/lifecycle JPA và kiểm tra liên kết APPLIED–PAID trước khi hoàn tất. DB giữ UNIQUE để chống trùng request/giao dịch cùng các FK cấu trúc. Sau V55, các business trigger/CHECK/EXCLUDE của thuê bao không còn bảo vệ lệnh SQL trực tiếp.
7. Khoản chuyển thứ hai dùng mã giao dịch khác vẫn có receipt đối soát. Kỳ paid và mã giao dịch áp dụng đầu tiên không bị ghi đè.

Lý do đối soát: `AMOUNT_MISMATCH`, `CURRENCY_MISMATCH`, `PAYMENT_TIME_UNKNOWN`, `PAYMENT_TIME_INVALID`, `PAID_AFTER_DEADLINE`, `PERIOD_ALREADY_EXPIRED`, `PAID_PERIOD_OVERLAP`, `SECOND_SETTLEMENT`, `ORDER_NOT_PENDING`. Chưa có chức năng tự hoàn tiền hoặc duyệt đối soát qua API trong thay đổi này.

Màn hình chi tiết có thể đọc `GET /api/vehicle-subscriptions/{id}/receipts` để hiển thị từng khoản chuyển đã được xác minh, số tiền thực, thời điểm trả/nhận và lý do đối soát. `SubscriptionResponse.paidReceiptId` chỉ tới khoản đã cấp quyền, không phải tất cả tiền đã nhận. Một kỳ `PAID` vẫn có thể có khoản chuyển thứ hai `REVIEW_REQUIRED`; frontend cần hiển thị cả hai, không suy luận mọi receipt đã được áp dụng từ trạng thái kỳ. API không trả `metadata` của nhà cung cấp và không cho chỉnh/xóa biên nhận hoặc duyệt cấp quyền.

## Sequence theo luồng backend

Các actor DB bên dưới gom các bảng có cùng vai trò để hình dễ đọc. Nhãn mũi tên chỉ rõ bảng và thao tác. Các thao tác ghi của mỗi luồng nằm trong một transaction; lỗi trước commit sẽ rollback. Đọc catalog trên frontend chỉ giúp chọn gói, backend vẫn kiểm tra lại khi tạo đơn.

### 1. Đăng ký mới: từ màn hình đăng ký tới đơn chờ thanh toán

```mermaid
sequenceDiagram
    participant FE as "Màn hình đăng ký"
    participant Core as "VehicleSubscriptionService"
    participant Periods as "vehicle / vehicle_subscription"
    participant Catalog as "subscription_package / schedule"
    FE->>Core: register(vehicleId, parkingLotId, packageId, requestKey, startsAt, paymentDeadlineAt)
    Note over FE,Core: Frontend chọn rõ ngày bắt đầu và hạn trả tiền. Không gửi giá hoặc ngày hết hạn.
    Core->>Core: Kiểm tra ID, requestKey và độ chính xác thời gian
    Core->>Periods: Khóa JPA PESSIMISTIC_WRITE trên vehicle; tìm vehicle_subscription theo request_key
    Note over Core,Periods: Khóa xe có tác dụng cả khi xe chưa có kỳ nào. request_key chống tạo đơn trùng khi bấm lại.
    alt Đã có request_key
        Core->>Core: So sánh nội dung yêu cầu với đơn đã lưu
        Core-->>FE: Trả đơn cũ nếu giống; HTTP 409 nếu khác
    else Yêu cầu mới
        Core->>Periods: Đọc vehicle_type; kiểm tra parking_lot tồn tại
        Core->>Catalog: Khóa JPA PESSIMISTIC_READ theo package_id; refresh phiên bản; đọc lịch khi cần response
        Note over Core,Catalog: Gói phải đúng bãi, loại xe, PUBLISHED, sale_enabled và còn hiệu lực bán. Lịch gói giữ nguyên theo phiên bản.
        Core->>Core: Chốt price_vnd; ends_at = starts_at + duration_days x 86400 giây
        Core->>Periods: JPQL kiểm tra tồn tại kỳ PAID giao với khoảng mới
        Note over Core,Periods: Chỉ PAID có quyền lợi. Đơn pending khác chưa giữ chỗ; kỳ tiếp giáp được phép.
        alt Có kỳ PAID chồng lấn
            Core-->>FE: HTTP 409; không tạo đơn
        else Không chồng lấn
            Core->>Periods: INSERT vehicle_subscription NEW, PENDING_PAYMENT; saveAndFlush
            Core->>Core: Commit transaction
            Core-->>FE: ID đơn, giá chốt, thời hạn, lịch gói; periodStatus NONE
        end
    end
```

`package_id` liên kết đúng phiên bản quyền lợi; `price_vnd` lưu số tiền phải thu của đơn. `starts_at/ends_at` là thời hạn dự kiến, chưa phải bằng chứng được hưởng quyền. Màn hình chỉ hiển thị đăng ký thành công/chờ thanh toán, chưa hiển thị đã kích hoạt.

### 2. Gia hạn: tạo kỳ nối tiếp và giữ nguyên kỳ cũ

```mermaid
sequenceDiagram
    participant FE as "Màn hình gia hạn"
    participant Core as "VehicleSubscriptionService"
    participant Periods as "vehicle / vehicle_subscription"
    participant Catalog as "subscription_package / schedule"
    FE->>Core: renew(previousSubscriptionId, packageId, requestKey, paymentDeadlineAt)
    Core->>Periods: Đọc kỳ trước để xác định vehicle_id
    Core->>Periods: Khóa vehicle, khóa kỳ trước; refresh kỳ trước
    Note over Core,Periods: Thứ tự khóa giống cấp kỳ. Refresh tránh dùng trạng thái cũ từ cache JPA sau khi chờ khóa.
    Core->>Periods: Tìm vehicle_subscription theo request_key
    alt Đã có request_key
        Core->>Core: So sánh kỳ trước, gói và hạn trả tiền
        Core-->>FE: Trả đơn cũ nếu giống; HTTP 409 nếu khác
    else Yêu cầu mới
        Core->>Core: Yêu cầu kỳ trước PAID và hạn trả tiền còn trong tương lai
        Core->>Catalog: Đọc, khóa và refresh phiên bản gói đang được bán
        Note over Core,Catalog: Giá và lịch được chọn tại lúc mua thêm. Không lấy phiên bản gói theo ngày kỳ tương lai bắt đầu.
        Core->>Core: starts_at = kỳ trước.ends_at; ends_at = starts_at + N x 86400 giây
        Core->>Periods: JPQL kiểm tra tồn tại kỳ PAID giao với khoảng nối tiếp
        Note over Core,Periods: Kỳ paid tiếp theo đã có sẽ chặn gia hạn từ kỳ cũ do chồng lấn. Kỳ pending không tự giữ chỗ.
        alt Có kỳ PAID chồng lấn
            Core-->>FE: HTTP 409; chọn lại kỳ cuối phù hợp
        else Không chồng lấn
            Core->>Periods: INSERT RENEW, previous_subscription_id, giá chốt, PENDING_PAYMENT
            Core->>Core: Commit transaction
            Core-->>FE: Đơn gia hạn và thời hạn nối tiếp; chưa cấp quyền
        end
    end
```

`previous_subscription_id` giữ quan hệ chuỗi gia hạn; không UPDATE thời hạn kỳ trước. Gia hạn muộn vẫn dùng `ends_at` cũ làm điểm nối, không thay bằng giờ hiện tại. Khi thanh toán, kỳ mới có thể là `UPCOMING` hoặc `IN_TERM`; nếu toàn bộ kỳ đã hết, bằng chứng nhận tiền đi vào đối soát thay vì tự mua bù nhiều kỳ.

### 3. Xác nhận thanh toán: ghi bằng chứng và cấp quyền nhất quán

```mermaid
sequenceDiagram
    participant Gateway as "Adapter gateway đã xác minh"
    participant Core as "VehicleSubscriptionService"
    participant Periods as "vehicle / vehicle_subscription"
    participant Money as "payment / vehicle_subscription_receipt"
    Gateway->>Core: confirmVerifiedPayment(subscriptionId, paymentId, source, reference, amount, currency, paidAt)
    Note over Gateway,Core: Đây là gọi nội bộ sau API key, chữ ký hoặc inquiry hợp lệ. Frontend không được tự xác nhận paid.
    Core->>Periods: Đọc kỳ; khóa vehicle rồi vehicle_subscription; refresh
    Core->>Money: Khóa payment; refresh; đối chiếu invoice, gateway, context.subscriptionId và giá intent
    Core->>Money: Tìm receipt theo source + reference
    alt Đã có cùng bằng chứng
        Core-->>Gateway: Trả kỳ hiện có; không ghi lại receipt hoặc cấp thêm quyền
    else Cùng mã nhưng bằng chứng khác
        Core-->>Gateway: HTTP 409; không áp dụng cho đơn khác
    else Giao dịch mới
        Core->>Periods: JPQL kiểm tra tồn tại kỳ PAID giao với thời hạn đơn
        Core->>Core: Kiểm tra tiền thực, paidAt, deadline, thời hạn kỳ và chồng lấn
        Note over Core,Money: paidAt là giờ trả tiền được xác minh; receivedAt là giờ ghi nhận. Giá đơn không thay số tiền thực trong receipt.
        alt Đủ điều kiện cấp quyền
            Core->>Money: INSERT receipt APPLIED; saveAndFlush
            Core->>Money: Chuẩn bị payment PAID; lưu mã giao dịch và paid_at
            Core->>Periods: saveAndFlush kỳ PAID, paid_at, paid_receipt_id
            Note over Periods,Money: Java kiểm tra receipt khớp đơn PAID, giá và paid_at dưới khóa xe. FK/UNIQUE vẫn được DB bảo vệ.
            Core->>Core: Đăng ký callback afterCommit để phát SubscriptionEntitlementsChanged
        else Tiền đã xác minh nhưng cần đối soát
            Core->>Money: INSERT receipt REVIEW_REQUIRED và reason_code; saveAndFlush
            Core->>Money: Lưu REVIEW_REQUIRED nếu intent chưa PAID hoặc REVIEW_REQUIRED
            Core->>Periods: Pending thành PAYMENT_EXCEPTION; giữ nguyên kỳ PAID đã có
            Note over Periods,Money: Khoản chuyển lần hai vẫn có receipt riêng. Không ghi đè bằng chứng đã cấp quyền hoặc tự hoàn tiền.
        end
        Core->>Core: Commit transaction; afterCommit mới phát sự kiện
        Core-->>Gateway: Trả trạng thái kỳ đã được lưu
    end
```

`payment_id` nối intent báo giá với bằng chứng; `source + reference` nhận diện giao dịch thực; `paid_receipt_id` trỏ tới receipt đã cấp quyền. Receipt đối soát không cấp quyền và không sửa giá intent. Event chỉ được phát sau commit, hiện chưa có consumer và chưa tự tính lại phí phiên. Đây là thông báo best effort; lỗi listener được log, không đảo ngược khoản thu/quyền đã commit và không có durable retry/outbox trong phạm vi này.

Khi triển khai chốt phí phiên, consumer chỉ hủy cache/báo giá tạm sau commit. Căn cứ thu tiền phải lấy bằng `lockPaidPeriodsForSettlement` **bên trong write transaction đang tồn tại**: khóa xe → đọc lại và refresh kỳ paid → giao thời hạn với lịch gói → tính lại phí chưa thu → chốt kết quả khi vẫn giữ khóa. Query `/current` và `/paid` dành cho đọc dữ liệu không thay thế quy trình này.

## API cho frontend

Các API trả `Result<T>` theo quy ước hiện có. Thời gian gửi dạng chuỗi có offset; backend dùng `Instant`, lưu `TIMESTAMPTZ`. Chấp nhận tối đa sáu chữ số phần giây để giữ `requestKey` so sánh chính xác với dữ liệu PostgreSQL.

| Method/path | Dữ liệu vào | Kết quả |
| --- | --- | --- |
| `GET /api/subscription-packages` | Query `parkingLotId`, `vehicleType=CAR/MOTORBIKE` | Các phiên bản đang được bán |
| `POST /api/vehicle-subscriptions/register` | JSON như ví dụ dưới | Đơn pending hoặc đơn cũ cùng requestKey |
| `POST /api/vehicle-subscriptions/renew` | `previousSubscriptionId`, `packageId`, `requestKey`, `paymentDeadlineAt` | Kỳ gia hạn mới pending |
| `POST /api/vehicle-subscriptions/{id}/payment-link` | Không body, không giá | Invoice/link/QR theo giá đã chốt |
| `GET /api/vehicle-subscriptions/{id}` | ID | Chi tiết kỳ, lịch và trạng thái |
| `GET /api/vehicle-subscriptions/{id}/receipts` | ID | Các khoản tiền đã xác minh theo `receivedAt`, rồi `id`; số tiền thực, kết quả và lý do đối soát |
| `GET /api/vehicle-subscriptions/history` | `vehicleId`, `parkingLotId` | Lịch sử đơn và kỳ |
| `GET /api/vehicle-subscriptions/current` | `vehicleId`, `parkingLotId` | Kỳ paid đang trong thời hạn |
| `GET /api/vehicle-subscriptions/paid` | `vehicleId`, `parkingLotId`, `from`, `until` | Các kỳ paid giao với khoảng `[from, until)` |

Ví dụ đăng ký, thay ID bằng dữ liệu thật và chọn hạn thanh toán trong tương lai:

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

Không gửi `price`, `endsAt`, `paidAt` hoặc trạng thái thanh toán. Nếu người dùng sửa thông tin đơn, frontend cấp `requestKey` mới; gửi lại cùng đơn giữ key cũ.

`periodStatus` được tính bằng giờ server: `NONE` trước khi paid; `UPCOMING`, `IN_TERM`, `EXPIRED` sau paid. `IN_TERM` chỉ nói đang trong thời hạn kỳ; **không nói giờ hiện tại nằm trong lịch WEEKLY**.

`paidReceiptId` là `null` khi chưa được cấp kỳ. Trong kết quả receipt, `amountVnd` là số tiền của **một giao dịch thực**, `outcome=APPLIED` nghĩa là đã dùng để cấp kỳ, `REVIEW_REQUIRED` nghĩa là đang cần xử lý tài chính; `reasonCode` giải thích vì sao chưa được áp dụng. Không cộng các khoản chuyển thiếu rồi tự cấp kỳ. Không đồng nhất thời điểm `paidAt` và `receivedAt`; `paidAt` có thể `null` khi chưa xác định được thời điểm trả tiền đáng tin.

Tra cứu invoice qua API thanh toán hiện có cũng trả kết quả **nội bộ đã xác minh** cho intent `SUBSCRIPTION`: `PENDING`, `REVIEW_REQUIRED` hoặc `PAID`, kèm `subscriptionId`, `resultCode`, `resultMessage`. `amount` ở đây vẫn là giá báo của intent; tiền thực xem qua API receipts. Inquiry không gọi gateway để tự cấp kỳ, không thay callback và không trả payload/link/QR từ context. Nếu chưa nhận callback xác minh thì vẫn `PENDING`, kể cả người dùng báo đã chuyển tiền.

## Phát hành gói và cấu hình

`SubscriptionPackageService` đã có API JPA tạo/sửa DRAFT, publish và điều chỉnh việc bán; không thêm màn hình frontend. `ALL_DAY` không có lịch; `WEEKLY` phải có lịch hợp lệ trước publish. Service kiểm tra toàn tuần, kể cả Chủ nhật sang Thứ Hai, và hiệu lực bán không chồng giữa các phiên bản PUBLISHED cùng mã. Bản đã publish chỉ được đổi `sale_enabled`, `effective_to`; không đổi giá, thời lượng, lịch, tên hoặc `effective_from`. Thay quyền lợi tạo phiên bản mới. API không tự đóng phiên bản cũ để công bố phiên bản mới. Ví dụ request tại [vehicle-subscription-jpa.md](vehicle-subscription-jpa.md).

`app.vehicle-subscription.auth-enabled` mặc định `true`; dev `false`, prod `true`. Để frontend thử không token, đặt `APP_VEHICLE_SUBSCRIPTION_AUTH_ENABLED=false`. Repository mặc định chạy profile prod, vì vậy không thể chỉ dựa vào cấu hình dev. Công tắc này chỉ áp dụng API của module; xác minh webhook vẫn hoạt động.

Chi tiết gateway, hạn QR và giới hạn MegaPay tại [vehicle-subscription-payment.md](vehicle-subscription-payment.md).

## Điểm chưa chuyển đổi ở hệ thống cũ

Các luồng nhận biển số, phân loại xe tháng và mở barrier hiện vẫn đọc `ticket` ở `ParkingSessionLifecycleService`/`mqttPlateMessageService`. API mới không ghi song song vào ticket và không sửa gia hạn ticket cũ. Khi chuyển sang cơ chế mới, các luồng này phải dùng kỳ paid + lịch tuần qua `SubscriptionCoverageCalculator`.

`SubscriptionCoverageCalculator` và thuật toán tính tiền phiên chưa được triển khai trong thay đổi này. Gia hạn muộn vì vậy chưa tự tính lại báo giá phiên. Bước triển khai tiếp theo phải đọc quyền mới trước khi chốt phí/thu tiền, xử lý đua với thanh toán phiên và giữ nguyên phiên đã quyết toán. Không coi module này là đã hoàn thành chuyển đổi phí ngoài gói hoặc miễn phí tại barrier.

Điểm tích hợp backend đã có: `SubscriptionEntitlementsChanged` được service phát **sau commit** khi cấp kỳ thành công. Chưa có listener, consumer, gửi MQTT hoặc durable retry trong thay đổi này; đây chỉ là thông báo best effort. Consumer thông thường có thể nhận bằng `@EventListener`; không mặc định dùng `@TransactionalEventListener(AFTER_COMMIT)` để trì hoãn thêm một sự kiện đã được phát sau commit. Event không tự tính lại phí. Đường chốt phí phải mở **write transaction**, gọi `lockPaidPeriodsForSettlement(vehicleId, parkingLotId, from, until)`, giao kỳ với lịch gói, tính lại phí chưa thu và chốt kết quả khi vẫn giữ khóa xe. Hook yêu cầu transaction đã tồn tại (`MANDATORY`), refresh các kỳ sau khóa và giữ cùng thứ tự khóa với cấp kỳ. Không dùng kết quả đọc dành cho màn hình hoặc event làm căn cứ duy nhất để thu tiền. Quyết toán đã thanh toán không được mở lại khi nhận event.

Báo cáo thanh toán đã nối khoản thuê bao theo `receipt → vehicle_subscription → parking_lot` để lọc bãi/toà nhà. Intent `SUBSCRIPTION` bị loại khỏi nguồn cộng tiền legacy; từng receipt là nguồn tiền thực nhận, phân biệt APPLIED/REVIEW_REQUIRED. Không cộng giá intent và tiền receipt của cùng khoản. Chi tiết trường báo cáo tại [payment.md](payment.md). Chưa có thao tác duyệt đối soát hoặc hoàn tiền.

Gói giá 0 có thể tồn tại trong catalog; đăng ký/gia hạn mới và tạo link thanh toán đều yêu cầu giá dương. Chưa có luồng kích hoạt miễn phí được phê duyệt; không tạo giao dịch giả để cấp quyền. Chưa hỗ trợ thu tiền mặt, trả một phần, thay gói giữa kỳ, tạm dừng hoặc tự hoàn tiền. Không có TTL mặc định hoặc lịch hành chính được hardcode.

## Kiểm thử

Lệnh dưới chạy bộ test biểu phí/thuê bao được chọn bởi init script, không phải toàn bộ test project:

```bash
./gradlew --init-script gradle/subscription-tests.init.gradle test
```

Init script chỉ biên dịch test biểu phí/thuê bao vì test nguồn cũ `ParkingLotRepositoryTest.java` đang có lỗi cú pháp. Không load profile prod hoặc gọi DB/gateway thật trong unit/security test.

Test PostgreSQL dùng DB riêng qua `SUBSCRIPTION_TEST_JDBC_URL`, `SUBSCRIPTION_TEST_DB_USER`, `SUBSCRIPTION_TEST_DB_PASSWORD`. Nếu thiếu URL, chúng được đánh dấu skipped, không giả lập PostgreSQL bằng H2. Workflow `.github/workflows/subscription-tests.yml` tạo PostgreSQL 17 riêng để chạy các ràng buộc, transaction, cạnh tranh và repository JPA. Không trỏ các biến test này tới DB vận hành.

`VehicleSubscriptionServicePostgresIntegrationTest` gọi service thực qua Spring transaction advice và các repository thực, bao gồm `PaymentEntity`/JSON context. Kiểm tra đăng ký → cấp kỳ → callback lặp → chuyển khoản lần hai → gia hạn sớm; rollback cả receipt/payment/kỳ khi lỗi sau flush; chuyển thiếu lưu số tiền thực; hai callback cạnh tranh khóa xe, chỉ một kỳ được cấp và tiền của luồng còn lại vẫn được lưu đối soát. Hook chốt phí được kiểm tra trong write transaction, ngoài transaction và trong read-only transaction.

Schema test service tách biệt hoàn toàn: Hibernate tạo baseline các entity legacy và dữ liệu xe/bãi fixture; Flyway áp dụng V54 rồi V55; sau đó Hibernate `validate` các mapping thực. Test package/subscription gọi service và repository JPA thực sau khi các business trigger đã bị gỡ, kiểm chứng khóa, bất biến và rollback. Test riêng vẫn chạy V54 để kiểm tra hợp đồng migration lịch sử. Kiểm tra này không thay thế việc chạy toàn bộ chuỗi migration legacy trên bản sao DB triển khai hoặc kiểm chứng gateway/barrier hiện trường.
