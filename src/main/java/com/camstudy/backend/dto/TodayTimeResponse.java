package com.camstudy.backend.dto;

public record TodayTimeResponse(
    Long id,
    String userId,
    String date,
    long totalSeconds,
    int dailyHourGoal,
    int pomoCycles
) {}