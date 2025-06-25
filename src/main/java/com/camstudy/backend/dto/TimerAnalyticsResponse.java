// src/main/java/com/camstudy/backend/dto/TimerAnalyticsResponse.java
package com.camstudy.backend.dto;

import java.time.DayOfWeek;
import java.util.Map;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class TimerAnalyticsResponse {
    private double achievementRateToday;
    private MonthComparison monthComparison;
    private Map<DayOfWeek, Long> weekdayStats;
    private BestFocusDay bestFocusDay;

    @Getter
    @Builder
    public static class MonthComparison {
        private long currentMonthTotal;
        private long previousMonthTotal;
        private long difference;
        private Double changeRate; // Use Double for null if previousTotal is 0
    }

    @Getter
    @Builder
    public static class BestFocusDay {
        private String date; // Format as "YYYY-MM-DD"
        private long totalSeconds;
        private int dailyHourGoal;
    }
}