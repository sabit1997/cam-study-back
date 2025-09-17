package com.camstudy.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.livekit.server.AccessToken;
import io.livekit.server.VideoGrant;
import io.livekit.server.RoomJoin;
import io.livekit.server.RoomName;
import io.livekit.server.CanPublish;
import io.livekit.server.CanSubscribe;

import java.util.Arrays;
import java.util.List;

@Service
public class LivekitService {

    @Value("${livekit.apiKey}")
    private String apiKey;

    @Value("${livekit.apiSecret}")
    private String apiSecret;

    /**
     * LiveKit 액세스 토큰 발급 (모두 송출 정책)
     */
    public String issueJoinToken(String roomName, String identity, boolean canPublish) {
        // 1) 토큰 생성 + identity 설정
        AccessToken token = new AccessToken(apiKey, apiSecret);
        token.setIdentity(identity);

        // 2) 구체 그랜트들 생성
        List<VideoGrant> grants = Arrays.asList(
            new RoomJoin(true),             // 방 접속 허용
            new RoomName(roomName),         // 대상 방 이름
            new CanPublish(canPublish),     // 송출 권한
            new CanSubscribe(true)          // 구독 권한
        );

        // 3) 그랜트 적용 후 JWT 생성
        token.addGrants(grants);            // Iterable<VideoGrant> 지원
        return token.toJwt();
    }
}
