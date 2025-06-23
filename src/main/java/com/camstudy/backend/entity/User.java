package com.camstudy.backend.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "users")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String email;

    private String password; // 암호화된 비밀번호

    private String username; // username 추가

    // [추가] 사용자의 일일 목표 시간을 저장하는 컬럼 (기본값: 2)
    @Column(name = "daily_goal_hours", nullable = false)
    private int dailyGoalHours = 2;
}