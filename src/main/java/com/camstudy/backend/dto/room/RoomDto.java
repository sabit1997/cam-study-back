package com.camstudy.backend.dto.room;

import com.camstudy.backend.entity.Room;

public record RoomDto(
        String id,
        String name,
        Integer capacity,
        Boolean isPrivate,
        String ownerId,
        long memberCount          // ⬅️ 현재 활성 인원 수
) {
    public static RoomDto from(Room r) {
        return new RoomDto(
                r.getId(), r.getName(), r.getCapacity(),
                r.getIsPrivate(), r.getOwnerId(),
                0L
        );
    }
    public static RoomDto from(Room r, long memberCount) {
        return new RoomDto(
                r.getId(), r.getName(), r.getCapacity(),
                r.getIsPrivate(), r.getOwnerId(),
                memberCount
        );
    }
}
