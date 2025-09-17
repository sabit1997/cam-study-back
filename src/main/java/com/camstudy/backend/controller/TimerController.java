// src/main/java/com/camstudy/backend/controller/TimerController.java
package com.camstudy.backend.controller;

import com.camstudy.backend.dto.TimerAnalyticsResponse;
import com.camstudy.backend.dto.TimerGoalRequest;
import com.camstudy.backend.dto.TimerGoalResponse;
import com.camstudy.backend.dto.TodayTimeResponse;
import com.camstudy.backend.entity.Timer;
import com.camstudy.backend.service.TimerService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.*;
import java.util.List;

@RestController
@RequestMapping("/timer")
public class TimerController {
    private final TimerService timerService;
    public TimerController(TimerService timerService) {
        this.timerService = timerService;
    }

    @PostMapping
    public void recordTime(@RequestBody RecordRequest req,
                          @RequestHeader(value = "X-User-Timezone", defaultValue = "UTC") String tz,
                          @AuthenticationPrincipal String userEmail) {
        ZoneId userZone = ZoneId.of(tz);
        timerService.record(userEmail,
            Instant.parse(req.getStartAt()),
            Instant.parse(req.getEndAt()),
            userZone);
    }

    @GetMapping
    public TimerMonthlyResponse listByMonth(
            @RequestParam int year,
            @RequestParam int month,
            @RequestHeader(value = "X-User-Timezone", defaultValue = "UTC") String timezone,
            @AuthenticationPrincipal String userEmail) {

        ZoneId userZone = ZoneId.of(timezone);
        List<Timer> entries = timerService.listByMonth(userEmail, year, month, userZone);
        long total = timerService.getMonthlyTotal(userEmail, year, month, userZone);
        return new TimerMonthlyResponse(entries, total);
    }

    @GetMapping("/analytics")
    public TimerAnalyticsResponse getAnalytics(
            @RequestParam int year,
            @RequestParam int month,
            @RequestHeader(value = "X-User-Timezone", defaultValue = "UTC") String timezone,
            @AuthenticationPrincipal String userEmail) {

        ZoneId userZone = ZoneId.of(timezone);
        return timerService.getTimerAnalytics(userEmail, year, month, userZone);
    }

    @GetMapping("/today")
    public TodayTimeResponse getTodayTime(
            @RequestHeader(value = "X-User-Timezone", defaultValue = "UTC") String timezone,
            @AuthenticationPrincipal String userEmail) {

        ZoneId userZone = ZoneId.of(timezone);
        return timerService.getTodayTime(userEmail, userZone);
    }

    @GetMapping("/goal")
    public TimerGoalResponse getTimerGoal(@AuthenticationPrincipal String userEmail) {
        return timerService.getTimerGoal(userEmail);
    }

    @PostMapping("/goal")
    public TimerGoalResponse updateTimerGoal(@RequestBody TimerGoalRequest req,
                                              @AuthenticationPrincipal String userEmail) {
        return timerService.updateTimerGoal(userEmail, req.hour());
    }

    @DeleteMapping("/day/{date}")
    public void resetDailyTimer(
            @PathVariable String date,
            @RequestHeader(value = "X-User-Timezone", defaultValue = "UTC") String timezone,
            @AuthenticationPrincipal String userEmail) {

        LocalDate localDate = LocalDate.parse(date);
        ZoneId userZone = ZoneId.of(timezone);
        timerService.resetDailyTimer(userEmail, localDate, userZone);
    }

    // --- DTO 클래스들 ---
    static class RecordRequest {
        private String startAt;
        private String endAt;
        public String getStartAt() { return startAt; }
        public String getEndAt() { return endAt; }
    }

    static class TimerMonthlyResponse {
        private final List<Timer> entries;
        private final long monthlyTotal;
        public TimerMonthlyResponse(List<Timer> entries, long monthlyTotal) {
            this.entries = entries;
            this.monthlyTotal = monthlyTotal;
        }
        public List<Timer> getEntries() { return entries; }
        public long getMonthlyTotal() { return monthlyTotal; }
    }
}
