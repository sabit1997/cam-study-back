package com.camstudy.backend.controller;

import com.camstudy.backend.dto.chat.ChatMessage;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;

import java.security.Principal;

@Controller
public class ChatController {

    private static final int MAX_CONTENT_LENGTH = 500;

    // 클라 발행:   /app/rooms/{roomId}/chat
    // 클라 구독:   /topic/rooms/{roomId}
    @MessageMapping("/rooms/{roomId}/chat")
    @SendTo("/topic/rooms/{roomId}")
    public ChatMessage chat(@DestinationVariable String roomId,
                            ChatMessage msg,
                            Principal principal) {
        // 핸드셰이크에서 JwtFilter 가 채운 인증 주체. 페이로드의 userId 는 신뢰하지 않는다.
        if (principal == null) {
            throw new AccessDeniedException("unauthenticated chat message");
        }

        String content = (msg == null) ? null : msg.content();
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("chat content is required");
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException(
                    "chat content exceeds " + MAX_CONTENT_LENGTH + " characters");
        }

        // roomId 는 목적지에서, 발신자는 인증 주체에서, 시각은 서버에서 채운다.
        // 클라이언트가 보낸 값을 그대로 중계하면 사칭과 순서 조작이 가능하다.
        return new ChatMessage(roomId, principal.getName(), content, System.currentTimeMillis());
    }
}
