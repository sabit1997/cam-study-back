package com.camstudy.backend.controller;

import com.camstudy.backend.dto.WindowPatchDto;
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

    /** todos 없이 Window 정보만 반환 */
    @GetMapping
    public List<Window> getWindows() {
        return windowService.findAllByUser();
    }

    @PostMapping
    public Window create(@RequestBody Window window) {
        return windowService.create(window);
    }

    @PatchMapping("/{id}")
    public Window patchUpdate(@PathVariable Long id,
                              @RequestBody WindowPatchDto dto) {
        return windowService.partialUpdate(id, dto);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        windowService.delete(id);
    }

    /** 창을 포커스(맨 위)로 올리고 zIndex 재정렬 */
    @PostMapping("/{id}/focus")
    public List<Window> focus(@PathVariable Long id) {
        return windowService.focusWindow(id);
    }
}
