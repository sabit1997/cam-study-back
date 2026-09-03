package com.camstudy.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocketMessageBroker
public class WsConfig implements WebSocketMessageBrokerConfigurer {

    /**
     * WebSocket 핸드셰이크에는 CORS 가 적용되지 않는다.
     * 브라우저는 Origin 헤더만 실어 보내고, 검증 책임은 전적으로 서버에 있다.
     * 여기를 "*" 로 열어두면 임의 사이트가 피해자의 쿠키로 STOMP 세션을 열 수 있다.
     *
     * TODO(2-4): SecurityConfig 의 CORS 목록과 함께 app.cors.allowed-origins 로 외부화
     */
    private static final String[] ALLOWED_ORIGINS = {
            "https://oeyo-cam.site",
            "https://www.oeyo-cam.site",
            "http://localhost:3000",
            "http://localhost:3001"
    };

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // 프런트에서 ws(s)://<host>/ws 로 접속
        registry.addEndpoint("/ws").setAllowedOrigins(ALLOWED_ORIGINS);
        // SockJS를 쓸 경우: .withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // 클라이언트가 보낼 prefix
        registry.setApplicationDestinationPrefixes("/app");
        // 브로드캐스트 구독 prefix
        registry.enableSimpleBroker("/topic", "/queue");
    }
}
