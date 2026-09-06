package com.voicenote.config;

import com.voicenote.web.RealtimeRecordingHandshakeInterceptor;
import com.voicenote.web.RealtimeRecordingWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
public class RealtimeRecordingWebSocketConfiguration implements WebSocketConfigurer {
    private static final int MAX_MESSAGE_BYTES = 64 * 1024;
    private final RealtimeRecordingWebSocketHandler handler;
    private final RealtimeRecordingHandshakeInterceptor interceptor;
    public RealtimeRecordingWebSocketConfiguration(RealtimeRecordingWebSocketHandler handler, RealtimeRecordingHandshakeInterceptor interceptor) {
        this.handler = handler; this.interceptor = interceptor;
    }
    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/api/realtime-recordings/socket").addInterceptors(interceptor);
    }

    @Bean
    ServletServerContainerFactoryBean realtimeRecordingWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        // A 100 ms PCM16 frame at the common 48 kHz browser sample rate is
        // 9,600 bytes, larger than Tomcat's 8 KiB default receive buffer.
        container.setMaxBinaryMessageBufferSize(MAX_MESSAGE_BYTES);
        container.setMaxTextMessageBufferSize(MAX_MESSAGE_BYTES);
        return container;
    }
}
