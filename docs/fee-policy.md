# Thiết lập biểu phí gửi xe

Module đầu tiên triển khai `FeePolicyService`: nhận cấu hình từ màn hình thiết lập, kiểm tra và công bố phiên bản biểu phí theo **bãi xe + loại phương tiện**. Hiện hỗ trợ `CAR`, `MOTORBIKE` và hai phương pháp `DAILY_ENTRY`, `TIME_BLOCK`; phương pháp không bị cố định theo loại xe.

`FeePolicyService` không tính tiền phiên hoặc thu tiền. Sau V56, các bước này đã được triển khai trong `ParkingFeeService`, các calculator và `ParkingPaymentService`; xem [tính phí/thu phí bằng JPA](parking-fee-jpa.md) để biết API, quy tắc còn cần chốt và ranh giới với luồng lane cũ.

## API và quyền truy cập

Tất cả API trả envelope `Result` hiện có: `code`, `msg`, `data`.

| API | Đầu vào | Kết quả |
| --- | --- | --- |
| `POST /api/fee-policies/apply` | Cấu hình đầy đủ trong JSON body | Phiên bản mới cùng các khung giá |
| `GET /api/fee-policies/current` | `parkingLotId`, `vehicleType` | Bản có hiệu lực tại thời điểm server hiện tại |
| `GET /api/fee-policies/at` | `parkingLotId`, `vehicleType`, `at` | Bản có hiệu lực tại thời điểm chỉ định |
| `GET /api/fee-policies/history` | `parkingLotId`, `vehicleType` | Lịch sử phiên bản theo thứ tự hiệu lực tăng dần |
| `GET /api/fee-policies/{id}` | ID integer của phiên bản | Chi tiết một phiên bản |

Kiểm soát xác thực của module bằng thuộc tính `app.fee-policy.auth-enabled`:

| Cấu hình | Giá trị | Cách gọi API |
| --- | --- | --- |
| Profile dev | `false` | Cho phép gọi đọc và áp dụng biểu phí không cần bearer token |
| Profile prod | `true` | Yêu cầu JWT hợp lệ và quyền phù hợp |
| Không khai báo thuộc tính | `true` | Mặc định bật xác thực và phân quyền |

Có thể ghi đè bằng biến môi trường `APP_FEE_POLICY_AUTH_ENABLED=true` hoặc `false`. Giá trị được nạp khi ứng dụng khởi động; sau khi đổi cấu hình cần khởi động lại ứng dụng, không phải bật/tắt trực tiếp qua API.

Repository đang mặc định profile `prod` trong `application.yml`. Để frontend gọi không cần token trong môi trường hiện tại, đặt `APP_FEE_POLICY_AUTH_ENABLED=false` rồi khởi động lại backend. Cách này giữ nguyên các cấu hình kết nối của profile đang chạy; không cần chuyển profile chỉ để thử API biểu phí.

Khi bật, security chain dành riêng cho `/api/fee-policies/**` dùng JWT converter/issuer hiện có. `apply` yêu cầu `RoleEnum.CAN_EDIT()`: `ROLE_ORG_ROOT`, `ROLE_ORG_EDIT`, `ROLE_BRANCH_EDIT`. API đọc yêu cầu `CAN_VIEW()`, gồm các quyền trên và `ROLE_ORG_VIEW`, `ROLE_BRANCH_VIEW`. Gửi bearer token theo cơ chế xác thực của hệ thống.

Khi tắt, cả kiểm tra JWT và kiểm tra quyền theo method của các endpoint module này được bỏ qua, cho phép request ẩn danh đọc/ghi mà không tạo user giả. Sau V56, toggle cũng áp dụng cho `/api/parking-fees/**`; không thay đổi xác thực của các module còn lại. Kiểm tra dữ liệu, khóa, transaction và các ràng buộc DB vẫn thực hiện đầy đủ.

### Ví dụ theo lượt/ngày: áp dụng ngay

```json
{
  "parkingLotId": 33,
  "vehicleType": "MOTORBIKE",
  "name": "Biểu phí xe máy",
  "calculationMethod": "DAILY_ENTRY",
  "dailyPriceVnd": 5000
}
```

Không gửi `effectiveFrom` (hoặc gửi NULL) thì service dùng thời gian server sau khi lấy khóa. `timeBands` có thể bỏ qua, NULL hoặc là mảng rỗng. `remainderThresholdMinutes` phải NULL hoặc bỏ qua. Giá phải không âm.

### Ví dụ theo block: khung ngày và đêm

```json
{
  "parkingLotId": 33,
  "vehicleType": "CAR",
  "name": "Biểu phí ô tô ngày đêm",
  "effectiveFrom": "2030-10-10T00:00:00+07:00",
  "calculationMethod": "TIME_BLOCK",
  "remainderThresholdMinutes": 10,
  "timeBands": [
    {
      "name": "Ban ngày",
      "sortOrder": 0,
      "startTime": "06:00:00",
      "endTime": "18:00:00",
      "firstBlockMinutes": 60,
      "firstBlockPriceVnd": 20000,
      "nextBlockMinutes": 60,
      "nextBlockPriceVnd": 10000
    },
    {
      "name": "Ban đêm",
      "sortOrder": 1,
      "startTime": "18:00:00",
      "endTime": "06:00:00",
      "firstBlockMinutes": 60,
      "firstBlockPriceVnd": 15000,
      "nextBlockMinutes": 60,
      "nextBlockPriceVnd": 8000
    }
  ]
}
```

Thay mốc tương lai trong ví dụ bằng mốc vận hành mong muốn; bỏ trường này nếu áp dụng ngay. Với `TIME_BLOCK`, không gửi `dailyPriceVnd`; ngưỡng phải là số nguyên không âm, block phải dương và các giá phải không âm.

Các khung phải phủ đủ 24 giờ, không chồng và không hở. `endTime > startTime` là cùng ngày; nhỏ hơn là sang ngày hôm sau; bằng nhau là một khung 24 giờ. Khung lấy đầu, không lấy cuối: đúng 18:00 thuộc ban đêm. `sortOrder` chỉ điều khiển hiển thị, không ưu tiên một khung khi bị chồng.

Giờ khung là giờ địa phương, không kèm offset, tối đa 6 chữ số thập phân của giây. `Instant` như `effectiveFrom` và `at` cần ISO-8601 kèm `Z` hoặc offset; không gửi giờ địa phương thiếu offset. Service chuẩn hóa các Instant về độ chính xác microsecond để khớp PostgreSQL. Với query có `+07:00`, encode dấu `+` thành `%2B` hoặc dùng công cụ encode query tự động.

## Phiên bản và lịch hiệu lực

Mỗi lần áp dụng tạo một dòng `fee_policy` mới với **ID integer tự tăng mới**. `versionNo` bắt đầu từ 1 và tăng riêng trong cùng bãi + loại xe. `fee_time_band` giữ ID `BIGSERIAL`/`Long` theo migration đã có và tham chiếu ID integer của đúng phiên bản.

| ID minh họa | Version | Giá/lượt | Bắt đầu | Kết thúc |
| --- | --- | --- | --- | --- |
| 101 | 1 | 5.000 | 01/09/2026 | 10/10/2026 |
| 102 | 2 | 7.000 | 10/10/2026 | NULL |

Khoảng hiệu lực là `[effectiveFrom, effectiveTo)`: tại đúng mốc đổi giá chọn bản mới. Bản cũ giữ nguyên phương pháp, giá và khung; service chỉ đóng `effectiveTo` và cập nhật timestamp của bản trước. Các ID trên là minh họa, không gửi trong request.

`apply` hiện hỗ trợ phiên bản đầu và thêm vào cuối lịch. Mốc mới không được ở quá khứ và phải sau mốc bắt đầu của phiên bản cuối, kể cả bản đã lên lịch. Nếu đã có bản tương lai, yêu cầu áp dụng ngay sẽ xung đột; module chưa hỗ trợ hủy hoặc chèn trước bản tương lai đó.

Một transaction khóa dòng `parking_lot`, đọc lại lịch, đóng bản trước rồi lưu bản mới cùng các khung. Khóa bãi cũng bảo vệ hai yêu cầu tạo phiên bản đầu. UNIQUE theo phạm vi + số phiên bản và EXCLUDE theo khoảng hiệu lực bảo vệ dữ liệu tại DB. Lỗi ghi dữ liệu phải rollback cả việc đóng bản trước.

Nháp chỉ nằm ở frontend. `pricing_rules` được giữ trong DB nhưng chưa dùng: bản mới lưu `{}`, DTO không nhận/hiển thị trường này và thuật toán không đọc JSON.

## Hợp đồng tích hợp tiếp theo

`findForVisitor(parkingLotId, vehicleType, enteredAt)` chọn toàn bộ phiên bản có hiệu lực lúc xe vào. Xe vào 01/10, đổi giá 10/10 và ra 30/10 vẫn dùng bản cũ. Giữ bản cũ gồm cả phương pháp và các khung ngày/đêm của bản đó, không phải giữ cố định một khung trong suốt phiên.

Việc lấy `enteredAt` từ phiên gửi, truyền cấu hình đó cho calculator và lưu căn cứ tính phí sẽ thuộc `ParkingFeeService` sau này. Module này chưa tích hợp vào luồng check-in/checkout và không thêm trường vào `parking_session`.

`findOverlapping(parkingLotId, vehicleType, from, until)` trả các phiên bản giao với khoảng `[from, until)` để chuẩn bị tính phí ngoài gói thuê bao. Hàm kiểm tra phủ đủ khoảng và phát hiện chồng hiệu lực; chưa tính quyền lợi, chia block hay tính tiền. Hai helper này chưa có endpoint REST riêng.

Không có giá tại thời điểm yêu cầu trả lỗi `FEE_POLICY_NOT_FOUND`; lịch thiếu phủ/chồng trả lỗi tương ứng, không đổi sang giá 0 hoặc chọn giá mới nhất. Lịch sử rỗng của bãi tồn tại được trả dưới dạng mảng rỗng.

## Migration và kiểm tra

Nhánh hiện có `V52__create_parking_fee_tables.sql`. Migration V53 tiếp nối V52 để chuẩn hóa các bảng biểu phí, bổ sung ràng buộc, khóa ngoại và EXCLUDE chống chồng khoảng hiệu lực qua extension PostgreSQL `btree_gist`. Không thay V52 hoặc tạo lại bảng bằng một migration V50 mới.

Kiểu khóa được giữ thống nhất với dữ liệu và repository: `fee_policy.id` là integer; `parking_lot.id` và `fee_policy.parking_lot_id` là `BIGINT`/`Long`; `fee_time_band.id` giữ `BIGSERIAL`/`Long`. Các cột giá VND là `BIGINT`.

Nếu `fee_policy.parking_lot_id` trong schema cũ là UUID và đã có dữ liệu, V53 dừng thay vì tự đoán ánh xạ UUID sang ID bãi dạng số. Cần ánh xạ rõ từng khóa và phương án chuyển dữ liệu được duyệt trước khi tiếp tục; không xóa dữ liệu để chạy qua migration. Với DB dựng thủ công, đối chiếu schema và lịch Flyway trước khi áp dụng. Tài khoản migration cần quyền tạo extension hoặc extension phải được quản trị DB cài trước.

Project dùng Java 21 và Gradle wrapper. Lệnh kiểm tra thông thường:

```bash
./gradlew compileJava
./gradlew test --tests 'vio.barie.core.service.FeePolicyServiceTest'
```

`--tests` chỉ lọc test chạy, vẫn biên dịch toàn bộ test source. Bộ test toàn project hiện bị chặn bởi lỗi cú pháp sẵn có trong `ParkingLotRepositoryTest`. Để chạy riêng test mới của module, dùng init script giới hạn test source:

```bash
./gradlew --init-script gradle/fee-policy-tests.init.gradle test
```

Script này cũng cấu hình Mockito agent khi JVM test khởi động để tránh phụ thuộc vào cơ chế tự attach agent trong môi trường sandbox. Nó không sửa hay loại bỏ test cũ khỏi cấu hình build chính. Các unit test dùng mock repository không chứng minh được khóa cạnh tranh, rollback hoặc EXCLUDE thực tế; những cơ chế đó cần integration test PostgreSQL.

Tài liệu này mô tả cách kiểm tra, không xác nhận rằng các lệnh hoặc integration test đã chạy thành công.
