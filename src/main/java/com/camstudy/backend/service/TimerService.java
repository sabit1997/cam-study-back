package com.camstudy.backend.service;

import com.camstudy.backend.entity.Timer;
import com.camstudy.backend.repository.TimerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Service
public class TimerService {
    private final TimerRepository repo;

    public TimerService(TimerRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public void record(String userId, Instant startAt, Instant endAt) {
        ZoneId kst = ZoneId.of("Asia/Seoul");
        ZonedDateTime zStart = startAt.atZone(kst);
        ZonedDateTime zEnd = endAt.atZone(kst);

        LocalDate currentDate = zStart.toLocalDate();
        ZonedDateTime sliceStart = zStart;

        while (!sliceStart.isAfter(zEnd)) {
            ZonedDateTime sliceEnd = ZonedDateTime.of(currentDate, LocalTime.MAX, kst);
            if (sliceEnd.isAfter(zEnd)) sliceEnd = zEnd;

            long secs = Duration.between(sliceStart, sliceEnd).getSeconds();

            LocalDate finalCurrentDate = currentDate; // 🔧 람다 안에서 사용될 변수
            Timer entry = repo.findByUserIdAndDate(userId, finalCurrentDate)
                    .orElseGet(() -> {
                        Timer t = new Timer();
                        t.setUserId(userId);
                        t.setDate(finalCurrentDate);
                        return t;
                    });

            entry.setTotalSeconds(entry.getTotalSeconds() + secs);
            repo.save(entry);

            sliceStart = sliceEnd.plusNanos(1);
            currentDate = sliceStart.toLocalDate();
        }
    }

    public List<Timer> listByMonth(String userId, int year, int month) {
        LocalDate from = LocalDate.of(year, month, 1);
        LocalDate to = from.withDayOfMonth(from.lengthOfMonth());
        return repo.findByUserIdAndDateBetween(userId, from, to);
    }

    public long getMonthlyTotal(String userId, int year, int month) {
        return listByMonth(userId, year, month).stream()
                .mapToLong(Timer::getTotalSeconds)
                .sum();
    }

    public double getAchievementRate(String userId, LocalDate date, long dailyGoalSeconds) {
        return repo.findByUserIdAndDate(userId, date)
                .map(entry -> Math.min(100.0, (entry.getTotalSeconds() * 100.0) / dailyGoalSeconds))
                .orElse(0.0);
    }

    public Map<DayOfWeek, Long> getWeeklyStats(String userId, int year, int month) {
        List<Timer> entries = listByMonth(userId, year, month);
        Map<DayOfWeek, Long> result = new EnumMap<>(DayOfWeek.class);
        for (Timer entry : entries) {
            DayOfWeek day = entry.getDate().getDayOfWeek();
            result.put(day, result.getOrDefault(day, 0L) + entry.getTotalSeconds());
        }
        return result;
    }

    public Map<String, Object> getMonthComparison(String userId, int year, int month) {
        LocalDate currentMonthStart = LocalDate.of(year, month, 1);
        LocalDate prevMonth = currentMonthStart.minusMonths(1);

        long currentTotal = getMonthlyTotal(userId, year, month);
        long previousTotal = getMonthlyTotal(userId, prevMonth.getYear(), prevMonth.getMonthValue());

        Map<String, Object> result = new HashMap<>();
        result.put("currentMonthTotal", currentTotal);
        result.put("previousMonthTotal", previousTotal);
        result.put("difference", currentTotal - previousTotal);
        result.put("changeRate", previousTotal == 0 ? null : ((currentTotal - previousTotal) * 100.0) / previousTotal);
        return result;
    }

    public Optional<Timer> getBestFocusDay(String userId, int year, int month) {
        return listByMonth(userId, year, month).stream()
                .max(Comparator.comparingLong(Timer::getTotalSeconds));
    }
}
