# 01. JPA / 쿼리 (2단계)

조사 범위: `entity/` 8개, `repository/` 7개, `service/` 5개, `application.yml`의 JPA 설정.
grep으로 확인한 사실: 프로젝트 전체에 `@Index`, `@Table(indexes=...)`, `@Version`,
`@EntityGraph`, `join fetch`, `default_batch_fetch_size` **사용 0건**.

---

## P0-1. `ddl-auto: update`가 운영 스키마 관리 수단으로 쓰이고 있음 — 컬럼 타입 변경이 반영되지 않음

**위치** `src/main/resources/application.yml:11-17`
**연관** `src/main/java/com/camstudy/backend/entity/Window.java:28`

```yaml
  jpa:
    hibernate:
      ddl-auto: update
```

```java
// Window.java:28  — 커밋 5d0dfe9(2025-06-25)에서 String → String[] 로 변경됨
    private String[] url;
```

### 왜 문제인가

`ddl-auto: update`는 Hibernate가 부팅할 때 **엔티티 매핑과 실제 DB 스키마를 비교해서 "없는 것만 추가"** 하는 모드입니다. 구체적으로 하는 일과 안 하는 일이 명확히 갈립니다.

| Hibernate가 하는 일 | 하지 않는 일 |
|---|---|
| 없는 테이블 `CREATE TABLE` | 컬럼 **타입 변경** (`ALTER TABLE ... MODIFY`) |
| 없는 컬럼 `ADD COLUMN` | 컬럼/테이블 **삭제** |
| 없는 제약·인덱스 `ADD CONSTRAINT` | 컬럼 **이름 변경** |
| | nullable → not null 같은 **제약 강화** |

즉 "없으면 만든다"만 하고 "다르면 고친다"는 안 합니다. 프론트로 비유하면, 마이그레이션 없이 `localStorage`에 저장된 옛 스키마 객체를 새 코드가 그대로 읽는 상황과 같습니다 — 새로 추가한 키는 생기지만, 타입이 바뀐 키는 옛 값이 그대로 남아 있습니다.

이 레포에는 그 케이스가 **실제로 존재합니다**:

- 2025-04-17 ~ 2025-06-25 사이에는 `Window.url`이 `String`이었습니다 (`git log -- entity/Window.java`).
  → 그 시기에 부팅한 DB에는 `windows.url`이 `varchar(255)`로 만들어져 있습니다.
- 커밋 `5d0dfe9`에서 `String[]`으로 바뀌었습니다.
  → `ddl-auto: update`는 타입을 바꾸지 않으므로, **그 DB의 `windows.url`은 지금도 `varchar(255)`** 입니다.
  Hibernate는 `update` 모드에서 타입 검증(`validate`)을 하지 않으므로 부팅은 성공하고, 실제 읽기/쓰기 시점에 깨집니다.

추가로, `ddl-auto: update`를 운영에 켜두는 것 자체가 두 가지 위험을 만듭니다.

1. **부팅이 DDL을 실행합니다.** 애플리케이션 배포가 곧 스키마 변경입니다. 어떤 DDL이 나갈지 사전에 볼 수 없고, 롤백 스크립트도 없습니다. 엔티티에 필드 하나를 잘못 추가하고 배포하면 운영 테이블에 컬럼이 생기고, 코드를 되돌려도 컬럼은 남습니다.
2. **인스턴스를 2개 이상 띄우면 동시 DDL이 발생합니다.** MySQL DDL은 메타데이터 락을 잡으므로, 두 인스턴스가 같이 부팅하면 한쪽이 락 대기하거나 실패합니다.

`docker-compose.yml:41`의 `restart: always`와 조합되면, 실패한 컨테이너가 재시작을 반복하며 계속 DDL을 시도합니다.

### 수정 방향

`[[확인필요: 운영 DB의 windows.url 실제 컬럼 타입 — SHOW CREATE TABLE windows]]`
확인 전에는 아래 1단계만 먼저 적용하세요. `validate`로 바꾸면 스키마가 어긋나 있을 경우 **부팅이 실패하면서 어긋난 지점을 알려줍니다** — 지금은 그걸 모르고 돌고 있는 상태입니다.

```diff
 # application.yml  (1단계: 현상 파악)
   jpa:
     hibernate:
-      ddl-auto: update
+      ddl-auto: validate
```

2단계로 마이그레이션 도구를 붙이고, 스키마 변경을 커밋되는 SQL 파일로 관리합니다.

```diff
 # build.gradle
   implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
+  implementation 'org.flywaydb:flyway-mysql'
```
`src/main/resources/db/migration/V1__baseline.sql`에 현재 스키마를 덤프해 baseline으로 두고,
이후 변경은 `V2__...sql`로 추가합니다.

---

## P1-1. `users.email`에 인덱스도, 유니크 제약도 없음

**위치** `src/main/java/com/camstudy/backend/entity/User.java:15`
**조회 지점** `service/AuthService.java:25`, `:63` / `service/TimerService.java:91`, `:138`, `:160`, `:167`

```java
// User.java:15
    private String email;   // @Column 없음 → varchar(255), 인덱스 없음, unique 없음
```

```java
// UserRepository.java:9
    Optional<User> findByEmail(String email);
```

### 왜 문제인가

**(1) 조회 비용**

`findByEmail`은 `SELECT * FROM users WHERE email = ?`로 번역됩니다. `email` 컬럼에 인덱스가 없으면 InnoDB는 이 조건을 만족하는 행을 찾을 방법이 없어서 **클러스터드 인덱스(=PK 순서로 저장된 전체 행)를 처음부터 끝까지 읽고 한 행씩 비교**합니다. 이게 full table scan입니다.

인덱스가 있으면 B+트리를 타고 내려가 해당 값을 바로 찾습니다. 트리 높이만큼만 읽으므로, 행이 늘어나도 읽는 페이지 수가 거의 변하지 않습니다. 인덱스가 없으면 읽는 양이 행 수에 **비례해서** 늘어납니다.

문제는 호출 빈도입니다. `TimerService`는 `getTimerAnalytics`, `getTodayTime`, `getTimerGoal`, `updateTimerGoal` 네 메서드 모두에서 `findByEmail`을 호출합니다. 즉 `/timer/*` 계열 API를 부를 때마다 `users` 전체 스캔이 한 번씩 붙습니다.

**(2) 중복 이메일이 DB 레벨에서 막히지 않음**

`AuthService.signup:63`은 이렇게 생겼습니다.

```java
if (userRepository.findByEmail(email).isPresent()) {
    throw new RuntimeException("이미 존재하는 이메일입니다.");
}
```

"먼저 조회해서 없으면 저장"입니다. 조회와 저장 사이에 다른 요청이 같은 이메일로 저장할 수 있습니다(check-then-act). `AuthService`에는 `@Transactional`도 없어서 두 문장이 서로 다른 트랜잭션에서 실행됩니다. 유니크 제약이 없으면 DB가 마지막 방어선 역할을 못 하므로 **같은 이메일 유저가 2행 생깁니다.**

그러면 `findByEmail`이 `Optional`을 반환하는 이 코드가 깨집니다 — Spring Data는 결과가 2건이면 `IncorrectResultSizeDataAccessException`을 던집니다. 즉 그 이메일로는 **아무도 로그인할 수 없게 됩니다.** 그리고 `Window.userId`, `Timer.userId`가 이메일 문자열이므로(P1-2 참고) 두 유저의 데이터가 섞입니다.

### 수정 방향

```diff
 // entity/User.java
-    private String email;
+    @Column(nullable = false, unique = true, length = 255)
+    private String email;
```

`unique = true`면 `ddl-auto`가 유니크 인덱스를 만들고, 그 인덱스가 조회 인덱스 역할까지 겸합니다((1)과 (2)를 한 번에 해결). 단 **이미 중복 행이 있으면 인덱스 생성이 실패**하므로, 적용 전에 중복을 확인하세요.

```sql
SELECT email, COUNT(*) FROM users GROUP BY email HAVING COUNT(*) > 1;
```

그리고 조회 중복 자체를 줄이는 건 별건입니다 → `02-performance.md`.

---

## P1-2. 사용자 식별자로 이메일 문자열을 저장하고 FK가 없음

**위치** `entity/Window.java:42-43`, `entity/Timer.java:16`, `entity/Member.java:21`

```java
// Window.java:42-43
    @Column(name = "user_id")
    private String userId;      // 실제로 들어가는 값은 이메일

// Timer.java:16
    private String userId;      // 동일
```

`User` 엔티티(`entity/User.java:11-13`)는 `Long id`를 PK로 가지고 있는데, 아무도 그 값을 쓰지 않습니다. 세 테이블 모두 `User`와 **연관관계 매핑이 아예 없습니다** (`@ManyToOne` 없음 → FK 제약 없음).

### 왜 문제인가

**(1) 참조 정합성이 없습니다.** `users` 행을 지워도 `windows`/`timer`/`todo_items`는 그대로 남습니다. DB가 "이 user_id는 존재하는 유저인가"를 검사할 수단이 없습니다. FK 제약이 있으면 DB가 그걸 보장하고, 없는 유저를 참조하는 INSERT를 거부합니다.

**(2) 이메일이 바뀌면 데이터가 고아가 됩니다.** 이메일은 사용자가 바꿀 수 있는 값입니다(지금은 변경 API가 없지만, 만들면 즉시 문제가 됩니다). `User.email`을 UPDATE하면 `windows.user_id`는 옛 이메일 그대로 남아, 그 유저의 창·투두·타이머가 전부 안 보이게 됩니다. PK(`Long id`)는 불변이라 이 문제가 없습니다 — 그래서 **불변 대리키(surrogate key)로 참조**하는 게 관계형 모델의 기본입니다.

**(3) 조인 키가 varchar(255)입니다.** `TodoService.getAllTodosByUser`(아래 P1-4)는 `windows.user_id`로 필터합니다. 인덱스 엔트리 크기가 커지면 한 페이지에 들어가는 엔트리 수가 줄고, 트리 높이가 늘어납니다. `bigint`(8바이트)와 `varchar(255)`의 차이입니다.

**(4) JWT subject가 그대로 DB 키로 쓰입니다.** `JwtFilter.java:64-68`이 토큰의 `sub`(이메일)를 principal로 넣고, 컨트롤러가 `@AuthenticationPrincipal String userEmail`로 받아 그대로 WHERE 절에 넣습니다. 토큰 발급 시점과 현재 DB 상태가 어긋날 여지가 그대로 노출됩니다.

### 수정 방향

이건 최소 변경으로 끝나지 않습니다. 스키마 마이그레이션이 필요하므로 P0-1(Flyway 도입) 다음 단계로 잡으세요. 방향만 적습니다.

```diff
 // entity/Window.java
-    @Column(name = "user_id")
-    private String userId;
+    @ManyToOne(fetch = FetchType.LAZY, optional = false)
+    @JoinColumn(name = "user_id", nullable = false)
+    private User user;
```
마이그레이션 순서: ① `user_id_new bigint` 컬럼 추가 → ② `UPDATE ... JOIN users ON users.email = windows.user_id`로 채움 → ③ FK 추가 → ④ 코드 전환 → ⑤ 옛 컬럼 제거.

지금 당장 할 수 있는 최소 조치는, 이메일 변경 API를 **만들지 않는 것**과 P1-1의 유니크 제약을 거는 것입니다.

---

## P1-3. `windows.user_id`에 인덱스가 없음 — 가장 자주 도는 조회 경로

**위치** `entity/Window.java:42-43`
**조회 지점** `repository/WindowRepository.java:15`, `:19-20`, `service/TodoService.java:108`

```java
// WindowRepository.java:15,19-20
    List<Window> findByUserId(String userId);

    @Query("SELECT w FROM Window w WHERE w.userId = :userId ORDER BY w.zIndex ASC")
    List<Window> findWindowsByUserIdOrdered(@Param("userId") String userId);
```

### 왜 문제인가

P1-1과 같은 full scan 문제인데, 경로가 더 많습니다. `windows.user_id`로 필터하는 곳:

| API | 경로 |
|---|---|
| `GET /windows` | `WindowService.findAllByUser:25` → `findByUserId` |
| `POST /windows/{id}/focus` | `WindowService.focusWindow:79`, `:90` → `findWindowsByUserIdOrdered` **2회** |
| `GET /todos` | `TodoService.getAllTodosByUser:108` → `todo_items JOIN windows` 의 조인 조건 |

특히 세 번째가 중요합니다. `getAllTodosByUser`의 Specification은

```java
// TodoService.java:108
preds.add(cb.equal(root.get("window").get("userId"), userEmail));
```

`root`가 `TodoItem`인데 `window`를 거쳐 `userId`(= id가 아닌 필드)에 접근합니다. JPA Criteria에서 연관 엔티티의 **비-id 필드**를 참조하면 Hibernate가 **암묵적으로 `windows` 테이블과 조인**하는 SQL을 만듭니다 (`window.id`만 참조했다면 FK 컬럼으로 끝나 조인이 안 생깁니다 — `getTodosByWindow:59`가 그 경우입니다).

결과 SQL은 대략 `todo_items t inner join windows w on t.window_id = w.id where w.user_id = ?` 형태입니다. `w.user_id`에 인덱스가 없으면 옵티마이저는 `windows`를 전체 스캔해서 해당 유저의 창 id들을 모으는 경로를 택하게 됩니다.

한편 **`todo_items.window_id`와 `members.room_id`는 인덱스가 있습니다.** 이유는 `@ManyToOne` + `@JoinColumn`이 있으면 Hibernate가 FK 제약(`alter table todo_items add constraint ... foreign key (window_id) references windows(id)`)을 생성하고, **InnoDB는 FK 컬럼에 인덱스가 없으면 자동으로 만들어 주기 때문**입니다. `user_id`는 FK가 아니라 그냥 varchar 컬럼이므로 이 자동 혜택을 못 받습니다. P1-2를 해결하면 이 인덱스도 부수적으로 생깁니다.

### 수정 방향

P1-2를 당장 못 하면, 인덱스만 먼저 답니다.

```diff
 // entity/Window.java
 @Entity
-@Table(name = "windows")
+@Table(name = "windows", indexes = {
+    @Index(name = "ix_windows_user_id", columnList = "user_id")
+})
```

`focusWindow`가 `ORDER BY zIndex`까지 하므로 복합 인덱스가 더 낫습니다.

```java
@Index(name = "ix_windows_user_zindex", columnList = "user_id, z_index")
```

`[[확인필요: zIndex의 실제 물리 컬럼명 — SpringPhysicalNamingStrategy 기준 z_index로 예상되나 DB에서 확인]]`

같은 이유로 `Timer`는 이미 커버되어 있습니다. `entity/Timer.java:7-10`의 `@UniqueConstraint(columnNames = {"userId","date"})`가 복합 유니크 인덱스를 만들고, `findByUserIdAndDate`/`findByUserIdAndDateBetween` 둘 다 `userId`를 선두 컬럼으로 쓰므로 그 인덱스를 탑니다. 단 —

`[[확인필요: timer 테이블에 (user_id, date) 유니크 인덱스가 실제로 생성됐는지 — SHOW INDEX FROM timer]]`
`@UniqueConstraint(columnNames=...)`에 적은 이름은 **논리 컬럼명**이고, 실제 컬럼은 네이밍 전략을 거쳐 `user_id`가 됩니다. 이 변환이 유니크 제약에도 적용되는지는 Hibernate 버전에 따라 다뤄진 이력이 있어, 스키마에서 직접 확인해야 합니다. 이 프로젝트에서 **유일하게 존재하는 인덱스**이므로 확인 가치가 있습니다.

---

## P1-4. 날짜 필터의 경계가 양끝 포함이라 하루가 겹침

**위치** `service/TodoService.java:63-68`, 동일 패턴 `:112-117`

```java
if (date != null && !date.isBlank()) {
    LocalDate localDate = LocalDate.parse(date);
    Instant start = localDate.atStartOfDay(userZone).toInstant();
    Instant end   = localDate.plusDays(1).atStartOfDay(userZone).toInstant();
    preds.add(cb.between(root.get("createdAt"), start, end));
}
```

### 왜 문제인가

`end`는 **다음 날 00:00:00**입니다. 그런데 `cb.between`은 SQL `BETWEEN`으로 번역되고, `BETWEEN a AND b`는 **양쪽 끝을 모두 포함**합니다(`a <= x AND x <= b`).

따라서 `createdAt`이 정확히 다음 날 00:00:00.000000인 투두는

- `date=2026-09-02` 조회에서 `end` 경계에 걸려 포함되고,
- `date=2026-09-03` 조회에서 `start` 경계에 걸려 또 포함됩니다.

같은 항목이 두 날짜에 모두 나타납니다. 프론트에서 날짜별로 그룹핑하면 합계가 어긋납니다.

JS로 옮기면 `x >= start && x <= end` 를 쓴 셈인데, 반열린 구간 `[start, end)`이어야 맞습니다.

`createdAt`은 `@CreationTimestamp`로 채워지고 MySQL `datetime(6)`(마이크로초)에 저장되므로 정확히 자정에 걸릴 확률은 낮습니다. 하지만 구간 로직 자체가 틀린 것이고, 밀리초 이하를 버리는 DB 설정이나 배치 삽입이 생기면 바로 드러납니다.

### 수정 방향

```diff
-            preds.add(cb.between(root.get("createdAt"), start, end));
+            preds.add(cb.greaterThanOrEqualTo(root.get("createdAt"), start));
+            preds.add(cb.lessThan(root.get("createdAt"), end));
```

두 군데(`:67`, `:116`) 모두 동일하게. 이 날짜 필터 블록은 두 메서드에 그대로 복붙되어 있으므로, private 헬퍼로 빼면 재발을 막습니다.

---

## P1-5. `getMonthlyTotal`이 합계를 DB가 아니라 Java에서 계산

**위치** `service/TimerService.java:74-83`

```java
public List<Timer> listByMonth(String userId, int year, int month, ZoneId userZone) {
    LocalDate fromLocal = LocalDate.of(year, month, 1);
    LocalDate toLocal   = fromLocal.withDayOfMonth(fromLocal.lengthOfMonth());
    return repo.findByUserIdAndDateBetween(userId, fromLocal, toLocal);
}

public long getMonthlyTotal(String userId, int year, int month, ZoneId userZone) {
    return listByMonth(userId, year, month, userZone)
            .stream().mapToLong(Timer::getTotalSeconds).sum();
}
```

### 왜 문제인가

합계 하나를 얻으려고 **해당 월의 모든 행을 엔티티로 만들어 메모리에 올립니다.** DB → JDBC ResultSet → Hibernate 엔티티 인스턴스화 → 영속성 컨텍스트에 등록(1차 캐시에 스냅샷 저장) → 스트림 순회.

`SELECT SUM(total_seconds)`로 하면 DB가 스칼라 하나만 돌려주고, 엔티티 생성·스냅샷 저장이 전부 사라집니다. 영속성 컨텍스트에 등록된다는 게 특히 중요한데, Hibernate는 나중에 dirty checking을 하려고 각 엔티티의 **원본 필드 값 배열을 따로 복사해 둡니다**. 즉 읽기만 해도 엔티티당 메모리가 두 배로 듭니다.

지금은 월 최대 31행이므로 이 자체로 장애가 나지는 않습니다. 문제는 이 메서드가 `getTimerAnalytics`에서 여러 번 호출된다는 점이고, 그건 `02-performance.md`에서 다룹니다.

`@Transactional(readOnly = true)`가 클래스에 걸려 있어(`TimerService.java:19`) Hibernate가 `FlushMode.MANUAL`로 동작하므로 dirty checking 자체는 스킵됩니다 — 스냅샷 복사 비용은 그래도 발생합니다.

### 수정 방향

```diff
 // repository/TimerRepository.java
     List<Timer> findByUserIdAndDateBetween(String userId, LocalDate start, LocalDate end);
+
+    @Query("select coalesce(sum(t.totalSeconds), 0) from Timer t " +
+           "where t.userId = :userId and t.date between :start and :end")
+    long sumTotalSeconds(@Param("userId") String userId,
+                         @Param("start") LocalDate start,
+                         @Param("end") LocalDate end);
```

```diff
 // service/TimerService.java
     public long getMonthlyTotal(String userId, int year, int month, ZoneId userZone) {
-        return listByMonth(userId, year, month, userZone)
-                .stream().mapToLong(Timer::getTotalSeconds).sum();
+        LocalDate from = LocalDate.of(year, month, 1);
+        return repo.sumTotalSeconds(userId, from, from.withDayOfMonth(from.lengthOfMonth()));
     }
```

`coalesce`가 필요한 이유: 행이 0건이면 `SUM`은 `NULL`을 반환하고, 반환형이 primitive `long`이면 NPE가 납니다.

참고: `listByMonth`와 `getMonthlyTotal`의 `userZone` 파라미터는 받기만 하고 **본문에서 쓰이지 않습니다**(`:74`, `:80`). `LocalDate.of(year, month, 1)`은 타임존과 무관합니다. 죽은 파라미터입니다.

---

## P1-6. `Window.todos` 양방향 연관관계가 cascade 용도로만 존재 — 창 삭제가 N+1 DELETE가 됨

**위치** `entity/Window.java:38-40`, `service/WindowService.java:59-67`

```java
// Window.java:38-40
    @OneToMany(mappedBy = "window", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @JsonIgnore
    private List<TodoItem> todos = new ArrayList<>();
```

```java
// WindowService.java:66
        windowRepository.delete(window);
```

### 왜 문제인가

**(1) 이 컬렉션은 어디서도 읽히지 않습니다.** grep 결과 `window.getTodos()` 호출 0건입니다. 투두 조회는 전부 `TodoItemRepository`를 통해 이뤄집니다(`TodoService.getTodosByWindow`, `getAllTodosByUser`). 즉 양방향으로 만든 유일한 실효는 `cascade`/`orphanRemoval`입니다.

**(2) 그 cascade가 벌크 삭제를 막습니다.** `cascade = ALL`(REMOVE 포함) + `orphanRemoval = true`가 걸린 컬렉션을 가진 엔티티를 삭제하면, Hibernate는 **삭제를 자식에게 전파해야 하므로 컬렉션을 반드시 초기화합니다.** LAZY여도 마찬가지입니다 — 무엇을 지울지 알아야 하니까요. 실행되는 SQL은:

```sql
SELECT ... FROM todo_items WHERE window_id = ?   -- 컬렉션 초기화 1회
DELETE FROM todo_items WHERE id = ?              -- 자식 1건당 1회
DELETE FROM todo_items WHERE id = ?
...
DELETE FROM windows WHERE id = ?
```

투두가 200건인 창을 지우면 SELECT 1 + DELETE 201회입니다. `DELETE FROM todo_items WHERE window_id = ?` 한 줄로 끝날 일입니다.

Hibernate가 이렇게 하는 이유는 JPA 명세상 `CascadeType.REMOVE`가 **엔티티 단위 생명주기 이벤트**로 정의되어 있어서입니다. 각 자식마다 `@PreRemove` 콜백이 호출될 수 있어야 하므로, 개별 DELETE를 발행할 수밖에 없습니다.

**(3) 양방향의 일반적 부작용도 그대로 있습니다.** `todos` 필드는 `@JsonIgnore`로 직렬화만 막혀 있고, 매핑은 살아 있습니다. `Window`를 응답으로 직접 내보내는 지금 구조(`WindowController.java:22,27,33,46`)에서 누군가 `@JsonIgnore`를 실수로 지우면 즉시 지연로딩 예외 또는 전체 투두 직렬화가 됩니다.

### 수정 방향

컬렉션을 읽지 않으므로 **양방향을 끊고 삭제는 명시적 벌크 쿼리로** 바꾸는 게 최소 변경입니다.

```diff
 // entity/Window.java
-    @OneToMany(mappedBy = "window", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
-    @JsonIgnore
-    private List<TodoItem> todos = new ArrayList<>();
```

```diff
 // repository/TodoItemRepository.java
+    @Modifying
+    @Query("delete from TodoItem t where t.window.id = :windowId")
+    int deleteByWindowId(@Param("windowId") Long windowId);
```

```diff
 // service/WindowService.java
+    @Transactional
     public void delete(Long id, String userEmail) {
         ...
+        todoItemRepository.deleteByWindowId(id);
         windowRepository.delete(window);
     }
```

`@Modifying` 벌크 삭제는 영속성 컨텍스트를 우회하므로 **반드시 트랜잭션 안에서, 그리고 자식을 먼저** 지워야 합니다(FK 제약 때문). `WindowService`에 트랜잭션이 없는 문제는 아래 P1-7 및 `03-transaction.md`에서 다룹니다.

---

## P1-7. `focusWindow`가 같은 조회를 두 번 수행 — 트랜잭션이 없어 컨텍스트를 못 재사용

**위치** `service/WindowService.java:12-13`(클래스 선언), `:70-91`

```java
@Service                       // ← @Transactional 없음. 이 클래스 전체에 없습니다.
public class WindowService {
...
    public List<Window> focusWindow(Long id, String userEmail) {
        Window focusedWindow = windowRepository.findById(id)          // SELECT 1
                .orElseThrow(() -> new RuntimeException("Window not found"));
        ...
        List<Window> userWindows = windowRepository.findWindowsByUserIdOrdered(userEmail);  // SELECT 2

        int maxZIndex = userWindows.stream().mapToInt(Window::getZIndex).max().orElse(0);
        focusedWindow.setZIndex(maxZIndex + 1);
        windowRepository.save(focusedWindow);                          // UPDATE

        return windowRepository.findWindowsByUserIdOrdered(userEmail);  // SELECT 3 ← 중복
    }
```

### 왜 문제인가

**(1) 쿼리 3번 + UPDATE 1번, 그리고 각각이 별도 트랜잭션입니다.**

서비스 메서드에 `@Transactional`이 없으면, 트랜잭션 경계는 **Spring Data의 `SimpleJpaRepository`가 각 메서드에 붙여 둔 것**이 됩니다. `findById`는 `@Transactional(readOnly=true)`, `save`는 `@Transactional`입니다. 즉 리포지토리 호출 하나하나가 `begin → 실행 → commit`이고, 그때마다 커넥션 풀에서 커넥션을 얻고 반납합니다. 하나의 논리적 작업이 4개 트랜잭션으로 쪼개집니다.

**(2) SELECT 3은 데이터상 불필요합니다.**

SELECT 2가 이미 그 유저의 모든 `Window`를 가져왔습니다. Spring Boot는 `spring.jpa.open-in-view`를 **기본값 true**로 켜두므로(이 프로젝트는 이 값을 설정하지 않았습니다 — grep 0건), 요청 하나 동안 하나의 `EntityManager`가 스레드에 바인딩됩니다. 따라서 SELECT 2로 가져온 엔티티들은 계속 **영속 상태**이고, `focusedWindow`의 `zIndex` 변경도 그 인스턴스에 이미 반영되어 있습니다.

SELECT 3이 필요한 유일한 이유는 **정렬 순서**입니다 — `zIndex`가 바뀌었으니 `ORDER BY zIndex ASC` 결과 순서가 달라집니다. 그건 Java에서 정렬하면 됩니다.

**(3) OSIV 자체가 별개 문제입니다.**
`open-in-view=true`는 요청 전체 구간(컨트롤러 반환 후 JSON 직렬화까지) 동안 `EntityManager`를 열어 둡니다. Spring Boot는 부팅 시 이 사실을 경고 로그로 남깁니다. 커넥션 점유와 관련되므로 `02-performance.md`에서 커넥션 풀과 함께 다룹니다.

### 수정 방향

```diff
 // service/WindowService.java
+import org.springframework.transaction.annotation.Transactional;
+import java.util.Comparator;
 
 @Service
+@Transactional(readOnly = true)
 public class WindowService {
```

```diff
+    @Transactional
     public List<Window> focusWindow(Long id, String userEmail) {
-        Window focusedWindow = windowRepository.findById(id)
-                .orElseThrow(() -> new RuntimeException("Window not found"));
-
-        if (!Objects.equals(focusedWindow.getUserId(), userEmail)) {
-            throw new RuntimeException("접근 권한이 없습니다.");
-        }
-
         List<Window> userWindows = windowRepository.findWindowsByUserIdOrdered(userEmail);
+        Window focusedWindow = userWindows.stream()
+                .filter(w -> w.getId().equals(id))
+                .findFirst()
+                .orElseThrow(() -> new RuntimeException("Window not found"));
 
         int maxZIndex = userWindows.stream().mapToInt(Window::getZIndex).max().orElse(0);
         focusedWindow.setZIndex(maxZIndex + 1);
-        windowRepository.save(focusedWindow);
 
-        return windowRepository.findWindowsByUserIdOrdered(userEmail);
+        userWindows.sort(Comparator.comparingInt(Window::getZIndex));
+        return userWindows;
     }
```

- `findById`가 사라집니다 — 어차피 전체 목록을 가져오므로 그 안에서 찾으면 됩니다. 목록에 없으면 남의 창이거나 없는 창이므로 권한 검사도 자연히 됩니다.
- `save()`도 사라집니다. `@Transactional` 안에서 영속 엔티티의 필드를 바꾸면 Hibernate가 커밋 시점에 **dirty checking**으로 변경을 감지해 UPDATE를 자동 발행합니다. `TodoService.updateDone:80-84`가 이미 이 방식을 쓰고 있어, 프로젝트 안에서도 스타일이 갈려 있습니다.
- 쿼리 1회 + UPDATE 1회, 트랜잭션 1개로 줄어듭니다.

`partialUpdate:36-56`, `delete:59-67`도 같은 이유로 `@Transactional`이 필요합니다(`save()` 호출은 제거 가능).
`findWindowsByUserIdOrdered`가 반환하는 `List`는 Hibernate가 만든 `ArrayList`이므로 `sort()` 호출이 안전합니다.

---

## P1-8. 페이징 없는 무한 목록 응답

**위치** `controller/GlobalTodoController.java:24-39`, `controller/TodoController.java:33-49`

```java
// GlobalTodoController.java:24
    @GetMapping
    public ResponseEntity<List<TodoResponse>> getAllTodos(
            @RequestParam(required = false) String date, ...)
```

### 왜 문제인가

`GET /todos`는 `date`가 없으면 **그 유저가 지금까지 만든 모든 투두**를 반환합니다. `TodoService.getAllTodosByUser:106-125`의 Specification에는 `LIMIT`에 해당하는 것이 없고, `findAll(spec, sort)`는 전체를 가져옵니다.

응답 크기가 사용자 사용 기간에 **비례해서 무한히 커집니다.** 서버 쪽에서는 그 행을 전부 엔티티로 만들어 영속성 컨텍스트에 올리고, DTO로 매핑하고, JSON으로 직렬화합니다. 프론트에서 `date` 파라미터를 항상 붙인다 해도, 그건 클라이언트 관례일 뿐 서버가 보장하는 계약이 아닙니다.

`/rooms`는 `Pageable`로 잘 처리되어 있습니다(`RoomController.java:37-40`). 같은 앱 안에서 정책이 갈려 있는 상태입니다.

### 수정 방향

`Pageable`을 받고 `JpaSpecificationExecutor.findAll(spec, pageable)`로 바꾸는 게 정석이지만, 프론트 계약이 바뀝니다. 계약을 안 바꾸려면 상한만 먼저 거세요.

```diff
 // service/TodoService.java  (getAllTodosByUser)
-        return todoItemRepository.findAll(spec, sort);
+        return todoItemRepository.findAll(spec, PageRequest.of(0, 500, sort)).getContent();
```

`findAll(Specification, Pageable)`은 `LIMIT`을 붙입니다. 500건에서 잘렸다는 사실을 클라이언트가 알아야 하므로, 중장기적으로는 `Page` 반환으로 전환하는 게 맞습니다.

---

## P1-9. `/rooms` 목록의 정렬 기준이 UUID 문자열

**위치** `controller/RoomController.java:38`, `entity/Room.java:14-15`, `:27`

```java
// RoomController.java:38
      @PageableDefault(size = 9, sort= "id", direction = Sort.Direction.DESC)
      Pageable pageable
```

```java
// Room.java:14-15, 27
    @Id
    private String id;          // prePersist에서 UUID.randomUUID().toString()
    ...
    private Instant createdAt;  // 존재하지만 정렬에 쓰이지 않음
```

### 왜 문제인가

`sort = "id"`, `id`는 랜덤 UUID 문자열입니다. `ORDER BY id DESC`는 **UUID 문자열의 사전순 역순**이므로, 사용자에게는 사실상 무작위 순서로 보입니다. "최신 방이 위로" 같은 의도가 있었다면 그렇게 동작하지 않습니다.

`createdAt` 필드가 이미 있고(`Room.java:27`, `prePersist`에서 채움) 이게 의도한 정렬 키일 가능성이 높습니다.

페이징 자체의 안정성은 문제없습니다 — `id`는 유일하고 불변이므로 페이지 경계에서 항목이 중복/누락되지는 않습니다. 순서의 **의미**만 없습니다.

### 수정 방향

```diff
 // controller/RoomController.java
-      @PageableDefault(size = 9, sort= "id", direction = Sort.Direction.DESC)
+      @PageableDefault(size = 9, sort = "createdAt", direction = Sort.Direction.DESC)
       Pageable pageable
```

`createdAt`에 인덱스가 없으므로 정렬 시 filesort가 발생합니다. 방 수가 늘어나면 인덱스를 추가하세요.

```diff
 // entity/Room.java
-@Table(name = "rooms")
+@Table(name = "rooms", indexes = @Index(name = "ix_rooms_created_at", columnList = "created_at"))
```

`Instant`는 밀리초/마이크로초까지 있어 동시 생성 시에도 tie가 드물지만, 완전한 안정 정렬을 원하면 `sort=createdAt,id`처럼 tiebreaker를 추가합니다.

---

## P1-10. `members`에 `(room_id, user_id)` 유니크 제약이 없음

**위치** `entity/Member.java:14-28`, `service/RoomService.java:64-77`

```java
// RoomService.java:64-77
    public Member upsertActiveMember(Room room, String userId) {
        var memOpt = memberRepo.findByRoomAndUserId(room, userId);   // 있으면 재활성화
        var member = memOpt.orElseGet(() -> Member.builder()...build());  // 없으면 새로 생성
        member.setActive(true);
        ...
        return memberRepo.save(member);
    }
```

### 왜 문제인가

메서드 이름이 `upsert`이고 `findByRoomAndUserId`가 `Optional<Member>`를 반환합니다. 즉 이 코드는 **"(room, userId) 조합이 최대 1건"이라는 전제**로 쓰였습니다. 그런데 `Member` 엔티티에는 그 전제를 강제하는 제약이 없습니다(`@Table(name="members")`만 있고 `uniqueConstraints` 없음, `entity/Member.java:10`).

전제가 코드에만 있고 DB에 없으면 두 가지가 깨집니다.

1. **동시 입장 요청이 중복 행을 만듭니다.** 같은 유저가 `POST /rooms/{id}/join`을 두 번 거의 동시에 보내면(더블클릭, 재연결 재시도), 두 요청 모두 `findByRoomAndUserId`에서 빈 `Optional`을 받고 둘 다 새 `Member`를 INSERT합니다. `Member.id`는 각자 새 UUID이므로 PK 충돌도 안 납니다. `RoomService`에 `@Transactional`이 없어 조회와 저장이 서로 다른 트랜잭션이므로 창이 더 넓습니다.
2. **중복이 생기면 그 다음부터 500이 납니다.** `findByRoomAndUserId`가 2건을 만나면 Spring Data가 `IncorrectResultSizeDataAccessException`을 던집니다. 그 유저는 그 방에 다시 입장/퇴장할 수 없게 됩니다.
3. **정원 계산이 틀어집니다.** `countByRoomAndActive`(`RoomService.java:57`, `:81`)가 사람 수가 아니라 행 수를 셉니다. 중복 행이 있으면 실제보다 많게 세어 정원이 조기에 막힙니다.

경쟁 조건 자체는 `08-concurrency.md`에서 더 다룹니다. 여기서 짚는 건 **DB 제약이 없다는 스키마 문제**입니다. 유니크 제약이 있으면 중복 INSERT가 `DataIntegrityViolationException`으로 실패하므로, 애플리케이션이 경쟁에서 지더라도 데이터는 깨지지 않습니다.

### 수정 방향

```diff
 // entity/Member.java
 @Entity
-@Table(name = "members")
+@Table(name = "members", uniqueConstraints = @UniqueConstraint(
+        name = "uq_members_room_user", columnNames = {"room_id", "user_id"}))
```

`room_id`는 `@JoinColumn(name = "room_id")`로 명시되어 있어 물리 컬럼명이 확실합니다. `userId`는 `@Column`이 없어 네이밍 전략에 의존하므로, 이 기회에 명시하는 게 안전합니다.

```diff
-    private String userId;
+    @Column(name = "user_id", nullable = false)
+    private String userId;
```

`ddl-auto: update`는 **기존 데이터에 중복이 있으면 제약 생성에 실패**합니다. 적용 전 확인:

```sql
SELECT room_id, user_id, COUNT(*) FROM members GROUP BY room_id, user_id HAVING COUNT(*) > 1;
```

이 복합 유니크 인덱스는 `findByRoomAndUserId`의 조회 인덱스 역할도 합니다.

---

## P2-1. `String[]` 필드가 어떤 컬럼으로 매핑되는지 불투명

**위치** `entity/Window.java:28,30`

```java
    private String[] url;
    private String[] urlTitles;
```

### 왜 문제인가

`@ElementCollection`도, `@Convert`(JSON 컨버터)도, `@Column(columnDefinition=...)`도 없는 맨 `String[]`입니다. Hibernate 6은 기본 타입의 배열을 하나의 컬럼에 매핑하는데, **어떤 SQL 타입을 쓰는지는 방언(dialect)이 결정**합니다. PostgreSQL처럼 네이티브 배열이 있으면 `text[]`, 없으면 JSON 또는 이진 직렬화로 떨어집니다. MySQL에서 무엇이 되는지는 실제 스키마를 봐야 합니다.

`[[확인필요: windows.url / windows.url_titles 의 실제 컬럼 타입 — SHOW CREATE TABLE windows]]`

무엇이 되든 공통 결과는 이렇습니다.

- **원소 단위로 조회할 수 없습니다.** "특정 유튜브 URL을 담은 창 찾기" 같은 쿼리가 불가능하거나, 전체 스캔 + 문자열 매칭이 됩니다. 인덱스를 걸 수도 없습니다.
- **부분 수정이 불가능합니다.** `WindowService.partialUpdate:52-53`이 배열 전체를 통째로 교체합니다. 재생목록에 한 곡 추가도 전체 재전송입니다.
- **DB에서 사람이 읽기 어렵습니다.** 이진 직렬화로 떨어졌다면 SQL 콘솔에서 값을 확인할 수 없습니다.

그리고 P0-1에서 짚은 대로, 이 필드는 `String` → `String[]` 타입 변경을 겪었고 `ddl-auto: update`는 그 변경을 DB에 반영하지 않습니다. 두 문제가 같은 컬럼에서 만납니다.

### 수정 방향

DB에 자연스러운 형태를 **명시적으로** 고르세요. 원소 단위 조회가 필요 없다면 JSON 컬럼이 가장 실용적입니다.

```diff
 // entity/Window.java
+import io.hypersistence.utils.hibernate.type.json.JsonType;  // 또는 직접 AttributeConverter 작성
...
-    private String[] url;
-    private String[] urlTitles;
+    @Convert(converter = StringListConverter.class)
+    @Column(name = "url", columnDefinition = "json")
+    private List<String> url;
```

원소 단위 조회·인덱스가 필요해지면 별도 테이블(`@ElementCollection` 또는 `WindowUrl` 엔티티)로 정규화합니다. 어느 쪽이든 **컬럼 타입 마이그레이션 SQL을 직접 써야** 하므로 P0-1의 Flyway 도입이 선행 조건입니다.

---

## P2-2. `Room`/`Member`의 PK가 랜덤 UUID 문자열

**위치** `entity/Room.java:14-15,30-32`, `entity/Member.java:14-15,30-33`

```java
    @Id
    private String id;
    ...
    @PrePersist
    public void prePersist() {
        if (id == null) id = UUID.randomUUID().toString();
```

### 왜 문제인가

InnoDB는 **클러스터드 인덱스** 구조입니다. 즉 행 데이터 자체가 PK 순서로 B+트리에 저장됩니다(프론트 비유로는, 배열이 항상 PK로 정렬된 상태를 유지하는 것). 여기서 두 가지가 따라옵니다.

1. **랜덤 PK는 삽입 위치가 매번 트리 중간입니다.** 새 행이 이미 꽉 찬 페이지 중간에 들어가야 하면 InnoDB는 페이지를 쪼갭니다(page split). 자동 증가 PK면 항상 맨 끝에 붙어 split이 없습니다.
2. **모든 세컨더리 인덱스가 PK 값을 복사해 갖습니다.** 세컨더리 인덱스 리프에는 "인덱스 키 + PK"가 들어가고, PK로 클러스터드 인덱스를 다시 찾아갑니다. PK가 36바이트 문자열이면 그 36바이트가 세컨더리 인덱스마다 중복 저장됩니다. `bigint`면 8바이트입니다. P1-10에서 추가할 `(room_id, user_id)` 인덱스가 바로 이 비용을 냅니다.

측정은 하지 않았습니다. 방/멤버 규모가 작으면 실무상 무시할 수 있는 수준입니다.

한 가지 더: `@Id`에 `@GeneratedValue`가 없고 `@PrePersist`에서 값을 채우는 구조라, `SimpleJpaRepository.save()`의 신규 판별이 `id == null`에 의존합니다. `upsertActiveMember`에서 기존 멤버를 `save()`할 때는 `id != null`이므로 `persist`가 아니라 `merge`가 호출됩니다.

### 수정 방향

지금 당장 PK를 바꾸는 건 비용이 큽니다. 새 테이블을 만들 때는 `@GeneratedValue(strategy = IDENTITY)`의 `Long`을 기본으로 쓰고, 외부 노출용 식별자가 필요하면 UUID를 **별도 유니크 컬럼**으로 두세요.

```java
@Id @GeneratedValue(strategy = GenerationType.IDENTITY)
private Long id;                    // 내부 PK / FK

@Column(unique = true, nullable = false, updatable = false, length = 36)
private String publicId;            // API에 노출
```

---

## P2-3. `findTodoItemForUser`가 지연로딩 프록시를 초기화해 쿼리가 한 번 더 나감

**위치** `service/TodoService.java:149-157`, `:159-166`

```java
    private TodoItem findTodoItemForUser(String userEmail, Long winId, Long todoId) {
        TodoItem todoItem = todoItemRepository.findById(todoId)          // SELECT todo_items
                .orElseThrow(() -> new IllegalArgumentException("TodoItem not found"));
        if (!todoItem.getWindow().getId().equals(winId)
         || !todoItem.getWindow().getUserId().equals(userEmail)) {       // ← SELECT windows
            throw new SecurityException("Permission denied");
        }
        return todoItem;
    }
```

### 왜 문제인가

`TodoItem.window`는 `fetch = FetchType.LAZY`(`entity/TodoItem.java:23`)입니다. LAZY 연관은 Hibernate가 실제 `Window` 대신 **프록시 객체**를 넣어 둡니다. 프록시는 필드 접근이 일어나는 순간 DB에서 실제 데이터를 채웁니다.

- `getWindow().getId()` — 프록시는 **식별자만은 이미 알고 있으므로**(FK 컬럼에서 읽었으니까) DB 조회 없이 반환합니다.
- `getWindow().getUserId()` — 식별자가 아닌 필드이므로 **여기서 `SELECT * FROM windows WHERE id = ?`가 발생**합니다.

그래서 투두 하나를 수정/삭제할 때마다 쿼리 2회입니다. N+1은 아닙니다(루프가 없으니까) — 하지만 "권한 확인" 한 가지를 위해 왕복이 두 번 생기는 건 조인 한 번으로 없앨 수 있습니다.

`updateDone`, `updateText`, `deleteTodo`, `updateDoneGlobal`, `updateTextGlobal`, `deleteTodoGlobal` 6개 메서드가 모두 이 헬퍼를 씁니다.

### 수정 방향

권한 조건을 쿼리에 넣어 한 번에 가져옵니다.

```diff
 // repository/TodoItemRepository.java
-    List<TodoItem> findByWindowId(Long windowId);
-    List<TodoItem> findAllByWindow_UserId(String userEmail);
+    @Query("select t from TodoItem t join fetch t.window w " +
+           "where t.id = :todoId and w.id = :winId and w.userId = :userEmail")
+    Optional<TodoItem> findOwnedByUser(@Param("todoId") Long todoId,
+                                       @Param("winId") Long winId,
+                                       @Param("userEmail") String userEmail);
```

```diff
 // service/TodoService.java
     private TodoItem findTodoItemForUser(String userEmail, Long winId, Long todoId) {
-        TodoItem todoItem = todoItemRepository.findById(todoId)
-                .orElseThrow(() -> new IllegalArgumentException("TodoItem not found"));
-        if (!todoItem.getWindow().getId().equals(winId)
-         || !todoItem.getWindow().getUserId().equals(userEmail)) {
-            throw new SecurityException("Permission denied");
-        }
-        return todoItem;
+        return todoItemRepository.findOwnedByUser(todoId, winId, userEmail)
+                .orElseThrow(() -> new SecurityException("Permission denied"));
     }
```

`join fetch`가 `Window`를 같은 쿼리에서 함께 채우므로 프록시 초기화가 사라집니다.
`findByWindowId`, `findAllByWindow_UserId`는 호출부가 0건이라 지워도 됩니다.

`getTodosByWindow:53-55`도 같은 성격의 낭비입니다 — 창 소유권 확인용 `findById` 1회 후 Specification 쿼리 1회. Specification에 소유자 조건을 넣으면 하나로 줄어듭니다.

```diff
-        windowRepository.findById(winId)
-            .filter(w -> w.getUserId().equals(userEmail))
-            .orElseThrow(() -> new SecurityException("Permission denied"));
-
         Specification<TodoItem> spec = (root, query, cb) -> {
             List<Predicate> preds = new ArrayList<>();
             preds.add(cb.equal(root.get("window").get("id"), winId));
+            preds.add(cb.equal(root.get("window").get("userId"), userEmail));
```
단 이렇게 하면 "남의 창"과 "빈 창"이 모두 빈 배열로 응답됩니다. 403을 유지하려면 기존 검사를 남겨야 합니다 — `04-exception.md`, `07-api-design.md`와 함께 결정하세요.

---

## P2-4. 스키마 네이밍이 일관되지 않음

- 테이블명: `users`, `rooms`, `members`, `windows`, `todo_items`는 복수형인데 `timer`(`entity/Timer.java:8`)만 단수형입니다.
- `@Column(name=...)` 명시 여부가 뒤섞여 있습니다. `Window.userId`/`createdAt`은 명시(`:42`, `:45`), `Timer.userId`/`date`, `Member.userId`, `Room` 전체 필드는 미명시로 네이밍 전략에 의존합니다.

물리 컬럼명이 네이밍 전략에 의존하면, P1-3·P1-10처럼 **인덱스/제약을 컬럼명 문자열로 선언할 때 무엇을 써야 하는지 확실하지 않습니다.** 실제로 이 리포트에서 `[[확인필요]]`가 두 번 나온 이유가 그것입니다. 인덱스를 추가할 컬럼은 `@Column(name=...)`을 명시하세요.

`timer` → `timers` 개명은 데이터 이관이 필요하므로 Flyway 도입 후에 하세요.

---

## 요약

| 심각도 | 개수 | 항목 |
|---|---|---|
| **P0** | 1 | `ddl-auto: update`로 운영 스키마 관리 (컬럼 타입 변경 미반영 — `windows.url` 실제 사례) |
| **P1** | 10 | `users.email` 인덱스·유니크 없음 / 이메일을 식별자로 쓰고 FK 없음 / `windows.user_id` 인덱스 없음 / 날짜 필터 경계 중복 / 합계를 Java에서 계산 / `Window.todos` cascade가 N+1 DELETE 유발 / `focusWindow` 중복 조회 + 트랜잭션 없음 / 페이징 없는 무한 목록 / `/rooms` 정렬이 UUID 문자열 / `members` 복합 유니크 없음 |
| **P2** | 4 | `String[]` 매핑 불투명 / UUID 문자열 PK / 지연로딩 프록시로 쿼리 1회 추가 / 스키마 네이밍 불일치 |

**확인이 필요한 항목 3개**
- `SHOW CREATE TABLE windows` — `url` 컬럼 타입 (P0-1, P2-1)
- `SHOW INDEX FROM timer` — `(user_id, date)` 유니크 인덱스 존재 여부 (P1-3)
- `windows` 테이블의 `zIndex` 물리 컬럼명 (P1-3)

측정은 하지 않았습니다. 쿼리 실행 계획도 확인하지 않았습니다 — 위의 full scan 판단은 "해당 컬럼에 인덱스 선언이 없다"는 소스 사실과 InnoDB 동작 원리에 근거한 것이고, 실제 실행 계획은 `EXPLAIN`으로 확인해야 합니다.
