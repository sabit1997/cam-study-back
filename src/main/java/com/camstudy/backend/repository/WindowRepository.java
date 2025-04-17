package com.camstudy.backend.repository;

import com.camstudy.backend.entity.Window;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WindowRepository extends JpaRepository<Window, Long> {
    List<Window> findByUserId(String userId);
}
