package vio.barie.core.service;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Nullable;
import jakarta.persistence.Tuple;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import vio.barie.core.common.PaymentCallbackType;
import vio.barie.core.common.PaymentType;
import vio.barie.core.common.TicketStatus;
import vio.barie.core.common.TicketType;
import vio.barie.core.config.exception.ServiceException;
import vio.barie.core.config.payment.MegaPayConfig;
import vio.barie.core.dto.parking.PaymentMessage;
import vio.barie.core.dto.parking.PaymentSuccessCommand;
import vio.barie.core.dto.payment.*;
import vio.barie.core.dto.payment.PaymentListItemResponse;
import vio.barie.core.dto.payment.RevenueSummaryResponse;
import vio.barie.core.entity.ParkingSessionEntity;
import vio.barie.core.entity.PaymentEntity;
import vio.barie.core.entity.TicketEntity;
import vio.barie.core.repository.ParkingSessionRepository;
import vio.barie.core.repository.PaymentRepository;
import vio.barie.core.repository.TicketRepository;
import vio.barie.core.service.mqtt.ParkingMessageService;
import vio.barie.core.service.payment.common.PaymentGateway;
import vio.barie.core.utils.MegaPaySignatureUtil;
import vio.barie.core.utils.Sanitize;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import vio.barie.core.dto.payment.RevenueReportResponse;

import static cn.hutool.core.convert.Convert.hexToBytes;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway; // inject adapter vào đây
    private final StringRedisTemplate stringRedisTemplate;

    @Transactional
    public PaymentEntity createSessionPayment(
            String invoiceNo,
            BigDecimal amount,
            String payType,
            String description,
            Long sessionId,
            String barrierId,
            String plateNumber,
            Integer aiIdentityCustomerId,
            Long laneId,
            LocalDateTime exitTime
    ) {
        // Mark any previous PENDING payment for this session as FAILED (superseded)
        failPendingPaymentsForSession(sessionId);

        PaymentEntity payment = new PaymentEntity();

        payment.setInvoiceNo(invoiceNo);
        payment.setAmount(amount);
        payment.setCurrency("VND");
        payment.setPaymentMethod("QR");
        payment.setPayType(payType);
        payment.setDescription(Sanitize.clean(description));
        payment.setStatus(PaymentType.PENDING.name());
        payment.setCallbackType(PaymentCallbackType.SESSION.name());

        JSONObject context = new JSONObject();
        context.set("sessionId", sessionId);
        context.set("barrierId", barrierId);
        context.set("plateNumber", plateNumber);
        context.set("aiIdentityCustomerId", aiIdentityCustomerId);
        context.set("laneId", laneId);
        context.set("exitTime", exitTime);

        payment.setCallbackContext(context);

        return paymentRepository.save(payment);
    }

    public PaymentEntity createTicketPayment(
            String invoiceNo,
            BigDecimal amount,
            String payType,
            String description,
            Long ticketId
//            Long customerId
    ) {
        // Mark any previous PENDING payment for this ticket as FAILED (superseded)
        failPendingPaymentsForTicket(ticketId);

        PaymentEntity payment = new PaymentEntity();

        payment.setInvoiceNo(invoiceNo);
        payment.setAmount(amount);
        payment.setCurrency("VND");
        payment.setPaymentMethod("QR");
        payment.setPayType(payType);
        payment.setDescription(Sanitize.clean(description));
        payment.setStatus(PaymentType.PENDING.name());
        payment.setCallbackType(PaymentCallbackType.TICKET.name());

        JSONObject context = new JSONObject();
        context.set("ticketId", ticketId);
//        context.set("customerId", customerId);
        payment.setCallbackContext(context);

        return paymentRepository.save(payment);
    }

    public CreatePaymentResponse createPaymentLink(CreatePaymentRequest req){
        CreatePaymentResponse response = paymentGateway.createPaymentLink(req);
        log.debug("CreatePaymentResponse : {}",response);
        return response;
    };

    public void handleCallback(String body){
        paymentGateway.handleCallback(body);
    };

    public PaymentInquiryResponse inquiryByInvoiceNo(String invoiceNo){
        return paymentGateway.inquiryByInvoiceNo(invoiceNo);
    };

    @Transactional(readOnly = true)
    public RevenueSummaryResponse getDailyRevenueSummary(Long parkingLotId, Long buildingId) {
        LocalDateTime from = LocalDateTime.now().toLocalDate().atStartOfDay();
        LocalDateTime to = LocalDateTime.now();

        Tuple result = paymentRepository.sumAndCountByStatus("PAID", from, to, parkingLotId, buildingId);

        BigDecimal revenue = result.get("revenue", BigDecimal.class);
        Number count = (Number) result.get("count");

        return RevenueSummaryResponse.builder()
                .totalRevenue(revenue != null ? revenue : BigDecimal.ZERO)
                .successfulTransactions(count != null ? count.longValue() : 0L)
                .build();
    }

    @Transactional(readOnly = true)
    public Page<PaymentListItemResponse> getPaymentList(Boolean success, int page, int size) {
        String statusFilter = success == null ? "ALL" : (success ? "PAID" : "OTHER");
        Page<Object[]> raw = paymentRepository.findPaymentListRaw(statusFilter, PageRequest.of(page, size));
        return raw.map(row -> PaymentListItemResponse.builder()
                .plateNumber((String) row[0])
                .type((String) row[1])
                .amount(row[2] != null ? new BigDecimal(row[2].toString()) : null)
                .paymentMethod((String) row[3])
                .status((String) row[4])
                .time(row[5] instanceof Timestamp ? ((Timestamp) row[5]).toLocalDateTime() : null)
                .build());
    }

    @Transactional(readOnly = true)
    public RevenueReportResponse getRevenueReport(LocalDate startDate, LocalDate endDate, int page, int size) {
        LocalDateTime from = startDate.atStartOfDay();
        LocalDateTime to = endDate.plusDays(1).atStartOfDay();

        // Redis keys use ISO format "yyyy-MM-dd" — matches Postgres DATE::text for exclude comparison
        DateTimeFormatter redisKeyFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        // Display format "dd/MM/yyyy" matches what TO_CHAR returns from DB
        DateTimeFormatter displayFmt = DateTimeFormatter.ofPattern("dd/MM/yyyy");

        List<LocalDate> allDates = getDateListBetween(from, to); // "yyyy-MM-dd"
        List<LocalDate> excludeDates = new ArrayList<>();
        excludeDates.add(LocalDate.now().plusDays(1));

        List<RevenueReportResponse.DailyDetail> details = new ArrayList<>();

        for (LocalDate dateKey : allDates) {
            if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(dateKey.toString()))) {
                log.debug("FOUND REDIS DATE KEY : {} !",dateKey);
                // StringRedisTemplate stores all values as String — must parse explicitly
                String countStr = (String) stringRedisTemplate.opsForHash().get(dateKey.toString(), "transactionCount");
                String revenueStr = (String) stringRedisTemplate.opsForHash().get(dateKey.toString(), "revenue");
                if (countStr != null && revenueStr != null) {
                    excludeDates.add(dateKey);
                    details.add(RevenueReportResponse.DailyDetail.builder()
                            .date(dateKey.format(displayFmt))
                            .transactionCount(Long.parseLong(countStr))
                            .revenue(new BigDecimal(revenueStr))
                            .build());
                }
            }
        }

        List<Object[]> rows = paymentRepository.findDailyRevenue(from, to, excludeDates);
        for (Object[] row : rows) {
            String displayDate = (String) row[0]; // "dd/MM/yyyy" from TO_CHAR in SQL
            long count = ((Number) row[1]).longValue();
            BigDecimal revenue = new BigDecimal(row[2].toString());

            details.add(RevenueReportResponse.DailyDetail.builder()
                    .date(displayDate)
                    .transactionCount(count)
                    .revenue(revenue)
                    .build());

            // Cache with ISO key so exclude list matches Postgres DATE::text on next call
            String dateKey = LocalDate.parse(displayDate, displayFmt).format(redisKeyFmt);
            stringRedisTemplate.opsForHash().put(dateKey, "transactionCount", String.valueOf(count));
            stringRedisTemplate.opsForHash().put(dateKey, "revenue", revenue.toPlainString());
        }

        // Sort descending (newest first) — same order as the DB query
        details.sort((a, b) -> LocalDate.parse(b.getDate(), displayFmt)
                .compareTo(LocalDate.parse(a.getDate(), displayFmt)));

        BigDecimal totalRevenue = details.stream()
                .map(RevenueReportResponse.DailyDetail::getRevenue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        long totalTransactions = details.stream()
                .mapToLong(RevenueReportResponse.DailyDetail::getTransactionCount)
                .sum();

        long daysInRange = ChronoUnit.DAYS.between(startDate, endDate) + 1;
        BigDecimal avgDailyRevenue = daysInRange > 0
                ? totalRevenue.divide(BigDecimal.valueOf(daysInRange), 0, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        List<RevenueReportResponse.DailyDetail> pageContent = paginate(details, page, size);
        Page<RevenueReportResponse.DailyDetail> pagedDetails = new PageImpl<>(
                pageContent,
                PageRequest.of(page, Math.max(size, 1)),
                details.size()
        );

        return RevenueReportResponse.builder()
                .totalRevenue(totalRevenue)
                .avgDailyRevenue(avgDailyRevenue)
                .totalTransactions(totalTransactions)
                .dailyDetails(pagedDetails)
                .build();
    }

    public static List<LocalDate> getDateListBetween(LocalDateTime from, LocalDateTime to) {
        List<LocalDate> result = new ArrayList<>();

        LocalDateTime current = from;
        while (current.isBefore(to)) {
            result.add(current.toLocalDate());
            current = current.plusDays(1);
        }

        return result;
    }

    public static <T> List<T> paginate(List<T> list, int page, int size) {
        if (list == null || list.isEmpty() || size <= 0 || page < 0) {
            return Collections.emptyList();
        }

        int fromIndex = page * size;

        if (fromIndex >= list.size()) {
            return Collections.emptyList();
        }

        int toIndex = Math.min(fromIndex + size, list.size());

        return list.subList(fromIndex, toIndex);
    }

    @Transactional
    public byte[] exportRevenueReportToExcel(LocalDate startDate, LocalDate endDate) {
        RevenueReportResponse report = getRevenueReport(startDate, endDate, 0, Integer.MAX_VALUE);
        DateTimeFormatter displayFmt = DateTimeFormatter.ofPattern("dd/MM/yyyy");

        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Sheet sheet = wb.createSheet("Báo cáo doanh thu");

            // --- styles ---
            DataFormat dataFormat = wb.createDataFormat();
            short currencyFmt = dataFormat.getFormat("#,##0");

            CellStyle titleStyle = wb.createCellStyle();
            XSSFFont titleFont = wb.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 14);
            titleStyle.setFont(titleFont);
            titleStyle.setAlignment(HorizontalAlignment.CENTER);

            CellStyle boldCenter = wb.createCellStyle();
            XSSFFont boldFont = wb.createFont();
            boldFont.setBold(true);
            boldCenter.setFont(boldFont);
            boldCenter.setAlignment(HorizontalAlignment.CENTER);
            boldCenter.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
            boldCenter.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            setBorder(boldCenter);

            CellStyle boldCurrencyCenter = wb.createCellStyle();
            boldCurrencyCenter.cloneStyleFrom(boldCenter);
            boldCurrencyCenter.setDataFormat(currencyFmt);

            CellStyle cell = wb.createCellStyle();
            setBorder(cell);

            CellStyle cellCurrency = wb.createCellStyle();
            cellCurrency.cloneStyleFrom(cell);
            cellCurrency.setDataFormat(currencyFmt);

            CellStyle footerStyle = wb.createCellStyle();
            footerStyle.cloneStyleFrom(boldCenter);

            CellStyle footerCurrencyStyle = wb.createCellStyle();
            footerCurrencyStyle.cloneStyleFrom(boldCenter);
            footerCurrencyStyle.setDataFormat(currencyFmt);

            // --- Row 0: title ---
            Row r0 = sheet.createRow(0);
            setCell(r0, 0, "BÁO CÁO DOANH THU", titleStyle);
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 2));

            // --- Row 1: date range ---
            Row r1 = sheet.createRow(1);
            String rangeLabel = "Từ ngày: " + startDate.format(displayFmt)
                    + "   Đến ngày: " + endDate.format(displayFmt);
            setCell(r1, 0, rangeLabel, null);
            sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, 2));

            // --- Row 3: summary header ---
            Row r3 = sheet.createRow(3);
            setCell(r3, 0, "Tổng doanh thu (VND)", boldCenter);
            setCell(r3, 1, "Trung bình / ngày (VND)", boldCenter);
            setCell(r3, 2, "Tổng giao dịch", boldCenter);

            // --- Row 4: summary values ---
            Row r4 = sheet.createRow(4);
            setNumCell(r4, 0, report.getTotalRevenue().doubleValue(), boldCurrencyCenter);
            setNumCell(r4, 1, report.getAvgDailyRevenue().doubleValue(), boldCurrencyCenter);
            setNumCell(r4, 2, (double) report.getTotalTransactions(), boldCenter);

            // --- Row 6: table header ---
            Row r6 = sheet.createRow(6);
            setCell(r6, 0, "Ngày", boldCenter);
            setCell(r6, 1, "Số giao dịch", boldCenter);
            setCell(r6, 2, "Doanh thu (VND)", boldCenter);

            // --- Data rows ---
            int rowIdx = 7;
            for (RevenueReportResponse.DailyDetail d : report.getDailyDetails()) {
                Row dr = sheet.createRow(rowIdx++);
                setCell(dr, 0, d.getDate(), cell);
                setNumCell(dr, 1, (double) d.getTransactionCount(), cell);
                setNumCell(dr, 2, d.getRevenue().doubleValue(), cellCurrency);
            }

            // --- Footer row ---
            Row footer = sheet.createRow(rowIdx);
            setCell(footer, 0, "Tổng cộng", footerStyle);
            setNumCell(footer, 1, (double) report.getTotalTransactions(), footerStyle);
            setNumCell(footer, 2, report.getTotalRevenue().doubleValue(), footerCurrencyStyle);

            // --- Column widths ---
            sheet.setColumnWidth(0, 16 * 256);
            sheet.setColumnWidth(1, 20 * 256);
            sheet.setColumnWidth(2, 24 * 256);

            wb.write(out);
            return out.toByteArray();

        } catch (Exception e) {
            throw new RuntimeException("Lỗi khi xuất file Excel: " + e.getMessage(), e);
        }
    }

    private void setCell(Row row, int col, String value, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(value != null ? value : "");
        if (style != null) c.setCellStyle(style);
    }

    private void setNumCell(Row row, int col, double value, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(value);
        if (style != null) c.setCellStyle(style);
    }

    private void setBorder(CellStyle style) {
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
    }

    private void failPendingPaymentsForSession(Long sessionId) {
        if (sessionId == null) return;
        java.util.List<PaymentEntity> stale =
                paymentRepository.findPendingBySessionId(String.valueOf(sessionId));
        if (stale.isEmpty()) return;
        for (PaymentEntity p : stale) {
            p.setStatus(PaymentType.FAILED.name());
            p.setResultMessage("Superseded by new payment attempt");
        }
        paymentRepository.saveAll(stale);
        log.info("Marked {} stale PENDING payment(s) as FAILED for sessionId={}", stale.size(), sessionId);
    }

    private void failPendingPaymentsForTicket(Long ticketId) {
        if (ticketId == null) return;
        java.util.List<PaymentEntity> stale =
                paymentRepository.findPendingByTicketId(String.valueOf(ticketId));
        if (stale.isEmpty()) return;
        for (PaymentEntity p : stale) {
            p.setStatus(PaymentType.FAILED.name());
            p.setResultMessage("Superseded by new payment attempt");
        }
        paymentRepository.saveAll(stale);
        log.info("Marked {} stale PENDING payment(s) as FAILED for ticketId={}", stale.size(), ticketId);
    }
}
