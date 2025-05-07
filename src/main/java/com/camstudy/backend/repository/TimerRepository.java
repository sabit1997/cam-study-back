package com.camstudy.backend.repository;

import com.camstudy.backend.entity.Timer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface TimerRepository extends JpaRepository<Timer, Long> {
    Optional<Timer> findByUserIdAndDate(String userId, LocalDate date);
    List<Timer> findByUserIdAndDateBetween(String userId, LocalDate start, LocalDate end);
}