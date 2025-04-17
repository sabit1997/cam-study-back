package com.camstudy.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "windows")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Window {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    private WindowType type;

    private String url;

    private int zIndex;

    private int x;
    private int y;
    private int width;
    private int height;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
