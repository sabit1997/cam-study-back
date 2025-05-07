package com.camstudy.backend.controller;

import com.camstudy.backend.entity.Timer;
import com.camstudy.backend.service.TimerService;
import com.camstudy.backend.util.SecurityUtil;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

@RestController
@RequestMapping("/timer")
public class TimerController {
    private final TimerService svc;

    public TimerController(TimerService s) { this.svc = s; }

    @PostMapping
    public void record(@RequestBody RecordRequest req) {
        String userId = SecurityUtil.getCurrentEmail();
        svc.record(userId, Instant.parse(req.getStartAt()), Instant.parse(req.getEndAt()));
    }

    @GetMapping
    public TimerMonthlyResponse list(@RequestParam int year, @RequestParam int month) {
        String userId = SecurityUtil.getCurrentEmail();
        List<Timer> entries = svc.listByMonth(userId, year, month);
        long total = svc.getMonthlyTotal(userId, year, month);
        return new TimerMonthlyResponse(entries, total);
    }

    @GetMapping("/analytics")
    public Map<String, Object> analytics(@RequestParam int year, @RequestParam int month) {
        String userId = SecurityUtil.getCurrentEmail();
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        long dailyGoal = 7200; // 2시간 목표

        Map<String, Object> res = new HashMap<>();
        res.put("achievementRateToday", svc.getAchievementRate(userId, today, dailyGoal));
        res.put("weekdayStats", svc.getWeeklyStats(userId, year, month));
        res.put("monthComparison", svc.getMonthComparison(userId, year, month));
        res.put("bestFocusDay", svc.getBestFocusDay(userId, year, month).orElse(null));
        return res;
    }

    static class RecordRequest {
        private String startAt;
        private String endAt;
        public String getStartAt() { return startAt; }
        public void setStartAt(String startAt) { this.startAt = startAt; }
        public String getEndAt() { return endAt; }
        public void setEndAt(String endAt) { this.endAt = endAt; }
    }

    static class TimerMonthlyResponse {
        private List<Timer> entries;
        private long monthlyTotal;

        public TimerMonthlyResponse(List<Timer> entries, long monthlyTotal) {
            this.entries = entries;
            this.monthlyTotal = monthlyTotal;
        }

        public List<Timer> getEntries() { return entries; }
        public long getMonthlyTotal() { return monthlyTotal; }
    }
}
