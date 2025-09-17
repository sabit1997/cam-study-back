package com.camstudy.backend.controller;

import com.camstudy.backend.dto.chat.ChatMessage;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;

@Controller
public class ChatController {

    // 클라 발행:   /app/rooms/{roomId}/chat
    // 클라 구독:   /topic/rooms/{roomId}
    @MessageMapping("/rooms/{roomId}/chat")
    @SendTo("/topic/rooms/{roomId}")
    public ChatMessage chat(@DestinationVariable String roomId, ChatMessage msg) {
        // TODO: 길이 제한, 금칙어, XSS 방지(plain text) 등
        return msg;
    }
}
