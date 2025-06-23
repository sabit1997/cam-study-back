// src/main/java/com/camstudy/backend/service/TodoService.java
package com.camstudy.backend.service;

import com.camstudy.backend.entity.TodoItem;
import com.camstudy.backend.entity.Window;
import com.camstudy.backend.repository.TodoItemRepository;
import com.camstudy.backend.repository.WindowRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Sort; // Sort 임포트
import org.springframework.data.jpa.domain.Specification; // Specification 임포트

import jakarta.persistence.criteria.Predicate; // JPA Criteria API Predicate 임포트
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional; // Optional 임포트

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
        
        // [보안] 요청한 사용자가 해당 창의 소유자인지 확인
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

    public List<TodoItem> getTodosByWindow(String userEmail, Long winId, String date, Boolean done, String order) {
        // [보안] 요청한 사용자가 해당 창의 소유자인지 확인
        windowRepository.findById(winId)
            .filter(w -> w.getUserId().equals(userEmail))
            .orElseThrow(() -> new SecurityException("User does not have permission for this window"));
        
        Specification<TodoItem> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("window").get("id"), winId));

            // done 필터링
            if (done != null) {
                predicates.add(cb.equal(root.get("done"), done));
            }

            // date 필터링 (createdAt 필드가 LocalDate 타입이라고 가정)
            if (date != null && !date.isEmpty()) {
                try {
                    LocalDate filterDate = LocalDate.parse(date);
                    predicates.add(cb.equal(root.get("createdAt"), filterDate));
                } catch (DateTimeParseException e) {
                    // 날짜 형식 오류 로깅 (필요시)
                    System.err.println("Invalid date format for filter: " + date);
                }
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        // 정렬
        Sort sort = Sort.by("createdAt"); // 기본 정렬 기준 (createdAt)
        if (order != null && !order.isEmpty()) {
            if (order.equalsIgnoreCase("desc")) {
                sort = Sort.by(Sort.Direction.DESC, "createdAt");
            } else { // "asc" 또는 다른 값인 경우
                sort = Sort.by(Sort.Direction.ASC, "createdAt");
            }
        }
        
        return todoItemRepository.findAll(spec, sort);
    }

    @Transactional
    public TodoItem updateDone(String userEmail, Long winId, Long todoId, boolean done) {
        TodoItem todoItem = findTodoItemForUser(userEmail, winId, todoId);
        todoItem.setDone(done);
        return todoItem; // @Transactional에 의해 자동 저장 (Dirty Checking)
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

    // --- Global API를 위한 서비스 메서드들 (이 부분은 수정 요청이 없었으므로 원본 유지) ---

    public List<TodoItem> getAllTodosByUser(String userEmail, String date, Boolean done, String order) {
        // TODO: query 파라미터를 사용한 동적 쿼리 구현
        // 이 부분도 Specification을 사용하여 구현해야 합니다.
        // 현재는 window_id와 관계없이 userEmail만으로 조회
        Specification<TodoItem> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("window").get("userId"), userEmail)); // window.userId로 필터링

            if (done != null) {
                predicates.add(cb.equal(root.get("done"), done));
            }

            if (date != null && !date.isEmpty()) {
                try {
                    LocalDate filterDate = LocalDate.parse(date);
                    predicates.add(cb.equal(root.get("createdAt"), filterDate));
                } catch (DateTimeParseException e) {
                    System.err.println("Invalid date format for global filter: " + date);
                }
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Sort sort = Sort.by("createdAt");
        if (order != null && !order.isEmpty()) {
            if (order.equalsIgnoreCase("desc")) {
                sort = Sort.by(Sort.Direction.DESC, "createdAt");
            } else {
                sort = Sort.by(Sort.Direction.ASC, "createdAt");
            }
        }

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


    // --- 중복 로직을 위한 private 헬퍼 메서드 ---

    private TodoItem findTodoItemForUser(String userEmail, Long winId, Long todoId) {
        TodoItem todoItem = todoItemRepository.findById(todoId)
                .orElseThrow(() -> new IllegalArgumentException("TodoItem not found"));
        // [보안] 요청한 사용자가 이 투두 항목에 대한 권한이 있는지 확인
        if (!todoItem.getWindow().getId().equals(winId) || !todoItem.getWindow().getUserId().equals(userEmail)) {
            throw new SecurityException("User does not have permission for this todo item");
        }
        return todoItem;
    }

    private TodoItem findTodoItemForUserGlobal(String userEmail, Long todoId) {
        TodoItem todoItem = todoItemRepository.findById(todoId)
                .orElseThrow(() -> new IllegalArgumentException("TodoItem not found"));
        if (!todoItem.getWindow().getUserId().equals(userEmail)) {
            throw new SecurityException("User does not have permission for this todo item");
        }
        return todoItem;
    }
}