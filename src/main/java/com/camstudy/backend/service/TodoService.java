package com.camstudy.backend.service;

import com.camstudy.backend.entity.TodoItem;
import com.camstudy.backend.entity.Window;
import com.camstudy.backend.repository.TodoItemRepository;
import com.camstudy.backend.repository.WindowRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TodoService {
    private final WindowRepository windowRepo;
    private final TodoItemRepository todoRepo;

    public TodoService(WindowRepository w, TodoItemRepository t) {
        this.windowRepo = w;
        this.todoRepo = t;
    }

    public TodoItem addTodo(Long windowId, String text) {
        Window win = windowRepo.findById(windowId).orElseThrow();
        TodoItem item = new TodoItem();
        item.setWindow(win);
        item.setText(text);
        return todoRepo.save(item);
    }

    public List<TodoItem> getTodos(Long windowId) {
        return todoRepo.findByWindowId(windowId);
    }

    public TodoItem updateDone(Long todoId, boolean done) {
        TodoItem item = todoRepo.findById(todoId).orElseThrow();
        item.setDone(done);
        return todoRepo.save(item);
    }

    public void deleteTodo(Long todoId) {
        todoRepo.deleteById(todoId);
    }
}
