package com.camstudy.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocketMessageBroker
public class WsConfig implements WebSocketMessageBrokerConfigurer {

    private final CorsProperties corsProperties;

    public WsConfig(CorsProperties corsProperties) {
        this.corsProperties = corsProperties;
    }

    /**
     * WebSocket 핸드셰이크에는 CORS 가 적용되지 않는다.
     * 브라우저는 Origin 헤더만 실어 보내고, 검증 책임은 전적으로 서버에 있다.
     * 여기를 "*" 로 열어두면 임의 사이트가 피해자의 쿠키로 STOMP 세션을 열 수 있다.
     *
     * 허용 목록은 SecurityConfig 의 CORS 설정과 같은 프로퍼티를 읽는다.
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // 프런트에서 ws(s)://<host>/ws 로 접속
        registry.addEndpoint("/ws").setAllowedOrigins(corsProperties.allowedOriginsArray());
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
