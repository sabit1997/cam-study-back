package com.camstudy.backend.service;

import com.camstudy.backend.dto.TodayTimeResponse;
import com.camstudy.backend.dto.TimerGoalResponse;
import com.camstudy.backend.entity.User;
import com.camstudy.backend.repository.UserRepository;

import com.camstudy.backend.entity.Timer;
import com.camstudy.backend.repository.TimerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Service
public class TimerService {
    
    private final TimerRepository repo; 
    private final UserRepository userRepository;

    public TimerService(TimerRepository repo, UserRepository userRepository) {
        this.repo = repo;
        this.userRepository = userRepository;
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

            LocalDate finalCurrentDate = currentDate;
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
        return listByMonth(userId, year, month).stream().mapToLong(Timer::getTotalSeconds).sum();
    }
    public double getAchievementRate(String userId, LocalDate date, long dailyGoalSeconds) {
        return repo.findByUserIdAndDate(userId, date).map(entry -> Math.min(100.0, (entry.getTotalSeconds() * 100.0) / dailyGoalSeconds)).orElse(0.0);
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
        return listByMonth(userId, year, month).stream().max(Comparator.comparingLong(Timer::getTotalSeconds));
    }


    public TodayTimeResponse getTodayTime(String userEmail) {
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userEmail));
        
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        Timer todayTimer = repo.findByUserIdAndDate(userEmail, today)
                .orElseGet(() -> {
                    Timer newTimer = new Timer();
                    newTimer.setUserId(userEmail);
                    newTimer.setDate(today);
                    newTimer.setTotalSeconds(0);
                    return newTimer;
                });

        return new TodayTimeResponse(
                todayTimer.getId(),
                todayTimer.getUserId(),
                todayTimer.getDate().toString(),
                todayTimer.getTotalSeconds(),
                user.getDailyGoalHours()
        );
    }
    
    public TimerGoalResponse getTimerGoal(String userEmail) {
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userEmail));
        return new TimerGoalResponse(user.getDailyGoalHours());
    }

    @Transactional
    public TimerGoalResponse updateTimerGoal(String userEmail, int newHour) {
      User user = userRepository.findByEmail(userEmail)
          .orElseThrow(() -> new IllegalArgumentException("User not found: ".concat(userEmail)));

      user.setDailyGoalHours(newHour);

      return new TimerGoalResponse(user.getDailyGoalHours());
    }
    
    @Transactional
    public void resetDailyTimer(String userId, LocalDate date) {
        // 해당 날짜의 타이머 기록을 찾아 totalSeconds를 0으로 설정하고 저장합니다.
        repo.findByUserIdAndDate(userId, date).ifPresent(timer -> {
            timer.setTotalSeconds(0);
            repo.save(timer);
        });
        // (선택 사항) 만약 0으로 설정하는 대신 해당 항목을 아예 삭제하고 싶다면 다음 줄을 사용하세요:
        // repo.findByUserIdAndDate(userId, date).ifPresent(repo::delete);
}
}