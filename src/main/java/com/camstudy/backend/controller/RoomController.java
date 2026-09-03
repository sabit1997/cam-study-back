package com.camstudy.backend.controller;

import com.camstudy.backend.dto.room.CreateRoomReq;
import com.camstudy.backend.dto.room.JoinReq;
import com.camstudy.backend.dto.room.JoinRes;
import com.camstudy.backend.dto.room.RoomDto;
import com.camstudy.backend.entity.Role;
import com.camstudy.backend.entity.Room;
import com.camstudy.backend.service.RoomService;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/rooms")
@RequiredArgsConstructor
public class RoomController {

    private final RoomService roomService;

    /** 방 생성 (응답에 memberCount=0 포함). 소유자는 요청자로 고정된다 */
    @PostMapping
    public RoomDto create(@RequestBody CreateRoomReq req,
                          @AuthenticationPrincipal String userId) {
        var room = roomService.create(req.name(), req.capacity(), req.password(), userId);
        return RoomDto.from(room, 0L);
    }

    /** 방 목록 (각 방의 현재 활성 인원 수 포함) */
    @GetMapping
    public Page<RoomDto> list(
      @PageableDefault(size = 9, sort= "id", direction = Sort.Direction.DESC)
      Pageable pageable
    ) {
        Page<Room> roomPage = roomService.list(pageable);

        List<String> ids = roomPage.getContent().stream()
                                    .map(r -> r.getId())
                                    .toList();

        Map<String, Long> counts = roomService.activeCounts(ids);

        return roomPage.map(room ->
            RoomDto.from(room, counts.getOrDefault(room.getId(), 0L))
        );
    }

    /** 방 상세 (현재 활성 인원 수 포함) */
    @GetMapping("/{roomId}")
    public RoomDto get(@PathVariable String roomId) {
        var room = roomService.getOrThrow(roomId);
        long active = roomService.activeCount(room);
        return RoomDto.from(room, active);
    }

    /** 방 입장. 입장자는 인증 주체로 결정된다 */
    @PostMapping("/{roomId}/join")
    public JoinRes join(@PathVariable String roomId,
                        @RequestBody JoinReq req,
                        @AuthenticationPrincipal String userId) {
        var member = roomService.join(roomId, userId, req.password());

        String role = (member.getRole() == Role.HOST) ? "HOST" : "MEMBER";
        return new JoinRes(role);
    }

    /** 나가기(활성 off). 본인만 나갈 수 있다 */
    @PostMapping("/{roomId}/leave")
    public void leave(@PathVariable String roomId,
                      @AuthenticationPrincipal String userId) {
        roomService.leave(roomId, userId);
    }
}
