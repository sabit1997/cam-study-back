package com.camstudy.backend.controller;

import com.camstudy.backend.service.RoomService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/rooms/{roomId}/admin")
@RequiredArgsConstructor
public class RoomAdminController {

    private final RoomService roomService;

    /** 강퇴 대상. 실행자는 인증 주체에서 가져오므로 바디에 담지 않는다 */
    public record KickReq(String userId) {}

    @PostMapping("/kick")
    public ResponseEntity<Void> kick(@PathVariable String roomId,
                                     @RequestBody KickReq req,
                                     @AuthenticationPrincipal String requesterId) {
        roomService.kick(roomId, requesterId, req.userId());
        return ResponseEntity.noContent().build();
    }
}
