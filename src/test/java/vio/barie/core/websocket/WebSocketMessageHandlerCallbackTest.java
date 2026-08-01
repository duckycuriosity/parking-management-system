package vio.barie.core.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebSocketMessageHandlerCallbackTest {

    @Test
    void shouldRequestReconnectOnTransportError() {
        VmsWebSocketReconnectService reconnectService = mock(VmsWebSocketReconnectService.class);
        VmsService vmsService = mock(VmsService.class);
        WebSocketMessageHandler handler = new WebSocketMessageHandler(new ObjectMapper(), reconnectService, vmsService);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("session-1");

        handler.handleTransportError(session, new RuntimeException("socket error"));

        verify(reconnectService).requestReconnect("transport error");
    }
}