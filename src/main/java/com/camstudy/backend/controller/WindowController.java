package com.camstudy.backend.controller;

import com.camstudy.backend.entity.Window;
import com.camstudy.backend.service.WindowService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/windows")
public class WindowController {

    private final WindowService windowService;

    public WindowController(WindowService windowService) {
        this.windowService = windowService;
    }

    @GetMapping
    public List<Window> getWindows() {
        return windowService.findAllByUser();
    }

    @PostMapping
    public Window create(@RequestBody Window window) {
        return windowService.create(window);
    }

    @PutMapping("/{id}")
    public Window update(@PathVariable Long id, @RequestBody Window window) {
        return windowService.update(id, window);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        windowService.delete(id);
    }
}
