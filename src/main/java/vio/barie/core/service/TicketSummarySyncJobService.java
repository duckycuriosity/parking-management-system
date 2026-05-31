package vio.barie.core.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vio.barie.core.repository.TicketRepository;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class TicketSummarySyncJobService implements ApplicationRunner {
    private final TicketRepository ticketRepo;
    private final TicketSummaryCacheService summaryCacheService;

    @Override
    public void run(ApplicationArguments args) {
        log.info("[TicketSummary] Khởi tạo bộ đếm Cache từ Database lần đầu.");
        try {
            syncCache();
            log.info("[TicketSummary] Đồng bộ Cache thành công!");
        } catch (Exception e) {
            log.error("[TicketSummary] Lỗi kết nối Redis lúc khởi động. App vẫn sẽ tiếp tục chạy. Chi tiết: {}", e.getMessage());
        }
    }

    // chạy ngầm mỗi 10 phút một lần để xử lý vé "Sắp hết hạn" và "Hết hạn" theo thời gian thực
    @Scheduled(cron = "0 */10 * * * *")
    public void scheduledSync() {
        log.debug("[TicketSummary] Đồng bộ lại Cache chống trôi số ngầm...");
        syncCache();
    }

    private void syncCache() {
        LocalDateTime in5Days = LocalDateTime.now().plusDays(5);

        long total = ticketRepo.countTotalTickets();
        long active = ticketRepo.countActiveTickets();
        long expired = ticketRepo.countExpiredTickets();
        long expiringSoon = ticketRepo.countExpiringSoonTickets(in5Days);

        summaryCacheService.syncAllCounters(total, active, expired, expiringSoon);
    }
}
