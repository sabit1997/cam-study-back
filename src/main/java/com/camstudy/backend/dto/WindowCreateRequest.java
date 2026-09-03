package com.camstudy.backend.dto;

import com.camstudy.backend.entity.WindowType;

/**
 * 창 생성 요청.
 *
 * 엔티티(Window)를 그대로 @RequestBody 로 받으면 엔티티의 모든 필드가 입력 표면이 된다.
 * 특히 id 를 지정할 수 있게 되면 save() 가 persist 대신 merge 경로로 가서
 * 그 id 의 기존 창을 덮어쓴다(= 타인 창 탈취).
 *
 * 그래서 이 DTO 에는 id, userId, createdAt, todos 가 아예 존재하지 않는다.
 * 방어 코드를 추가하는 것보다 "보낼 수 없게" 만드는 편이 안전하다.
 */
public record WindowCreateRequest(
        WindowType type,
        String[] url,
        String[] urlTitles,
        Integer x,
        Integer y,
        Integer width,
        Integer height,
        Integer zIndex
) { }
