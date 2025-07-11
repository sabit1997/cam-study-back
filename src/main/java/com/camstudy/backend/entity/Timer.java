package com.camstudy.backend.entity;

import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
@Table(
    name = "timer",
    uniqueConstraints = @UniqueConstraint(columnNames = {"userId", "date"})
)
public class Timer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String userId;

    /**
     * 사용자 로컬 날짜만 저장
     * (서비스 레이어에서 헤더로 받은 ZoneId 기준으로 날짜 계산)
     */
    private LocalDate date;

    private long totalSeconds = 0;

    public void addDuration(long seconds) {
        this.totalSeconds += seconds;
    }

    // === Getter / Setter ===
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }

    public long getTotalSeconds() { return totalSeconds; }
    public void setTotalSeconds(long totalSeconds) { this.totalSeconds = totalSeconds; }
}
