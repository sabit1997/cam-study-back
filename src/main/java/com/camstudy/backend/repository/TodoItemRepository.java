// src/main/java/com/camstudy/backend/repository/TodoItemRepository.java
package com.camstudy.backend.repository;

import com.camstudy.backend.entity.TodoItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TodoItemRepository extends JpaRepository<TodoItem, Long> {
    List<TodoItem> findByWindowId(Long windowId);
}
