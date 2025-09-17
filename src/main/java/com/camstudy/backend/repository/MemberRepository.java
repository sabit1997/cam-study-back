package com.camstudy.backend.repository;

import com.camstudy.backend.entity.Member;
import com.camstudy.backend.entity.Room;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MemberRepository extends JpaRepository<Member, String> {
    long countByRoomAndActive(Room room, boolean active);
    Optional<Member> findByRoomAndUserId(Room room, String userId);

    // ⬇️ 방 여러 개의 활성 인원 수를 한 번에 조회 (N+1 방지)
    @Query("""
      select m.room.id as roomId, count(m) as cnt
      from Member m
      where m.active = true and m.room.id in :roomIds
      group by m.room.id
    """)
    List<ActiveCountProjection> countActiveByRoomIds(@Param("roomIds") Collection<String> roomIds);
}
