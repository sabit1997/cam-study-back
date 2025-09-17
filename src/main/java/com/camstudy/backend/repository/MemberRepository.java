package com.camstudy.backend.repository;

import com.camstudy.backend.entity.Member;
import com.camstudy.backend.entity.Room;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MemberRepository extends JpaRepository<Member, String> {
    long countByRoomAndActive(Room room, boolean active);
    Optional<Member> findByRoomAndUserId(Room room, String userId);
}
