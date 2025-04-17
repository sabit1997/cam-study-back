package com.camstudy.backend.service;

import com.camstudy.backend.util.SecurityUtil;
import com.camstudy.backend.entity.Window;
import com.camstudy.backend.repository.WindowRepository;
import org.springframework.stereotype.Service;

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

    public Window update(Long id, Window updates) {
        Window window = windowRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Window not found"));
        window.setType(updates.getType());
        window.setUrl(updates.getUrl());
        return windowRepository.save(window);
    }

    public void delete(Long id) {
        windowRepository.deleteById(id);
    }
}
