// src/main/java/com/camstudy/backend/service/TimerService.java
package com.camstudy.backend.service;

import com.camstudy.backend.dto.TodayTimeResponse;
import com.camstudy.backend.dto.TimerAnalyticsResponse;
import com.camstudy.backend.dto.TimerGoalResponse;
import com.camstudy.backend.entity.Timer;
import com.camstudy.backend.entity.User;
import com.camstudy.backend.repository.TimerRepository;
import com.camstudy.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TimerService {

    private final TimerRepository repo;
    private final UserRepository userRepository;

    @Transactional
    public void record(String userId, Instant startAt, Instant endAt) {
        // 기록 로직: 기록 시점에 태국 기준이 아닌, UTC 저장만 합니다.
        // 화면에 자정 분리 등은 조회 로직에서 처리하므로, record는 기존 대로 두셔도 무방합니다.
        ZoneId utc = ZoneOffset.UTC;
        ZonedDateTime zStartUtc = startAt.atZone(utc);
        ZonedDateTime zEndUtc   = endAt.atZone(utc);

        LocalDate currentDate = zStartUtc.toLocalDate();
        ZonedDateTime sliceStart = zStartUtc;

        while (!sliceStart.isAfter(zEndUtc)) {
            ZonedDateTime sliceEnd = sliceStart.toLocalDate()
                                               .atTime(LocalTime.MAX)
                                               .atZone(utc);
            if (sliceEnd.isAfter(zEndUtc)) sliceEnd = zEndUtc;

            long secs = Duration.between(sliceStart, sliceEnd).getSeconds();
            LocalDate finalDate = sliceStart.toLocalDate();
            Timer entry = repo.findByUserIdAndDate(userId, finalDate)
                    .orElseGet(() -> {
                        Timer t = new Timer();
                        t.setUserId(userId);
                        t.setDate(finalDate);
                        return t;
                    });

            entry.setTotalSeconds(entry.getTotalSeconds() + secs);
            repo.save(entry);

            sliceStart = sliceEnd.plusNanos(1);
        }
    }

    public List<Timer> listByMonth(String userId, int year, int month, ZoneId userZone) {
        // 조회 범위를 사용자 로컬 기준으로 계산
        LocalDate fromLocal = LocalDate.of(year, month, 1);
        LocalDate toLocal   = fromLocal.withDayOfMonth(fromLocal.lengthOfMonth());
        // DB 질의는 LocalDate 기준으로 TIMESTAMP WITH TIME ZONE 매핑된 컬럼을 비교합니다.
        return repo.findByUserIdAndDateBetween(userId, fromLocal, toLocal);
    }

    public long getMonthlyTotal(String userId, int year, int month, ZoneId userZone) {
        return listByMonth(userId, year, month, userZone)
            .stream().mapToLong(Timer::getTotalSeconds).sum();
    }

    public TimerAnalyticsResponse getTimerAnalytics(
            String userEmail,
            int year,
            int month,
            ZoneId userZone
    ) {
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        LocalDate todayLocal = LocalDate.now(userZone);
        long dailyGoalSec = user.getDailyGoalHours() * 3600L;

        double achievementRateToday = repo.findByUserIdAndDate(userEmail, todayLocal)
            .map(e -> Math.min(100.0, (e.getTotalSeconds() * 100.0) / dailyGoalSec))
            .orElse(0.0);

        // 월간 비교
        long currentTotal  = getMonthlyTotal(userEmail, year, month, userZone);
        LocalDate prevMonthStart = LocalDate.of(year, month, 1).minusMonths(1);
        long previousTotal = getMonthlyTotal(userEmail, prevMonthStart.getYear(), prevMonthStart.getMonthValue(), userZone);
        long diff = currentTotal - previousTotal;
        Double changeRate = previousTotal == 0 ? null : diff * 100.0 / previousTotal;

        // 요일별 통계
        Map<DayOfWeek, Long> weekdayStats = new EnumMap<>(DayOfWeek.class);
        listByMonth(userEmail, year, month, userZone)
            .forEach(e -> {
                DayOfWeek d = e.getDate().getDayOfWeek();
                weekdayStats.merge(d, e.getTotalSeconds(), Long::sum);
            });
        // best focus day
        Optional<Timer> best = listByMonth(userEmail, year, month, userZone)
            .stream().max(Comparator.comparingLong(Timer::getTotalSeconds));

        TimerAnalyticsResponse.BestFocusDay bestDay = best.map(e ->
            TimerAnalyticsResponse.BestFocusDay.builder()
                .date(e.getDate().toString())
                .totalSeconds(e.getTotalSeconds())
                .dailyHourGoal(user.getDailyGoalHours())
                .build()
        ).orElse(null);

        return TimerAnalyticsResponse.builder()
            .achievementRateToday(achievementRateToday)
            .monthComparison(
                TimerAnalyticsResponse.MonthComparison.builder()
                    .currentMonthTotal(currentTotal)
                    .previousMonthTotal(previousTotal)
                    .difference(diff)
                    .changeRate(changeRate)
                    .build()
            )
            .weekdayStats(weekdayStats)
            .bestFocusDay(bestDay)
            .build();
    }

    public TodayTimeResponse getTodayTime(String userEmail, ZoneId userZone) {
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        LocalDate todayLocal = LocalDate.now(userZone);
        Timer t = repo.findByUserIdAndDate(userEmail, todayLocal)
            .orElseGet(() -> {
                Timer x = new Timer();
                x.setUserId(userEmail);
                x.setDate(todayLocal);
                x.setTotalSeconds(0);
                return x;
            });
        return new TodayTimeResponse(
            t.getId(),
            t.getUserId(),
            t.getDate().toString(),
            t.getTotalSeconds(),
            user.getDailyGoalHours()
        );
    }

    public TimerGoalResponse getTimerGoal(String userEmail) {
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        return new TimerGoalResponse(user.getDailyGoalHours());
    }

    @Transactional
    public TimerGoalResponse updateTimerGoal(String userEmail, int newHour) {
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        user.setDailyGoalHours(newHour);
        return new TimerGoalResponse(user.getDailyGoalHours());
    }

    @Transactional
    public void resetDailyTimer(String userEmail, LocalDate date, ZoneId userZone) {
        // 사용자 로컬 자정 기준 Instant 범위는 조회 단계가 아닌, Timer 자체가 LocalDate key이므로
        // 단순히 date(LocalDate)만 초기화하면 됩니다.
        repo.findByUserIdAndDate(userEmail, date)
            .ifPresent(timer -> {
                timer.setTotalSeconds(0);
                repo.save(timer);
            });
    }
}
