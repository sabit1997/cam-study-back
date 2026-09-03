package com.camstudy.backend.dto.room;

/**
 * 방 입장 요청.
 * 입장하는 사용자는 인증 주체(JWT)에서 가져오므로 요청 바디에 담지 않는다.
 */
public record JoinReq(
        String password
) { }
