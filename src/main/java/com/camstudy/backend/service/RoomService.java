package com.camstudy.backend.service;

import com.camstudy.backend.entity.Member;
import com.camstudy.backend.entity.Role;
import com.camstudy.backend.entity.Room;
import com.camstudy.backend.repository.MemberRepository;
import com.camstudy.backend.repository.RoomRepository;
import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RoomService {

    private final RoomRepository roomRepo;
    private final MemberRepository memberRepo;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public Room create(String name, Integer capacity, String rawPassword, String ownerId) {
        if (ownerId == null || ownerId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required");
        }
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

    public Page<Room> list(Pageable pageable) {
      return roomRepo.findAll(pageable);
    }

    // ---------- 진입점: 컨트롤러는 아래 세 메서드만 호출한다 ----------

    /**
     * 방 입장. 비밀번호 확인 → 정원 확인 → 멤버 활성화를 한 트랜잭션으로 처리한다.
     * userId 는 반드시 인증 주체여야 한다. 요청 바디에서 온 값을 넘기면 사칭이 가능하다.
     */
    @Transactional
    public Member join(String roomId, String userId, String rawPassword) {
        requireUserId(userId);
        Room room = getOrThrow(roomId);
        verifyPassword(room, rawPassword);
        ensureCapacity(room);
        return upsertActiveMember(room, userId);
    }

    /** 본인 나가기 */
    @Transactional
    public void leave(String roomId, String userId) {
        requireUserId(userId);
        deactivateMemberIfExists(getOrThrow(roomId), userId);
    }

    /** 강퇴. 방장만 실행할 수 있다 */
    @Transactional
    public void kick(String roomId, String requesterId, String targetUserId) {
        requireUserId(requesterId);
        if (targetUserId == null || targetUserId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId is required");
        }
        Room room = getOrThrow(roomId);

        if (room.getOwnerId() == null || !room.getOwnerId().equals(requesterId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not room owner");
        }
        if (requesterId.equals(targetUserId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cannot kick yourself");
        }
        deactivateMemberIfExists(room, targetUserId);
    }

    // ---------- 조회 ----------

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

    // ---------- 내부 구현 ----------
    // join/leave/kick 을 거치지 않고 개별 호출되면 인가 검사를 건너뛰게 되므로 private 으로 둔다.

    private void requireUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required");
        }
    }

    private void verifyPassword(Room room, String rawPassword) {
        if (room.getIsPrivate() != null && room.getIsPrivate()) {
            if (rawPassword == null || !passwordEncoder.matches(rawPassword, room.getPasswordHash())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "wrong password");
            }
        }
    }

    private void ensureCapacity(Room room) {
        long active = memberRepo.countByRoomAndActive(room, true);
        if (active >= room.getCapacity()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "room is full");
        }
    }

    /** 멤버 활성화(없으면 생성). 방 소유자면 HOST 를 부여한다. */
    private Member upsertActiveMember(Room room, String userId) {
        var memOpt = memberRepo.findByRoomAndUserId(room, userId);
        var member = memOpt.orElseGet(() ->
                Member.builder().room(room).userId(userId).role(Role.MEMBER).active(true).build()
        );
        member.setActive(true);

        // create() 에서 ownerId 를 인증 주체로 채우므로 null 일 수 없다.
        // 소유자 여부를 읽기만 하므로 동시 입장 시 HOST 가 둘이 되는 문제가 없다.
        member.setRole(userId.equals(room.getOwnerId()) ? Role.HOST : Role.MEMBER);

        return memberRepo.save(member);
    }

    private void deactivateMemberIfExists(Room room, String userId) {
        memberRepo.findByRoomAndUserId(room, userId).ifPresent(m -> {
            m.setActive(false);
            memberRepo.save(m);
        });
    }
}
