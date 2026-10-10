# Tính phí và thu phí phiên bằng JPA

Phạm vi thực thi sau V56: đọc phiên, xác định coverage, thử tính, lưu báo giá bất biến, tạo invoice qua payment hiện có, xác minh callback và chốt đúng bản tính. Module không tự quyết định quy tắc tài chính còn thiếu, thời gian được ra sau thanh toán hoặc thu bổ sung. Xem [quy tắc calculator](parking-fee-calculation-rules.md), [biểu phí](fee-policy.md), [thuê bao](vehicle-subscription-jpa.md) và [DBML](schema/parking-fee-jpa.dbml).

## Trách nhiệm đã triển khai

| Class | Thực thi |
| --- | --- |
| SubscriptionCoverageCalculator | Giao kỳ PAID với phiên và lịch ALL_DAY/WEEKLY; giữ nguồn từng cửa sổ; hợp nhất coverage và lấy phần bù. |
| ParkingFeeService | Đọc/khóa dữ liệu bằng JPA, chọn phiên bản giá, chuẩn bị input, thử tính và lưu snapshot, thay thế báo giá cũ, kiểm tra lại quyền lợi khi chốt. |
| FeeCalculator | Kiểm tra allocation, chọn phương pháp từ cấu hình, cộng phí bằng số nguyên có kiểm tra overflow, làm tròn lên 1.000 một lần. |
| TimeBlockCalculator | Chia theo khung địa phương, tính block đầu/tiếp theo và ngưỡng dư chính xác; không làm tròn tiền. |
| DailyEntryCalculator | Chia tại 00:00 địa phương, chỉ đếm ngày có thời lượng dương, áp dụng quy tắc gom/chọn giá đã cấu hình; không làm tròn tiền. |
| ParkingPaymentService | Dùng payment/gateway hiện có, ràng buộc invoice với bản tính, chống replay, ghi tiền cần đối soát, chốt nguyên tử và phát event sau commit. |

Calculator chỉ nhận DTO; không truy vấn DB, gọi gateway hoặc thiết bị. `FeePolicyService` vẫn quản lý cấu hình/phiên bản. `pricing_rules` không được đọc, validate hoặc dùng điều khiển tính tiền. Không hardcode phương pháp theo CAR/MOTORBIKE.

## Cấu hình phải xác nhận

`ParkingFeeSettings` dùng prefix `app.parking-fee`. Không có giá trị mặc định cho các quy ước dưới đây. Đây là các lựa chọn Java hữu hạn, không phải JSON DSL biểu phí; giá vẫn lấy từ các trường biểu phí hiện có.

| Thuộc tính | Contract |
| --- | --- |
| `session-time-zone` | Múi giờ thực của `parking_session.entry_time/exit_time` cũ không có offset. Cần xác nhận cách dữ liệu hiện tại được ghi. |
| `parking-lot-time-zones[lotId]` | ZoneId của bãi dùng chia lịch/khung/ngày. Không suy ra từ JVM. |
| `outside-pricing` | `ENTRY_VERSION`: ngoài gói dùng bản lúc vào; `EFFECTIVE_AT_TIME`: giao phần ngoài gói với các phiên bản có hiệu lực tại thời điểm đó. Không cấu hình thì từ chối tính ngoài gói. Vãng lai luôn ghim bản lúc vào. |
| `entitlement-timing` | `PERIOD_START`: quyền lợi từ đầu kỳ; `PAYMENT_TIME`: cắt quyền lợi trước thời điểm thanh toán xác minh. Khi PAID phát sinh sau giờ vào mà chưa cấu hình, từ chối suy diễn hồi tố. |
| `block-grouping` | `CONTIGUOUS_SEGMENT` hoặc `POLICY_AND_BAND`; xem bảng số tiền khác nhau trong tài liệu calculator. |
| `first-block-rule` | `ALWAYS_CHARGE` hoặc `APPLY_THRESHOLD` khi thời lượng nhỏ hơn block đầu. |
| `daily-grouping` | `SESSION_DAY` hoặc `SEGMENT_DAY` khi nhiều khoảng tính phí cùng ngày. |
| `daily-price-selection` | `FIRST`, `HIGHEST` hoặc `EACH_VERSION` khi cùng ngày giao nhiều phiên bản DAILY_ENTRY. |
| `allow-zero-duration-settlement` | Phải là `true` rõ ràng mới chốt phiên có thời lượng bằng 0. Thử tính có thể trả 0 để xem. |

Mỗi quy tắc chỉ bắt buộc khi tình huống thực tế cần dùng đến nó. Thiếu quyết định trả `FEE_RULE_DECISION_REQUIRED` (409), thiếu zone trả `FEE_TIMEZONE_REQUIRED` (409). Mốc địa phương rơi vào DST gap/overlap bị từ chối vì không có instant duy nhất. Timestamp đầu vào thanh toán/báo giá tối đa microsecond như PostgreSQL.

Với `PAYMENT_TIME`, kỳ trả tiền sau khi phiên đã kết thúc không biến phiên ấy thành thuê bao ngoài gói: chỉ kỳ có quyền hợp lệ giao phiên mới tham gia phân loại. WEEKLY có kỳ hợp lệ nhưng phiên nằm ngoài lịch vẫn có thể phát sinh toàn bộ phí ngoài gói.

Chưa chốt thời gian được ra sau thanh toán, cách tính tiếp khi chưa ra và workflow thu bổ sung. API yêu cầu `paymentDeadlineAt` rõ ràng; không đặt TTL cố định. Hạn này là hạn trả tiền, không phải hạn được mở barrier. Một phiên có bản tính SETTLED bị từ chối báo giá thu thêm cho tới khi có workflow được duyệt.

## API và ví dụ

Các API mới thuộc `/api/parking-fees`, dùng cơ chế xác thực `app.fee-policy.auth-enabled` hiện có (mặc định bật). VIEW cho xem, EDIT cho báo giá/yêu cầu thu/chốt phí 0. Không có endpoint trình duyệt tự đánh dấu PAID.

| API | Tác dụng |
| --- | --- |
| `POST /simulate` | Chỉ xem phí, không lưu calculation/payment hoặc đổi phiên. `until` tùy chọn, phải nằm trong khoảng đã gửi thực tế. |
| `POST /quotes` | Lưu snapshot, thay QUOTED cũ thành SUPERSEDED; giờ kết thúc lấy từ exit thực hoặc server now. |
| `GET /calculations/{id}` | Đọc bản tính và snapshot đã lưu. |
| `GET /sessions/{id}/calculations` | Lịch sử các bản tính. |
| `POST /calculations/{id}/payment` | Tạo yêu cầu thu đúng tiền backend, replay theo requestKey. |
| `GET /calculations/{id}/payments` | Đọc từng attempt và các giao dịch thực nhận, gồm khoản cần đối soát. |
| `POST /calculations/{id}/settle-no-charge` | Chốt tổng 0; không tạo payment hay giao dịch tiền giả. |

Ví dụ request (ID và thời điểm chỉ minh họa; hạn phải ở tương lai khi gọi):

```http
POST /api/parking-fees/simulate
Content-Type: application/json

{"sessionId":123,"until":"2026-10-09T12:00:00Z"}
```

```http
POST /api/parking-fees/quotes
Content-Type: application/json

{"sessionId":123,"paymentDeadlineAt":"2026-10-09T12:15:00Z"}
```

```http
POST /api/parking-fees/calculations/456/payment
Content-Type: application/json

{"requestKey":"fee-session-123-quote-456-attempt-1"}
```

`Result.data` của bản tính có dạng sau (snapshot rút gọn chỉ để mô tả API, thực tế giữ toàn bộ):

```json
{
  "calculationId": 456,
  "sessionId": 123,
  "status": "QUOTED",
  "calculatedAt": "2026-10-09T12:00:00Z",
  "calculationUntil": "2026-10-09T12:00:00Z",
  "validUntil": "2026-10-09T12:15:00Z",
  "subtotalVnd": 23400,
  "roundingAdjustmentVnd": 600,
  "totalVnd": 24000,
  "inputFingerprint": "<SHA-256 của snapshot canonical>",
  "snapshot": {"engineRevision":"parking-fee-v1","timezone":"Asia/Ho_Chi_Minh"}
}
```

Yêu cầu thu trả `attemptId`, `calculationId`, `paymentId`, `invoiceNo`, `amountVnd`, `currency`, `status`, `applicationStatus`, `applicationReason`, `gatewayLink`. Link/QR chỉ trả khi attempt và payment còn PENDING; replay khoản REVIEW_REQUIRED không mời khách trả thêm. Cùng request key khác bản tính/tiền trả conflict. Cùng bản tính có PENDING/PAID hoặc tiền REVIEW_REQUIRED không tạo invoice mới bằng key khác.

Phí vượt sức chứa số nguyên VND của `payment.amount numeric(18,2)` trả `FEE_PAYMENT_AMOUNT_LIMIT` trước khi gọi gateway; không cắt/tràn số để tạo intent.

## Snapshot và chọn giá

`fee_detail` chứa phiên/xe/bãi/loại xe, entry/exit gốc, khoảng instant và cả hai timezone, coverage sources với kỳ/phiên bản/lịch thực dùng, covered/uncovered, allocation với giá/khung/phiên bản thực dùng, quy ước đã chọn, fee lines và tổng trước/sau làm tròn. `input_fingerprint` là SHA-256 JSON canonical; object fields sắp xếp, thứ tự array giữ nguyên.

Vãng lai gọi `findForVisitor` tại giờ vào và dùng toàn bộ phiên bản đó cho mọi đoạn/khung suốt phiên. Đóng `effective_to` của bản cũ sau này không làm mất báo giá vãng lai đã lưu. Với ngoài gói `EFFECTIVE_AT_TIME`, mỗi khoảng uncovered phải được `findOverlapping` bao phủ đầy đủ; thiếu giá không trở thành miễn phí. Một snapshot cùng policy ID nhưng dữ liệu không nhất quán bị từ chối; không tự chọn bản mới nhất.

Snapshot/tiền/hạn không sửa sau khi lưu. JPA lifecycle chỉ cho QUOTED → SUPERSEDED hoặc SETTLED; lịch sử không xóa. Các guard lifecycle không bảo vệ bulk SQL hoặc sửa trực tiếp DB; repository phí không cung cấp bulk update/delete.

## Thanh toán, cạnh tranh và đối soát

Thứ tự khóa là vehicle → parking_session → fee_calculation → parking_fee_payment → payment. Vehicle dùng đúng hook khóa thuê bao. Sau khi chờ khóa, refresh entity và kiểm tra lại phạm vi. Khi chốt, đọc lại PAID qua `lockPaidPeriodsForSettlement` và tính coverage theo quy ước trong snapshot. Quyền lợi hoặc dữ liệu phiên thay đổi làm bản tính SUPERSEDED; callback vẫn lưu tiền đã xác minh để đối soát thay vì chuyển sang báo giá mới.

Một attempt dùng chính `payment` hiện có với callback_type SESSION và context gồm calculationId/parkingFeePaymentId/sessionId/vehicleId/parkingLotId. Gateway định tuyến phạm vi này về `ParkingPaymentService` trước nhánh SESSION cũ. Không dùng `vehicle_subscription_receipt` cho phí phiên và không thay mô hình payment cha–con.

`parking_fee_payment.receipt` là array chỉ thêm các observation đã xác minh: source/reference/invoice/amount/currency/paidAt/receivedAt/outcome/reason/metadata cho phép. Cùng source+reference và dữ kiện quan trọng trả outcome cũ; khác dữ kiện trả conflict. Giao dịch thực sự khác được thêm bằng chứng REVIEW_REQUIRED, không chốt hai lần. Bảng `parking_fee_transaction_key` chỉ là registry PK toàn cục bảo vệ identity giữa các intent đang chạy cạnh tranh; tiền thực nhận vẫn nằm trong receipt JSON hiện có. Không phải ledger hoặc adapter mới.

Thiếu/thừa tiền, giờ trả tiền không xác định, trả sau hạn, báo giá cũ hoặc chuyển thêm đều có lý do đối soát cụ thể. Attempt đã cần đối soát không được tự mở lại hoặc tự cộng các khoản thiếu; chưa có API hoàn tiền/chuyển REVIEW thành APPLIED. `paidAt` tin cậy đúng hạn được chấp nhận dù callback nhận sau hạn. Trường `payment.paid_at` cũ không có timezone được ghi UTC cho phạm vi mới, như contract thuê bao; observation giữ instant gốc.

SePay dùng truy vấn authenticated và đối chiếu invoice/reference/amount/tài khoản nhận; dữ kiện mâu thuẫn hoặc kết quả chưa xác minh không chốt phí. MegaPay dùng chữ ký và liên kết invoice/merchant/currency; contract hiện tại chưa có `paidAt` tin cậy, vì vậy thành công cần REVIEW_REQUIRED/PAYMENT_TIME_UNKNOWN. MQTT tiền mặt cho phạm vi mới bị từ chối vì chưa có danh tính thiết bị và mã giao dịch ổn định được xác minh. Các luồng TICKET/SUBSCRIPTION tiếp tục định tuyến riêng; MegaPay FAILED/PENDING không chạy nhánh thành công legacy.

Cập nhật tiền và SETTLED trong một transaction DB; lỗi kỹ thuật rollback. Event `ParkingFeeSettled` chỉ phát afterCommit, không phát lại khi replay. Lỗi listener được log; chưa thêm outbox hoặc bảo đảm chuyển phát/retry. Không có listener tự mở barrier cho phí mới khi quy tắc checkout/grace chưa được chốt. Tạo invoice bên ngoài và transaction DB không phải distributed transaction: lỗi DB sau khi gateway tạo link có thể để invoice không được liên kết, cần đối soát nhà cung cấp.

## Ranh giới với luồng cũ

Luồng lane cũ vẫn dùng `CalculatorService` và monthly ticket. Đây chưa phải rollout tự động sáu class vào toàn bộ làn. `ParkingFeeWorkflowGuard` chặn dùng hỗn hợp: phiên có bất kỳ invoice SESSION không gắn bản tính mới không được tạo quote mới; phiên có lịch sử fee_calculation không được checkout/createSessionPayment legacy. Kiểm tra legacy đặt trước thay đổi phiên/MQTT/gateway và dùng cùng khóa vehicle/session để tránh hai luồng cùng vượt qua kiểm tra. Invoice cũ ở trạng thái FAILED vẫn có thể nhận tiền muộn nên không tự coi là an toàn để chuyển workflow.

Cần chốt handoff ở làn, giờ cắt phí khi xin ra, khoảng được ra và thu bổ sung trước khi triển khai tự động mở barrier. Khi guard trả `FEE_WORKFLOW_CONFLICT`, vận hành phải xử lý đúng workflow đã chọn, không lách bằng invoice khác.

Truy vấn guard dùng native SQL **qua JPA** với `EXISTS` vì liên kết session/scope của payment cũ nằm trong JSONB. Nó chỉ trả boolean, không tải đồ thị payment/lịch/receipt; không thêm JDBC hoặc connection nghiệp vụ riêng.

Báo cáo `PaymentService` hiện cộng các intent SESSION PAID; tổng tiền thực nhận chung chưa cộng observation review/chuyển thêm của phí phiên. Ví dụ invoice 5.000 có hai giao dịch 5.000: evidence API giữ 10.000, còn báo cáo chung chỉ phản ánh intent PAID 5.000. Dùng endpoint payments để đối soát phần này; không coi `totalReceivedAmount` hiện tại là tổng đầy đủ của phí phiên. Báo cáo receipt thuê bao giữ nguyên contract.

## Migration và kiểm chứng

V56 nối các bảng V52 với schema thật: fee_calculation.session_id BIGINT → parking_session, valid_until, FK biểu phí; parking_fee_payment.calculation_id BIGINT và payment_id cùng unique requestKey/paymentId, trạng thái áp dụng; registry identity toàn cục. Không sửa migration đã áp dụng, không thêm trigger/function nghiệp vụ. Nếu bảng calculation còn UUID và có dữ liệu, migration dừng yêu cầu mapping rõ ràng, không tự xóa hoặc đoán ID. Dữ liệu cũ không hợp lệ FK/unique phải được đối soát trước khi migrate; quote cũ NULL valid_until không được trả tiền qua luồng mới.

Chạy bộ module có PostgreSQL thật:

```bash
SUBSCRIPTION_TEST_JDBC_URL=jdbc:postgresql://localhost:5432/subscription_test \
SUBSCRIPTION_TEST_DB_USER=subscription_test \
SUBSCRIPTION_TEST_DB_PASSWORD=subscription_test \
bash ./gradlew --no-daemon --init-script gradle/parking-fee-tests.init.gradle clean test
```

Biến datasource này chỉ dùng fixture test; không nạp profile/DB vận hành. CI `parking-fee-tests.yml` dùng Java 21/PostgreSQL 17, yêu cầu suite thuật toán và PostgreSQL thực sự chạy, không skipped. Local kiểm chứng PostgreSQL 16 bằng môi trường tạm.

Kiểm chứng ngày 09/10/2026: clean build đạt 426/426 test trong 25 suite, gồm 63 test PostgreSQL thực; 0 failures/errors/skips. `bootJar` thành công. Có 270 test module nền và 156 test mới, gồm 52 test calculator, 39 test gateway phí phiên, 23 test payment service, 15 test orchestration, 2 test guard và 25 test PostgreSQL mới. Không gọi gateway thanh toán hoặc barrier vận hành trong kiểm thử.

Bộ chọn lọc không đại diện toàn repository. `compileTestJava` mặc định còn lỗi cú pháp có sẵn trong `ParkingLotRepositoryTest` (đoạn method ngoài class); init script không chọn file này.
