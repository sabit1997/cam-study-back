package com.camstudy.backend.repository;

import com.camstudy.backend.entity.Room;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoomRepository extends JpaRepository<Room, String> { }
