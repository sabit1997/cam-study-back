// src/main/java/com/camstudy/backend/entity/TodoItem.java
package com.camstudy.backend.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "todo_items")
public class TodoItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** TODO 반환 시 window 정보는 순환참조 방지를 위해 제외합니다 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "window_id")
    @JsonIgnore
    private Window window;

    private String text;
    private Boolean done = false;
    private LocalDateTime createdAt = LocalDateTime.now();

    // --- getters & setters ---
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Window getWindow() { return window; }
    public void setWindow(Window window) { this.window = window; }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public Boolean getDone() { return done; }
    public void setDone(Boolean done) { this.done = done; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
