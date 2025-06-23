package com.camstudy.backend.dto;

import com.camstudy.backend.entity.TodoItem;
import java.time.Instant;

public record TodoResponse(
    Long id,
    String text,
    boolean done,
    String createdAt
) {
    /**
     * TodoItem 엔티티를 TodoResponse DTO로 변환합니다.
     * @param todoItem 변환할 TodoItem 객체
     * @return 변환된 TodoResponse 객체
     */
    public static TodoResponse from(TodoItem todoItem) {
        // createdAt 필드가 null일 수 있는 경우를 대비한 안전장치
        Instant createdAtInstant = todoItem.getCreatedAt();
        String formattedDate = (createdAtInstant != null) ? createdAtInstant.toString() : null;

        return new TodoResponse(
            todoItem.getId(),
            todoItem.getText(),
            todoItem.isDone(),
            formattedDate
        );
    }
}