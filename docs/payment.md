# Thanh toán và báo cáo tiền thuê bao

Tài liệu này mô tả phần tích hợp `SUBSCRIPTION` vào module payment hiện có. Luồng tạo yêu cầu thu, gateway và callback `TICKET`/`SESSION` vẫn giữ contract cũ. Chi tiết tạo QR và xác minh gateway nằm trong [Thanh toán kỳ thuê bao](vehicle-subscription-payment.md).

## Vai trò của dữ liệu

| Bảng | Một bản ghi đại diện cho | Số tiền có ý nghĩa gì? |
| --- | --- | --- |
| `payment` | Yêu cầu thu tiền theo invoice; định tuyến bằng `callback_type` và `callback_context` | `amount` là số tiền yêu cầu khách trả. Với `SUBSCRIPTION`, không dùng nó để cộng tiền thực nhận. |
| `vehicle_subscription_receipt` | Một giao dịch tiền thực nhận đã được backend xác minh | `amount_vnd` là số tiền của chính giao dịch đó; `currency` xác định tiền tệ. `APPLIED` và `REVIEW_REQUIRED` đều là tiền đã được ghi nhận. |
| `vehicle_subscription` | Đơn/kỳ thuê bao và quyền sử dụng | `price_vnd` là giá đã chốt. `paid_receipt_id` chỉ receipt được áp dụng để cấp chính kỳ đó. |

Giữ bảng receipt riêng, không đổi `payment` thành bản ghi cha–con. Một payment có thể có nhiều receipt. `payment_id` trong receipt không có UNIQUE; cặp `source + reference` mới định danh một giao dịch thực và có UNIQUE.

Gateway gửi lại cùng giao dịch không tạo thêm receipt hoặc cấp quyền lần nữa. Khách chuyển một giao dịch khác vào invoice đã thanh toán thì vẫn phải được ghi nhận.

Ví dụ invoice yêu cầu 300.000 VND:

| Bản ghi | Số tiền | Kết quả |
| --- | ---: | --- |
| `payment` | 300.000 | Yêu cầu thu, sau lần áp dụng đầu tiên có trạng thái `PAID`. |
| Receipt `TX001` | 300.000 | `APPLIED`, cấp một kỳ thuê bao. |
| Receipt `TX002` | 300.000 | `REVIEW_REQUIRED` / `SECOND_SETTLEMENT`, giữ để đối soát. |

Kết quả là 600.000 VND thực nhận, 300.000 VND đã áp dụng và 300.000 VND cần đối soát. Kỳ vẫn chỉ được cấp một lần. Không cộng thêm `payment.amount`, vì như vậy sẽ báo sai thành 900.000 VND.

`payment.status = PAID` của thuê bao thể hiện intent đã cấp kỳ thành công. Nó không thay thế lịch sử tiền: sau khoản chuyển thứ hai, intent vẫn `PAID`, kỳ giữ receipt đầu tiên và receipt thứ hai ghi kết quả cần đối soát. Khi một khoản tiền không đủ điều kiện áp dụng, receipt vẫn được lưu; trạng thái intent có thể là `REVIEW_REQUIRED`.

V1 không cộng nhiều khoản thiếu để cấp kỳ, không tự hoàn tiền và không đổi receipt đã ghi từ `REVIEW_REQUIRED` thành `APPLIED`.

## Nguồn tiền trong báo cáo

`SubscriptionPaymentReportService` dùng JPA Criteria để tổng hợp receipt theo đơn, bãi và toà nhà. Không fetch lịch gói hoặc toàn bộ entity graph và không truy cập DB qua JDBC.

Phần thuê bao được tổng hợp riêng rồi ghép với kết quả báo cáo legacy:

- Báo cáo legacy tiếp tục dùng `payment` có trạng thái `PAID`, nhưng loại `callback_type = SUBSCRIPTION` khỏi SUM số tiền.
- Tiền thuê bao thực nhận là SUM receipt VND, gồm cả `APPLIED` và `REVIEW_REQUIRED`.
- Tiền thuê bao đã áp dụng chỉ SUM receipt `APPLIED`.
- Tiền thuê bao cần đối soát chỉ SUM receipt `REVIEW_REQUIRED`.
- Không JOIN một payment với nhiều receipt rồi SUM `payment.amount`.

Các tổng tiền mới là tổng VND. Receipt tiền tệ khác không bị xoá, nhưng không được cộng vào tổng VND hoặc tự đổi tỷ giá; truy cập lịch sử receipt để đối soát chúng. Cách ghi nhận tiền của `TICKET`/`SESSION` cũ không được chuyển sang receipt trong thay đổi này, nên phần legacy vẫn mang semantics `payment.amount`/`PAID` hiện có.

## API và ý nghĩa trường

Các endpoint giữ URL và tham số hiện có. Trường bổ sung là additive; frontend có thể tiếp tục đọc các trường cũ.

### `GET /api/payment/statistics`

Tham số: `parkingLotId`, `buildingId`, `startDate`, `endDate`.

| Trường response | Ý nghĩa |
| --- | --- |
| `totalRevenue` | Tổng legacy `PAID` cộng tiền thuê bao `APPLIED`. Khoản thuê bao cần đối soát không được coi là tiền đã áp dụng. |
| `totalReceivedAmount` | Tổng legacy `PAID` cộng toàn bộ tiền thuê bao thực nhận VND, bao gồm tiền cần đối soát. |
| `subscriptionAppliedAmount` | Tiền thuê bao VND đã áp dụng. |
| `subscriptionReviewRequiredAmount` | Tiền thuê bao VND cần đối soát. |
| `subscriptionReceivedTransactions` | Số receipt VND trong phạm vi báo cáo; callback lặp không tăng số này. |
| `successfulTransactions` | Số yêu cầu payment có trạng thái `PAID`, gồm cả yêu cầu thuê bao. Đây không phải số giao dịch ngân hàng thực. |
| `pendingTransactions` | Số yêu cầu payment `PENDING`. |
| `failedTransactions` | Giữ cách phân nhóm cũ: số yêu cầu có trạng thái khác `PAID`/`PENDING`, kể cả `REVIEW_REQUIRED`. Không dùng trường này để kết luận tiền chưa được nhận. |

Với ví dụ hai khoản chuyển và không có khoản legacy khác:

```json
{
  "totalRevenue": 300000,
  "totalReceivedAmount": 600000,
  "subscriptionAppliedAmount": 300000,
  "subscriptionReviewRequiredAmount": 300000,
  "subscriptionReceivedTransactions": 2,
  "successfulTransactions": 1,
  "pendingTransactions": 0,
  "failedTransactions": 0
}
```

Số yêu cầu và số receipt là hai thước đo riêng; không cộng chúng với nhau để đếm giao dịch.

### `GET /api/payment/report`

Tham số: `startDate`, `endDate`, `buildingId`, `parkingLotId`, `page`, `size`.

Response giữ `totalRevenue`, `avgDailyRevenue`, `totalTransactions`, `dailyDetails` và thêm các trường sau:

| Trường | Ý nghĩa |
| --- | --- |
| `totalReceivedAmount` | Tổng tiền thực nhận VND theo nguồn dữ liệu nêu trên. |
| `subscriptionAppliedAmount` | Tổng tiền thuê bao đã áp dụng. |
| `subscriptionReviewRequiredAmount` | Tổng tiền thuê bao cần đối soát. |
| `totalReceivedTransactions` | Số payment legacy `PAID` cộng số receipt thuê bao VND thực nhận. |
| `totalTransactions` | Số payment legacy `PAID` cộng số receipt thuê bao `APPLIED`; không gồm receipt thuê bao cần đối soát. |

Mỗi phần tử `dailyDetails` giữ `date`, `transactionCount`, `revenue` và thêm `receivedAmount`, `subscriptionAppliedAmount`, `subscriptionReviewRequiredAmount`, `receivedTransactionCount`. Cách tính tương ứng với các tổng của response. Ngày chỉ có tiền cần đối soát vẫn xuất hiện, với `revenue = 0` và `receivedAmount > 0`.

Các tổng tính trên toàn bộ phạm vi ngày; phân trang chỉ tác động tới danh sách chi tiết ngày. `avgDailyRevenue` dùng `totalRevenue`, không dùng tiền cần đối soát.

### `GET /api/payment`

Danh sách vẫn là **yêu cầu thanh toán**, mỗi payment xuất hiện một lần. `amount` vẫn là số tiền yêu cầu. Không biến từng receipt thành một dòng trong API này.

Luồng thuê bao bổ sung biển số và nhãn `Đóng thuê bao`, cùng bộ lọc bãi/toà nhà. Việc tìm phạm vi đi qua `callback_context.subscriptionId` → `vehicle_subscription.parking_lot_id` → `parking_lot.building_id`. Đây là định tuyến JSON đã được service kiểm tra, không phải một FK mới. Content và count query dùng cùng phạm vi; nhiều receipt không làm tăng số yêu cầu.

Các truy vấn legacy cần thao tác JSONB vẫn nằm trong `JpaRepository` với `nativeQuery`; chúng chạy qua JPA, không thêm `JdbcTemplate` hoặc kết nối JDBC vào production module.

## Mốc ngày và thời điểm

Ngày lọc nhận dạng `yyyy-MM-dd`; `startDate` bao gồm đầu ngày, `endDate` bao gồm cả ngày đó bằng cách query đến đầu ngày tiếp theo với điều kiện `<`. Không truyền cả hai ngày thì giữ mặc định tháng hiện tại đến hết ngày hiện tại theo đồng hồ môi trường ứng dụng.

Riêng receipt thuê bao, giới hạn ngày và nhóm ngày sử dụng UTC. Thời điểm dùng cho báo cáo là `paid_at` nếu có; khi thiếu thì dùng `received_at` để giao dịch vẫn xuất hiện trên ngày backend ghi nhận. Việc COALESCE này chỉ nằm trong truy vấn báo cáo:

- Không ghi `received_at` vào `paid_at`.
- Không sử dụng thời điểm nhận callback thay thời điểm trả tiền để kiểm tra deadline hoặc cấp quyền.
- Không kết luận khoản tiền được trả đúng hạn chỉ vì nó xuất hiện trong một ngày báo cáo.

Báo cáo payment legacy vẫn giữ semantics timestamp cũ; thay đổi này không chuyển toàn bộ lịch sử legacy sang UTC. Muốn báo cáo mọi nguồn theo múi giờ từng bãi cần một yêu cầu và bước chuyển đổi dữ liệu riêng.

Receipt được đọc trực tiếp mỗi lần báo cáo, kể cả callback nhận hôm nay nhưng `paid_at` thuộc một ngày cũ. Cache Redis của tổng legacy dùng namespace mới `payment:legacy-revenue:v2:` để không tái sử dụng cache từng chứa giá báo của thuê bao. Không lưu số tiền receipt vào cache đó; ngày hiện tại không lấy từ cache legacy.

## Excel và đối soát

`GET /api/payment/report/export` dùng cùng bộ lọc, nguồn dữ liệu và tổng tiền với API report. Giữ ba cột ngày/số giao dịch/doanh thu và bổ sung tiền thực nhận, tiền thuê bao đã áp dụng, tiền thuê bao cần đối soát, số giao dịch thực nhận. Summary và footer có cùng các tổng; chú thích nêu rõ receipt thuê bao theo ngày UTC.

Đọc `GET /api/vehicle-subscriptions/{subscriptionId}/receipts` để xem từng `source`, `reference`, số tiền, `outcome`, `reasonCode`, `paidAt`, `receivedAt`. Báo cáo không phải công cụ duyệt ngoại lệ; V1 chưa có API giải quyết đối soát hoặc hoàn tiền.

## Giới hạn xác minh gateway

SePay chỉ cung cấp `paidAt` khi ngày giờ từ API xác thực có offset hợp lệ. Không đoán múi giờ từ webhook.

MegaPay hiện chưa có hợp đồng thời gian được xác nhận cho các trường `trxDt`/`transDt` và các trường này không nằm trong chữ ký callback đang dùng. Callback thành công đã xác minh được ghi thành receipt có `paidAt = null`, cần đối soát với `PAYMENT_TIME_UNKNOWN`; không tự cấp kỳ thuê bao. Báo cáo có thể đưa khoản này vào ngày `receivedAt`, nhưng đó không phải bằng chứng thời điểm thanh toán. Cần nguồn thời gian/inquiry đáng tin và kiểm thử bổ sung trước khi bật cấp kỳ tự động cho MegaPay.

## Kiểm chứng

- `VehicleSubscriptionPaymentReportTest`: ví dụ 600.000 VND thực nhận / 300.000 VND đã áp dụng / 300.000 VND cần đối soát, ghép với legacy và ngày chỉ có tiền cần đối soát.
- `SubscriptionPaymentReportServicePostgresIntegrationTest`: chạy JPA Criteria và truy vấn repository thật trên PostgreSQL, kiểm tra bãi/toà nhà, VND, ranh giới ngày, không sửa `paidAt` thiếu, danh sách một yêu cầu dù có nhiều receipt.
- Bộ test service PostgreSQL kiểm chứng riêng việc ghi receipt/payment/kỳ nguyên tử, callback lặp và giao dịch thứ hai. Test báo cáo dùng fixture trong schema tạm, không gọi gateway hoặc đọc cấu hình kết nối production.
