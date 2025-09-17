package com.camstudy.backend.controller;

import com.camstudy.backend.entity.Room;
import com.camstudy.backend.service.LivekitAdminService;
import com.camstudy.backend.service.RoomService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/rooms/{roomId}/admin")
@RequiredArgsConstructor
public class RoomAdminController {

    private final RoomService roomService;
    private final LivekitAdminService adminService;

    public record KickReq(String userId) {}
    public record PublishPermissionReq(String userId, boolean canPublish) {}

    /** 참가자 강퇴 (HOST/Admin만 호출하도록 Security에서 권한 체크 추천) */
    @PostMapping("/kick")
    public ResponseEntity<Void> kick(@PathVariable String roomId, @RequestBody KickReq req) {
        Room room = roomService.getOrThrow(roomId);
        // LiveKit에서 실시간 연결 제거
        adminService.kick(room.getId(), req.userId());
        // DB에서도 비활성화 처리(있으면)
        roomService.deactivateMemberIfExists(room, req.userId());
        return ResponseEntity.noContent().build();
    }

    /** 참가자의 publish 권한 on/off */
    @PostMapping("/publish-permission")
    public ResponseEntity<Void> setPublishPermission(
            @PathVariable String roomId,
            @RequestBody PublishPermissionReq req
    ) {
        Room room = roomService.getOrThrow(roomId);
        adminService.setPublishPermission(room.getId(), req.userId(), req.canPublish());
        return ResponseEntity.noContent().build();
    }
}
