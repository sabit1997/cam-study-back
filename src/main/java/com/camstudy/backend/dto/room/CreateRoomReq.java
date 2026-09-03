package com.camstudy.backend.dto.room;

/**
 * 방 생성 요청.
 * 소유자는 인증 주체(JWT)로 결정하므로 요청 바디에 담지 않는다.
 */
public record CreateRoomReq(
        String name,
        Integer capacity,
        String password
) { }
