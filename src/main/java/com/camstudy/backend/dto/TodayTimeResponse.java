package com.camstudy.backend.dto;

public record TodayTimeResponse(
    Long id,
    String userId,
    String date,
    long totalSeconds,
    int dailyHourGoal
) {
    // goalInSeconds는 프론트엔드에서 계산 가능하므로 DTO에서 제외하거나,
    // 필요 시 여기에 long goalInSeconds 필드를 추가할 수 있습니다.
}