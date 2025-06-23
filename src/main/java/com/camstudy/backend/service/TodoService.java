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
import jakarta.persistence.criteria.Expression; // Expression 임포트 추가

import java.time.LocalDate;
import java.time.Instant; // Instant 임포트 추가
import java.time.ZoneOffset; // ZoneOffset 임포트 추가 (UTC를 위해)
import java.time.format.DateTimeParseException;
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

            // date 필터링 (createdAt이 Instant 타입임을 고려하여 수정)
            if (date != null && !date.isEmpty()) {
                try {
                    LocalDate filterDate = LocalDate.parse(date);
                    
                    // Instant에서 LocalDate를 추출하여 비교합니다.
                    // UTC 기준으로 Instant를 LocalDate로 변환하여 비교하는 것이 일반적입니다.
                    Expression<LocalDate> createdAtDate = cb.function(
                        "DATE", // SQL DATE 함수 (데이터베이스에 따라 다를 수 있음, MySQL, H2 등)
                        LocalDate.class,
                        root.get("createdAt")
                    );
                    // 또는 Instant를 특정 ZoneOffset으로 변환 후 LocalDate를 얻는 방법
                    // 이 방법은 데이터베이스 함수에 의존하지 않으므로 더 이식성이 높을 수 있습니다.
                    // root.get("createdAt").as(Instant.class)를 직접 사용하기 어렵기 때문에
                    // 일반적으로는 custom Function이나 Expression을 사용하거나, JPQL 쿼리를 직접 작성합니다.
                    // Specification에서는 cb.function()이 가장 직접적인 방법입니다.
                    // 데이터베이스의 DATE 함수가 Instant 타입을 지원해야 합니다.
                    // 만약 데이터베이스 DATE 함수가 Instant를 직접 처리하지 못한다면,
                    // JPA Criteria API의 Path<Instant>를 LocalDate로 변환하는 더 복잡한 방법이 필요합니다.
                    // 대부분의 DB는 타임스탬프에서 날짜를 추출하는 함수를 제공합니다.
                    // 예를 들어, PostgreSQL은 date(timestamp_column)
                    // MySQL은 DATE(datetime_column)

                    // 더 안전하고 일반적인 방법은 다음과 같이 Instant를 Long (epoch milliseconds)으로 변환 후
                    // Java에서 LocalDate로 변환하는 로직을 사용하는 것입니다.
                    // 하지만 Specification 내에서는 복잡하므로, SQL DATE 함수 사용을 가정합니다.
                    
                    // 대안: Instant를 `LocalDate`로 매핑하기 위한 SQL 함수 사용 예시 (데이터베이스 종속적)
                    // H2, MySQL: DATE(column)
                    // PostgreSQL: date(column)
                    // Oracle: TRUNC(column)
                    
                    // 여기서는 'DATE' 함수를 사용하여 Instant에서 날짜 부분만 추출한다고 가정합니다.
                    predicates.add(cb.equal(createdAtDate, filterDate));

                } catch (DateTimeParseException e) {
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

    public List<TodoItem> getAllTodosByUser(String userEmail, String date, Boolean done, String order) {
        Specification<TodoItem> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("window").get("userId"), userEmail));

            if (done != null) {
                predicates.add(cb.equal(root.get("done"), done));
            }

            if (date != null && !date.isEmpty()) {
                try {
                    LocalDate filterDate = LocalDate.parse(date);
                    // Instant에서 LocalDate를 추출하여 비교
                    Expression<LocalDate> createdAtDate = cb.function(
                        "DATE", // SQL DATE 함수
                        LocalDate.class,
                        root.get("createdAt")
                    );
                    predicates.add(cb.equal(createdAtDate, filterDate));
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

    private TodoItem findTodoItemForUser(String userEmail, Long winId, Long todoId) {
        TodoItem todoItem = todoItemRepository.findById(todoId)
                .orElseThrow(() -> new IllegalArgumentException("TodoItem not found"));
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