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

    /**
     * 사용자 로컬 타임존(userZone)을 기준으로 시간 분할하여 기록합니다.
     */
    @Transactional
    public void record(String userId,
                       Instant startAt,
                       Instant endAt,
                       ZoneId userZone) {

        // Instant → 사용자 로컬 ZonedDateTime
        ZonedDateTime zStart = startAt.atZone(userZone);
        ZonedDateTime zEnd   = endAt.atZone(userZone);

        ZonedDateTime sliceStart = zStart;

        // sliceStart가 zEnd 이전일 때만 반복
        while (sliceStart.isBefore(zEnd)) {
            // sliceEnd: 해당 날짜의 자정까지 (userZone 기준)
            ZonedDateTime sliceEnd = sliceStart
                    .toLocalDate()
                    .atTime(LocalTime.MAX)
                    .atZone(userZone);
            if (sliceEnd.isAfter(zEnd)) {
                sliceEnd = zEnd;
            }

            // Duration 전체 받아서 초 단위만 사용 (나노초 버림)
            Duration d = Duration.between(sliceStart, sliceEnd);
            long secs = d.getSeconds();

            // 로컬 날짜 키
            LocalDate date = sliceStart.toLocalDate();
            Timer entry = repo.findByUserIdAndDate(userId, date)
                    .orElseGet(() -> {
                        Timer t = new Timer();
                        t.setUserId(userId);
                        t.setDate(date);
                        t.setTotalSeconds(0L);
                        return t;
                    });

            entry.setTotalSeconds(entry.getTotalSeconds() + secs);
            repo.save(entry);

            // 다음 조각 시작은 이전 끝으로 설정
            sliceStart = sliceEnd;
        }
    }

    public List<Timer> listByMonth(String userId, int year, int month, ZoneId userZone) {
        LocalDate fromLocal = LocalDate.of(year, month, 1);
        LocalDate toLocal   = fromLocal.withDayOfMonth(fromLocal.lengthOfMonth());
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

        long currentTotal  = getMonthlyTotal(userEmail, year, month, userZone);
        LocalDate prevMonthStart = LocalDate.of(year, month, 1).minusMonths(1);
        long previousTotal = getMonthlyTotal(userEmail, prevMonthStart.getYear(), prevMonthStart.getMonthValue(), userZone);
        long diff = currentTotal - previousTotal;
        Double changeRate = previousTotal == 0 ? null : diff * 100.0 / previousTotal;

        Map<DayOfWeek, Long> weekdayStats = new EnumMap<>(DayOfWeek.class);
        listByMonth(userEmail, year, month, userZone)
            .forEach(e -> weekdayStats.merge(e.getDate().getDayOfWeek(), e.getTotalSeconds(), Long::sum));

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
        repo.findByUserIdAndDate(userEmail, date)
            .ifPresent(timer -> {
                timer.setTotalSeconds(0);
                repo.save(timer);
            });
    }
}
