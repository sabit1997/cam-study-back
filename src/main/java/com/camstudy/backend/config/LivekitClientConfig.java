package com.camstudy.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.livekit.server.RoomServiceClient;

@Configuration
public class LivekitClientConfig {

    @Bean
    public RoomServiceClient roomServiceClient(
            @Value("${livekit.url}") String url,
            @Value("${livekit.apiKey}") String apiKey,
            @Value("${livekit.apiSecret}") String apiSecret
    ) {
        // LiveKit Cloud: https://<project>.livekit.cloud
        // 자가호스팅: http(s)://<host>:7880
        return RoomServiceClient.createClient(url, apiKey, apiSecret);
    }
}
