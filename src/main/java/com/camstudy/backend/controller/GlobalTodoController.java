// src/main/java/com/camstudy/backend/controller/GlobalTodoController.java
package com.camstudy.backend.controller;

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
@RequestMapping("/api/todos")
@RequiredArgsConstructor
public class GlobalTodoController {

    private final TodoService svc;

    @GetMapping
    public ResponseEntity<List<TodoResponse>> getAllTodos(
            @RequestParam(required = false) String date,
            @RequestParam(required = false) Boolean done,
            @RequestParam(required = false) String order,
            @RequestHeader(value = "X-User-Timezone", defaultValue = "UTC") String timezone,
            @AuthenticationPrincipal String userEmail) {
        
        ZoneId userZone = ZoneId.of(timezone);
        List<TodoResponse> response = svc.getAllTodosByUser(
                userEmail, date, userZone, done, order)
            .stream()
            .map(TodoResponse::from)
            .collect(Collectors.toList());
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{todoId}")
    public ResponseEntity<TodoResponse> updateTodoTextGlobal(
            @PathVariable Long todoId,
            @RequestBody UpdateTodoRequest req,
            @AuthenticationPrincipal String userEmail) {
        return ResponseEntity.ok(
            TodoResponse.from(svc.updateTextGlobal(userEmail, todoId, req.text()))
        );
    }

    @PatchMapping("/{todoId}/done")
    public ResponseEntity<TodoResponse> toggleDoneGlobal(
            @PathVariable Long todoId,
            @RequestBody UpdateDoneRequest req,
            @AuthenticationPrincipal String userEmail) {
        return ResponseEntity.ok(
            TodoResponse.from(svc.updateDoneGlobal(userEmail, todoId, req.done()))
        );
    }

    @DeleteMapping("/{todoId}")
    public ResponseEntity<Void> deleteTodoGlobal(@PathVariable Long todoId,
                                                 @AuthenticationPrincipal String userEmail) {
        svc.deleteTodoGlobal(userEmail, todoId);
        return ResponseEntity.noContent().build();
    }
}
