// src/main/java/com/camstudy/backend/service/WindowService.java
package com.camstudy.backend.service;

import com.camstudy.backend.dto.WindowPatchDto;
import com.camstudy.backend.entity.Window;
import com.camstudy.backend.repository.WindowRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects; // Objects.requireNonNull을 위해 추가

@Service
public class WindowService {

    private final WindowRepository windowRepository;

    public WindowService(WindowRepository windowRepository) {
        this.windowRepository = windowRepository;
    }

    // findAllByUser 메서드 수정: userEmail을 받아서 해당 사용자의 윈도우만 조회
    public List<Window> findAllByUser(String userEmail) {
        // userId 필드가 있다면 findByUserId 등을 사용
        // 현재 Window 엔티티에 user_id (String) 필드가 있습니다.
        return windowRepository.findByUserId(userEmail); // UserRepository와 유사하게 userId로 조회하는 메서드 필요
    }

    // create 메서드 수정: Window 생성 시 userEmail을 함께 받아 userId 설정
    public Window create(Window window, String userEmail) {
        // 엔티티에 userId를 설정해야 해당 사용자의 윈도우로 저장됩니다.
        window.setUserId(userEmail); // Window 엔티티에 setUserId 메서드가 있어야 합니다.
        return windowRepository.save(window);
    }

    // partialUpdate 메서드 수정: 해당 사용자의 윈도우만 수정 가능하도록 검증
    public Window partialUpdate(Long id, WindowPatchDto dto, String userEmail) {
        Window window = windowRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Window not found"));

        // 현재 로그인된 사용자의 윈도우가 맞는지 검증 (다른 유저의 윈도우 수정 방지)
        if (!Objects.equals(window.getUserId(), userEmail)) {
            throw new RuntimeException("접근 권한이 없습니다."); // 또는 new AccessDeniedException("...")
        }

        // DTO 필드를 엔티티에 복사
        if (dto.getX() != null) window.setX(dto.getX());
        if (dto.getY() != null) window.setY(dto.getY());
        if (dto.getWidth() != null) window.setWidth(dto.getWidth());
        if (dto.getHeight() != null) window.setHeight(dto.getHeight());
        if (dto.getZIndex() != null) window.setZIndex(dto.getZIndex());
        if (dto.getType() != null) window.setType(dto.getType());
        if (dto.getUrl() != null) window.setUrl(dto.getUrl());

        return windowRepository.save(window);
    }

    // delete 메서드 수정: 해당 사용자의 윈도우만 삭제 가능하도록 검증
    public void delete(Long id, String userEmail) {
        Window window = windowRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Window not found"));

        if (!Objects.equals(window.getUserId(), userEmail)) {
            throw new RuntimeException("삭제 권한이 없습니다.");
        }
        windowRepository.delete(window);
    }

    // focusWindow 메서드 수정: 해당 사용자의 윈도우만 포커스 가능하도록 검증 및 zIndex 재정렬
    public List<Window> focusWindow(Long id, String userEmail) {
        Window focusedWindow = windowRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Window not found"));

        if (!Objects.equals(focusedWindow.getUserId(), userEmail)) {
            throw new RuntimeException("접근 권한이 없습니다.");
        }

        // 현재 사용자의 모든 윈도우를 가져와 zIndex를 재정렬
        List<Window> userWindows = windowRepository.findWindowsByUserIdOrdered(userEmail); // ZIndex 정렬을 위한 메서드 추가 필요

        int maxZIndex = userWindows.stream()
                .mapToInt(Window::getZIndex)
                .max()
                .orElse(0);

        focusedWindow.setZIndex(maxZIndex + 1);
        windowRepository.save(focusedWindow);

        // 업데이트된 모든 윈도우를 다시 가져와 반환
        return windowRepository.findWindowsByUserIdOrdered(userEmail);
    }
}