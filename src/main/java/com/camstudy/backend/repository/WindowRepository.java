package com.camstudy.backend.repository;

import com.camstudy.backend.entity.Window;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WindowRepository extends JpaRepository<Window, Long> {

    // 이 메서드는 정상 동작하므로 그대로 둡니다.
    List<Window> findByUserId(String userId);

    // 문제가 되는 자동 생성 규칙 대신, JPQL 쿼리를 직접 작성해서 실행합니다.
    // 이 방법은 메서드 이름 규칙에 전혀 영향을 받지 않으므로 가장 확실합니다.
    @Query("SELECT w FROM Window w WHERE w.userId = :userId ORDER BY w.zIndex ASC")
    List<Window> findWindowsByUserIdOrdered(@Param("userId") String userId);

}