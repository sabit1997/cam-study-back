package com.camstudy.backend.controller;

import com.camstudy.backend.entity.Room;
import com.camstudy.backend.service.RoomService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/rooms/{roomId}/admin")
@RequiredArgsConstructor
public class RoomAdminController {

    private final RoomService roomService;

    public record KickReq(String userId) {}

    @PostMapping("/kick")
    public ResponseEntity<Void> kick(@PathVariable String roomId, @RequestBody KickReq req) {
        Room room = roomService.getOrThrow(roomId);
        roomService.deactivateMemberIfExists(room, req.userId());
        return ResponseEntity.noContent().build();
    }
}
