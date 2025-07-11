// src/main/java/com/camstudy/backend/service/TodoService.java
package com.camstudy.backend.service;

import com.camstudy.backend.entity.TodoItem;
import com.camstudy.backend.entity.Window;
import com.camstudy.backend.repository.TodoItemRepository;
import com.camstudy.backend.repository.WindowRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import jakarta.persistence.criteria.Predicate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TodoService {

    private final TodoItemRepository todoItemRepository;
    private final WindowRepository windowRepository;

    @Transactional
    public TodoItem addTodo(String userEmail, Long winId, String text) {
        Window window = windowRepository.findById(winId)
                .orElseThrow(() -> new IllegalArgumentException("Window not found"));
        if (!window.getUserId().equals(userEmail)) {
            throw new SecurityException("User does not have permission for this window");
        }
        TodoItem newTodo = TodoItem.builder()
                .window(window)
                .text(text)
                .done(false)
                .build();
        return todoItemRepository.save(newTodo);
    }

    public List<TodoItem> getTodosByWindow(
            String userEmail,
            Long winId,
            String date,         // "YYYY-MM-DD" or null
            ZoneId userZone,     // 추가된 파라미터
            Boolean done,
            String order
    ) {
        windowRepository.findById(winId)
            .filter(w -> w.getUserId().equals(userEmail))
            .orElseThrow(() -> new SecurityException("Permission denied"));

        Specification<TodoItem> spec = (root, query, cb) -> {
            List<Predicate> preds = new ArrayList<>();
            preds.add(cb.equal(root.get("window").get("id"), winId));
            if (done != null) {
                preds.add(cb.equal(root.get("done"), done));
            }
            if (date != null && !date.isBlank()) {
                LocalDate localDate = LocalDate.parse(date);
                Instant start = localDate.atStartOfDay(userZone).toInstant();
                Instant end   = localDate.plusDays(1).atStartOfDay(userZone).toInstant();
                preds.add(cb.between(root.get("createdAt"), start, end));
            }
            return cb.and(preds.toArray(new Predicate[0]));
        };

        Sort sort = "desc".equalsIgnoreCase(order)
            ? Sort.by(Sort.Direction.DESC, "createdAt")
            : Sort.by(Sort.Direction.ASC,  "createdAt");

        return todoItemRepository.findAll(spec, sort);
    }

    @Transactional
    public TodoItem updateDone(String userEmail, Long winId, Long todoId, boolean done) {
        TodoItem todoItem = findTodoItemForUser(userEmail, winId, todoId);
        todoItem.setDone(done);
        return todoItem;
    }

    @Transactional
    public void deleteTodo(String userEmail, Long winId, Long todoId) {
        TodoItem todoItem = findTodoItemForUser(userEmail, winId, todoId);
        todoItemRepository.delete(todoItem);
    }

    @Transactional
    public TodoItem updateText(String userEmail, Long winId, Long todoId, String text) {
        TodoItem todoItem = findTodoItemForUser(userEmail, winId, todoId);
        todoItem.setText(text);
        return todoItem;
    }

    public List<TodoItem> getAllTodosByUser(
            String userEmail,
            String date,
            ZoneId userZone,    // 추가된 파라미터
            Boolean done,
            String order
    ) {
        Specification<TodoItem> spec = (root, query, cb) -> {
            List<Predicate> preds = new ArrayList<>();
            preds.add(cb.equal(root.get("window").get("userId"), userEmail));
            if (done != null) {
                preds.add(cb.equal(root.get("done"), done));
            }
            if (date != null && !date.isBlank()) {
                LocalDate localDate = LocalDate.parse(date);
                Instant start = localDate.atStartOfDay(userZone).toInstant();
                Instant end   = localDate.plusDays(1).atStartOfDay(userZone).toInstant();
                preds.add(cb.between(root.get("createdAt"), start, end));
            }
            return cb.and(preds.toArray(new Predicate[0]));
        };

        Sort sort = "desc".equalsIgnoreCase(order)
            ? Sort.by(Sort.Direction.DESC, "createdAt")
            : Sort.by(Sort.Direction.ASC,  "createdAt");

        return todoItemRepository.findAll(spec, sort);
    }

    @Transactional
    public TodoItem updateTextGlobal(String userEmail, Long todoId, String text) {
        TodoItem todoItem = findTodoItemForUserGlobal(userEmail, todoId);
        todoItem.setText(text);
        return todoItem;
    }

    @Transactional
    public TodoItem updateDoneGlobal(String userEmail, Long todoId, boolean done) {
        TodoItem todoItem = findTodoItemForUserGlobal(userEmail, todoId);
        todoItem.setDone(done);
        return todoItem;
    }

    @Transactional
    public void deleteTodoGlobal(String userEmail, Long todoId) {
        TodoItem todoItem = findTodoItemForUserGlobal(userEmail, todoId);
        todoItemRepository.delete(todoItem);
    }

    // --- 헬퍼 메서드 ---
    private TodoItem findTodoItemForUser(String userEmail, Long winId, Long todoId) {
        TodoItem todoItem = todoItemRepository.findById(todoId)
                .orElseThrow(() -> new IllegalArgumentException("TodoItem not found"));
        if (!todoItem.getWindow().getId().equals(winId)
         || !todoItem.getWindow().getUserId().equals(userEmail)) {
            throw new SecurityException("Permission denied");
        }
        return todoItem;
    }

    private TodoItem findTodoItemForUserGlobal(String userEmail, Long todoId) {
        TodoItem todoItem = todoItemRepository.findById(todoId)
                .orElseThrow(() -> new IllegalArgumentException("TodoItem not found"));
        if (!todoItem.getWindow().getUserId().equals(userEmail)) {
            throw new SecurityException("Permission denied");
        }
        return todoItem;
    }
}
