package com.camstudy.backend.config;

import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * 교차 출처 허용 목록.
 *
 * HTTP(CORS)와 WebSocket 핸드셰이크(Origin 검사)가 같은 값을 읽어야 한다.
 * 예전에는 SecurityConfig 에는 목록이, WsConfig 에는 "*" 가 각각 적혀 있어서
 * 한쪽만 고치면 다른 쪽이 열린 채로 남았다.
 *
 * @Validated + @NotEmpty 이므로 값이 비면 부팅이 실패한다.
 * 설정 누락이 런타임이 아니라 배포 시점에 드러나게 하려는 것이다.
 */
@Validated
@ConfigurationProperties(prefix = "app.cors")
public record CorsProperties(
        @NotEmpty List<String> allowedOrigins
) {
    public String[] allowedOriginsArray() {
        return allowedOrigins.toArray(String[]::new);
    }
}
