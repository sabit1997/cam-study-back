package com.camstudy.backend.dto.room;

public record CreateRoomReq(
        String name,
        Integer capacity,
        String password,
        String ownerId
) { }
