package vio.barie.core.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vio.barie.core.entity.InternetHealthEventEntity;
import vio.barie.core.service.InternetHealthEventLogService;
@RestController
@RequestMapping("/api/v1/network-events")
@RequiredArgsConstructor
public class InternetHealthEventController {
    private final InternetHealthEventLogService eventLogService;

    @GetMapping
    public ResponseEntity<Page<InternetHealthEventEntity>> getNetworkEvents(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {


        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "changedAt"));
        Page<InternetHealthEventEntity> result = eventLogService.getEvents(pageRequest);

        return ResponseEntity.ok(result);
    }
}
