package com.camstudy.backend.controller;

import com.camstudy.backend.entity.Room;
import com.camstudy.backend.service.RoomService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * LiveKit Webhook 수신: participant_joined / participant_left / room_started / room_finished
 * TODO(보안): 서명 검증(예: Authorization 헤더의 서명 토큰), 허용 IP 화이트리스트 등
 */
@RestController
@RequestMapping("/livekit/webhook")
@RequiredArgsConstructor
public class LivekitWebhookController {

    private final RoomService roomService;

    @PostMapping
    public ResponseEntity<Void> handle(@RequestBody Map<String, Object> payload,
                                       @RequestHeader Map<String, String> headers) {
        // 1) (선택) 헤더로 서명 검증
        // String sig = headers.get("Authorization"); // 예시. 실제 검증 로직 구현 필요.

        // 2) 이벤트/데이터 파싱(최소 처리)
        // LiveKit 표준 페이로드에서 event, room, participant.identity 등을 사용
        String event = (String) payload.get("event");
        Map<String, Object> roomObj = (Map<String, Object>) payload.get("room");
        Map<String, Object> participant = (Map<String, Object>) payload.get("participant");

        if (event == null) return ResponseEntity.ok().build();
        if (roomObj == null) return ResponseEntity.ok().build();

        String roomName = safeStr(roomObj.get("name"));
        if (roomName == null || roomName.isBlank()) return ResponseEntity.ok().build();

        Room room = roomService.getOrThrow(roomName); // roomId = name 가정(우린 id를 roomName으로 사용중)

        switch (event) {
            case "participant_joined" -> {
                String identity = safeStr(participant != null ? participant.get("identity") : null);
                if (identity != null) {
                    // 멤버 활성화(없으면 추가), 역할은 MEMBER 유지
                    roomService.upsertActiveMember(room, identity);
                }
            }
            case "participant_left" -> {
                String identity = safeStr(participant != null ? participant.get("identity") : null);
                if (identity != null) {
                    roomService.deactivateMemberIfExists(room, identity);
                }
            }
            case "room_finished" -> {
                // 방 정리 로직이 필요하면 여기에(예: 모든 멤버 active=false)
                // roomService.deactivateAll(room);
            }
            default -> {
                // 다른 이벤트들은 필요 시 확장
            }
        }
        return ResponseEntity.ok().build();
    }

    private static String safeStr(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
