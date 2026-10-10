# Thanh toán kỳ thuê bao

## API frontend

`POST /api/vehicle-subscriptions/{subscriptionId}/payment-link` không nhận body và không nhận giá tiền từ frontend. Backend dùng transaction ngắn khóa xe rồi khóa kỳ thuê bao, kiểm tra trạng thái chờ thanh toán và hạn thanh toán, lưu payment/invoice cùng giá `vehicle_subscription.price_vnd` đã chốt và commit. Sau commit mới gọi gateway bằng đúng invoice đã lưu, rồi dùng một transaction ngắn khác để lưu kết quả QR. Không giữ khóa DB trong lúc chờ gateway. Phải commit đăng ký trước khi gọi API này; các bước lưu hồ sơ và tạo QR là hai hành động riêng.

API trả `Result<CreatePaymentResponse>` như API thanh toán hiện có. Frontend hiển thị `paymentLink`/`qrCode` và tra cứu kỳ thuê bao để biết `status`; tạo QR thành công chưa đồng nghĩa đã thanh toán. Khi bấm lại trong thời hạn QR, backend trả cùng invoice/link thay vì tạo một invoice mới. Thời hạn tái sử dụng không vượt quá `payment_deadline_at` của kỳ.

Quyền của API này sử dụng cùng công tắc `app.vehicle-subscription.auth-enabled` với module thuê bao. Công tắc đó không bỏ xác minh webhook/API key/chữ ký của gateway.

Không có API frontend truyền `paid=true`, `paidAt`, `amount` hay gọi `confirm-payment`. Không có luồng xác nhận tiền mặt hoặc kích hoạt miễn phí trong thay đổi này.

## Dữ liệu và đường xác nhận

Payment intent lưu `payment.callback_type = SUBSCRIPTION` và `callback_context.subscriptionId`. Context lưu thêm link, mã QR và thời hạn tái sử dụng; không lưu API key hoặc khóa ký. Mỗi lần tạo intent dùng invoice chứa UUID chuyển sang hệ thập phân, giữ tiền tố và quy tắc nhận diện tiền tố + chữ số hiện có.

Các gateway xử lý `SUBSCRIPTION` trước mọi đoạn cập nhật `PAID`, kích hoạt `ticket` hay gửi MQTT của luồng cũ. Gateway chỉ tạo `VerifiedSubscriptionPayment`, gọi `VehicleSubscriptionService.confirmVerifiedPayment`; service lõi khóa **vehicle → vehicle_subscription → payment** và ghi receipt, kết quả payment, quyền lợi trong cùng transaction.

`source` và `reference` định danh bằng chứng gateway. Service lõi xử lý callback lặp lại, khoản tiền khác giá chốt, giao dịch đến sau hạn, thời điểm trả tiền không rõ, kỳ đã được trả bằng giao dịch khác. Gateway không ghi đè mã tham chiếu đã áp dụng khi nhận callback lặp.

Hết thời hạn QR trên giao diện không có nghĩa một chuyển khoản SePay đã bị ngân hàng ngăn chặn. Intent cũ được giữ nguyên để giao dịch đến muộn vẫn được ghi nhận và phân loại bởi service lõi.

## Trạng thái tạo QR và thử lại

`callback_context.linkState` chỉ mô tả quá trình tạo QR, độc lập với `payment.status` mô tả kết quả tiền. Không lưu khóa API, chữ ký hoặc merchantToken vào context.

| linkState | Hành vi |
| --- | --- |
| CREATING | Invoice và đường callback đã commit; một request đang tạo QR. Request cạnh tranh trả `SUBSCRIPTION_PAYMENT_LINK_IN_PROGRESS`, không tạo thêm intent. |
| READY | Có QR/link hợp lệ; tái sử dụng trong `linkExpiresAt`. Khi QR đã hết hạn được tạo intent mới, giữ intent cũ cho chuyển khoản đến muộn. |
| UNKNOWN | Timeout, mất response, response sai invoice/amount hoặc không thể kiểm tra. Giữ payment PENDING và invoice hiện có, trả `SUBSCRIPTION_PAYMENT_LINK_UNKNOWN`; không tự thử lại hoặc tạo invoice mới. |
| OBSOLETE | Gateway đã tạo QR cho intent mới nhưng đơn đã được intent cũ thanh toán hoặc đã hết hạn trong lúc IO. Không trả QR; giữ intent để nhận chuyển khoản đến muộn. |
| REJECTED | Lỗi điều kiện cục bộ trước IO, hoặc adapter chứng minh được gateway từ chối mà chưa tạo invoice. Payment chuyển FAILED; request tiếp theo có thể tạo attempt mới cho cùng đơn. |

Callback đến sớm vẫn tìm được intent. Khi kết quả QR quay về, backend khóa lại **vehicle → vehicle_subscription → payment**, đọc trạng thái mới nhất của đơn và payment; nếu đã PAID hoặc REVIEW_REQUIRED thì trả `SUBSCRIPTION_PAYMENT_ALREADY_PROCESSED` và không ghi đè trạng thái, mã kết quả hay bằng chứng thanh toán. Frontend tải lại đơn/inquiry để hiển thị kết quả đã commit.

Nếu tiến trình chết sau commit CREATING, lần gọi tiếp theo vẫn không tạo invoice thứ hai. Nếu chết sau gateway nhưng trước khi lưu kết quả, không coi việc chưa có link là bằng chứng gateway thất bại. Mã non-success MegaPay hiện không được coi là bằng chứng invoice chưa tạo. Hiện chưa có contract provider đủ để tự phục hồi QR hoặc chứng minh invoice chưa được tạo; cần đối soát invoice hiện có trước khi cho phép phát hành attempt khác. Inquiry thuê bao vẫn là đọc kết quả đã xác minh nội bộ, không được dùng để suy đoán an toàn retry tạo QR.

## SePay

1. Endpoint webhook hiện có tiếp tục yêu cầu `Authorization: Apikey ...`.
2. Với kỳ thuê bao, payload chỉ là thông báo. Backend gọi API SePay bằng Bearer token, đối chiếu mã invoice (hoặc nội dung giao dịch nếu `code` rỗng), tài khoản nhận tiền; xác nhận tiền vào, không có tiền ra, số tiền thực từ API khớp thông báo. Webhook dùng ID số nhưng API v2 dùng UUID. Khi hai ID khác nhau, adapter chỉ chấp nhận ánh xạ nếu **mã tham chiếu ngân hàng + tài khoản nhận + số tiền + invoice** cùng khớp; receipt dùng UUID từ API làm định danh chuẩn, giữ ID webhook trong metadata. Không đoán hoặc ép ID số thành UUID.
3. Số tiền thực được chuyển sang service lõi; nếu khác giá chốt, service lõi lưu ngoại lệ để kiểm tra, không thay thế nó bằng giá kỳ thuê bao.
4. Backend yêu cầu `timestamp_format=iso8601` để lấy ngày giờ kèm offset. Chỉ giá trị đọc được bằng `OffsetDateTime` được dùng làm `paidAt`. Payload webhook và thời điểm máy chủ nhận callback không được dùng thay ngày giờ giao dịch thực.

API v2 mô tả tùy chọn này tại [SePay API v2: danh sách giao dịch](https://developer.sepay.vn/vi/sepay-api/v2/giao-dich/danh-sach). Quy tắc ánh xạ ID được mô tả tại [Nâng cấp từ v1](https://developer.sepay.vn/vi/sepay-api/v2/nang-cap-tu-v1); cấu trúc webhook tại [Tích hợp webhook](https://developer.sepay.vn/vi/sepay-webhooks/tich-hop-webhook).

Query của module thuê bao được tách riêng, không thêm tham số v2 vào inquiry của luồng ticket/session cũ. Mặc định dùng endpoint `sepay.api-base-url + /transactions` (dev/prod hiện trỏ v2). Có thể cấu hình riêng full URL `sepay.subscription-transactions-url` để đổi endpoint kiểm chứng thuê bao mà không đổi inquiry cũ. Wrapper đọc được cả `data` của v2 và `transactions` của v1. Nếu API trả ngày giờ không có offset, receipt được giữ để đối soát; không suy đoán múi giờ và không tự cấp quyền dựa trên giờ máy chủ.

## MegaPay

Chỉ callback có chữ ký hợp lệ và `resultCd = 00_000` được gửi sang service lõi. Callback thất bại/đang chờ không cấp quyền. Do chữ ký hiện có chứa `merTrxId` nhưng không chứa `invoiceNo`, adapter yêu cầu `merTrxId` khớp invoice nội bộ, đồng thời kiểm tra merchant và mã giao dịch. Chữ ký cũng không chứa `currency`; module chỉ tạo intent VND nên callback khai báo tiền tệ khác VND bị từ chối trước settlement, không lưu khai báo chưa được xác minh đó làm dữ kiện tài chính và không đổi kỳ thuê bao thành ngoại lệ do một trường bị sửa ngoài chữ ký. Hỗ trợ tiền tệ khác đòi hỏi nguồn inquiry có xác thực xác nhận tiền tệ thực.

Hiện chưa có hợp đồng định dạng/múi giờ được xác nhận cho `trxDt`/`trxTm` hoặc `transDt`/`transTm`, và các trường này không nằm trong chữ ký đang được dùng. Vì vậy adapter chưa coi chúng là ngày giờ trả tiền đáng tin; `paidAt` của bằng chứng MegaPay là `null`, chuyển sang quy trình đối soát của service lõi. **MegaPay chưa tự động cấp quyền thuê bao trong cấu hình này**; cần xác nhận định dạng thời gian và nguồn inquiry có xác thực với nhà cung cấp, bổ sung mapper cùng kiểm thử trước khi bật cấp quyền tự động.

Tra cứu giao dịch MegaPay không ghi `payment.status = PAID` riêng cho intent thuê bao. Việc ghi nhận tiền và cấp quyền phải đi qua cùng đường settlement. Payment intent chỉ có `PAID` khi quyền lợi được cấp; giao dịch cần đối soát giữ `REVIEW_REQUIRED` để báo cáo doanh thu cũ không cộng giá báo trước như số tiền thực đã nhận. Các receipt giữ số tiền thực của từng giao dịch, kể cả giao dịch thứ hai cho một kỳ đã thanh toán.

## Tra cứu kết quả và đối soát trên frontend

Với cả hai gateway, inquiry invoice của intent `SUBSCRIPTION` đọc kết quả nội bộ, không dùng trạng thái thành công chưa xử lý của nhà cung cấp để trả `PAID`. Nó không gọi gateway và không ghi dữ liệu. Các trường bổ sung `callbackType`, `subscriptionId`, `resultCode`, `resultMessage` giúp frontend nối invoice tới kỳ và hiển thị ngoại lệ. `amount` là giá báo, `metadata` không được trả cho inquiry thuê bao; không lộ context/link/QR hoặc payload nhà cung cấp qua API này. Luồng inquiry ticket/session cũ tiếp tục hoạt động như trước.

Sau QR, frontend có thể poll chi tiết kỳ hoặc inquiry. `PENDING` nghĩa là chưa có kết quả xác minh đã commit; `REVIEW_REQUIRED` nghĩa là có tiền được ghi nhận nhưng chưa đủ điều kiện cấp kỳ; `PAID` nghĩa là intent này đã cấp kỳ thành công. Đây là trạng thái **của intent**, không thay lịch sử từng khoản tiền. Ví dụ intent đã `PAID`, sau đó khách chuyển thêm lần hai: intent giữ `PAID`, kỳ giữ `paidReceiptId` đầu tiên, khoản thứ hai nằm trong receipt `REVIEW_REQUIRED` với `SECOND_SETTLEMENT`.

Đọc `GET /api/vehicle-subscriptions/{subscriptionId}/receipts` để xem số tiền thực của từng khoản, `outcome`, `reasonCode`, `paidAt` và `receivedAt`. API dùng quyền xem và công tắc xác thực của module thuê bao. Chưa có API duyệt đối soát, hoàn tiền hoặc cấp quyền từ thao tác frontend.

## Kiểm thử

Các test gateway/link độc lập dùng mock service/repository và HTTP server giả. Chúng kiểm tra sai chữ ký, sửa invoice/amount, giao dịch chưa được xác minh, khác tài khoản nhận, callback thất bại/đang chờ, số tiền thực, timestamp có/không offset, callback lặp, tái sử dụng QR, giá đã chốt và invoice trùng. Không gọi gateway thật hoặc sử dụng thông tin kết nối thanh toán đang được cấu hình trong ứng dụng.

Test unit không chứng minh tính nguyên tử/khóa của PostgreSQL; phần đó cần chạy kiểm thử tích hợp DB riêng.

