package com.camstudy.backend.controller;

import com.camstudy.backend.dto.room.*;
import com.camstudy.backend.entity.Role;
import com.camstudy.backend.service.LivekitService;
import com.camstudy.backend.service.RoomService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/rooms")
@RequiredArgsConstructor
public class RoomController {

    private final RoomService roomService;
    private final LivekitService livekit;

    @PostMapping
    public RoomDto create(@RequestBody CreateRoomReq req) {
        var room = roomService.create(req.name(), req.capacity(), req.password(), req.ownerId());
        return RoomDto.from(room);
    }

    @PostMapping("/{roomId}/join")
    public JoinRes join(@PathVariable String roomId, @RequestBody JoinReq req) {
      var room = roomService.getOrThrow(roomId);
      roomService.verifyPassword(room, req.password());
      roomService.ensureCapacity(room);
      var member = roomService.upsertActiveMember(room, req.userId());

      String token = livekit.issueJoinToken(roomId, req.userId(), true);
      String role = (member.getRole() == Role.HOST) ? "HOST" : "MEMBER";
      return new JoinRes(token, role);
    }
    
    @GetMapping
    public java.util.List<RoomDto> list() {
        return roomService.list().stream().map(RoomDto::from).toList();
    }

    // === (추가) 방 상세 ===
    @GetMapping("/{roomId}")
    public RoomDto get(@PathVariable String roomId) {
        var room = roomService.getOrThrow(roomId);
        return RoomDto.from(room);
    }

    // === (추가) 나가기(활성 플래그 false) ===
    public record LeaveReq(String userId) {}

    @PostMapping("/{roomId}/leave")
    public void leave(@PathVariable String roomId, @RequestBody LeaveReq req) {
        var room = roomService.getOrThrow(roomId);
        roomService.deactivateMemberIfExists(room, req.userId());

        // LiveKit에서도 끊고 싶다면(선택):
        // try { adminService.kick(room.getId(), req.userId()); } catch (Exception ignore) {}
    }

}
