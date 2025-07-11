// src/main/java/com/camstudy/backend/controller/TodoController.java
package com.camstudy.backend.controller;

import com.camstudy.backend.dto.AddTodoRequest;
import com.camstudy.backend.dto.TodoResponse;
import com.camstudy.backend.dto.UpdateDoneRequest;
import com.camstudy.backend.dto.UpdateTodoRequest;
import com.camstudy.backend.service.TodoService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.ZoneId;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/windows/{winId}/todos")
@RequiredArgsConstructor
public class TodoController {
    
    private final TodoService svc;

    @PostMapping
    public ResponseEntity<TodoResponse> add(@PathVariable Long winId,
                                            @RequestBody AddTodoRequest req,
                                            @AuthenticationPrincipal String userEmail) {
        return ResponseEntity.status(201)
            .body(TodoResponse.from(svc.addTodo(userEmail, winId, req.text())));
    }

    @GetMapping
    public ResponseEntity<List<TodoResponse>> list(
            @PathVariable Long winId,
            @RequestParam(required = false) String date,
            @RequestParam(required = false) Boolean done,
            @RequestParam(required = false) String order,
            @RequestHeader(value = "X-User-Timezone", defaultValue = "UTC") String timezone,
            @AuthenticationPrincipal String userEmail) {

        ZoneId userZone = ZoneId.of(timezone);
        List<TodoResponse> response = svc.getTodosByWindow(
                userEmail, winId, date, userZone, done, order)
            .stream()
            .map(TodoResponse::from)
            .collect(Collectors.toList());
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{todoId}")
    public ResponseEntity<TodoResponse> updateDone(@PathVariable Long winId,
                                                   @PathVariable Long todoId,
                                                   @RequestBody UpdateDoneRequest req,
                                                   @AuthenticationPrincipal String userEmail) {
        return ResponseEntity.ok(
            TodoResponse.from(svc.updateDone(userEmail, winId, todoId, req.done()))
        );
    }

    @DeleteMapping("/{todoId}")
    public ResponseEntity<Void> delete(@PathVariable Long winId,
                                       @PathVariable Long todoId,
                                       @AuthenticationPrincipal String userEmail) {
        svc.deleteTodo(userEmail, winId, todoId);
        return ResponseEntity.noContent().build();
    }
    
    @PatchMapping("/{todoId}/text")
    public ResponseEntity<TodoResponse> updateText(@PathVariable Long winId,
                                                   @PathVariable Long todoId,
                                                   @RequestBody UpdateTodoRequest req,
                                                   @AuthenticationPrincipal String userEmail) {
        return ResponseEntity.ok(
            TodoResponse.from(svc.updateText(userEmail, winId, todoId, req.text()))
        );
    }
}
