// src/main/java/com/camstudy/backend/entity/Window.java
package com.camstudy.backend.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

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

    private String[] url;

    private int zIndex;
    private int x;
    private int y;
    private int width;
    private int height;

    @OneToMany(mappedBy = "window", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @JsonIgnore
    private List<TodoItem> todos = new ArrayList<>();

    @Column(name = "user_id")
    private String userId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
