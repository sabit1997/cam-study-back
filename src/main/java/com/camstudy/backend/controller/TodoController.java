// src/main/java/com/camstudy/backend/controller/TodoController.java
package com.camstudy.backend.controller;

import com.camstudy.backend.entity.TodoItem;
import com.camstudy.backend.service.TodoService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/windows/{winId}/todos")
public class TodoController {
    private final TodoService svc;

    public TodoController(TodoService svc) {
        this.svc = svc;
    }

    @PostMapping
    public TodoItem add(@PathVariable Long winId, @RequestBody Map<String, String> body) {
        return svc.addTodo(winId, body.get("text"));
    }

    @GetMapping
    public List<TodoItem> list(@PathVariable Long winId) {
        return svc.getTodos(winId);
    }

    @PatchMapping("/{todoId}")
    public TodoItem toggleDone(@PathVariable Long todoId,
                              @RequestBody Map<String, Boolean> body) {
        return svc.updateDone(todoId, body.get("done"));
    }

    @DeleteMapping("/{todoId}")
    public void delete(@PathVariable Long todoId) {
        svc.deleteTodo(todoId);
    }
}
