package com.camstudy.backend.dto.room;

import com.camstudy.backend.entity.Room;

public record RoomDto(
        String id,
        String name,
        Integer capacity,
        Boolean isPrivate,
        String ownerId
) {
    public static RoomDto from(Room r) {
        return new RoomDto(
                r.getId(), r.getName(), r.getCapacity(),
                r.getIsPrivate(), r.getOwnerId()
        );
    }
}
