package com.camstudy.backend.controller;

import com.camstudy.backend.dto.WindowPatchDto;
import com.camstudy.backend.entity.Window;
import com.camstudy.backend.service.WindowService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
    public List<Window> getWindows(@AuthenticationPrincipal String userEmail) {
        return windowService.findAllByUser(userEmail);
    }

    // [핵심 수정] PostMapping에서 "/"를 제거하여 "/windows" 경로에 매핑되도록 합니다.
    @PostMapping
    public Window create(@RequestBody Window window, @AuthenticationPrincipal String userEmail) {
        return windowService.create(window, userEmail);
    }

    @PatchMapping("/{id}")
    public Window patchUpdate(@PathVariable Long id,
                              @RequestBody WindowPatchDto dto,
                              @AuthenticationPrincipal String userEmail) {
        return windowService.partialUpdate(id, dto, userEmail);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id, @AuthenticationPrincipal String userEmail) {
        windowService.delete(id, userEmail);
    }
    
    @PostMapping("/{id}/focus")
    public List<Window> focus(@PathVariable Long id, @AuthenticationPrincipal String userEmail) {
        return windowService.focusWindow(id, userEmail);
    }
}