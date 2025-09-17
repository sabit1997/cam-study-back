package com.camstudy.backend.service;

import com.camstudy.backend.entity.Member;
import com.camstudy.backend.entity.Role;
import com.camstudy.backend.entity.Room;
import com.camstudy.backend.repository.MemberRepository;
import com.camstudy.backend.repository.RoomRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RoomService {

    private final RoomRepository roomRepo;
    private final MemberRepository memberRepo;
    private final PasswordEncoder passwordEncoder;

    public Room create(String name, Integer capacity, String rawPassword, String ownerId) {
        String hash = (rawPassword == null || rawPassword.isBlank()) ? null : passwordEncoder.encode(rawPassword);
        var room = Room.builder()
                .name(name)
                .capacity(Optional.ofNullable(capacity).orElse(6))
                .passwordHash(hash)
                .ownerId(ownerId)
                .build();
        return roomRepo.save(room);
    }

    public Room getOrThrow(String roomId) {
        return roomRepo.findById(roomId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "room not found"));
    }

    public java.util.List<Room> list() {
        return roomRepo.findAll();
    }

    public void verifyPassword(Room room, String rawPassword) {
        if (room.getIsPrivate() != null && room.getIsPrivate()) {
            if (rawPassword == null || !passwordEncoder.matches(rawPassword, room.getPasswordHash())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "wrong password");
            }
        }
    }

    public void ensureCapacity(Room room) {
        long active = memberRepo.countByRoomAndActive(room, true);
        if (active >= room.getCapacity()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "room is full");
        }
    }

    /** 멤버 활성화(없으면 생성). 첫 멤버면 HOST 부여 및 ownerId 세팅. */
    public Member upsertActiveMember(Room room, String userId) {
        var memOpt = memberRepo.findByRoomAndUserId(room, userId);
        var member = memOpt.orElseGet(() ->
                Member.builder().room(room).userId(userId).role(Role.MEMBER).active(true).build()
        );
        member.setActive(true);

        if (room.getOwnerId() == null) {
            room.setOwnerId(userId);
            roomRepo.save(room);
            member.setRole(Role.HOST);
        }
        return memberRepo.save(member);
    }

    /** 단일 방의 활성 인원 수 */
    public long activeCount(Room room) {
        return memberRepo.countByRoomAndActive(room, true);
    }

    /** 여러 방의 활성 인원 수를 한 번에 맵으로 반환 (roomId -> count) */
    public Map<String, Long> activeCounts(Collection<String> roomIds) {
        if (roomIds == null || roomIds.isEmpty()) return Collections.emptyMap();
        return memberRepo.countActiveByRoomIds(roomIds)
                .stream()
                .collect(Collectors.toMap(
                        com.camstudy.backend.repository.ActiveCountProjection::getRoomId,
                        com.camstudy.backend.repository.ActiveCountProjection::getCnt
                ));
    }

    public void deactivateMemberIfExists(Room room, String userId) {
        memberRepo.findByRoomAndUserId(room, userId).ifPresent(m -> {
            m.setActive(false);
            memberRepo.save(m);
        });
    }
}
