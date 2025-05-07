package com.camstudy.backend.service;

import com.camstudy.backend.util.SecurityUtil;
import com.camstudy.backend.dto.WindowPatchDto;
import com.camstudy.backend.entity.Window;
import com.camstudy.backend.repository.WindowRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class WindowService {

    private final WindowRepository windowRepository;

    public WindowService(WindowRepository windowRepository) {
        this.windowRepository = windowRepository;
    }

    public List<Window> findAllByUser() {
        String userId = SecurityUtil.getCurrentEmail();
        return windowRepository.findByUserId(userId);
    }

    public Window create(Window window) {
        window.setUserId(SecurityUtil.getCurrentEmail());
        return windowRepository.save(window);
    }

    public void delete(Long id) {
        windowRepository.deleteById(id);
    }

    public Window partialUpdate(Long id, WindowPatchDto dto) {
        Window window = windowRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Window not found"));

        if (dto.getType() != null)    window.setType(dto.getType());
        if (dto.getUrl() != null)     window.setUrl(dto.getUrl());
        if (dto.getX() != null)       window.setX(dto.getX());
        if (dto.getY() != null)       window.setY(dto.getY());
        if (dto.getWidth() != null)   window.setWidth(dto.getWidth());
        if (dto.getHeight() != null)  window.setHeight(dto.getHeight());
        if (dto.getZIndex() != null)  window.setZIndex(dto.getZIndex());

        return windowRepository.save(window);
    }

    @Transactional
    public List<Window> focusWindow(Long id) {
        String userId = SecurityUtil.getCurrentEmail();
        List<Window> list = windowRepository.findByUserId(userId);
        Window target = list.stream()
                .filter(w -> w.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Window not found"));
        list.remove(target);
        list.add(target);
        for (int i = 0; i < list.size(); i++) {
            list.get(i).setZIndex(i + 1);
        }
        return windowRepository.saveAll(list);
    }

}
