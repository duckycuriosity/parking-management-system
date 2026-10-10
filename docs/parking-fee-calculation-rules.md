# Hợp đồng calculator phí gửi xe

Tài liệu này mô tả bốn calculator đã triển khai trong Java. Các lựa chọn tính tiền dưới đây là **khả năng của implementation**, chưa phải quyết định nghiệp vụ đã được duyệt. Không có lựa chọn mặc định cho tình huống chưa chốt. Cấu hình thiếu mà kết quả phụ thuộc vào lựa chọn đó trả `FEE_RULE_DECISION_REQUIRED`.

Calculator không đọc DB, gọi gateway, ghi receipt hay phát lệnh barrier. `ParkingFeeService` chuẩn bị dữ liệu qua JPA và chọn phiên bản biểu phí; `ParkingPaymentService` quản lý thu tiền. `pricing_rules` không tham gia tính toán, kiểm tra cấu hình hay chọn thuật toán.

## Đầu vào và đầu ra

Các record nằm trong `vio.barie.core.dto.fee.calculation`.

| Thành phần | Hợp đồng |
| --- | --- |
| `TimeRange(Instant start, Instant end)` | Khoảng `[start, end)`. Hai mốc bằng nhau là khoảng rỗng; mốc cuối trước mốc đầu không hợp lệ. |
| `PricingAllocation(TimeRange range, FeePolicyResponse policy)` | Một đoạn cần tính và phiên bản giá do caller chọn. Các allocation không được chồng nhau. |
| `FeeRules` | Bốn lựa chọn nullable: `blockGrouping`, `firstBlockRule`, `dailyGrouping`, `dailyPriceSelection`. Chỉ yêu cầu lựa chọn khi gặp tình huống tương ứng. |
| `CoverageResult` | Các cửa sổ nguồn, phần được bao phủ đã hợp nhất và phần ngoài gói. |
| `FeeLine` | Một khoản **chưa làm tròn tiền**: bản sao cấu hình giá, các khoảng thực dùng, ngày địa phương hoặc block, thời lượng, phần dư, quy tắc đã dùng và subtotal. |
| `FeeResult` | Các dòng phí, tổng trước làm tròn, phần làm tròn và tổng phải thu. |

API calculator:

```java
CoverageResult coverage.calculate(
    TimeRange session, List<SubscriptionResponse> paidPeriods, ZoneId parkingZone);

FeeResult fee.calculate(
    List<PricingAllocation> allocations, ZoneId parkingZone, FeeRules rules);

List<FeeLine> blocks.calculate(
    List<PricingAllocation> allocations, ZoneId parkingZone, FeeRules rules);

List<FeeLine> days.calculate(
    List<PricingAllocation> allocations, ZoneId parkingZone, FeeRules rules);
```

`ZoneId` bắt buộc được cung cấp rõ; calculator không dùng timezone mặc định của JVM. Timestamp là `Instant`; giờ khung/lịch là `LocalTime` trong timezone bãi. Nếu một mốc giờ lịch cần dùng không xác định duy nhất do DST, calculator trả `FEE_TIME_DECISION_REQUIRED`, không tự chọn offset.

## SubscriptionCoverageCalculator

Chỉ kỳ `PAID` tham gia quyền lợi. `PENDING_PAYMENT`, `CANCELLED` và `PAYMENT_EXCEPTION` không cấp coverage.

- `ALL_DAY`: giao khoảng phiên với `[startsAt, endsAt)` của kỳ.
- `WEEKLY`: mở rộng lịch theo ngày địa phương rồi giao với phiên và kỳ. `weekday` là ngày bắt đầu: Thứ Hai = 1, Chủ nhật = 7. Cuối lớn hơn đầu là cùng ngày; nhỏ hơn đầu là ngày sau; bằng nhau là 24 giờ.
- Khi xét cửa sổ qua nửa đêm, calculator xét cả ngày trước mốc bắt đầu để không mất phần Chủ nhật kéo sang Thứ Hai.
- Hợp nhất các đoạn chồng hoặc tiếp giáp. Tính phần ngoài gói bằng phần bù trong đúng khoảng phiên. Các cửa sổ nguồn vẫn được giữ riêng để giải thích kỳ và lịch đã sử dụng.

Ví dụ phiên 07:00–19:00 với lịch hợp lệ 08:00–17:00: coverage là 08:00–17:00, phần ngoài gói là 07:00–08:00 và 17:00–19:00. Kết quả này chưa quyết định gom block hay số lượt tiền.

`CoverageWindow` giữ ID kỳ, ID/phiên bản gói, mode, ngày tuần và giờ lịch thực dùng. Calculator không quyết định hồi tố quyền lợi khi khách trả tiền trong lúc phiên đang diễn ra; caller phải cung cấp các kỳ đã được áp dụng theo lựa chọn nghiệp vụ tương ứng.

## TimeBlockCalculator

Calculator dùng phương pháp trong cấu hình, không gắn `CAR` cố định với `TIME_BLOCK`. Các khung phải phủ đủ 24 giờ địa phương và không hở/chồng. Khung qua nửa đêm và khung 24 giờ được hỗ trợ.

Khoảng cần tính được chia theo khung giá. Hai đoạn tiếp giáp có cùng phiên bản và cùng khung được nối lại trước khi đếm block: việc caller chia nhỏ input hoặc chuyển ngày trong cùng một khung không tự tạo block đầu mới.

### Gom hoặc tách block

Khi có nhiều đoạn sau bước nối, `blockGrouping` phải được cung cấp:

| Lựa chọn | Hành vi đã triển khai |
| --- | --- |
| `CONTIGUOUS_SEGMENT` | Mỗi đoạn liên tục có cùng phiên bản và khung được tính một chuỗi block riêng. Khoảng miễn phí, đổi phiên bản hoặc đổi khung tách chuỗi. |
| `POLICY_AND_BAND` | Cộng thời lượng cần thu theo cùng phiên bản và khung, kể cả các đoạn cách nhau hoặc ở ngày khác; tính một chuỗi block cho mỗi nhóm đó. Thời gian được miễn giữa các đoạn không được cộng. |

Ví dụ cùng khung: block đầu 60 phút giá 20.000, block tiếp theo 60 phút giá 10.000; phần ngoài gói 07:00–08:00 và 17:00–19:00:

| Lựa chọn | Phép tính | Subtotal VND |
| --- | --- | ---: |
| `CONTIGUOUS_SEGMENT` | 20.000 + (20.000 + 10.000) | 50.000 |
| `POLICY_AND_BAND` | Tổng 3 giờ: 20.000 + 2 × 10.000 | 40.000 |
| Không cấu hình | Không tự chọn một cách thu | Lỗi cần chốt quy tắc |

Hai lựa chọn này đều giữ các khung giá khác nhau thành các nhóm khác nhau. Chúng **chưa triển khai** một chuỗi block duy nhất tiếp tục xuyên qua các khung có giá/độ dài khác nhau. Nếu muốn hành vi đó, cần chốt cách phân bổ thời lượng và giá trước khi mở rộng thuật toán.

Ví dụ 17:30–18:30, khung ngày kết thúc 18:00, block đầu ngày 20.000, block đầu đêm 15.000, cả hai dài 60 phút: chọn `CONTIGUOUS_SEGMENT` và `ALWAYS_CHARGE` cho hai phần 30 phút cho subtotal 35.000. Thiếu lựa chọn thì calculator không tự tính 35.000 hoặc suy ra một mức khác.

### Block đầu chưa đủ thời lượng

Một thời lượng dương ngắn hơn block đầu yêu cầu `firstBlockRule`:

| Lựa chọn | Hành vi |
| --- | --- |
| `ALWAYS_CHARGE` | Thu một block đầu cho mọi thời lượng dương chưa đủ block. |
| `APPLY_THRESHOLD` | Thu block đầu nếu thời lượng bằng hoặc lớn hơn ngưỡng; thấp hơn ngưỡng thì không thu. |

Với block đầu 60 phút giá 20.000, ngưỡng 10 phút: 9 phút cho 20.000 theo `ALWAYS_CHARGE`, nhưng 0 theo `APPLY_THRESHOLD`; 10 phút theo `APPLY_THRESHOLD` cho 20.000. Đây là khác biệt cần được người sở hữu nghiệp vụ duyệt, không phải miễn phí mặc định.

### Block tiếp theo và phần dư

Khi thời lượng đã đủ block đầu, tính một block đầu; phần còn lại chia cho độ dài block tiếp theo. Các block đầy đủ được tính đủ. Chỉ phần dư dương mới xét ngưỡng: nhỏ hơn ngưỡng bỏ qua; bằng hoặc lớn hơn ngưỡng thêm một block tiếp theo.

| Cấu hình: đầu 60 phút/20.000, tiếp 60 phút/10.000, ngưỡng 10 phút | Số block đầu | Số block tiếp | Subtotal VND |
| --- | ---: | ---: | ---: |
| 2 giờ 9 phút | 1 | 1 | 30.000 |
| 2 giờ 9 phút 59,999999 giây | 1 | 1 | 30.000 |
| 2 giờ 10 phút | 1 | 2 | 40.000 |

Ngưỡng 0: thời lượng đúng 2 giờ vẫn chỉ có một block đầu và một block tiếp; thêm 1 microsecond thì phát sinh một block tiếp nữa. Không cắt phần giây trước khi so sánh.

## DailyEntryCalculator

Một phiên vãng lai liên tục được tính theo các ngày địa phương có **thời gian dương** cần thu. Mỗi dòng ngày đại diện cho một lượt. Chuyển ngày không tạo phiên gửi mới.

| Phiên, timezone bãi `Asia/Ho_Chi_Minh`, giá 5.000/lượt | Ngày tính phí | Subtotal VND |
| --- | --- | ---: |
| 20:00 ngày 03/10 → 08:00 ngày 04/10 | 03/10, 04/10 | 10.000 |
| 20:00 ngày 03/10 → đúng 00:00 ngày 04/10 | 03/10 | 5.000 |
| Tiếp tục tới 00:00:00,000001 ngày 04/10 | 03/10, 04/10 | 10.000 |

Hành vi đúng 00:00 bắt nguồn từ khoảng `[start, end)`: mốc cuối bị loại khỏi khoảng, nên không có thời gian ở ngày kế tiếp. Nếu yêu cầu sản phẩm muốn thu cả ngày ở mốc cuối, đó là thay đổi contract cần được duyệt.

### Nhiều đoạn ngoài gói trong cùng ngày

Khi coverage tạo nhiều đoạn rời nhau trong một ngày, `dailyGrouping` bắt buộc:

| Lựa chọn | Hai đoạn 07:00–08:00, 17:00–19:00 cùng ngày, giá 5.000 |
| --- | ---: |
| `SESSION_DAY`: một nhóm cho toàn phiên/ngày | 5.000 |
| `SEGMENT_DAY`: một nhóm cho mỗi đoạn liên tục/ngày | 10.000 |
| Không cấu hình | Lỗi cần chốt quy tắc |

Các allocation tiếp giáp vẫn là một đoạn liên tục; không lấy số allocation làm số lượt.

### Nhiều phiên bản giá trong một nhóm ngày

Nếu nhóm ngày giao các phiên bản khác nhau, `dailyPriceSelection` bắt buộc. Ví dụ đoạn đầu giá 5.000, đoạn sau giá 7.000:

| Lựa chọn | Hành vi | Subtotal VND |
| --- | --- | ---: |
| `FIRST` | Giá của allocation sớm nhất trong nhóm | 5.000 |
| `HIGHEST` | Giá cao nhất trong nhóm; giá bằng nhau giữ nguồn xuất hiện sớm nhất | 7.000 |
| `EACH_VERSION` | Một lượt cho mỗi phiên bản khác nhau trong nhóm | 12.000 |
| Không cấu hình | Không tự chọn giá | Lỗi cần chốt quy tắc |

`EACH_VERSION` không thu thêm khi cùng một phiên bản xuất hiện nhiều lần trong **cùng nhóm**. `SEGMENT_DAY` tạo nhiều nhóm riêng, nên một phiên bản có thể xuất hiện ở nhiều lượt theo lựa chọn đã cấu hình.

Calculator không chọn phiên bản ngoài gói. Caller phải quyết định dùng phiên bản lúc vào hay phiên bản có hiệu lực theo thời gian. Với xe vãng lai, caller cung cấp phiên bản lúc vào cho toàn phiên, kể cả khi phiên vượt `effectiveTo`; calculator không tự chuyển sang bản mới.

## FeeCalculator, số tiền và snapshot

`FeeCalculator` kiểm tra allocation không chồng nhau và cùng ID biểu phí không có cấu hình mâu thuẫn. Nó chọn calculator bằng `calculationMethod`, cộng subtotal của toàn bộ dòng rồi làm tròn lên bội số 1.000 đúng một lần:

| Subtotal VND | Điều chỉnh VND | Total VND |
| ---: | ---: | ---: |
| 23.400 | 600 | 24.000 |
| 23.000 | 0 | 23.000 |
| 0 | 0 | 0 |
| Hai thành phần 270 và 330 | 400 | 1.000 |

Tiền dùng `long`, khớp `BIGINT` VND. Phép cộng/nhân/làm tròn tràn miền số trả `FEE_AMOUNT_OVERFLOW`. Không dùng float/double. Giờ thực tế và thời lượng thực tế giữ nguyên; bỏ phần dư chỉ ảnh hưởng số block tính phí.

`TariffSnapshot` và `TimeBandSnapshot` sao chép các giá trị đã dùng; sửa DTO nguồn sau phép tính không đổi kết quả. Các danh sách trong output không sửa được. Serialization giữ thời gian theo ISO và Duration chính xác; `TimeRange.isEmpty()` không trở thành dữ liệu snapshot bổ sung.

Input rỗng hoặc toàn khoảng rỗng cho kết quả toán học 0, không tạo dòng thu giả. Cho phép **chốt thanh toán** một phiên thời lượng 0 là quyết định riêng của service; output 0 của calculator không tự cho phép checkout.

## Phần còn cần quyết định ngoài calculator

Các lựa chọn financial trong `FeeRules` vẫn không có mặc định. Ngoài bốn nhóm lựa chọn đã mô tả, cần xác định timezone của timestamp legacy và từng bãi, cách chọn giá ngoài gói, hiệu lực hồi tố khi cấp quyền trong phiên, hiệu lực báo giá, chính sách ra sau thanh toán và thu bổ sung. Calculator không tạo TTL, thời gian grace, giá mới hoặc khoản thu để lấp những điểm chưa chốt.

Unit test tại `SubscriptionCoverageCalculatorTest`, `TimeBlockCalculatorTest`, `DailyEntryCalculatorTest` và `FeeCalculatorTest` kiểm tra các kết quả ở trên, tính phân hoạch không trùng/bỏ sót, thứ tự đầu vào, ngưỡng microsecond, snapshot và tràn số. Các test thuật toán này không chứng minh khóa JPA, unique hay rollback; các cơ chế đó thuộc integration test PostgreSQL của service.
