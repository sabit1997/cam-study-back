# 08. 동시성

조사 범위: 공유 상태, 스케줄러 중복 실행, 카운터·타이머류 갱신의 경쟁 조건.

## grep으로 확정한 사실

| 확인 항목 | 결과 |
|---|---|
| `@Version` (낙관적 잠금) | **0건** |
| `@Lock` / `LockModeType` / `PESSIMISTIC` / `FOR UPDATE` | **0건** |
| `synchronized` / `ReentrantLock` / `volatile` | **0건** |
| `AtomicInteger` / `AtomicLong` / `ConcurrentHashMap` | **0건** |
| `static` 가변 필드 | **0건** (매칭된 4건은 모두 `static` **메서드**) |
| `@Scheduled` / `@Async` / `@EnableScheduling` / `@EnableAsync` | **0건** |
| WebSocket 세션 이벤트 리스너 (`SessionDisconnectEvent` 등) | **0건** |
| `isolation` 명시 | **0건** → MySQL 기본 REPEATABLE READ |

즉 **동시성 제어 장치가 프로젝트에 하나도 없습니다.** 그리고 공유 가변 상태는 전부 DB에 있습니다(JVM 메모리에는 없음) — 이건 좋은 출발점입니다. 문제는 DB 상태를 갱신하는 방식입니다.

## 스케줄러 중복 실행: 해당 없음

`@Scheduled`가 0건이므로 **현재 스케줄러 중복 실행 문제는 존재하지 않습니다.** 다만 아래 P1-4를 해결하려면 정리 작업이 필요하고, 그때 다중 인스턴스 중복 실행을 고려해야 합니다. P2-4에서 다룹니다.

---

## 먼저: `@Transactional`은 경쟁 조건을 막지 않습니다

이 카테고리의 절반이 같은 오해에서 나옵니다. `03-transaction.md` P1-2에서 한 번 짚었는데, 여기서 다시 정확히 정리해 둡니다.

MySQL InnoDB의 기본 격리 수준은 **REPEATABLE READ**입니다. 이 수준에서 평범한 `SELECT`는 **잠금을 걸지 않는 일관된 읽기(consistent read)** 입니다. MVCC 스냅샷을 읽고 아무 락도 남기지 않습니다.

따라서 이런 코드는 트랜잭션 안에 있어도 안전하지 않습니다.

```java
@Transactional
void increment(Long id) {
    Entity e = repo.findById(id).get();     // 잠금 없음
    e.setCount(e.getCount() + 1);           // 자바 메모리에서 계산
    // 커밋 시: UPDATE ... SET count = <계산된 절대값> WHERE id = ?
}
```

핵심은 **UPDATE의 SET 값이 이미 자바에서 계산되어 있다**는 점입니다. UPDATE 자체는 행 잠금을 걸지만, 그 락은 "내가 쓸 차례를 기다린다"만 보장합니다. 쓰려는 값이 낡은 읽기에서 나왔다는 사실은 바꾸지 못합니다.

프론트 비유로는 `setState(count + 1)`과 `setState(prev => prev + 1)`의 차이와 같습니다. 전자는 렌더 시점의 낡은 `count`를 캡처합니다.

안전하게 만드는 방법은 세 가지뿐입니다.

| 방법 | 어떻게 | 언제 쓰나 |
|---|---|---|
| **원자적 UPDATE** | `SET count = count + 1` — 읽기와 쓰기를 DB가 락 안에서 함께 수행 | 단순 누적. 가장 저렴함 |
| **비관적 잠금** | `SELECT ... FOR UPDATE` (`@Lock(PESSIMISTIC_WRITE)`) — 읽는 순간 락 | 읽은 값으로 복잡한 판단을 해야 할 때 |
| **낙관적 잠금** | `@Version` — 커밋 시 버전이 바뀌었으면 예외, 재시도 | 충돌이 드물고 재시도가 가능할 때 |

아래 findings는 이 세 가지 중 무엇이 필요한지를 각각 판단한 것입니다.

---

## P1-1. 공부 시간 누적이 read-modify-write — 동시 요청 시 기록이 조용히 사라짐

**위치** `service/TimerService.java:57-67`

```java
            Timer entry = repo.findByUserIdAndDate(userId, date)      // ① 잠금 없는 읽기
                    .orElseGet(() -> { ... });

            entry.setTotalSeconds(entry.getTotalSeconds() + secs);    // ② 자바에서 덧셈
            repo.save(entry);                                          // ③ 절대값으로 UPDATE
```

`record`에 `@Transactional`(`:28`)이 붙어 있지만, 위에서 설명한 이유로 보호되지 않습니다.

### 왜 문제인가

`POST /timer` 두 건이 같은 사용자·같은 날짜에 겹치면 이렇게 됩니다.

| 시각 | 요청 A (10분 기록) | 요청 B (5분 기록) | DB `total_seconds` |
|---|---|---|---|
| t1 | `SELECT` → 3600 | | 3600 |
| t2 | | `SELECT` → 3600 | 3600 |
| t3 | 자바: 3600 + 600 = **4200** | | 3600 |
| t4 | | 자바: 3600 + 300 = **3900** | 3600 |
| t5 | `UPDATE SET total=4200` (행 락 획득) | | 4200 |
| t6 | `COMMIT` (락 해제) | | 4200 |
| t7 | | `UPDATE SET total=3900` (락 획득) | **3900** |
| t8 | | `COMMIT` | **3900** |

최종값 3900. **요청 A의 10분이 사라졌습니다.** 5분만 기록되고, 아무 오류도 나지 않습니다.

t7에서 B의 UPDATE는 A가 커밋한 4200을 실제로 봅니다(UPDATE는 현재 읽기이므로). 그런데 SET 절의 `3900`은 t2의 낡은 읽기에서 이미 계산된 값이라 그대로 덮어씁니다. 이게 **lost update**입니다.

**트리거가 현실적입니다.** 이건 공부 시간 기록 앱입니다.

- 브라우저 탭 두 개에서 각각 타이머를 돌리는 경우
- 데스크톱과 모바일에서 동시에 사용
- 네트워크 오류 후 클라이언트가 재시도하면서 이전 요청과 겹치는 경우
- 페이지를 닫을 때 `beforeunload`에서 보내는 기록과, 다음 접속 시 보내는 기록이 겹치는 경우

그리고 **손실이 눈에 잘 안 띕니다.** 사용자는 "어제 3시간 공부했는데 2시간으로 나온다"는 것을 나중에야 알아차리고, 서버에는 아무 기록도 없습니다.

`record`가 while 루프로 날짜별 조각을 여러 번 저장하므로(`:41-71`), 자정을 넘는 기록 하나가 여러 날짜 행을 건드립니다 — 경쟁 구간이 그만큼 넓습니다.

### 수정 방향

**원자적 UPDATE가 정답입니다.** 이 연산은 "현재 값에 더한다"뿐이므로 DB가 락 안에서 처리할 수 있습니다.

```diff
--- a/src/main/java/com/camstudy/backend/repository/TimerRepository.java
+++ b/src/main/java/com/camstudy/backend/repository/TimerRepository.java
+    /** 원자적 누적. 대상 행이 없으면 0을 반환한다 */
+    @Modifying(clearAutomatically = true, flushAutomatically = true)
+    @Query("update Timer t set t.totalSeconds = t.totalSeconds + :secs " +
+           "where t.userId = :userId and t.date = :date")
+    int addSeconds(@Param("userId") String userId,
+                   @Param("date") LocalDate date,
+                   @Param("secs") long secs);
```

```diff
--- a/src/main/java/com/camstudy/backend/service/TimerService.java
+++ b/src/main/java/com/camstudy/backend/service/TimerService.java
-            Timer entry = repo.findByUserIdAndDate(userId, date)
-                    .orElseGet(() -> {
-                        Timer t = new Timer();
-                        t.setUserId(userId);
-                        t.setDate(date);
-                        t.setTotalSeconds(0L);
-                        return t;
-                    });
-            entry.setTotalSeconds(entry.getTotalSeconds() + secs);
-            repo.save(entry);
+            if (repo.addSeconds(userId, date, secs) == 0) {
+                // 그 날짜 행이 아직 없다 → 만들고 다시 시도
+                createRowIfAbsent(userId, date);
+                repo.addSeconds(userId, date, secs);
+            }
```

`addSeconds`가 발행하는 SQL은 `UPDATE timer SET total_seconds = total_seconds + ? WHERE user_id = ? AND date = ?`입니다. InnoDB가 해당 행에 배타 락을 걸고 **락 안에서 읽고 더하고 씁니다.** 두 요청이 겹치면 하나가 기다리고, 둘 다 반영됩니다.

`@Modifying(clearAutomatically = true)`가 필요한 이유: 벌크 UPDATE는 영속성 컨텍스트를 우회하므로, 같은 트랜잭션에서 그 엔티티를 다시 읽으면 낡은 값이 나옵니다. `clearAutomatically`가 컨텍스트를 비워 다음 조회가 DB를 보게 합니다. `flushAutomatically = true`는 반대로, 대기 중인 변경을 UPDATE 전에 먼저 flush합니다.

**행 생성 부분은 P1-2에서 함께 다룹니다** — 그 자체가 별도 경쟁 조건입니다.

`Timer.addDuration(long)`(`entity/Timer.java:29-31`)이라는 미사용 메서드가 있는데(`01-jpa-query.md`에서 확인), 그것도 같은 read-modify-write 방식입니다. 삭제하세요 — 남겨두면 나중에 누가 씁니다.

---

## P1-2. 같은 사용자·날짜의 `Timer` 행을 동시에 만들려 하면 500, 또는 영구 고장

**위치** `service/TimerService.java:57-64`, `:175-184` / `entity/Timer.java:7-10`

```java
// record:57-64 와 updatePomoCycles:175-182 가 같은 패턴
            Timer entry = repo.findByUserIdAndDate(userId, date)
                    .orElseGet(() -> {                 // 없으면 새로 만든다
                        Timer t = new Timer();
                        t.setUserId(userId);
                        t.setDate(date);
                        t.setTotalSeconds(0L);
                        return t;
                    });
            ...
            repo.save(entry);                          // id == null → INSERT
```

```java
// entity/Timer.java:7-10
@Table(
    name = "timer",
    uniqueConstraints = @UniqueConstraint(columnNames = {"userId", "date"})
)
```

### 왜 문제인가

`03-transaction.md` P1-2에서 다룬 check-then-act와 같은 구조입니다. "없으면 만든다"는 애플리케이션 코드로 안전하게 만들 수 없습니다 — **존재하지 않는 행에는 걸 락이 없기** 때문입니다.

하루의 첫 기록 두 건이 동시에 들어오면:

1. A: `SELECT` → 결과 없음
2. B: `SELECT` → 결과 없음
3. A: `INSERT` → 성공
4. B: `INSERT` → ?

4번의 결과가 유니크 인덱스 존재 여부에 따라 갈립니다. 그리고 **그 존재 여부가 아직 확인되지 않았습니다** (`01-jpa-query.md` P1-3의 `[[확인필요]]`).

**갈래 A — `(user_id, date)` 유니크 인덱스가 있는 경우**

B의 INSERT가 중복 키로 실패 → `DataIntegrityViolationException` → `04-exception.md` P0-1의 핸들러가 `RuntimeException`으로 잡아 **500 + SQL 전문 노출**. 클라이언트는 그 기록을 잃습니다. 재시도하면 이제 행이 존재하므로 성공합니다. **일시적 실패**입니다.

**갈래 B — 유니크 인덱스가 없는 경우 (더 나쁩니다)**

두 INSERT가 모두 성공해 **같은 `(user_id, date)`에 행이 2개** 생깁니다. 그 이후:

- `findByUserIdAndDate`가 `Optional<Timer>`를 반환하는데 결과가 2건 → Spring Data가 `IncorrectResultSizeDataAccessException`을 던집니다
- 이 예외는 `RuntimeException` 하위 → 500
- **그 사용자의 그 날짜에 대한 모든 API가 영구히 500이 됩니다** — `POST /timer`, `GET /timer/today`, `GET /timer/analytics`, `PATCH /timer/today/cycles`, `DELETE /timer/day/{date}` 전부
- 재시도로 복구되지 않습니다. DB에서 중복 행을 수동으로 지워야 합니다

`GET /timer/analytics`는 `findByUserIdAndDate`를 호출하므로(`:97`) 그 사용자는 **분석 화면 자체를 열 수 없게 됩니다.**

즉 **유니크 인덱스의 존재 여부가 "일시적 오류"와 "영구 고장"을 가릅니다.** 그래서 `01-jpa-query.md` P1-3의 확인 항목이 중요합니다.

```sql
SHOW INDEX FROM timer;
-- (user_id, date) 복합 유니크 인덱스가 있는지 확인

-- 이미 중복이 생겼는지도 함께 확인
SELECT user_id, date, COUNT(*) FROM timer
GROUP BY user_id, date HAVING COUNT(*) > 1;
```

### 수정 방향

**1단계 — 유니크 제약을 확실히 만듭니다.**

`@UniqueConstraint(columnNames = {"userId", "date"})`의 `columnNames`는 **논리 컬럼명**이고, 실제 물리 컬럼은 네이밍 전략을 거쳐 `user_id`가 됩니다. 이 변환이 제약에도 적용되는지가 확실하지 않으므로, `@Column`으로 물리명을 명시하세요.

```diff
--- a/src/main/java/com/camstudy/backend/entity/Timer.java
+++ b/src/main/java/com/camstudy/backend/entity/Timer.java
 @Table(
     name = "timer",
-    uniqueConstraints = @UniqueConstraint(columnNames = {"userId", "date"})
+    uniqueConstraints = @UniqueConstraint(
+        name = "uq_timer_user_date", columnNames = {"user_id", "date"})
 )
 public class Timer {
 ...
-    private String userId;
+    @Column(name = "user_id", nullable = false)
+    private String userId;
 
-    private LocalDate date;
+    @Column(name = "date", nullable = false)
+    private LocalDate date;
```

중복 행이 이미 있으면 제약 생성이 실패합니다. 위 쿼리로 먼저 확인하고, 있으면 합산해서 하나로 정리하세요.

**2단계 — 경쟁에서 진 쪽이 실패하지 않게 합니다.**

유니크 제약을 최후 방어선으로 두고, 애플리케이션은 중복 키 예외를 잡아 재시도합니다.

```java
    /** 행이 없으면 만든다. 경쟁에서 지면 상대가 만든 것을 쓴다 */
    private void createRowIfAbsent(String userId, LocalDate date) {
        try {
            Timer t = new Timer();
            t.setUserId(userId);
            t.setDate(date);
            t.setTotalSeconds(0L);
            repo.saveAndFlush(t);                    // 여기서 즉시 INSERT
        } catch (DataIntegrityViolationException e) {
            // 동시 요청이 먼저 만들었다 → 정상 경로
        }
    }
```

`saveAndFlush`가 중요합니다 — `save`만 하면 INSERT가 커밋 시점에 나가므로 예외를 여기서 잡을 수 없습니다.

**주의**: `DataIntegrityViolationException`이 발생한 트랜잭션은 MySQL에서 계속 사용할 수 있지만, Hibernate의 영속성 컨텍스트는 예외 이후 신뢰할 수 없는 상태가 됩니다. 안전하게 하려면 행 생성을 별도 트랜잭션으로 분리하세요.

```java
@Service
@RequiredArgsConstructor
public class TimerRowInitializer {
    private final TimerRepository repo;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createIfAbsent(String userId, LocalDate date) { ... }
}
```

`REQUIRES_NEW`가 별도 트랜잭션을 열므로, 실패해도 호출한 쪽 트랜잭션이 오염되지 않습니다. **별도 빈으로 분리해야 합니다** — 같은 클래스 안에서 호출하면 self-invocation으로 프록시를 거치지 않아 `REQUIRES_NEW`가 무시됩니다(`03-transaction.md` P2-1).

**더 단순한 대안**: MySQL의 `INSERT ... ON DUPLICATE KEY UPDATE`를 네이티브 쿼리로 쓰면 조회·생성·누적이 한 문장으로 원자화됩니다.

```java
@Modifying(clearAutomatically = true)
@Query(value = """
    INSERT INTO timer (user_id, date, total_seconds, pomo_cycles)
    VALUES (:userId, :date, :secs, 0)
    ON DUPLICATE KEY UPDATE total_seconds = total_seconds + :secs
    """, nativeQuery = true)
void upsertAddSeconds(@Param("userId") String userId,
                      @Param("date") LocalDate date,
                      @Param("secs") long secs);
```

P1-1과 P1-2를 한 번에 해결합니다. 대가는 MySQL 전용 SQL이 되는 것 — 이 프로젝트는 MySQL에 고정되어 있으므로(`build.gradle:32`) 실용적인 선택입니다. 이 방식은 **유니크 인덱스가 반드시 있어야** 동작하므로 1단계가 선행 조건입니다.

---

## P1-3. 정원 검사와 입장이 원자적이지 않음 — 정원을 초과해 입장 가능

**위치** `service/RoomService.java:56-61`, `:64-77` / `controller/RoomController.java:63-72`

```java
    public void ensureCapacity(Room room) {
        long active = memberRepo.countByRoomAndActive(room, true);   // ① COUNT
        if (active >= room.getCapacity()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "room is full");
        }
    }
```

```java
    public Member upsertActiveMember(Room room, String userId) {
        var memOpt = memberRepo.findByRoomAndUserId(room, userId);
        ...
        return memberRepo.save(member);                              // ② INSERT/UPDATE
    }
```

`03-transaction.md` P1-1에서 트랜잭션 경계 관점으로 다뤘습니다. 여기서는 **트랜잭션을 하나로 합쳐도 해결되지 않는다**는 점이 요점입니다.

### 왜 문제인가

정원 6명, 현재 5명인 방에 10명이 동시에 입장 요청을 보내면:

1. 10개 요청 모두 ①에서 `COUNT` → 전부 **5** 를 봅니다
2. `5 >= 6`이 거짓이므로 전부 통과
3. 10개 요청 모두 ②에서 `Member`를 INSERT
4. 결과: **15명이 활성 상태**

`COUNT`는 잠금 없는 읽기이므로 다른 트랜잭션의 INSERT를 막지 못합니다. 그리고 SERIALIZABLE로 격리 수준을 올려도 완전하지 않습니다 — 갭 락이 개입하지만 대가가 크고 데드락 가능성이 생깁니다.

**"정원"처럼 여러 행을 세어서 판단하는 불변식은 그 행들만으로는 지킬 수 없습니다.** 세는 대상이 "아직 없는 행"이기 때문입니다(P1-2와 같은 이유).

부수적으로, `03-transaction.md` P1-1과 `01-jpa-query.md` P1-10에서 다룬 대로 `(room_id, user_id)` 유니크 제약이 없어서 **같은 사용자가 중복 INSERT되어 인원 수를 부풀릴 수도** 있습니다.

### 수정 방향

**세는 대상이 아니라 "정원을 관리하는 주체"에 락을 거는 것**이 정석입니다. 여기서는 `Room` 행입니다.

```diff
--- a/src/main/java/com/camstudy/backend/repository/RoomRepository.java
+++ b/src/main/java/com/camstudy/backend/repository/RoomRepository.java
-public interface RoomRepository extends JpaRepository<Room, String> { }
+public interface RoomRepository extends JpaRepository<Room, String> {
+
+    /** 입장 처리용. 같은 방에 대한 동시 입장을 직렬화한다 */
+    @Lock(LockModeType.PESSIMISTIC_WRITE)
+    @Query("select r from Room r where r.id = :id")
+    Optional<Room> findByIdForUpdate(@Param("id") String id);
+}
```

```diff
--- a/src/main/java/com/camstudy/backend/service/RoomService.java
+++ b/src/main/java/com/camstudy/backend/service/RoomService.java
+    @Transactional
+    public Member join(String roomId, String userId, String rawPassword) {
+        // SELECT ... FOR UPDATE — 이 방에 대한 다른 입장 요청은 여기서 대기
+        Room room = roomRepo.findByIdForUpdate(roomId)
+                .orElseThrow(() -> new NotFoundException("room not found"));
+        verifyPassword(room, rawPassword);
+        ensureCapacity(room);          // 이제 COUNT 결과가 커밋까지 유효
+        return upsertActiveMember(room, userId);
+    }
```

`@Lock(PESSIMISTIC_WRITE)`는 `SELECT ... FOR UPDATE`를 발행합니다. 그 방의 행에 배타 락이 걸리므로, **같은 방에 대한 입장 요청은 한 번에 하나씩 처리됩니다.** COUNT를 한 뒤 INSERT할 때까지 다른 요청이 끼어들 수 없습니다.

비용: 같은 방에 대한 입장이 직렬화됩니다. 방 입장은 자주 일어나지 않고 처리도 짧으므로 실무상 문제되지 않습니다. **다른 방끼리는 서로 다른 행이라 병렬로 처리됩니다.**

주의할 점 두 가지:

- **락 획득에 타임아웃을 두세요.** MySQL `innodb_lock_wait_timeout` 기본값은 50초입니다. `02-performance.md` P1-3에서 커넥션 `connection-timeout`을 3초로 낮추자고 했는데, 락 대기가 50초면 커넥션을 그만큼 붙잡습니다. `@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))`로 제한할 수 있습니다.
- **비밀번호 검증을 락 안에서 하면 락 보유 시간이 늘어납니다.** `verifyPassword`는 Argon2 검증(메모리 하드, `05-security.md` P1-3)입니다. 락을 잡기 **전에** 검증하도록 순서를 바꾸는 편이 낫습니다.

```diff
+    @Transactional
+    public Member join(String roomId, String userId, String rawPassword) {
+        Room preview = getOrThrow(roomId);
+        verifyPassword(preview, rawPassword);        // 락 밖에서 (느린 연산)
+
+        Room room = roomRepo.findByIdForUpdate(roomId).orElseThrow(...);
+        ensureCapacity(room);                        // 락 안에서 (짧은 연산)
+        return upsertActiveMember(room, userId);
+    }
```

그리고 `(room_id, user_id)` 유니크 제약(`01-jpa-query.md` P1-10)을 함께 걸어 중복 멤버를 DB에서 막으세요. 락이 실패해도 그게 최후 방어선입니다.

---

## P1-4. WebSocket이 끊겨도 `Member.active`가 정리되지 않음 — 방이 영구히 "꽉 찬" 상태가 됨

**위치** `service/RoomService.java:64-77`, `:95-100` / `config/WsConfig.java` / `entity/Member.java:26`

`Member.active`가 `false`로 바뀌는 경로는 **딱 두 개**입니다.

| 경로 | 위치 |
|---|---|
| `POST /rooms/{roomId}/leave` | `RoomController.java:76-80` |
| `POST /rooms/{roomId}/admin/kick` | `RoomAdminController.java:18-23` |

둘 다 **클라이언트가 명시적으로 호출해야** 합니다. 그리고 grep 결과 `SessionDisconnectEvent` 리스너, `configureClientInboundChannel`, 하트비트 처리, 정리 스케줄러가 **전부 0건**입니다.

### 왜 문제인가

사용자가 다음처럼 나가면 `leave`가 호출되지 않습니다.

- 브라우저/탭을 닫음
- 네트워크 끊김 (지하철, 와이파이 전환)
- 브라우저 크래시, 기기 절전
- `beforeunload`에서 보낸 요청이 유실 (브라우저가 페이지 종료 시 진행 중인 fetch를 취소함 — `navigator.sendBeacon`이 아니면 보장 안 됨)

이 경우 `members` 행의 `active`가 **영원히 `true`로 남습니다.**

그러면 `ensureCapacity`(`:57`)의 `countByRoomAndActive(room, true)`가 **실제로 접속해 있지 않은 사람까지 셉니다.** 결과:

1. 정원 6명 방에 6명이 들어왔다 나가면(정상 종료 없이) `active=true` 행이 6개 남습니다
2. 그 방은 **아무도 없는데 "room is full"** 이 됩니다
3. 복구 경로가 없습니다 — `leave`는 본인만 호출할 수 있고, `kick`은 방장이 각 사용자 id를 알아야 합니다
4. `GET /rooms`의 `memberCount`(`RoomController.java:47-51`)도 실제와 다른 값을 보여줍니다

**이건 경쟁 조건이 아니라 확정적으로 발생하는 상태 누수입니다.** 시간이 지나면 모든 방이 이 상태가 됩니다.

`[[확인필요: 운영 DB의 members 테이블에 active=true 행이 몇 건이고, joined_at이 오래된 것이 있는지]]`
```sql
SELECT room_id, COUNT(*) AS active_cnt, MIN(joined_at) AS oldest
FROM members WHERE active = 1
GROUP BY room_id ORDER BY active_cnt DESC;
```
`oldest`가 며칠 전인 행이 있으면 이미 누수가 진행 중입니다.

### 수정 방향

세 가지를 조합해야 합니다. 하나만으로는 부족합니다.

**(a) WebSocket 종료 이벤트로 즉시 정리**

STOMP 세션이 끊기면 Spring이 `SessionDisconnectEvent`를 발행합니다. 그 세션이 어느 방의 누구였는지 알아야 하므로, 구독 시점에 세션 속성에 기록합니다.

```java
@Component
@RequiredArgsConstructor
public class WsSessionRegistry {

    private final RoomService roomService;

    @EventListener
    public void onSubscribe(SessionSubscribeEvent e) {
        StompHeaderAccessor a = StompHeaderAccessor.wrap(e.getMessage());
        String roomId = extractRoomId(a.getDestination());
        if (roomId != null && a.getUser() != null) {
            a.getSessionAttributes().put("roomId", roomId);
            a.getSessionAttributes().put("userId", a.getUser().getName());
        }
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent e) {
        StompHeaderAccessor a = StompHeaderAccessor.wrap(e.getMessage());
        Map<String, Object> attrs = a.getSessionAttributes();
        if (attrs == null) return;
        String roomId = (String) attrs.get("roomId");
        String userId = (String) attrs.get("userId");
        if (roomId != null && userId != null) {
            roomService.leave(roomId, userId);
        }
    }
}
```

**이것만으로는 부족한 이유**: `SessionDisconnectEvent`는 그 사용자가 붙어 있던 **인스턴스에서만** 발생합니다. 그리고 서버가 죽으면 이벤트가 아예 발생하지 않습니다. 네트워크가 조용히 끊긴 경우도 TCP 타임아웃까지 감지되지 않습니다.

**(b) 하트비트 + `lastSeenAt`**

`Member`에 마지막 확인 시각을 두고, 클라이언트가 주기적으로 갱신합니다. STOMP는 하트비트 프레임을 지원하므로 별도 API 없이 처리할 수도 있습니다.

```diff
--- a/src/main/java/com/camstudy/backend/entity/Member.java
+++ b/src/main/java/com/camstudy/backend/entity/Member.java
     private Instant joinedAt;
+
+    @Column(name = "last_seen_at")
+    private Instant lastSeenAt;
```

**(c) 정리 스케줄러**

일정 시간 하트비트가 없는 멤버를 비활성화합니다.

```java
@Scheduled(fixedDelay = 60_000)
@Transactional
public void deactivateStaleMembers() {
    int n = memberRepo.deactivateNotSeenSince(Instant.now().minus(Duration.ofMinutes(3)));
    if (n > 0) log.info("deactivated {} stale members", n);
}
```

```java
@Modifying
@Query("update Member m set m.active = false " +
       "where m.active = true and (m.lastSeenAt is null or m.lastSeenAt < :threshold)")
int deactivateNotSeenSince(@Param("threshold") Instant threshold);
```

이 UPDATE는 벌크 연산이라 원자적입니다 — 개별 조회 없이 조건에 맞는 행을 DB가 한 번에 바꿉니다.

**(c)를 도입하면 스케줄러 중복 실행 문제가 처음으로 생깁니다** → P2-4.

**즉시 할 수 있는 임시 조치**: `Instant.now()` 기준으로 오래된 `active=true` 행을 수동으로 정리하는 SQL을 한 번 돌리면 현재 막힌 방들이 풀립니다. 근본 해결 전에 서비스를 살리는 수단입니다.

```sql
-- joined_at이 하루 이상 지난 활성 멤버 비활성화 (하트비트 도입 전 임시)
UPDATE members SET active = 0
WHERE active = 1 AND joined_at < NOW() - INTERVAL 1 DAY;
```

---

## P1-5. 방장 지정이 동시에 일어나면 HOST가 2명이 됨

**위치** `service/RoomService.java:71-75`

```java
        if (room.getOwnerId() == null) {       // ① 읽기 (잠금 없음)
            room.setOwnerId(userId);
            roomRepo.save(room);               // ② UPDATE rooms
            member.setRole(Role.HOST);         // ③ 이 멤버에게 HOST 부여
        }
```

### 왜 문제인가

`ownerId`가 아직 없는 방에 두 사용자가 동시에 입장하면:

| | 요청 A (userX) | 요청 B (userY) |
|---|---|---|
| ① | `ownerId == null` → 참 | `ownerId == null` → 참 |
| ③ | `memberX.role = HOST` | `memberY.role = HOST` |
| ② | `UPDATE rooms SET owner_id = 'X'` | `UPDATE rooms SET owner_id = 'Y'` |

결과:
- `members`에 **HOST가 2명**
- `rooms.owner_id`는 나중에 커밋한 쪽(Y) — lost update
- `JoinRes.role`(`RoomController.java:70`)이 **둘 모두에게 `"HOST"`를 반환**하므로 두 클라이언트가 방장 UI를 표시합니다

`05-security.md` P0-1의 `kick` 수정안이 `room.getOwnerId()`를 기준으로 하므로 실제 강퇴 권한은 Y만 갖게 되지만, X의 화면에는 방장 버튼이 보이고 누르면 403이 납니다. 나중에 `Member.role` 기준으로 바꾸면 **두 명이 강퇴 권한을 갖습니다.**

`ownerId`가 `null`일 수 있는 이유는 `CreateRoomReq.ownerId`(`dto/room/CreateRoomReq.java:7`)를 클라이언트가 보내고 `RoomService.create:34`가 그대로 저장하며, `Room.prePersist`(`entity/Room.java:30-35`)가 기본값을 채우지 않기 때문입니다.

### 수정 방향

**P1-3의 비관적 잠금이 이 문제도 함께 해결합니다.** `SELECT ... FOR UPDATE`로 방 행을 잡으면 ①~③이 직렬화되므로, 두 번째 요청은 `ownerId != null`을 보게 됩니다. **별도 수정이 필요하지 않습니다.**

그리고 `05-security.md` P0-1의 수정(`ownerId`를 body에서 제거하고 인증 주체로 채움)을 적용하면 **`ownerId`가 `null`인 방이 애초에 만들어지지 않습니다.** 그러면 `if (room.getOwnerId() == null)` 분기 자체가 죽은 코드가 됩니다.

```diff
--- a/src/main/java/com/camstudy/backend/service/RoomService.java
+++ b/src/main/java/com/camstudy/backend/service/RoomService.java
     public Room create(String name, Integer capacity, String rawPassword, String ownerId) {
+        Objects.requireNonNull(ownerId, "ownerId is required");
         String hash = (rawPassword == null || rawPassword.isBlank()) ? null : passwordEncoder.encode(rawPassword);
```

`ownerId`를 필수로 만들고 나면, `upsertActiveMember`의 방장 지정 로직은 "방 생성자가 첫 입장 시 HOST"로 단순화할 수 있습니다.

```diff
-        if (room.getOwnerId() == null) {
-            room.setOwnerId(userId);
-            roomRepo.save(room);
-            member.setRole(Role.HOST);
-        }
+        member.setRole(userId.equals(room.getOwnerId()) ? Role.HOST : Role.MEMBER);
```

읽기만 하므로 경쟁 조건이 사라집니다.

---

## P2-1. `focusWindow`의 `max(zIndex) + 1`이 경쟁 조건

**위치** `service/WindowService.java:79-87`

```java
        List<Window> userWindows = windowRepository.findWindowsByUserIdOrdered(userEmail);  // ① 읽기
        int maxZIndex = userWindows.stream().mapToInt(Window::getZIndex).max().orElse(0);   // ② 자바 계산
        focusedWindow.setZIndex(maxZIndex + 1);                                             // ③ 쓰기
        windowRepository.save(focusedWindow);
```

P1-1과 같은 read-modify-write입니다. 다만 `WindowService`에는 `@Transactional`조차 없어서(`03-transaction.md` P1-3) ①과 ③이 **서로 다른 트랜잭션**입니다.

두 창을 거의 동시에 포커스하면 둘 다 `max = 5`를 읽고 둘 다 `6`을 씁니다. → **두 창의 `zIndex`가 같아져** 프론트의 z-order가 결정 불가능해집니다. 브라우저의 렌더 순서에 따라 매번 다르게 보일 수 있습니다.

### 왜 P2인가

같은 사용자가 자기 창을 동시에 두 개 포커스하는 상황은 실제로는 드뭅니다(빠른 연속 클릭, 탭 두 개). 그리고 증상이 데이터 손실이 아니라 표시 순서 혼란이라 복구가 쉽습니다(다시 클릭하면 정상화).

부수적으로 `zIndex`가 **단조 증가하고 압축되지 않습니다.** 포커스할 때마다 커집니다. `int`이므로 21억 번 후 오버플로인데 현실적이지 않지만, 값이 계속 커지는 설계는 그 자체로 정리할 여지가 있습니다.

### 수정 방향

`03-transaction.md` P1-3에서 제안한 `@Transactional` + Java 정렬로 바꾸면 ①②③이 한 트랜잭션에 들어갑니다. 그것만으로는 잠금 없는 읽기라 경쟁이 남지만, 창 목록 전체를 한 번에 재정렬하는 방식으로 바꾸면 근본적으로 해결됩니다.

```java
    @Transactional
    public List<Window> focusWindow(Long id, String userEmail) {
        List<Window> windows = windowRepository.findWindowsByUserIdOrdered(userEmail);
        Window target = windows.stream().filter(w -> w.getId().equals(id)).findFirst()
                .orElseThrow(() -> new NotFoundException("window not found"));

        // 포커스 대상을 맨 뒤로 보내고 0..n-1 로 재부여 — 값이 무한히 커지지 않는다
        windows.remove(target);
        windows.add(target);
        for (int i = 0; i < windows.size(); i++) {
            windows.get(i).setZIndex(i);
        }
        return windows;
    }
```

`zIndex`가 항상 `0..n-1`로 정규화되므로 값이 커지지 않고, 같은 값이 두 창에 들어가는 상황도 한 트랜잭션 안에서는 생기지 않습니다. 동시 요청이 겹치면 둘 중 하나의 재정렬이 덮어써지지만 **결과는 항상 유효한 순서**입니다(같은 zIndex가 생기지 않음).

완전한 직렬화가 필요하면 P1-3처럼 사용자 단위 락을 걸어야 하는데, 이 정도 증상에는 과합니다.

---

## P2-2. 낙관적 잠금이 없어 동시 수정 시 한쪽 변경이 조용히 사라짐

`@Version`이 **모든 엔티티에 0건**입니다.

**위치** `service/WindowService.java:36-56` (`partialUpdate`)

```java
        if (dto.getX() != null) window.setX(dto.getX());
        ...
        if (dto.getUrl() != null) window.setUrl(dto.getUrl());
        if (dto.getUrlTitles() != null) window.setUrlTitles(dto.getUrlTitles());
```

### 왜 문제인가

두 탭에서 같은 창을 편집하면 나중 요청이 이깁니다(last write wins). `x`/`y`/`width`/`height` 같은 값은 "마지막 드래그가 이긴다"가 자연스러운 동작이라 문제되지 않습니다.

**문제는 `url`과 `urlTitles`입니다.** 이 필드들은 `String[]`이고 **배열 전체를 통째로 교체**합니다(`01-jpa-query.md` P2-1). 부분 수정 API가 없으므로 클라이언트는 항상 전체 목록을 보냅니다.

- 탭 A: 재생목록 `[1,2,3]`을 읽고, 4를 추가해 `[1,2,3,4]`를 전송
- 탭 B: 같은 `[1,2,3]`을 읽고, 5를 추가해 `[1,2,3,5]`를 전송
- 결과: `[1,2,3,5]` — **탭 A가 추가한 4가 사라집니다**

P1-1과 같은 lost update인데, read-modify-write의 "read"가 클라이언트에서 일어납니다.

### 수정 방향

`@Version`을 추가하면 충돌을 **감지**할 수 있습니다.

```diff
--- a/src/main/java/com/camstudy/backend/entity/Window.java
+++ b/src/main/java/com/camstudy/backend/entity/Window.java
+    @Version
+    private Long version;
```

동작 원리: Hibernate가 UPDATE에 `WHERE id = ? AND version = ?`를 붙이고 `version = version + 1`로 올립니다. 그 사이 다른 트랜잭션이 커밋했으면 영향받은 행이 0건이 되고, Hibernate가 `OptimisticLockException`을 던집니다. **락을 잡지 않으므로 비용이 거의 없습니다.**

클라이언트는 응답에서 받은 `version`을 PATCH에 되돌려 보내고, 409를 받으면 최신 상태를 다시 읽어 병합합니다.

**부수 효과 두 가지:**

- `06-architecture.md` P0-1(`POST /windows`로 창 탈취)의 기본 경로가 막힙니다. `isNew()`가 `@Version` 필드를 먼저 보므로, `version`이 `null`인 요청은 `persist`로 가서 새 행이 만들어집니다(`merge`가 아님). **다만 클라이언트가 `version`까지 보내면 다시 `merge`로 갈 수 있으므로, 요청 DTO 도입을 대체하지 않습니다.**
- 컬럼 추가이므로 마이그레이션이 필요합니다. `ddl-auto: update`가 컬럼은 추가하지만, **기존 행의 `version`이 NULL**이 됩니다. Hibernate는 NULL 버전을 "분리된 신규 엔티티"로 오해할 수 있으므로 `UPDATE windows SET version = 0 WHERE version IS NULL`을 함께 실행해야 합니다.

지금 단계에서 우선순위는 낮습니다. `Window` 편집을 여러 탭에서 하는 사용 패턴이 실제로 있는지 확인한 뒤 결정하세요.
`[[확인필요: 프론트가 여러 탭/기기에서 같은 창을 동시에 편집할 수 있는 UX인지]]`

---

## P2-3. 뽀모도로 사이클을 클라이언트가 계산한 절대값으로 저장

**위치** `service/TimerService.java:173-185`, `controller/TimerController.java:79-87`

```java
    @Transactional
    public void updatePomoCycles(String userEmail, LocalDate date, int cycles) {
        Timer entry = repo.findByUserIdAndDate(userEmail, date).orElseGet(...);
        entry.setPomoCycles(cycles);        // ← 절대값 설정
        repo.save(entry);
    }
```

서버에서 read-modify-write를 하지 않으므로 P1-1 같은 lost update는 없습니다. 대신 **read-modify-write가 클라이언트로 옮겨간** 형태입니다.

- 탭 A가 사이클 3개를 완료해 `{"cycles": 3}` 전송 → 저장 3
- 탭 B는 자기 카운트가 1이라 `{"cycles": 1}` 전송 → 저장 **1** (감소)

서버는 값을 검증하지 않으므로(`07-api-design.md` P1-1의 13번 — 음수도 통과) **되돌아가는 것을 감지할 수 없습니다.**

### 수정 방향

두 가지 중 하나입니다.

(a) 증가 연산으로 바꿉니다. 클라이언트는 "사이클 하나 완료"만 알립니다.

```java
@Modifying(clearAutomatically = true)
@Query("update Timer t set t.pomoCycles = t.pomoCycles + 1 " +
       "where t.userId = :userId and t.date = :date")
int incrementPomoCycles(@Param("userId") String userId, @Param("date") LocalDate date);
```

```
POST /timers/{date}/pomo-cycles      ← "하나 완료"
```

원자적이고 감소가 불가능합니다. 다만 네트워크 재시도로 중복 증가할 수 있으므로, 필요하면 요청에 멱등 키를 두세요.

(b) 절대값을 유지하되 **감소를 거부**합니다.

```java
@Query("update Timer t set t.pomoCycles = :cycles " +
       "where t.userId = :userId and t.date = :date and t.pomoCycles < :cycles")
int raisePomoCycles(...);
```

`and t.pomoCycles < :cycles` 조건이 단조 증가를 보장합니다. 프론트 계약을 바꾸지 않으므로 변경 비용이 적습니다.

(b)를 권합니다 — 현재 API 형태를 유지하면서 문제만 제거합니다. 여기에 `07-api-design.md`의 `@Min(0)` 검증을 더하세요.

---

## P2-4. 스케줄러가 없는 것 자체는 문제 아님 — 단 P1-4를 고치면 중복 실행을 고려해야 함

`@Scheduled`, `@EnableScheduling`이 **0건**이므로 **현재 스케줄러 중복 실행 위험은 없습니다.**

그런데 P1-4를 해결하려면 정리 작업이 필요하고, 그 시점에 새 문제가 생깁니다.

### 왜 미리 알아야 하는가

`@Scheduled`는 **JVM 인스턴스마다 독립적으로 동작합니다.** 인스턴스를 2개 띄우면 같은 작업이 2번 실행됩니다.

지금은 `docker-compose.yml`이 app 컨테이너 1개만 띄우므로(`docker-compose.yml:25-41`) 문제되지 않습니다. 하지만:

- 무중단 배포를 하면 **구·신 인스턴스가 잠시 함께 뜹니다** → 그 구간에 중복 실행
- 스케일아웃하면 상시 중복

P1-4의 `deactivateStaleMembers`는 벌크 UPDATE이고 **멱등**하므로(같은 조건을 두 번 실행해도 결과가 같음) 중복 실행이 치명적이지 않습니다. 하지만 두 인스턴스가 같은 행에 UPDATE를 걸면 락 경합이 생기고, 나중에 "알림 발송" 같은 **멱등하지 않은** 작업을 추가하면 즉시 사고가 됩니다.

### 수정 방향

**멱등한 정리 작업이면 지금은 그대로 두세요.** 대신 로그에 인스턴스 식별자를 남겨 중복 실행을 관찰할 수 있게 하세요(`09-logging.md`).

멱등하지 않은 작업을 추가할 때는 분산 락을 씁니다. DB만 있으면 ShedLock이 가장 간단합니다.

```gradle
implementation 'net.javacrumbs.shedlock:shedlock-spring:5.16.0'
implementation 'net.javacrumbs.shedlock:shedlock-provider-jdbc-template:5.16.0'
```

```java
@Scheduled(fixedDelay = 60_000)
@SchedulerLock(name = "deactivateStaleMembers", lockAtMostFor = "50s")
public void deactivateStaleMembers() { ... }
```

전용 락 테이블에 행을 잡아, 인스턴스가 몇 개든 한 번만 실행됩니다.

**한 가지 더**: `@EnableScheduling`을 켜면 기본 스케줄러 스레드 풀이 **1개**입니다. 작업이 여러 개인데 하나가 오래 걸리면 나머지가 밀립니다. `spring.task.scheduling.pool.size`를 명시하세요.

---

## P2-5. 인메모리 브로커가 인스턴스별 상태 — 확장 시 채팅이 갈라짐

**위치** `config/WsConfig.java:19-24`

```java
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes("/app");
        registry.enableSimpleBroker("/topic", "/queue");     // ← 인메모리
    }
```

`enableSimpleBroker`는 구독 정보와 메시지 라우팅을 **그 JVM의 메모리에** 둡니다. 구독 레지스트리 자체는 내부적으로 스레드 안전하므로 단일 인스턴스에서는 동시성 문제가 없습니다.

문제는 **인스턴스가 늘어나는 순간**입니다.

- 사용자 X는 인스턴스 1에, Y는 인스턴스 2에 붙습니다
- X가 `/app/rooms/R/chat`으로 메시지를 보내면 인스턴스 1의 브로커만 그걸 알고, **Y는 받지 못합니다**
- 같은 방에 있는데 서로 대화가 안 됩니다

그리고 상태가 갈라집니다 — `Member.active`(정원, 인원 수)는 **DB에 있어 공유**되는데 채팅 연결은 **인스턴스 로컬**입니다. 그래서 "방에 6명 있다고 표시되는데 3명만 대화가 보인다" 같은 불일치가 생깁니다.

이건 지금 발생하는 버그가 아닙니다(인스턴스 1개). **확장 시점에 반드시 만나는 제약**이라 기록해 둡니다.

### 수정 방향

지금은 바꾸지 마세요. 확장이 필요해지면 두 선택지가 있습니다.

| 방법 | 내용 |
|---|---|
| 외부 브로커 | `enableStompBrokerRelay`로 RabbitMQ/ActiveMQ에 위임. 인스턴스 간 메시지가 브로커를 통해 전달됩니다 |
| 스티키 세션 | 로드밸런서가 같은 방 사용자를 같은 인스턴스로 보냄. 간단하지만 방 단위 라우팅이 필요하고 인스턴스 장애 시 방이 끊깁니다 |

`docker-compose.yml`에 이미 Redis 같은 것이 없으므로, 브로커 도입은 인프라 추가입니다. **확장이 실제 요구사항이 될 때 결정하세요.**

P1-4의 `SessionDisconnectEvent` 방식도 같은 제약을 받습니다 — 이벤트는 그 사용자가 붙은 인스턴스에서만 발생합니다. 그래서 P1-4의 (c) 스케줄러가 함께 필요합니다.

---

## 요약

| 심각도 | 개수 | 항목 |
|---|---|---|
| **P0** | 0 | — (단 `02-performance.md` P0-1의 무한 루프가 커넥션 풀 고갈로 전체 정지를 유발하며, 이는 동시성 자원 문제입니다) |
| **P1** | 5 | 공부 시간 누적이 read-modify-write → 기록 소실 / `Timer` 행 동시 생성 → 500 또는 **영구 고장** / 정원 검사 TOCTOU → 정원 초과 입장 / **WebSocket 끊김 시 `Member.active` 누수 → 방이 영구히 꽉 찬 상태** / 방장 동시 지정 → HOST 2명 |
| **P2** | 5 | `zIndex` `max+1` 경쟁 / 낙관적 잠금 부재로 재생목록 편집 손실 / 뽀모도로 절대값 저장으로 감소 허용 / 스케줄러 도입 시 중복 실행 대비 / 인메모리 브로커의 확장 제약 |

## 사용자가 물은 항목에 대한 직접 답

| 질문 | 답 |
|---|---|
| 공유 상태 | **JVM 메모리에 가변 공유 상태가 없습니다.** `static` 가변 필드 0건, 싱글턴 빈의 필드는 모두 `final` 의존성이거나 `static final` 불변 컬렉션입니다. 공유 상태는 전부 DB에 있고, 예외는 `enableSimpleBroker`의 인메모리 구독 정보(P2-5)입니다. |
| 스케줄러 중복 실행 | **`@Scheduled`가 0건이므로 현재 해당 없음.** 단 P1-4를 해결하려면 정리 잡이 필요하고, 그때 다중 인스턴스 중복 실행을 고려해야 합니다(P2-4). |
| 카운터·타이머류 갱신 | **가장 심각한 영역입니다.** `Timer.totalSeconds` 누적(P1-1)이 lost update로 공부 기록을 잃고, 행 생성(P1-2)은 500 또는 영구 고장을 냅니다. `zIndex`(P2-1)와 뽀모도로 사이클(P2-3)도 같은 계열입니다. `@Version`·`@Lock`·`Atomic*`이 전부 0건이라 방어 장치가 없습니다. |

## 확인이 필요한 항목 (3개)

1. **`SHOW INDEX FROM timer`** — `(user_id, date)` 유니크 인덱스 존재 여부. P1-2가 "일시적 500"인지 "영구 고장"인지가 여기서 갈립니다
2. **`SELECT user_id, date, COUNT(*) FROM timer GROUP BY user_id, date HAVING COUNT(*) > 1`** — 이미 중복 행이 생겼는지
3. **`SELECT room_id, COUNT(*), MIN(joined_at) FROM members WHERE active = 1 GROUP BY room_id`** — `Member.active` 누수가 진행 중인지 (`joined_at`이 오래된 활성 멤버)

## 적용 순서

```
1. Timer 유니크 인덱스 확인 + 확실히 생성 (P1-2 1단계)   ← 다른 수정의 전제
2. INSERT ... ON DUPLICATE KEY UPDATE 로 누적 원자화 (P1-1 + P1-2 동시 해결)
3. members 임시 정리 SQL 실행 (P1-4) — 막힌 방 즉시 복구
4. RoomService.join 에 비관적 잠금 (P1-3 + P1-5 동시 해결)
5. WebSocket 종료 이벤트 + lastSeenAt + 정리 스케줄러 (P1-4 근본 해결)
6. 뽀모도로 감소 거부 (P2-3) — 쿼리 조건 한 줄
7. zIndex 재정규화 (P2-1) — 03 P1-3의 @Transactional 작업과 함께
8. @Version (P2-2) — 필요성 확인 후
```

2번이 P1-1과 P1-2를 한 번에 해결하므로 비용 대비 효과가 가장 큽니다. 단 **1번이 선행 조건**입니다 — `ON DUPLICATE KEY`는 유니크 인덱스가 있어야 동작합니다.

부하 테스트는 하지 않았습니다. 위 인터리빙 시나리오는 소스의 문장 순서와 InnoDB의 REPEATABLE READ 동작(잠금 없는 일관된 읽기)에 근거한 것이며, 실제 재현은 동시 요청을 발생시켜 확인해야 합니다.
