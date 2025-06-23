package com.camstudy.backend.repository;

import com.camstudy.backend.entity.TodoItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor; // 이 임포트 추가
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TodoItemRepository extends JpaRepository<TodoItem, Long>, JpaSpecificationExecutor<TodoItem> { // JpaSpecificationExecutor 추가
    
    // 기존 메서드 유지 (필요에 따라)
    List<TodoItem> findByWindowId(Long windowId);
    
    // 사용자의 모든 투두 항목을 가져오는 쿼리 (global 필터링에 사용될 수 있음)
    // @Query("SELECT t FROM TodoItem t JOIN t.window w WHERE w.userId = :userEmail")
    // List<TodoItem> findAllByUserEmail(@Param("userEmail") String userEmail);

    // 위 findAllByUserEmail 대신, Window 엔티티의 필드를 통한 쿼리 메서드 사용을 권장합니다.
    // 이는 @Query 어노테이션을 직접 사용하는 것보다 간결하며, JPA의 쿼리 메서드 규칙을 따릅니다.
    // Window 엔티티 내에 'userId' 필드가 직접 있다면 이렇게 사용할 수 있습니다.
    List<TodoItem> findAllByWindow_UserId(String userEmail);
}