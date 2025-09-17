package com.camstudy.backend.dto.chat;

public record ChatMessage(
        String roomId,
        String userId,
        String content,
        long ts
) { }
