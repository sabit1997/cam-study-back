package com.camstudy.backend.controller;

import com.camstudy.backend.dto.TimerGoalRequest;
import com.camstudy.backend.dto.TimerGoalResponse;
import com.camstudy.backend.dto.TodayTimeResponse;
import com.camstudy.backend.entity.Timer;
import com.camstudy.backend.service.TimerService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/timer") // 모든 경로에 /api 추가 (일관성을 위해)
public class TimerController {
    private final TimerService timerService;

    public TimerController(TimerService timerService) {
        this.timerService = timerService;
    }

    @PostMapping
    public void recordTime(@RequestBody RecordRequest req, @AuthenticationPrincipal String userEmail) {
        timerService.record(userEmail, Instant.parse(req.getStartAt()), Instant.parse(req.getEndAt()));
    }

    @GetMapping
    public TimerMonthlyResponse listByMonth(@RequestParam int year, @RequestParam int month, @AuthenticationPrincipal String userEmail) {
        List<Timer> entries = timerService.listByMonth(userEmail, year, month);
        long total = timerService.getMonthlyTotal(userEmail, year, month);
        return new TimerMonthlyResponse(entries, total);
    }

    @GetMapping("/analytics")
    public Map<String, Object> getAnalytics(@RequestParam int year, @RequestParam int month, @AuthenticationPrincipal String userEmail) {
        // 이 부분의 로직은 복잡하므로 기존 로직을 유지하거나, 별도의 DTO로 분리할 수 있습니다.
        // ... 기존 analytics 로직 ...
        return new HashMap<>(); // 임시 반환
    }

    // ▼▼▼ [추가된 API] ▼▼▼

    @GetMapping("/today")
    public TodayTimeResponse getTodayTime(@AuthenticationPrincipal String userEmail) {
        return timerService.getTodayTime(userEmail);
    }

    @GetMapping("/goal")
    public TimerGoalResponse getTimerGoal(@AuthenticationPrincipal String userEmail) {
        return timerService.getTimerGoal(userEmail);
    }

    @PostMapping("/goal")
    public TimerGoalResponse updateTimerGoal(@RequestBody TimerGoalRequest req, @AuthenticationPrincipal String userEmail) {
        return timerService.updateTimerGoal(userEmail, req.hour());
    }


    // --- DTO 클래스들 (Controller 내부에 static class로 정의해도 됩니다) ---
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