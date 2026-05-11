package vio.barie.core.config.vms;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import vio.barie.core.websocket.VmsWebSocketReconnectService;

@Slf4j
@Component
@RequiredArgsConstructor
public class VmsWebSocketBootstrap {

    private final VmsWebSocketProperties properties;
    private final VmsWebSocketReconnectService reconnectService;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!properties.isEnabled()) {
            log.info("VMS websocket is disabled");
            return;
        }
        // todo: disable ws
//        reconnectService.requestReconnect("initial startup");
    }
}