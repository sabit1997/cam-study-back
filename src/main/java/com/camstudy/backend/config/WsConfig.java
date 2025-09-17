package com.camstudy.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocketMessageBroker
public class WsConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // 프런트에서 ws(s)://<host>/ws 로 접속
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*");
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
