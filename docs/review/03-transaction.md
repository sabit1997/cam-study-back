# 03. 트랜잭션 (4단계)

조사 범위: `@Transactional` 경계와 범위, `readOnly` 누락, self-invocation, 트랜잭션 안 외부 호출.

## 현재 상태 (grep으로 확정)

| 서비스 | `@Transactional` 개수 | 클래스 레벨 | 상태 |
|---|---|---|---|
| `TimerService` | 5 | `@Transactional(readOnly = true)` (`:19`) | 패턴 올바름 |
| `TodoService` | 8 | `@Transactional(readOnly = true)` (`:24`) | 패턴 올바름 |
| `AuthService` | **0** | 없음 | 경계 없음 |
| `RoomService` | **0** | 없음 | 경계 없음 |
| `WindowService` | **0** | 없음 | 경계 없음 |

- 애너테이션 임포트는 두 곳 모두 `org.springframework.transaction.annotation.Transactional`입니다(`TimerService.java:12`, `TodoService.java:10`). `jakarta.transaction.Transactional`을 잘못 쓴 경우가 아닙니다.
- `Propagation`, `rollbackFor`, `noRollbackFor`, `isolation` 사용 **0건** — 전부 기본값(`REQUIRED`, `RuntimeException`만 롤백, DB 기본 격리 수준).
- `RestTemplate`, `WebClient`, `HttpURLConnection`, `java.net.http`, OkHttp, Feign, `SimpMessagingTemplate`, `@Async`, `@Scheduled` **사용 0건** → 트랜잭션 안에서 외부 API를 호출하는 코드는 **현재 없습니다**.

## 먼저: 트랜잭션이 없으면 실제로 무슨 일이 일어나는가

프론트 관점에서 `@Transactional`이 없다고 하면 "트랜잭션 없이 돈다"고 읽히기 쉬운데, 그렇지 않습니다. **트랜잭션은 항상 있습니다. 다만 경계가 리포지토리 메서드 단위로 쪼개집니다.**

Spring Data JPA가 제공하는 `SimpleJpaRepository`는 클래스에 `@Transactional(readOnly = true)`가, `save`/`delete` 같은 쓰기 메서드에 `@Transactional`이 이미 붙어 있습니다. 그래서 서비스에 애너테이션이 없으면 이렇게 됩니다.

```
서비스 메서드 (트랜잭션 없음)
├─ repo.findById(...)   → begin → SELECT → commit   ← 트랜잭션 1
├─ (자바 로직)
├─ repo.save(...)       → begin → UPDATE → commit   ← 트랜잭션 2
└─ repo.findAll(...)    → begin → SELECT → commit   ← 트랜잭션 3
```

즉 **하나의 논리적 작업이 3개의 독립 트랜잭션**이 되고, 중간에 실패하면 앞의 것만 커밋된 상태로 남습니다. 커넥션도 그때마다 얻고 반납합니다(`02-performance.md` P1-3).

여기에 OSIV(`spring.jpa.open-in-view` 기본값 `true`)가 겹치면 한 가지가 더 생깁니다. `EntityManager`는 요청 전체 동안 하나가 스레드에 바인딩되므로, **트랜잭션이 끝나도 엔티티는 영속 상태로 남습니다.** 그래서 트랜잭션 밖에서 엔티티 필드를 바꾸면 그 변경이 영속성 컨텍스트에 dirty로 남아 있다가, **그 요청에서 다음에 열리는 아무 쓰기 트랜잭션이 커밋될 때 함께 flush됩니다.** 이게 아래 P1-1의 핵심입니다.

---

## P1-1. `POST /rooms/{roomId}/join` 하나가 트랜잭션 5개로 쪼개짐 — 부분 커밋으로 방이 영구히 HOST 없는 상태가 될 수 있음

**위치** `controller/RoomController.java:63-72`, `service/RoomService.java:64-77`

```java
// RoomController.java:63-72
    @PostMapping("/{roomId}/join")
    public JoinRes join(@PathVariable String roomId, @RequestBody JoinReq req) {
        var room = roomService.getOrThrow(roomId);          // ① findById
        roomService.verifyPassword(room, req.password());   //   (쿼리 없음)
        roomService.ensureCapacity(room);                   // ② countByRoomAndActive
        var member = roomService.upsertActiveMember(room, req.userId());
        ...
    }
```

```java
// RoomService.java:64-77
    public Member upsertActiveMember(Room room, String userId) {
        var memOpt = memberRepo.findByRoomAndUserId(room, userId);   // ③ findByRoomAndUserId
        var member = memOpt.orElseGet(() ->
                Member.builder().room(room).userId(userId).role(Role.MEMBER).active(true).build()
        );
        member.setActive(true);

        if (room.getOwnerId() == null) {
            room.setOwnerId(userId);        // ← 트랜잭션 밖에서 영속 엔티티 변경
            roomRepo.save(room);            // ④ UPDATE rooms
            member.setRole(Role.HOST);
        }
        return memberRepo.save(member);     // ⑤ INSERT/UPDATE members
    }
```

`RoomService`에 `@Transactional`이 없으므로 ①~⑤가 **각각 독립 트랜잭션**입니다.

### 왜 문제인가

**(1) ④와 ⑤가 원자적이지 않습니다 — 구체적 실패 시나리오**

`ownerId`가 아직 없는 방(`POST /rooms`의 `CreateRoomReq.ownerId`가 `null`로 들어온 경우, `dto/room/CreateRoomReq.java:7`)에 첫 사용자 A가 입장합니다.

1. ④가 커밋됩니다 → `rooms.owner_id = 'A'`
2. ⑤가 실패합니다 (커넥션 타임아웃, 또는 `01-jpa-query.md` P1-10의 유니크 제약을 추가한 뒤 동시 요청으로 인한 중복 키)
3. 결과: **`rooms.owner_id = 'A'`인데 A의 `Member` 행이 없습니다.**

이 상태가 왜 영구적인가가 중요합니다. 다음에 누가 입장해도 `if (room.getOwnerId() == null)`이 **거짓**이므로 `member.setRole(Role.HOST)`가 다시는 실행되지 않습니다. 그 방은 **HOST 역할을 가진 멤버가 영원히 없는 방**이 됩니다. `Role.HOST`를 부여하는 코드 경로가 이 한 곳뿐이므로(grep: `Role.HOST`는 `RoomService.java:74`와 `RoomController.java:70`에만 등장) 복구 경로가 없습니다.

**(2) 트랜잭션 밖에서 영속 엔티티를 변경하는 것이 더 위험합니다**

`:72`의 `room.setOwnerId(userId)`는 **어떤 트랜잭션에도 속하지 않은 시점**에 실행됩니다. 그런데 `room`은 ①에서 로드된 뒤 OSIV 덕분에 여전히 영속 상태입니다. 즉 이 변경은 영속성 컨텍스트에 dirty로 남습니다.

지금은 바로 다음 줄 `roomRepo.save(room)`이 있어서 의도대로 UPDATE가 나갑니다. 그런데 이 코드에서 `save(room)`을 지우면 어떻게 될까요? 이 프로젝트 다른 곳(`TimerService.updateTimerGoal:169`)이 `save()` 없이 dirty checking에 의존하는 스타일을 쓰고 있으니, 통일한다고 지울 수 있습니다.

지우면 — **여전히 UPDATE가 나갑니다.** ⑤의 `memberRepo.save(member)`가 트랜잭션을 열고, 그 커밋 시점에 Hibernate가 영속성 컨텍스트 전체를 flush하면서 dirty인 `room`까지 함께 UPDATE합니다. **아무 관계 없는 트랜잭션이 내 변경을 커밋해 주는 상태**입니다.

이게 왜 나쁜가: 변경이 언제 DB에 반영되는지가 코드를 읽어서 알 수 없게 됩니다. `memberRepo.save`가 실패하면 `room` 변경도 롤백되고, 성공하면 같이 커밋됩니다 — 우연히 그렇게 된 것이지 의도한 게 아닙니다. 반대로 그 요청에 쓰기 트랜잭션이 하나도 없었다면 변경은 조용히 사라집니다.

**(3) ②와 ⑤ 사이에 정원 초과가 발생합니다**

`ensureCapacity`(②)가 `COUNT`로 정원을 확인하고, `upsertActiveMember`(⑤)가 INSERT합니다. 서로 다른 트랜잭션이므로 그 사이에 다른 요청이 끼어들 수 있습니다. 정원 6명인 방에 동시에 10명이 요청하면 모두 ②에서 "5명이니 여유 있음"을 보고 통과합니다.
→ 경쟁 조건 자체는 `08-concurrency.md`에서 다룹니다. 여기서 짚는 것은 **원인이 트랜잭션 경계 부재**라는 점입니다.

### 수정 방향

컨트롤러가 서비스 메서드를 4번 호출하는 구조 자체가 경계를 만들 수 없게 합니다. **"방 입장"이라는 하나의 작업을 서비스 메서드 하나로 합치고** 거기에 트랜잭션을 거세요.

```diff
--- a/src/main/java/com/camstudy/backend/service/RoomService.java
+++ b/src/main/java/com/camstudy/backend/service/RoomService.java
+import org.springframework.transaction.annotation.Transactional;
+
 @Service
 @RequiredArgsConstructor
+@Transactional(readOnly = true)
 public class RoomService {
```

```diff
+    /** 방 입장: 비밀번호 확인 → 정원 확인 → 멤버 활성화를 한 트랜잭션으로 */
+    @Transactional
+    public Member join(String roomId, String userId, String rawPassword) {
+        Room room = getOrThrow(roomId);
+        verifyPassword(room, rawPassword);
+        ensureCapacity(room);
+        return upsertActiveMember(room, userId);
+    }
```

```diff
--- a/src/main/java/com/camstudy/backend/controller/RoomController.java
+++ b/src/main/java/com/camstudy/backend/controller/RoomController.java
     public JoinRes join(@PathVariable String roomId, @RequestBody JoinReq req) {
-        var room = roomService.getOrThrow(roomId);
-        roomService.verifyPassword(room, req.password());
-        roomService.ensureCapacity(room);
-        var member = roomService.upsertActiveMember(room, req.userId());
-
+        var member = roomService.join(roomId, req.userId(), req.password());
         String role = (member.getRole() == Role.HOST) ? "HOST" : "MEMBER";
         return new JoinRes(role);
     }
```

그리고 `upsertActiveMember` 안의 `roomRepo.save(room)`은 **남겨두세요**. 트랜잭션 안에서는 불필요하지만(dirty checking이 처리함), 지우면 "어디서 저장되는지 모르는 코드"가 되기 때문입니다. 남긴다면 의도가 코드에 보입니다.

**중요**: 이 리팩터가 정원 초과 경쟁 조건을 해결하지는 않습니다. 하나의 트랜잭션 안에서도 잠금 없는 `COUNT`는 다른 트랜잭션의 INSERT를 막지 못합니다. 원자성(부분 커밋 방지)만 해결됩니다. 경쟁 조건은 `08-concurrency.md`에서 별도로 다룹니다.

`upsertActiveMember`, `ensureCapacity`, `verifyPassword`는 `public`으로 남겨둬도 되지만, 컨트롤러에서 개별 호출하지 못하게 하려면 `private`으로 내리는 게 안전합니다(`06-architecture.md`).

---

## P1-2. `AuthService.signup`의 check-then-act — 그리고 트랜잭션으로는 해결되지 않는 이유

**위치** `service/AuthService.java:62-73`

```java
    public String signup(String email, String password, String username) {
        if (userRepository.findByEmail(email).isPresent()) {      // :63  트랜잭션 A
            throw new RuntimeException("이미 존재하는 이메일입니다.");
        }
        String hash = encoder.encode(password);                   // :66  BCrypt 계산
        userRepository.save(User.builder()...build());            // :67  트랜잭션 B
        return "회원가입 완료";
    }
```

### 왜 문제인가

**(1) 확인과 저장 사이에 무거운 연산이 들어 있습니다**

`encoder`는 `new BCryptPasswordEncoder()`(`:17`), 기본 strength 10입니다. BCrypt는 **의도적으로 느리게 설계된 함수**입니다 — 무차별 대입 공격을 어렵게 하려고 키 확장을 2^strength번 반복합니다. 즉 "느린 게 기능"입니다.

문제는 그 느린 연산이 **확인(`:63`)과 저장(`:67`) 사이에 놓여 있다**는 점입니다. 경쟁 구간의 길이가 그 계산 시간만큼 늘어납니다. 순서만 바꿔도(먼저 해싱하고 나중에 확인) 구간이 짧아지지만, 그건 완화이지 해결이 아닙니다.

(구체적 시간은 측정하지 않았습니다. 요점은 "이 구간이 CPU 명령 몇 개가 아니라 의도적으로 느린 함수 하나만큼 길다"는 것입니다.)

**(2) `@Transactional`을 붙여도 해결되지 않습니다 — 이게 핵심입니다**

직관적으로는 `signup`에 `@Transactional`을 붙이면 "확인과 저장이 원자적으로" 될 것 같습니다. **되지 않습니다.**

MySQL InnoDB의 기본 격리 수준은 REPEATABLE READ입니다. 이 수준에서 `SELECT * FROM users WHERE email = ?`는 **잠금을 걸지 않는 일관된 읽기(consistent read)** 입니다. MVCC 스냅샷을 보고 결과를 돌려주고, 어떤 락도 남기지 않습니다. 락이 없으면 막을 것도 없습니다.

그래서 두 트랜잭션이 동시에 들어오면:

```
T1: SELECT email='x' → 0건          T2: SELECT email='x' → 0건
T1: INSERT email='x'                T2: INSERT email='x'
T1: COMMIT                          T2: COMMIT
→ 두 행 모두 저장됨
```

`users.email`에 유니크 인덱스가 없으므로(`01-jpa-query.md` P1-1) INSERT가 둘 다 성공합니다. 트랜잭션이 있든 없든 결과가 같습니다.

일반화하면: **"존재하지 않음을 확인하고 삽입한다"는 애플리케이션 코드로는 안전하게 만들 수 없습니다.** 존재하지 않는 행에는 걸 락이 없기 때문입니다(SERIALIZABLE에서 갭 락으로 막을 수는 있지만, 격리 수준을 올리는 대가가 큽니다). 유일한 실용적 해법은 **DB에 유니크 제약을 걸고, 위반 예외를 받아서 변환하는 것**입니다.

그리고 중복이 실제로 생기면 결과가 나쁩니다 — `findByEmail`이 `Optional`을 반환하므로 2건을 만나면 Spring Data가 `IncorrectResultSizeDataAccessException`을 던지고, 그 이메일로는 **아무도 로그인할 수 없게 됩니다.**

### 수정 방향

순서가 있습니다. 유니크 제약이 먼저입니다.

**1단계** — 유니크 제약 (`01-jpa-query.md` P1-1과 동일)

```diff
--- a/src/main/java/com/camstudy/backend/entity/User.java
+++ b/src/main/java/com/camstudy/backend/entity/User.java
-    private String email;
+    @Column(nullable = false, unique = true, length = 255)
+    private String email;
```

적용 전 중복 확인:
```sql
SELECT email, COUNT(*) FROM users GROUP BY email HAVING COUNT(*) > 1;
```

**2단계** — 제약 위반을 사용자에게 보여줄 예외로 변환

```diff
--- a/src/main/java/com/camstudy/backend/service/AuthService.java
+++ b/src/main/java/com/camstudy/backend/service/AuthService.java
     public String signup(String email, String password, String username) {
         if (userRepository.findByEmail(email).isPresent()) {
             throw new RuntimeException("이미 존재하는 이메일입니다.");
         }
         String hash = encoder.encode(password);
-        userRepository.save(User.builder()
-                .email(email).password(hash).username(username).build());
+        try {
+            userRepository.save(User.builder()
+                    .email(email).password(hash).username(username).build());
+        } catch (DataIntegrityViolationException e) {
+            // 사전 확인을 통과했지만 동시 요청이 먼저 삽입한 경우
+            throw new RuntimeException("이미 존재하는 이메일입니다.");
+        }
         return "회원가입 완료";
     }
```

사전 `findByEmail` 확인은 **남겨두는 게 맞습니다.** 정상 경로(이미 있는 이메일로 가입 시도)에서 친절한 메시지를 주는 역할이고, 예외 처리는 경쟁에서 진 드문 경우만 담당합니다. 사전 확인이 "최적화", 유니크 제약이 "보장"입니다.

`RuntimeException`에 메시지 문자열로 상태를 실어 보내는 현재 방식 자체가 문제인데, 그건 `04-exception.md`에서 다룹니다.

**3단계** — `AuthService`에 트랜잭션 경계

경쟁 조건과는 무관하지만, 나중에 "가입 시 기본 `Window` 3개 생성" 같은 게 추가되면 원자성이 필요해집니다. 지금 걸어두는 편이 낫습니다. 단 **아래 P2-2를 먼저 읽으세요** — `login`/`refresh`에 그대로 붙이면 안 됩니다.

```diff
 @Service
+@Transactional(readOnly = true)
 public class AuthService {
 ...
+    @Transactional
     public String signup(String email, String password, String username) {
```

---

## P1-3. `focusWindow`가 읽기와 쓰기를 다른 트랜잭션에서 수행

**위치** `service/WindowService.java:12-13`, `:70-91`

```java
@Service                                    // :12  @Transactional 없음
public class WindowService {
...
    public List<Window> focusWindow(Long id, String userEmail) {
        Window focusedWindow = windowRepository.findById(id)...;               // ① 트랜잭션 1
        ...
        List<Window> userWindows = windowRepository.findWindowsByUserIdOrdered(userEmail);  // ② 트랜잭션 2

        int maxZIndex = userWindows.stream().mapToInt(Window::getZIndex).max().orElse(0);
        focusedWindow.setZIndex(maxZIndex + 1);                               //   ← 트랜잭션 밖 변경
        windowRepository.save(focusedWindow);                                 // ③ 트랜잭션 3

        return windowRepository.findWindowsByUserIdOrdered(userEmail);         // ④ 트랜잭션 4
    }
```

### 왜 문제인가

**(1) `max`를 읽는 트랜잭션(②)과 쓰는 트랜잭션(③)이 분리되어 있습니다**

읽기와 쓰기가 같은 트랜잭션 안에 있어야 최소한 "내가 읽은 값이 커밋 시점에도 유효한가"를 논할 수 있습니다. 지금은 ②가 커밋된 뒤 ③이 시작하므로, 그 사이에 다른 요청이 `zIndex`를 올릴 수 있습니다. 두 요청이 동시에 들어오면 둘 다 `max = 5`를 읽고 둘 다 `6`을 씁니다 → **두 창이 같은 zIndex**를 갖고, 프론트의 z-order가 결정 불가능해집니다.

**(2) 부분 실패 시 상태가 어긋납니다**

③이 커밋된 뒤 ④가 실패하면, DB의 `zIndex`는 이미 바뀌었는데 클라이언트는 500을 받습니다. 클라이언트는 "포커스 실패"로 처리하지만 서버 상태는 바뀌어 있습니다.

**(3) 트랜잭션 밖에서 `setZIndex`를 호출합니다**

P1-1 (2)와 같은 문제입니다. `focusedWindow`는 ①에서 로드되어 OSIV로 영속 상태이므로, `setZIndex`는 dirty 변경으로 남습니다. ③의 `save()`가 없더라도 ③ 위치에 아무 쓰기 트랜잭션이 있으면 flush됩니다.

### 수정 방향

`WindowService` 전체에 경계를 세우고, `focusWindow`를 하나의 트랜잭션으로 묶습니다. 쿼리 축소는 `01-jpa-query.md` P1-7과 동일한 diff입니다.

```diff
--- a/src/main/java/com/camstudy/backend/service/WindowService.java
+++ b/src/main/java/com/camstudy/backend/service/WindowService.java
+import org.springframework.transaction.annotation.Transactional;
+
 @Service
+@Transactional(readOnly = true)
 public class WindowService {
...
+    @Transactional
     public Window create(Window window, String userEmail) { ... }
+
+    @Transactional
     public Window partialUpdate(Long id, WindowPatchDto dto, String userEmail) { ... }
+
+    @Transactional
     public void delete(Long id, String userEmail) { ... }
+
+    @Transactional
     public List<Window> focusWindow(Long id, String userEmail) { ... }
```

**적용 시 반드시 주의할 것** — 클래스에 `@Transactional(readOnly = true)`를 걸면서 쓰기 메서드에 `@Transactional`을 **빠뜨리면, 저장이 예외 없이 조용히 무시됩니다.** 이유는 아래 P1-4에서 설명합니다. `WindowService`의 쓰기 메서드는 `create`, `partialUpdate`, `delete`, `focusWindow` 4개 전부입니다.

`@Transactional`을 붙인 뒤에는 `save()` 호출이 불필요해지지만(dirty checking이 처리), P1-1과 같은 이유로 남겨둬도 무방합니다. `delete`는 `windowRepository.delete(window)`가 필요하므로 그대로입니다.

**이 리팩터가 (1)의 경쟁 조건을 해결하지는 않습니다.** 하나의 트랜잭션 안에서도 `SELECT MAX(z_index)`는 잠금을 걸지 않으므로 두 요청이 같은 값을 읽을 수 있습니다. 해결에는 비관적 잠금이나 다른 설계가 필요합니다 → `08-concurrency.md`.

---

## P1-4. `readOnly` 미사용 — 그리고 도입할 때의 함정

**위치** `service/AuthService.java:12-13`, `service/RoomService.java:20-22`, `service/WindowService.java:12-13`

세 서비스의 읽기 메서드가 모두 `readOnly = true` 없이 동작합니다.

| 메서드 | 위치 |
|---|---|
| `RoomService.getOrThrow` | `:39-42` |
| `RoomService.list` | `:44-46` |
| `RoomService.activeCount` | `:80-82` |
| `RoomService.activeCounts` | `:85-93` |
| `WindowService.findAllByUser` | `:22-26` |
| `AuthService.login` (읽기 + 쿠키) | `:24-60` |

정확히 말하면 이 메서드들은 `SimpleJpaRepository`의 `readOnly = true` 트랜잭션 안에서 쿼리를 실행하므로, **리포지토리 호출 하나하나는 이미 readOnly입니다.** 문제는 서비스 메서드 전체를 감싸는 경계가 없다는 것이고, 경계를 만들 때 `readOnly`를 붙일지가 이 항목의 논점입니다.

### `readOnly = true`가 실제로 하는 일

세 가지입니다. 두 번째와 세 번째가 중요합니다.

**(1) Hibernate의 flush mode를 `MANUAL`로 바꿉니다 → dirty checking이 생략됩니다**

dirty checking이 무엇인가부터. Hibernate는 엔티티를 DB에서 읽을 때 **필드 값들을 배열로 따로 복사해 둡니다**(스냅샷). 그리고 트랜잭션 커밋 시점에 영속성 컨텍스트에 있는 **모든 엔티티**에 대해 현재 필드 값과 스냅샷을 하나씩 비교해서, 달라진 필드가 있는 엔티티에만 UPDATE를 발행합니다.

이게 `repository.save()`를 부르지 않아도 변경이 저장되는 이유입니다(`TimerService.updateTimerGoal:169`가 그렇게 동작합니다). 대가는 커밋 시점의 비교 작업 — 엔티티 수 × 필드 수만큼입니다.

`readOnly = true`면 flush가 아예 일어나지 않으므로 이 비교가 전부 생략됩니다. `GET /todos`처럼 상한 없이 많은 엔티티를 읽는 경우(`01-jpa-query.md` P1-8) 차이가 커집니다.

**(2) JDBC 커넥션에 `setReadOnly(true)`를 호출합니다**

드라이버와 DB에 "이 트랜잭션은 쓰지 않는다"고 알립니다. MySQL Connector/J를 replication 구성으로 쓰면 읽기 전용 커넥션이 replica로 라우팅될 수 있습니다. 지금은 단일 DB라 실질 효과가 없지만, 나중에 읽기 복제본을 붙일 때 이 표시가 되어 있어야 라우팅이 가능합니다.

**(3) 의도하지 않은 UPDATE를 막습니다 — `RoomService`에 특히 필요합니다**

P1-1 (2)에서 본 문제, 즉 "트랜잭션 밖에서 바꾼 엔티티가 나중의 아무 트랜잭션에 얹혀 커밋되는" 현상은 **얹히는 쪽 트랜잭션이 `readOnly`면 일어나지 않습니다.** flush를 안 하니까요.

### 그래서 함정: `readOnly` 트랜잭션 안의 쓰기는 조용히 사라집니다

이게 도입할 때 가장 조심해야 할 부분입니다.

`readOnly = true` 트랜잭션 안에서 `repository.save(newEntity)`를 호출하면 — **예외가 나지 않습니다.** flush mode가 `MANUAL`이라 커밋 시점에 flush가 일어나지 않고, INSERT가 발행되지 않은 채 트랜잭션이 끝납니다. 컨트롤러는 200을 반환하고, 클라이언트는 저장에 성공한 줄 압니다. DB에는 아무것도 없습니다.

즉 **클래스에 `@Transactional(readOnly = true)`를 걸었으면, 쓰기 메서드에 `@Transactional`을 덮어쓰는 것을 하나도 빠뜨리면 안 됩니다.** 빠뜨렸을 때 알려주는 장치가 없습니다.

`TimerService`와 `TodoService`는 이걸 제대로 하고 있습니다 — 쓰기 메서드 전부에 `@Transactional`이 덮여 있습니다(`TimerService`: `record`, `updateTimerGoal`, `updatePomoCycles`, `resetDailyTimer` / `TodoService`: `addTodo`, `updateDone`, `deleteTodo`, `updateText`, `updateTextGlobal`, `updateDoneGlobal`, `deleteTodoGlobal`). 나머지 세 서비스에 같은 패턴을 적용할 때 이 체크리스트가 필요합니다.

| 서비스 | `@Transactional`(쓰기)를 붙여야 하는 메서드 |
|---|---|
| `AuthService` | `signup` (`:62`) — `login`/`logout`/`refreshAccessToken`은 P2-2 참고 |
| `RoomService` | `create` (`:28`), `join`(신설), `upsertActiveMember` (`:64`), `deactivateMemberIfExists` (`:95`) |
| `WindowService` | `create` (`:29`), `partialUpdate` (`:36`), `delete` (`:59`), `focusWindow` (`:70`) |

### 수정 방향

각 서비스에 클래스 레벨 `readOnly = true`를 걸고 위 표의 메서드에 `@Transactional`을 덮어씁니다. diff는 P1-1(`RoomService`), P1-3(`WindowService`), P1-2 3단계(`AuthService`)에 있습니다.

작업 후 검증: 각 쓰기 API를 한 번씩 호출하고 DB에서 실제로 반영됐는지 확인하세요. 이 리팩터의 실패 모드가 "200을 받지만 저장되지 않음"이라, 응답만 보면 알 수 없습니다.

---

## P2-1. self-invocation — 현재 실제 문제는 0건이지만 함정 지점이 3곳 있음

### 원리

Spring의 `@Transactional`은 **프록시**로 구현됩니다. `TimerService`를 주입받는 `TimerController`는 실제 `TimerService` 인스턴스가 아니라, 그것을 감싼 프록시 객체를 받습니다. 프록시가 메서드 호출을 가로채서 트랜잭션을 열고, 실제 객체에 위임하고, 반환되면 커밋합니다.

```
TimerController ──▶ [프록시] ──▶ TimerService 실제 객체
                    트랜잭션 시작/커밋
```

여기서 나오는 결론: **실제 객체 안에서 `this.someMethod()`를 호출하면 프록시를 거치지 않습니다.** 화살표가 프록시를 통과하지 않으므로 그 메서드의 `@Transactional`은 **무시됩니다.**

같은 이유로 `private`, `final`, `static` 메서드의 `@Transactional`도 효과가 없습니다 — 프록시가 오버라이드할 수 없기 때문입니다. 이 경우 컴파일 에러도 경고도 나지 않습니다.

### 이 프로젝트의 self-invocation 지점

**① `TimerService.getTimerAnalytics` → `getMonthlyTotal` → `listByMonth`** (`:101`, `:103`, `:108`, `:111`)

같은 클래스 안의 호출이므로 프록시를 거치지 않습니다. 다만 `getMonthlyTotal`과 `listByMonth`에는 자체 `@Transactional`이 없고, 클래스 레벨 `readOnly = true`가 이미 `getTimerAnalytics`에 적용되어 있으므로 **결과적으로 동작이 같습니다. 현재 버그 없음.**

위험은 나중에 생깁니다. `getMonthlyTotal`에 `@Cacheable`(같은 프록시 메커니즘)이나 `@Transactional(propagation = Propagation.REQUIRES_NEW)`을 붙이면 **조용히 적용되지 않습니다.** `02-performance.md` P1-1에서 이 중복 호출을 지역 변수로 없애는 수정을 제안했는데, 그렇게 하면 이 지점이 함께 사라집니다.

**② `TodoService`의 private 헬퍼** (`:149`, `:159`)

`findTodoItemForUser`, `findTodoItemForUserGlobal`은 `private`입니다. `@Transactional`이 붙어 있지 않으므로 현재 문제 없습니다. 여기에 애너테이션을 붙이면 아무 효과 없이 무시되므로, 붙이지 마세요.

**③ `RoomService.join`(신설 예정) → `getOrThrow`/`verifyPassword`/`ensureCapacity`/`upsertActiveMember`**

P1-1에서 제안한 `join` 메서드는 같은 클래스의 `public` 메서드 4개를 self-invoke합니다. `join`에 `@Transactional`이 있고 내부 호출들은 그 트랜잭션에 그냥 참여하므로(프록시를 안 거치니 새 트랜잭션 판단도 없음) **의도한 대로 동작합니다.** 하지만 `upsertActiveMember`에 `@Transactional`을 남겨두면 "이 메서드는 자체 트랜잭션이 있다"고 오해하게 됩니다.

→ P1-1 리팩터 후에는 `upsertActiveMember`/`ensureCapacity`/`verifyPassword`를 `private`으로 내리고 `@Transactional`을 붙이지 마세요. 외부 진입점은 `join`뿐이 되고, self-invocation 함정이 구조적으로 제거됩니다.

### 정리

지금 self-invocation으로 **깨지고 있는 코드는 없습니다.** 다만 트랜잭션을 새로 도입하는 과정(P1-1~P1-4)에서 실수하기 쉬운 지점이므로, 위 원리를 알고 진행하세요. 규칙은 단순합니다 — **`@Transactional`은 외부에서 호출되는 `public` 메서드에만 의미가 있습니다.**

---

## P2-2. 트랜잭션 안 외부 호출 — 현재 0건. 단 `AuthService`가 HTTP 응답을 직접 쓰고 있어 위험이 잠재

grep으로 확인한 사실: `RestTemplate`, `WebClient`, `HttpURLConnection`, `java.net.http`, OkHttp, Feign, `SimpMessagingTemplate`, `@Async`, `@Scheduled` **사용 0건**. 이메일 발송, 결제, 푸시 같은 외부 연동 코드도 없습니다. → **트랜잭션 안에서 외부 API를 호출하는 코드는 존재하지 않습니다.**

그런데 같은 성격의 부작용이 서비스 계층에 이미 있습니다.

**위치** `service/AuthService.java:24`, `:55-56`, `:75`, `:92-93`, `:99`, `:117`

```java
    public LoginResponse login(String email, String password, HttpServletResponse response) {
        ...
        response.addHeader("Set-Cookie", accessTokenCookie.toString());     // :55
        response.addHeader("Set-Cookie", refreshTokenCookie.toString());    // :56
```

### 왜 문제인가 (아직은 아니지만, 곧)

지금 `AuthService`에는 `@Transactional`이 없으므로 문제가 없습니다. 하지만 P1-2 3단계에서 `signup`에 트랜잭션을 붙이면, 자연스럽게 `login`과 `refreshAccessToken`에도 붙이고 싶어집니다(클래스 레벨 `readOnly = true`를 걸면 자동으로 적용됩니다).

붙는 순간 이렇게 됩니다.

```
트랜잭션 시작
├─ findByEmail
├─ 비밀번호 검증
├─ 토큰 생성
├─ response.addHeader("Set-Cookie", ...)     ← 되돌릴 수 없는 부작용
└─ 트랜잭션 커밋 ... 실패하면?
```

트랜잭션이 롤백되어도 **이미 응답 객체에 쓴 헤더는 되돌아가지 않습니다.** 롤백은 DB 변경만 취소합니다. 결과는 "로그인 실패 응답인데 유효한 인증 쿠키가 함께 내려가는" 상태입니다. 정확히는 예외가 `GlobalExceptionHandler`로 가서 500/401 바디가 나가는데, `Set-Cookie` 헤더는 그대로 붙어 있습니다.

이게 트랜잭션 안 외부 호출의 문제와 정확히 같은 구조입니다. 일반화하면:

> **트랜잭션 안에서는 되돌릴 수 없는 부작용을 만들지 않습니다.** HTTP 응답 쓰기, 메일 발송, 외부 API 호출, WebSocket 브로드캐스트, 파일 쓰기, 메시지 큐 발행이 모두 여기 해당합니다.

이유가 두 가지입니다. **되돌릴 수 없다**는 것과, **트랜잭션 시간이 외부 시스템의 응답 시간에 묶인다**는 것. 두 번째가 실무에서 더 자주 사고를 냅니다 — 외부 API가 30초 응답하지 않으면 DB 트랜잭션이 30초 열려 있고, 그동안 커넥션 1개와 락을 잡고 있습니다(`02-performance.md` P0-1과 같은 고갈 경로).

### 수정 방향

**단기(권장)**: 쿠키 생성을 컨트롤러로 올려서 서비스 계층에서 `HttpServletResponse`를 제거합니다. 그러면 트랜잭션을 어디에 붙여도 안전해집니다.

```diff
--- a/src/main/java/com/camstudy/backend/service/AuthService.java
+++ b/src/main/java/com/camstudy/backend/service/AuthService.java
-    public LoginResponse login(String email, String password, HttpServletResponse response) {
+    /** 인증만 담당. 토큰 2개와 유저 정보를 반환하고, 쿠키 세팅은 컨트롤러가 한다 */
+    public LoginResult login(String email, String password) {
         User user = userRepository.findByEmail(email)
                 .orElseThrow(() -> new RuntimeException("존재하지 않는 사용자"));
         if (!encoder.matches(password, user.getPassword())) {
             throw new RuntimeException("비밀번호 불일치");
         }
-        String accessToken = jwtUtil.createToken(user.getEmail());
-        String refreshToken = jwtUtil.createRefreshToken(user.getEmail());
-        ... ResponseCookie 생성 ...
-        response.addHeader("Set-Cookie", accessTokenCookie.toString());
-        response.addHeader("Set-Cookie", refreshTokenCookie.toString());
-        return new LoginResponse(user.getId(), user.getUsername());
+        return new LoginResult(
+                jwtUtil.createToken(user.getEmail()),
+                jwtUtil.createRefreshToken(user.getEmail()),
+                new LoginResponse(user.getId(), user.getUsername()));
     }
```

컨트롤러가 `ResponseEntity`에 쿠키를 실어 보냅니다.

```diff
--- a/src/main/java/com/camstudy/backend/controller/AuthController.java
+++ b/src/main/java/com/camstudy/backend/controller/AuthController.java
     @PostMapping("/login")
-    public LoginResponse login(@RequestBody LoginRequest loginRequest, HttpServletResponse response) {
-        return authService.login(loginRequest.getEmail(), loginRequest.getPassword(), response);
+    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest req) {
+        var result = authService.login(req.getEmail(), req.getPassword());
+        return ResponseEntity.ok()
+                .header(HttpHeaders.SET_COOKIE, cookieFactory.access(result.accessToken()).toString())
+                .header(HttpHeaders.SET_COOKIE, cookieFactory.refresh(result.refreshToken()).toString())
+                .body(result.body());
     }
```

쿠키 속성(`httpOnly`/`secure`/`sameSite`/`maxAge`)이 `login`·`logout`·`refreshAccessToken` 세 곳에 복붙되어 있고 값이 미묘하게 다릅니다(`logout`에는 `sameSite`가 없습니다 — `:77-89`). 한 곳으로 모으면 그 불일치도 함께 해결됩니다 → `05-security.md`.

**향후 외부 연동이 생기면**: 커밋 이후로 미루는 장치를 씁니다.

```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onSignedUp(SignedUpEvent e) { mailSender.send(...); }
```
서비스는 `applicationEventPublisher.publishEvent(new SignedUpEvent(...))`만 하고, 실제 발송은 커밋 후에 일어납니다. 롤백되면 발송도 일어나지 않습니다.

---

## P2-3. checked exception 롤백 규칙 — 현재 해당 코드는 없음, 원리만 기록

### 원리

`@Transactional`의 기본 롤백 규칙은 직관과 반대입니다.

| 던져진 예외 | 기본 동작 |
|---|---|
| `RuntimeException` 및 하위 | **롤백** |
| `Error` 및 하위 | **롤백** |
| checked exception (`Exception` 직접 상속) | **커밋** |

즉 `IOException`을 던지면서 메서드가 실패해도 **그 트랜잭션은 정상 커밋됩니다.** EJB 시절의 규약을 물려받은 기본값입니다.

### 현재 상태 (grep으로 확정)

서비스 계층에서 던지는 예외 전체:

| 예외 | 위치 | 계열 |
|---|---|---|
| `RuntimeException` | `AuthService:29`, `:64`, `:102` / `WindowService:42`, `:64`, `:75` | RuntimeException |
| `ResponseStatusException` | `RoomService:51`, `:59` | RuntimeException 하위 |
| `SecurityException` | `TodoService:35`, `:154`, `:163` | RuntimeException 하위 |
| `IllegalArgumentException` | `TodoService:33`, `:151`, `:161` / `TimerService:92`, `:139`, `:161`, `:168` | RuntimeException 하위 |

`throws` 절이 선언된 서비스 메서드는 **0건**입니다. → **checked exception으로 인해 잘못 커밋되는 코드는 현재 없습니다.**

### 앞으로 주의할 것

파일 I/O, 외부 API, `ObjectMapper` 등을 서비스에 들이면 checked exception이 생깁니다. 그때는 두 방법 중 하나입니다.

```java
@Transactional(rollbackFor = Exception.class)   // 모든 예외에 롤백
```
또는 checked exception을 서비스 경계에서 잡아 도메인 예외(`RuntimeException` 하위)로 변환합니다. 후자가 예외 처리 일관성 면에서 낫습니다 → `04-exception.md`.

---

## P2-4. 불필요한 `save()` 호출 — 단, 지금 지우면 안 되는 것이 섞여 있음

`@Transactional` 안에서 **영속 상태** 엔티티의 필드를 바꾸면 dirty checking으로 UPDATE가 자동 발행되므로 `save()`가 필요 없습니다. 반면 **새로 만든** 엔티티는 `save()`(→ `persist`)가 반드시 필요합니다. 이 프로젝트에는 두 경우가 섞여 있습니다.

| 위치 | `save()` 필요? | 이유 |
|---|---|---|
| `TimerService.record:67` | **필요** | `orElseGet`으로 새 `Timer`를 만들 수 있음 |
| `TimerService.updatePomoCycles:184` | **필요** | 동일 |
| `TimerService.resetDailyTimer:193` | **불필요** | `ifPresent` 안이므로 항상 영속 상태 |
| `TimerService.updateTimerGoal` | (없음) | 이미 dirty checking에 의존 — 올바름 |
| `RoomService.deactivateMemberIfExists:98` | **현재는 필요** | 트랜잭션이 없어서. P1-1 적용 후 불필요 |
| `RoomService.upsertActiveMember:73`, `:76` | **현재는 필요** | 동일 |
| `WindowService` 전부 (`:32`, `:55`, `:87`) | **현재는 필요** | 트랜잭션이 없어서. P1-3 적용 후 `:55`, `:87`은 불필요 |

### 왜 신경 쓸 가치가 있는가

`save()`가 있어도 성능상 손해는 거의 없습니다(영속 엔티티에 대한 `save`는 `merge`로 가고, 이미 영속성 컨텍스트에 있으면 추가 SELECT 없이 no-op입니다). 문제는 **읽는 사람이 트랜잭션 경계를 오해하게 만든다**는 점입니다.

`TimerService` 안에서 `updateTimerGoal`은 `save()` 없이, `resetDailyTimer`는 `save()`와 함께 쓰고 있습니다. 같은 클래스에서 두 스타일이 공존하면 "언제 필요한지"를 알 수 없는 코드가 됩니다. 그러면 트랜잭션이 없는 서비스에서 `save()`를 실수로 빼는 사고(→ 저장 안 됨)나, `readOnly` 안에서 `save()`를 쓰는 사고(→ P1-4의 조용한 무시)로 이어집니다.

### 수정 방향

**P1-1 / P1-3 / P1-4를 적용한 다음에** 정리하세요. 순서를 바꾸면 저장이 안 됩니다.

```diff
--- a/src/main/java/com/camstudy/backend/service/TimerService.java
+++ b/src/main/java/com/camstudy/backend/service/TimerService.java
     @Transactional
     public void resetDailyTimer(String userEmail, LocalDate date, ZoneId userZone) {
         repo.findByUserIdAndDate(userEmail, date)
             .ifPresent(timer -> {
                 timer.setTotalSeconds(0);
                 timer.setPomoCycles(0);
-                repo.save(timer);
             });
     }
```

한 가지 더: `resetDailyTimer`의 `userZone` 파라미터는 본문에서 쓰이지 않습니다(`:188`). `listByMonth:74`, `getMonthlyTotal:80`의 `userZone`도 동일합니다. 죽은 파라미터입니다.

---

## 요약

| 심각도 | 개수 | 항목 |
|---|---|---|
| **P0** | 0 | — (단 `02-performance.md` P0-1의 무한 루프가 `@Transactional` 메서드 안이라 커넥션 점유로 증폭됨) |
| **P1** | 4 | 방 입장이 트랜잭션 5개로 쪼개져 부분 커밋 발생 / `signup` check-then-act (트랜잭션으로 해결 불가, 유니크 제약 필요) / `focusWindow` 읽기·쓰기 트랜잭션 분리 / `readOnly` 미사용 + 도입 시 조용한 저장 실패 함정 |
| **P2** | 4 | self-invocation (현재 버그 0건, 함정 3지점) / 트랜잭션 안 외부 호출 (현재 0건, `HttpServletResponse`가 잠재 위험) / checked exception 롤백 규칙 (현재 해당 없음) / 불필요한 `save()` |

## 이 카테고리에서 꼭 가져갈 것 세 가지

1. **`@Transactional`이 없어도 트랜잭션은 있습니다.** 경계가 리포지토리 메서드 단위로 쪼개져서, 하나의 작업이 여러 트랜잭션이 되고 부분 커밋이 생깁니다.
2. **check-then-act는 트랜잭션으로 안전해지지 않습니다.** 존재하지 않는 행에는 걸 락이 없기 때문입니다. DB 유니크 제약만이 해법입니다.
3. **`readOnly = true`를 도입할 때 쓰기 메서드를 빠뜨리면, 예외 없이 저장이 무시됩니다.** 응답은 200입니다. 이 리팩터의 검증은 API 응답이 아니라 DB를 봐야 합니다.

## 적용 순서

```
1. User.email 유니크 제약        (01 P1-1 / 03 P1-2)  ← 데이터 정합성의 전제
2. AuthService  트랜잭션 + DataIntegrityViolationException 변환
3. RoomService  join() 통합 + 클래스 readOnly + 쓰기 4곳
4. WindowService 클래스 readOnly + 쓰기 4곳
5. 불필요한 save() 정리          (03 P2-4)
6. AuthService에서 HttpServletResponse 제거  (03 P2-2 / 06)
```

3·4번 이후 각 쓰기 API를 한 번씩 호출하고 **DB에서** 반영 여부를 확인하세요. 실패 모드가 조용합니다.
