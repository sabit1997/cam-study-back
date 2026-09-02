# 06. 아키텍처 / 레이어 경계 (6단계)

조사 범위: Controller의 도메인 로직, Service 순환 참조, Entity가 응답으로 새어나가는지.

## grep으로 확정한 사실

**Service 순환 참조: 없습니다.** 서비스가 다른 서비스를 주입받는 경우가 **0건**입니다.

| 서비스 | 주입받는 것 |
|---|---|
| `AuthService` | `UserRepository`, `JwtUtil` (+ `new BCryptPasswordEncoder()` 직접 생성) |
| `RoomService` | `RoomRepository`, `MemberRepository`, `PasswordEncoder` |
| `TimerService` | `TimerRepository`, `UserRepository` |
| `TodoService` | `TodoItemRepository`, `WindowRepository` |
| `WindowService` | `WindowRepository` |

**엔티티가 HTTP 경계를 넘는 지점 (7곳)**

| 위치 | 방향 | 엔티티 |
|---|---|---|
| `WindowController.java:22` | 응답 | `List<Window>` |
| `WindowController.java:27` | **요청 + 응답** | `Window` |
| `WindowController.java:33` | 응답 | `Window` |
| `WindowController.java:46` | 응답 | `List<Window>` |
| `TimerController.java:43`, `:111`, `:117` | 응답 | `List<Timer>` |

`Room`·`TodoItem`·`User`는 DTO로 감싸져 있습니다(`RoomDto`, `TodoResponse`, `LoginResponse`). 즉 **프로젝트 안에서 규칙이 반반으로 갈려 있습니다.**

---

## P0-1. `POST /windows`가 `Window` 엔티티를 요청 바디로 받음 — 남의 창을 탈취하고 그 창의 투두를 전부 삭제할 수 있음

**위치** `controller/WindowController.java:26-30`, `service/WindowService.java:29-33`, `entity/Window.java:14-40`

```java
// WindowController.java:26-30
    @PostMapping
    public Window create(@RequestBody Window window,           // ← 엔티티를 그대로 바디로
                         @AuthenticationPrincipal String userEmail) {
        return windowService.create(window, userEmail);
    }
```

```java
// WindowService.java:29-33
    public Window create(Window window, String userEmail) {
        window.setUserId(userEmail);        // userId는 덮어씀 (여기까지는 올바름)
        return windowRepository.save(window);   // ← id는 덮어쓰지 않음
    }
```

```java
// entity/Window.java:14-23, :38-40
@Getter
@Setter                                      // ← Jackson이 setId()로 id를 채울 수 있음
...
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
...
    @OneToMany(mappedBy = "window", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @JsonIgnore
    private List<TodoItem> todos = new ArrayList<>();
```

### 왜 문제인가

이건 "엔티티 노출"의 교과서적 결과입니다. 단계별로 따라가면 이렇게 됩니다.

**1단계 — 클라이언트가 `id`를 지정할 수 있습니다.**

`Window`에 Lombok `@Setter`가 붙어 있어 `setId(Long)`이 존재합니다. Jackson은 JSON의 `id` 필드를 그 setter로 채웁니다. DTO를 쓰면 DTO에 `id` 필드가 없으니 애초에 들어올 수 없는데, 엔티티를 바디로 받으면 **엔티티의 모든 필드가 입력 표면**이 됩니다. 이걸 mass assignment(또는 over-posting)라고 합니다.

**2단계 — `save()`가 INSERT가 아니라 UPDATE로 갈라집니다.**

Spring Data의 `SimpleJpaRepository.save()`는 이렇게 동작합니다.

```java
if (entityInformation.isNew(entity)) { em.persist(entity); }   // INSERT
else { return em.merge(entity); }                              // SELECT + UPDATE
```

`isNew()`는 `@Version` 필드가 없는 엔티티에 대해 **`id == null`인지만** 봅니다. `Window`에는 `@Version`이 없습니다(grep 확인). 따라서 바디에 `"id": 42`가 있으면 `isNew()`는 `false` → **`merge()`** 로 갑니다.

**3단계 — `merge()`가 그 행을 덮어씁니다.**

`merge`는 분리(detached) 상태 객체를 받아 ① 해당 id의 행을 DB에서 로드하고 ② 넘어온 객체의 **모든 필드 값을 그 위에 복사**하고 ③ 커밋 시 UPDATE를 발행합니다.

그런데 `WindowService.create`가 그 직전에 `window.setUserId(userEmail)`을 호출했습니다. 원래는 "새 창은 요청자 소유"라는 올바른 의도인데, merge 경로에서는 **"기존 창의 소유자를 요청자로 바꾼다"** 가 됩니다.

```sql
UPDATE windows SET type=?, url=?, url_titles=?, z_index=?, x=?, y=?,
                   width=?, height=?, user_id='attacker@x.com', created_at=NULL
WHERE id = 42
```

**`id`가 `IDENTITY`(자동 증가)라서 추측이 필요 없습니다.** `1, 2, 3...`을 순서대로 넣으면 됩니다. 로그인만 하면 됩니다.

**4단계 — 그 창의 투두가 삭제됩니다.**

`Window.todos`는 `cascade = CascadeType.ALL`(MERGE 포함) + `orphanRemoval = true`입니다. Jackson은 `@JsonIgnore` 때문에 이 필드를 채우지 않으므로, 넘어온 객체의 `todos`는 필드 초기화값인 **빈 `ArrayList`** 입니다.

merge는 컬렉션 상태도 복사합니다. DB에 투두 5건이 있는 창에 빈 컬렉션을 merge하면, 관리 상태의 컬렉션이 비게 되고 `orphanRemoval`이 **빠진 자식들을 삭제**합니다.

`[[확인필요: 투두 삭제까지 실제로 일어나는지 — merge 시 컬렉션 대체 동작을 실행해 확인하지 않았습니다. 창 탈취(user_id 재지정)는 코드 경로상 확실합니다]]`

**5단계 — `created_at`이 NULL이 됩니다.**

`@PrePersist onCreate()`(`Window.java:48-51`)는 `persist`에서만 호출됩니다. merge 경로에서는 실행되지 않고, 넘어온 객체의 `createdAt`은 `null`입니다. `@Column(name = "created_at")`에 `nullable = false`가 없으므로 NULL로 덮어써집니다.

### 정리하면

로그인한 사용자 아무나 `POST /windows`에 `{"id": <임의 숫자>}`를 보내서:
- 그 창을 자기 소유로 만들고 (**타인 데이터 탈취**)
- 그 창의 내용(위치, 크기, URL 목록)을 임의로 바꾸고
- 그 창의 투두를 전부 지우고 (**타인 데이터 파괴**)
- 생성일자를 날립니다

`05-security.md`에서 놓친 항목입니다. **P0으로 그 목록에 추가되어야 합니다.**

### 수정 방향

**즉시 조치 — 요청 DTO 도입.** 이게 근본 수정입니다.

```java
// dto/WindowCreateRequest.java (신규)
package com.camstudy.backend.dto;

import com.camstudy.backend.entity.WindowType;

public record WindowCreateRequest(
        WindowType type,
        String[] url,
        String[] urlTitles,
        Integer x, Integer y,
        Integer width, Integer height,
        Integer zIndex
) {}
```

`id`, `userId`, `createdAt`, `todos`가 **아예 존재하지 않으므로** 클라이언트가 보낼 수 없습니다. 이게 DTO를 쓰는 이유입니다 — 방어 코드를 추가하는 게 아니라, **보낼 수 없게 만드는 것**입니다.

```diff
--- a/src/main/java/com/camstudy/backend/controller/WindowController.java
+++ b/src/main/java/com/camstudy/backend/controller/WindowController.java
     @PostMapping
-    public Window create(@RequestBody Window window,
-                         @AuthenticationPrincipal String userEmail) {
-        return windowService.create(window, userEmail);
+    public WindowResponse create(@RequestBody WindowCreateRequest req,
+                                 @AuthenticationPrincipal String userEmail) {
+        return WindowResponse.from(windowService.create(req, userEmail));
     }
```

```diff
--- a/src/main/java/com/camstudy/backend/service/WindowService.java
+++ b/src/main/java/com/camstudy/backend/service/WindowService.java
-    public Window create(Window window, String userEmail) {
-        window.setUserId(userEmail);
-        return windowRepository.save(window);
+    @Transactional
+    public Window create(WindowCreateRequest req, String userEmail) {
+        Window window = Window.builder()
+                .userId(userEmail)                                  // 서버가 결정
+                .type(req.type())
+                .url(req.url())
+                .urlTitles(req.urlTitles())
+                .x(orZero(req.x())).y(orZero(req.y()))
+                .width(orZero(req.width())).height(orZero(req.height()))
+                .zIndex(orZero(req.zIndex()))
+                .build();
+        return windowRepository.save(window);                       // id == null 이므로 항상 persist
     }
```

빌더로 만들면 `id`가 항상 `null`이라 `save()`가 반드시 `persist` 경로로 갑니다.

**DTO를 지금 못 만들면, 최소한 id를 지우세요.** 임시 방편입니다.

```diff
     public Window create(Window window, String userEmail) {
+        window.setId(null);          // 클라이언트가 보낸 id를 무시
+        window.setCreatedAt(null);   // @PrePersist가 채우도록
         window.setUserId(userEmail);
         return windowRepository.save(window);
     }
```

**추가 방어 — 엔티티에 `@Version`을 넣으면 `isNew()` 판정이 달라집니다.** 낙관적 잠금이 필요하다면(`08-concurrency.md`) 겸사겸사 이 경로도 막힙니다. 다만 근본 해결은 DTO입니다.

`[[확인필요: 운영 DB에 이미 이 경로로 소유자가 바뀐 창이 있는지 — SELECT id, user_id, created_at FROM windows WHERE created_at IS NULL]]`
`created_at`이 NULL인 행은 merge 경로를 탄 흔적입니다.

---

## P1-1. 엔티티가 응답으로 나가는 곳이 7군데 — 프로젝트 안에서 규칙이 갈려 있음

**위치** `controller/WindowController.java:22`, `:33`, `:46` / `controller/TimerController.java:43`, `:110-119`

```java
// WindowController.java:21-24
    @GetMapping
    public List<Window> getWindows(@AuthenticationPrincipal String userEmail) {
        return windowService.findAllByUser(userEmail);
    }
```

```java
// TimerController.java:110-119
    static class TimerMonthlyResponse {
        private final List<Timer> entries;        // ← 엔티티를 그대로 담음
        private final long monthlyTotal;
        ...
        public List<Timer> getEntries() { return entries; }
    }
```

같은 프로젝트에서 `Room` → `RoomDto`, `TodoItem` → `TodoResponse`, `User` → `LoginResponse`로 감싸는 규칙이 지켜지고 있습니다. `Window`와 `Timer`만 예외입니다.

### 왜 문제인가

**(1) API 응답 스펙이 DB 스키마에 묶입니다.**

엔티티에 필드를 추가하면 API 응답이 **조용히 바뀝니다.** 이 프로젝트에 실제 사례가 두 개 있습니다.

- 커밋 `041e3c7` — `Window.urlTitles` 추가 → `GET /windows` 응답에 필드 하나가 늘었습니다.
- 커밋 `f6c212a` — `Timer.pomoCycles` 추가 → `GET /timer` 응답의 각 요소에 필드가 늘었습니다.

반대 방향이 더 위험합니다. 나중에 `Window`에 내부용 필드(예: `deleted`, `lastSyncedAt`, `internalNote`)를 추가하면 **의도 없이 클라이언트에 노출**됩니다. 지금 구조에서는 그걸 막는 장치가 `@JsonIgnore`를 잊지 않는 것뿐입니다.

**(2) 이미 노출되고 있는 것 — 사용자 이메일**

`Window.userId`와 `Timer.userId`는 이메일 문자열입니다(`01-jpa-query.md` P1-2). `@JsonIgnore`가 없으므로 응답의 **모든 요소마다** 요청자 이메일이 반복됩니다. 클라이언트가 이미 아는 값이고, 지금은 자기 데이터만 조회하니 타인 정보 유출은 아닙니다. 다만 "방 참가자 목록" 같은 API가 같은 패턴으로 추가되면 즉시 타인 이메일이 나갑니다.

**(3) `@JsonIgnore`가 유일한 방어선입니다.**

`Window.todos`(`:39`)와 `TodoItem.window`(`:25`)에 `@JsonIgnore`가 붙어 있습니다. 이게 없으면:
- `Window.todos` → 지연로딩 컬렉션을 직렬화하려다 쿼리가 나가거나(OSIV가 켜져 있으므로 조용히 성공), 순환 참조로 무한 루프
- `TodoItem.window` → `Window` → `todos` → `TodoItem` → ... 무한 순환

즉 이 두 애너테이션 중 하나만 지워지면 응답이 깨집니다. 엔티티는 DB 매핑을 표현하는 클래스이고 JSON 직렬화 규칙을 함께 담기에는 목적이 다릅니다.

**(4) `open-in-view: false`로 전환할 수 없습니다.**

`02-performance.md` P1-3에서 OSIV를 끄자고 했는데, 엔티티를 직접 반환하는 구조에서는 **직렬화가 트랜잭션 밖에서 일어나므로** 지연로딩 필드가 하나라도 열려 있으면 `LazyInitializationException`이 납니다. 이 P1-1이 그 작업의 선행 조건입니다.

### 수정 방향

응답 DTO를 만들어 컨트롤러 경계에서 변환합니다.

```java
// dto/WindowResponse.java (신규)
public record WindowResponse(
        Long id,
        WindowType type,
        String[] url,
        String[] urlTitles,
        int zIndex, int x, int y, int width, int height,
        String createdAt
) {
    public static WindowResponse from(Window w) {
        return new WindowResponse(
                w.getId(), w.getType(), w.getUrl(), w.getUrlTitles(),
                w.getZIndex(), w.getX(), w.getY(), w.getWidth(), w.getHeight(),
                w.getCreatedAt() != null ? w.getCreatedAt().toString() : null);
    }
}
```

`userId`가 빠졌습니다 — 클라이언트에 줄 이유가 없습니다.

```diff
--- a/src/main/java/com/camstudy/backend/controller/WindowController.java
+++ b/src/main/java/com/camstudy/backend/controller/WindowController.java
     @GetMapping
-    public List<Window> getWindows(@AuthenticationPrincipal String userEmail) {
-        return windowService.findAllByUser(userEmail);
+    public List<WindowResponse> getWindows(@AuthenticationPrincipal String userEmail) {
+        return windowService.findAllByUser(userEmail).stream()
+                .map(WindowResponse::from)
+                .toList();
     }
```

`Timer`도 동일하게 `TimerEntryResponse`를 만들고 `TimerMonthlyResponse`가 그걸 담게 합니다.

**프론트 계약이 바뀝니다.** `userId` 필드가 사라지므로, 프론트가 그 값을 쓰고 있다면 먼저 확인하세요.
`[[확인필요: 프론트가 GET /windows, GET /timer 응답의 userId 필드를 사용하는지]]`

이미 프로젝트에 `TodoResponse.from(TodoItem)`(`dto/TodoResponse.java:17-28`)이라는 좋은 선례가 있습니다. 같은 형태로 맞추면 됩니다.

---

## P1-2. 컨트롤러가 오케스트레이션과 도메인 계산을 하고 있음

### (a) `RoomController.list`가 두 조회를 조합

**위치** `controller/RoomController.java:36-52`

```java
    @GetMapping
    public Page<RoomDto> list(@PageableDefault(...) Pageable pageable) {
        Page<Room> roomPage = roomService.list(pageable);              // 조회 1

        List<String> ids = roomPage.getContent().stream()
                                    .map(r -> r.getId())
                                    .toList();

        Map<String, Long> counts = roomService.activeCounts(ids);      // 조회 2

        return roomPage.map(room ->
            RoomDto.from(room, counts.getOrDefault(room.getId(), 0L))  // 조합
        );
    }
```

"방 목록과 각 방의 활성 인원 수를 합쳐서 보여준다"는 **업무 규칙**이 컨트롤러에 있습니다. 배치 조회를 쓴 것 자체는 잘 되어 있습니다(`01-jpa-query.md`에서 확인) — 위치가 문제입니다.

왜 문제인가:
- 두 조회가 **서로 다른 트랜잭션**에서 실행됩니다(`RoomService`에 `@Transactional` 없음 → `03-transaction.md`). 그 사이에 인원이 바뀌면 목록과 카운트가 불일치합니다.
- WebSocket이나 다른 진입점에서 같은 데이터가 필요해지면 이 조합 로직을 복사해야 합니다.
- 테스트하려면 `MockMvc`로 HTTP 계층까지 띄워야 합니다. 서비스에 있으면 단위 테스트가 가능합니다(`11-test.md`).

수정:

```diff
--- a/src/main/java/com/camstudy/backend/service/RoomService.java
+++ b/src/main/java/com/camstudy/backend/service/RoomService.java
+    @Transactional(readOnly = true)
+    public Page<RoomDto> listWithMemberCount(Pageable pageable) {
+        Page<Room> rooms = roomRepo.findAll(pageable);
+        Map<String, Long> counts = activeCounts(
+                rooms.getContent().stream().map(Room::getId).toList());
+        return rooms.map(r -> RoomDto.from(r, counts.getOrDefault(r.getId(), 0L)));
+    }
```

```diff
--- a/src/main/java/com/camstudy/backend/controller/RoomController.java
+++ b/src/main/java/com/camstudy/backend/controller/RoomController.java
     public Page<RoomDto> list(@PageableDefault(...) Pageable pageable) {
-        Page<Room> roomPage = roomService.list(pageable);
-        List<String> ids = ...;
-        Map<String, Long> counts = roomService.activeCounts(ids);
-        return roomPage.map(room -> RoomDto.from(room, counts.getOrDefault(room.getId(), 0L)));
+        return roomService.listWithMemberCount(pageable);
     }
```

`Pageable`을 서비스가 받는 것에 대해 "웹 계층 타입이 서비스로 들어온다"는 지적이 가능하지만, Spring Data의 `Pageable`은 `org.springframework.data.domain` 패키지이고 웹 전용이 아니므로 실무에서는 통상 허용합니다. `Page<RoomDto>` 반환은 `02-performance.md` P2-2에서 다룬 대로 별도 정리 대상입니다.

### (b) `RoomController.join` / `RoomAdminController.kick`이 서비스 메서드를 순차 호출

**위치** `controller/RoomController.java:63-72`, `:76-80`, `controller/RoomAdminController.java:19-23`

`03-transaction.md` P1-1에서 트랜잭션 관점으로, `05-security.md` P0-1에서 인가 관점으로 다뤘습니다. 레이어 관점에서 요점은 하나입니다.

```java
        var room = roomService.getOrThrow(roomId);
        roomService.verifyPassword(room, req.password());
        roomService.ensureCapacity(room);
        var member = roomService.upsertActiveMember(room, req.userId());
```

**"방 입장 절차"라는 도메인 규칙의 순서가 컨트롤러에 적혀 있습니다.** 비밀번호를 확인하고, 정원을 확인하고, 멤버를 활성화한다 — 이 순서를 컨트롤러가 알고 있으면, 다른 진입점(예: WebSocket 연결 시 자동 입장)을 만들 때 순서를 다시 적어야 하고 하나를 빠뜨릴 수 있습니다. `kick`에서 권한 검사가 빠진 것이 정확히 그 결과입니다.

`RoomController.java:70`의 `String role = (member.getRole() == Role.HOST) ? "HOST" : "MEMBER";`도 같은 성격입니다. `Role.name()`으로 충분하고, `Role`이 3개로 늘면 이 삼항 연산자는 조용히 틀린 답을 냅니다.

수정 방향은 `03-transaction.md` P1-1과 `05-security.md` P0-1의 diff와 동일합니다 — 서비스에 `join`/`leave`/`kick` 메서드를 만들고 컨트롤러는 한 번만 호출합니다.

### (c) `TimerController`가 "오늘"을 계산하고 문자열을 파싱

**위치** `controller/TimerController.java:24-33`, `:79-87`

```java
// :28-32
        ZoneId userZone = ZoneId.of(tz);
        timerService.record(userEmail,
            Instant.parse(req.getStartAt()),      // ← 컨트롤러가 파싱
            Instant.parse(req.getEndAt()),
            userZone);
```

```java
// :84-86
        ZoneId userZone = ZoneId.of(tz);
        LocalDate today = LocalDate.now(userZone);    // ← 컨트롤러가 "오늘"을 결정
        timerService.updatePomoCycles(userEmail, today, req.cycles());
```

두 가지가 문제입니다.

**같은 프로젝트 안에서 일관되지 않습니다.** `TimerService.getTodayTime`(`:140`)과 `getTimerAnalytics`(`:94`)는 **서비스가** `LocalDate.now(userZone)`을 계산합니다. `updatePomoCycles`만 컨트롤러가 계산해서 넘깁니다. "오늘이 언제인가"는 도메인 판단이고 한 곳에 있어야 합니다.

**`ZoneId.of(tz)`가 6곳에 반복됩니다.** `TimerController:28`, `:42`, `:55`, `:64`, `:84`, `:96` / `TodoController:42` / `GlobalTodoController:32`. 매번 `@RequestHeader(value = "X-User-Timezone", defaultValue = "UTC")`와 짝을 이룹니다. 헤더 이름과 기본값이 8곳에 복붙되어 있어, 하나를 바꾸면 전부 찾아야 합니다.

그리고 `ZoneId.of()`와 `Instant.parse()`, `LocalDate.parse()`(`:95`)는 모두 **잘못된 입력에 예외를 던집니다.** 컨트롤러에서 던지면 `@Valid` 검증 흐름을 타지 못하고 500으로 나갑니다 → `04-exception.md`, `07-api-design.md`.

수정:

```java
// config/UserTimezoneResolver.java — 커스텀 아규먼트 리졸버
@Component
public class UserTimezoneResolver implements HandlerMethodArgumentResolver {
    @Override public boolean supportsParameter(MethodParameter p) {
        return ZoneId.class.equals(p.getParameterType());
    }
    @Override public Object resolveArgument(MethodParameter p, ModelAndViewContainer m,
                                            NativeWebRequest req, WebDataBinderFactory f) {
        String tz = req.getHeader("X-User-Timezone");
        try {
            return (tz == null || tz.isBlank()) ? ZoneOffset.UTC : ZoneId.of(tz);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("invalid X-User-Timezone: " + tz);
        }
    }
}
```

`WebConfig`에 등록하면 컨트롤러가 이렇게 짧아집니다.

```diff
     @PatchMapping("/today/cycles")
-    public void updatePomoCycles(@RequestBody CyclesRequest req,
-            @RequestHeader(value = "X-User-Timezone", defaultValue = "UTC") String tz,
-            @AuthenticationPrincipal String userEmail) {
-        ZoneId userZone = ZoneId.of(tz);
-        LocalDate today = LocalDate.now(userZone);
-        timerService.updatePomoCycles(userEmail, today, req.cycles());
+    public void updatePomoCycles(@RequestBody CyclesRequest req,
+                                 ZoneId userZone,
+                                 @AuthenticationPrincipal String userEmail) {
+        timerService.updatePomoCyclesToday(userEmail, userZone, req.cycles());
     }
```

"오늘" 계산은 서비스로 옮깁니다. `Instant.parse`도 요청 DTO의 필드 타입을 `Instant`로 바꾸면 Jackson이 처리하고, 실패 시 `HttpMessageNotReadableException`(400)이 됩니다 → `07-api-design.md`.

---

## P1-3. 도메인 예외 타입이 없어서 같은 의미에 5가지 예외가 쓰임

**위치** 서비스 5개 전체

grep으로 확인한 서비스 계층의 예외 사용:

| 의미 | 사용된 예외 | 위치 |
|---|---|---|
| 리소스 없음 | `RuntimeException("존재하지 않는 사용자")` | `AuthService:26` |
| 리소스 없음 | `ResponseStatusException(NOT_FOUND)` | `RoomService:41` |
| 리소스 없음 | `RuntimeException("Window not found")` | `WindowService:38`, `:61`, `:72` |
| 리소스 없음 | `IllegalArgumentException("TodoItem not found")` | `TodoService:151`, `:161` |
| 리소스 없음 | `IllegalArgumentException("User not found")` | `TimerService:92`, `:139`, `:161`, `:168` |
| 권한 없음 | `SecurityException("Permission denied")` | `TodoService:35`, `:154`, `:163` |
| 권한 없음 | `RuntimeException("접근 권한이 없습니다.")` | `WindowService:42`, `:75` |
| 권한 없음 | `ResponseStatusException(FORBIDDEN)` | `RoomService:51`, `:59` |
| 중복 | `RuntimeException("이미 존재하는 이메일입니다.")` | `AuthService:64` |

### 왜 문제인가

**의미가 타입이 아니라 문자열에 담겨 있습니다.**

`GlobalExceptionHandler`(`controller/GlobalExceptionHandler.java:13-25`)가 이 상황을 그대로 드러냅니다.

```java
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> handleRuntimeException(RuntimeException e) {
        String message = e.getMessage();
        HttpStatus status = switch (message) {
            case "존재하지 않는 사용자", "비밀번호 불일치",
                 "리프레시 토큰이 유효하지 않습니다.", "리프레시 토큰이 존재하지 않습니다." -> HttpStatus.UNAUTHORIZED;
            case "이미 존재하는 이메일입니다." -> HttpStatus.CONFLICT;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return ResponseEntity.status(status).body(Map.of("message", message));
    }
```

**한국어 메시지 문자열이 HTTP 상태 코드를 결정합니다.** 결과:

- `SecurityException("Permission denied")` → switch에 없음 → **500** (403이어야 함)
- `IllegalArgumentException("Window not found")` → **500** (404여야 함)
- `RuntimeException("접근 권한이 없습니다.")` → **500** (403이어야 함)
- 메시지 오타를 내면 그 경로가 401에서 500으로 조용히 바뀝니다
- `05-security.md` P2-1에서 "로그인 실패 메시지를 통일하라"고 했는데, 그러면 **이 switch도 함께 고쳐야 하고, 안 고치면 500이 됩니다**

이건 예외 처리 문제로 보이지만(→ `04-exception.md`) **원인은 아키텍처입니다.** 도메인 예외 타입이 없어서, 계층 간 의미 전달을 문자열로 하고 있는 것입니다.

### 수정 방향

의미별 예외 타입을 만들고 핸들러가 **타입으로** 분기합니다.

```java
// exception/ 패키지 신규
public class NotFoundException extends RuntimeException { ... }
public class ForbiddenException extends RuntimeException { ... }
public class ConflictException extends RuntimeException { ... }
public class UnauthorizedException extends RuntimeException { ... }
```

```diff
--- a/src/main/java/com/camstudy/backend/controller/GlobalExceptionHandler.java
+++ b/src/main/java/com/camstudy/backend/controller/GlobalExceptionHandler.java
-    @ExceptionHandler(RuntimeException.class)
-    public ResponseEntity<Map<String, String>> handleRuntimeException(RuntimeException e) {
-        HttpStatus status = switch (e.getMessage()) { ... };
-        return ResponseEntity.status(status).body(Map.of("message", e.getMessage()));
-    }
+    @ExceptionHandler(NotFoundException.class)
+    ResponseEntity<ErrorResponse> handle(NotFoundException e) { return of(HttpStatus.NOT_FOUND, e); }
+
+    @ExceptionHandler(ForbiddenException.class)
+    ResponseEntity<ErrorResponse> handle(ForbiddenException e) { return of(HttpStatus.FORBIDDEN, e); }
+    ...
```

각 서비스의 `throw`를 이 타입들로 교체합니다. **문자열은 사람이 읽는 용도로만 쓰이고, 상태 코드는 타입이 결정합니다.**

`ResponseStatusException`을 전부 쓰는 것도 선택지이지만(`RoomService`가 이미 그렇게 함), 그건 `org.springframework.web` 타입을 서비스 계층에 들이는 것이라 웹 의존이 도메인으로 새어들어갑니다. 지금 규모에서는 실용적 타협으로 받아들일 수도 있습니다 — 다만 **한 가지 방식으로 통일**하는 것이 핵심입니다. 지금은 5가지입니다.

상세 설계와 응답 포맷은 `04-exception.md`에서 다룹니다.

---

## P1-4. 창 소유권 검증이 두 서비스에 중복 구현되어 있고 방식도 다름

**위치** `service/WindowService.java:41`, `:63`, `:74` / `service/TodoService.java:34`, `:54`, `:152-153`, `:162`

```java
// WindowService.java:41, :63, :74 — Objects.equals + RuntimeException
        if (!Objects.equals(window.getUserId(), userEmail)) {
            throw new RuntimeException("접근 권한이 없습니다.");
        }
```

```java
// TodoService.java:34 — String.equals + SecurityException
        if (!window.getUserId().equals(userEmail)) {
            throw new SecurityException("User does not have permission for this window");
        }

// TodoService.java:54 — Optional.filter 방식
        windowRepository.findById(winId)
            .filter(w -> w.getUserId().equals(userEmail))
            .orElseThrow(() -> new SecurityException("Permission denied"));

// TodoService.java:152-153 — 프록시를 거쳐 접근
        if (!todoItem.getWindow().getId().equals(winId)
         || !todoItem.getWindow().getUserId().equals(userEmail)) {
```

**"이 창이 이 사용자의 것인가"** 라는 동일한 규칙이 **7군데에 4가지 방식**으로 구현되어 있습니다.

### 왜 문제인가

**(1) `TodoService`가 `WindowRepository`를 직접 씁니다.**

`TodoService`는 `Window` 애그리거트의 리포지토리를 주입받아(`:28`) 소유권을 직접 검증합니다. 즉 `Window`의 소유 규칙을 `TodoService`도 알고 있어야 합니다. 규칙이 바뀌면(예: 공유 창 기능 추가) 두 서비스를 모두 고쳐야 하고, 한쪽을 빠뜨리면 우회 경로가 생깁니다.

**(2) `equals` vs `Objects.equals`의 차이가 실제 동작 차이를 만듭니다.**

`TodoService:34`의 `window.getUserId().equals(userEmail)`은 `getUserId()`가 `null`이면 **NPE**입니다. `Window.userId`에 `nullable = false` 제약이 없으므로(`entity/Window.java:42-43`) NULL 행이 존재할 수 있습니다. P0-1의 merge 경로로 생긴 행이나, 과거 데이터가 그럴 수 있습니다.

NPE는 `GlobalExceptionHandler`의 `switch (message)`에서 **`message`가 `null`이라 `NullPointerException`을 다시 던집니다** — `switch`에 `null`을 넣으면 NPE입니다. 즉 핸들러 자체가 터지고 컨테이너 기본 오류 페이지가 나갑니다 → `04-exception.md`.

`WindowService`의 `Objects.equals`는 null-safe해서 `false`를 반환합니다. **같은 규칙인데 한쪽은 안전하고 한쪽은 아닙니다.**

**(3) 예외 타입이 달라 응답 상태 코드가 다릅니다.**

`SecurityException`과 `RuntimeException("접근 권한이 없습니다.")` 모두 P1-3 때문에 500이 되므로 지금은 결과가 같지만, P1-3을 고칠 때 두 곳을 각각 찾아야 합니다.

### 수정 방향

소유권 확인을 **쿼리 조건으로 내리고**, 한 곳에서만 구현합니다.

```diff
--- a/src/main/java/com/camstudy/backend/repository/WindowRepository.java
+++ b/src/main/java/com/camstudy/backend/repository/WindowRepository.java
+    Optional<Window> findByIdAndUserId(Long id, String userId);
```

```diff
--- a/src/main/java/com/camstudy/backend/service/WindowService.java
+++ b/src/main/java/com/camstudy/backend/service/WindowService.java
+    /** 소유권 확인까지 포함한 단일 조회 지점 */
+    Window getOwned(Long id, String userEmail) {
+        return windowRepository.findByIdAndUserId(id, userEmail)
+                .orElseThrow(() -> new NotFoundException("window not found: " + id));
+    }
```

```diff
     public Window partialUpdate(Long id, WindowPatchDto dto, String userEmail) {
-        Window window = windowRepository.findById(id)
-                .orElseThrow(() -> new RuntimeException("Window not found"));
-        if (!Objects.equals(window.getUserId(), userEmail)) {
-            throw new RuntimeException("접근 권한이 없습니다.");
-        }
+        Window window = getOwned(id, userEmail);
```

이 형태의 장점은 **실패가 안전한 방향**이라는 것입니다. 조건을 빠뜨리면 결과가 나오지 않으므로(fail-closed), P0-1이나 `05-security.md` P0-1처럼 "검사를 잊었는데 동작은 한다"가 발생하지 않습니다.

`TodoService`는 `WindowRepository` 대신 소유권이 조건에 포함된 쿼리를 씁니다(`01-jpa-query.md` P2-3의 `findOwnedByUser`). 그러면 `TodoService`에서 `WindowRepository` 의존을 **완전히 제거**할 수 있습니다.

찾기 실패와 권한 없음을 모두 404로 합치는 것에 대해: 403을 주면 "그 id의 리소스는 존재한다"는 정보가 새므로(리소스 열거), **404로 합치는 것이 보안상 더 안전합니다.** 다만 API 계약 변경이므로 프론트와 합의가 필요합니다 → `07-api-design.md`.

---

## P1-5. `TodoService`에 Window-scoped / Global 메서드가 쌍으로 중복 (8개 → 4개)

**위치** `service/TodoService.java:79-97`, `:127-146`, `:149-166` / `controller/TodoController.java`, `controller/GlobalTodoController.java`

```java
// 사실상 같은 일을 하는 쌍
    updateDone(userEmail, winId, todoId, done)   ↔  updateDoneGlobal(userEmail, todoId, done)
    updateText(userEmail, winId, todoId, text)   ↔  updateTextGlobal(userEmail, todoId, text)
    deleteTodo(userEmail, winId, todoId)         ↔  deleteTodoGlobal(userEmail, todoId)
    getTodosByWindow(userEmail, winId, ...)      ↔  getAllTodosByUser(userEmail, ...)

// 헬퍼도 쌍
    findTodoItemForUser(userEmail, winId, todoId)  ↔  findTodoItemForUserGlobal(userEmail, todoId)
```

두 컨트롤러가 이 쌍을 각각 호출합니다.

| 경로 | 컨트롤러 |
|---|---|
| `PATCH /windows/{winId}/todos/{todoId}` (done) | `TodoController:51` |
| `PATCH /windows/{winId}/todos/{todoId}/text` | `TodoController:69` |
| `DELETE /windows/{winId}/todos/{todoId}` | `TodoController:61` |
| `PATCH /todos/{todoId}/done` | `GlobalTodoController:51` |
| `PATCH /todos/{todoId}` | `GlobalTodoController:41` |
| `DELETE /todos/{todoId}` | `GlobalTodoController:61` |

### 왜 문제인가

두 버전의 **유일한 차이는 `winId` 일치를 확인하는지**입니다(`TodoService:152` vs `:162`). 그런데 `todoId`는 전역 유일한 PK이므로, `winId`를 추가로 검증해서 얻는 것이 없습니다 — 그 투두가 속한 창은 하나뿐이고, 소유자 검증은 어차피 그 창을 통해 이뤄집니다.

즉 **`winId` 검증은 보안상 아무것도 추가하지 않으면서** 코드를 두 배로 만들고 있습니다.

실질적 위험은 비대칭입니다. 새 기능(예: 투두 순서 변경, 마감일 설정)을 추가할 때 **두 곳에 추가해야 하고, 한쪽을 빠뜨리면 두 경로가 다르게 동작합니다.** `TodoController`에는 `text` 수정이 `/text` 하위 경로인데(`:69`) `GlobalTodoController`에서는 루트 PATCH(`:41`)라는 불일치가 이미 있습니다 → `07-api-design.md`.

`TodoResponse` 매핑 코드도 두 컨트롤러에 복붙되어 있습니다(`TodoController:43-48` ↔ `GlobalTodoController:33-38`).

### 수정 방향

**Global 버전만 남기고 window-scoped 쓰기 엔드포인트를 제거하는 것**이 가장 단순합니다. 창 단위 조회(`GET /windows/{winId}/todos`)는 의미가 있으므로 남깁니다.

```diff
--- a/src/main/java/com/camstudy/backend/service/TodoService.java
+++ b/src/main/java/com/camstudy/backend/service/TodoService.java
-    public TodoItem updateDone(String userEmail, Long winId, Long todoId, boolean done) { ... }
-    public TodoItem updateText(String userEmail, Long winId, Long todoId, String text) { ... }
-    public void deleteTodo(String userEmail, Long winId, Long todoId) { ... }
-    private TodoItem findTodoItemForUser(String userEmail, Long winId, Long todoId) { ... }
```

`TodoController`는 `POST`(추가)와 `GET`(목록)만 남기고, 수정·삭제는 `/todos/{todoId}` 계열로 통합합니다.

**프론트 변경이 필요합니다.** 기존 경로를 유지해야 한다면, 컨트롤러 두 개가 **같은 서비스 메서드**를 호출하도록만 바꾸세요 — 서비스 중복은 제거하고 URL 계약은 유지됩니다.

```diff
--- a/src/main/java/com/camstudy/backend/controller/TodoController.java
+++ b/src/main/java/com/camstudy/backend/controller/TodoController.java
     public ResponseEntity<TodoResponse> updateDone(@PathVariable Long winId,
                                                    @PathVariable Long todoId, ...) {
         return ResponseEntity.ok(
-            TodoResponse.from(svc.updateDone(userEmail, winId, todoId, req.done()))
+            TodoResponse.from(svc.updateDone(userEmail, todoId, req.done()))
         );
     }
```

---

## P2-1. 요청/응답 DTO의 위치와 형식이 일관되지 않음

`dto` 패키지가 있는데도 컨트롤러 안에 DTO가 5개 정의되어 있습니다.

| DTO | 위치 | 형식 |
|---|---|---|
| `KickReq` | `RoomAdminController.java:16` | `public record` |
| `LeaveReq` | `RoomController.java:75` | `public record` |
| `CyclesRequest` | `TimerController.java:101` | `record` (package-private) |
| `RecordRequest` | `TimerController.java:103-108` | `static class`, **setter 없음** |
| `TimerMonthlyResponse` | `TimerController.java:110-119` | `static class` |

`dto` 패키지 안에서도 형식이 갈립니다.

- `record`: `AddTodoRequest`, `TodoResponse`, `TimerGoalRequest/Response`, `TodayTimeResponse`, `UpdateDoneRequest`, `UpdateTodoRequest`, `CreateRoomReq`, `JoinReq/Res`, `RoomDto`, `ChatMessage`
- Lombok 클래스: `LoginRequest`(`@Getter @Setter @NoArgsConstructor @AllArgsConstructor`), `SignupRequest`(동일), `WindowPatchDto`(`@Data`), `TimerAnalyticsResponse`(`@Getter @Builder`)
- 수동 클래스: `LoginResponse`

서브패키지도 `dto/room/`, `dto/chat/`만 있고 나머지는 flat입니다.

### 왜 신경 쓸 가치가 있는가

**`RecordRequest`가 특히 취약합니다.**

```java
    static class RecordRequest {
        private String startAt;
        private String endAt;
        public String getStartAt() { return startAt; }
        public String getEndAt() { return endAt; }
        // setter 없음, @JsonCreator 없음, 생성자 없음
    }
```

Jackson은 getter로 프로퍼티(`startAt`, `endAt`)를 인식하지만, 값을 넣을 mutator(setter/생성자 파라미터)가 없습니다. Jackson에는 이 경우 **private 필드로 fallback**하는 동작이 있어 실제로는 역직렬화가 될 것으로 보입니다.

`[[확인필요: POST /timer 가 실제로 startAt/endAt을 받아 동작하는지 — Jackson의 필드 fallback에 의존하고 있어 확실하지 않습니다. 만약 null이 들어오면 TimerController:30의 Instant.parse(null)에서 NPE → 500입니다]]`

이게 요점입니다 — **Jackson의 내부 동작에 의존해서 겨우 동작하는 코드**입니다. 같은 파일 세 줄 위의 `CyclesRequest`는 `record`라 명시적 생성자가 있어 이런 모호함이 없습니다. Jackson 버전이 올라가거나 `ObjectMapper` 설정이 바뀌면 조용히 깨질 수 있습니다.

### 수정 방향

규칙을 하나 정하고 맞추세요. 이 프로젝트에는 `record`가 이미 다수이므로 그쪽이 자연스럽습니다.

```diff
--- a/src/main/java/com/camstudy/backend/controller/TimerController.java
+++ b/src/main/java/com/camstudy/backend/controller/TimerController.java
-    static class RecordRequest {
-        private String startAt;
-        private String endAt;
-        public String getStartAt() { return startAt; }
-        public String getEndAt() { return endAt; }
-    }
```

```java
// dto/TimerRecordRequest.java (신규) — 타입도 String이 아니라 Instant로
public record TimerRecordRequest(
        @NotNull Instant startAt,
        @NotNull Instant endAt
) {}
```

`Instant`로 받으면 Jackson이 파싱하고, 실패 시 `HttpMessageNotReadableException`(400)이 됩니다. 컨트롤러의 `Instant.parse()`(`:30-31`)와 그로 인한 500이 함께 사라집니다.

DTO 5개를 모두 `dto` 패키지로 옮기고, 도메인별 서브패키지(`dto/room/`, `dto/timer/`, `dto/todo/`, `dto/window/`, `dto/auth/`)로 정리하면 `dto` 패키지가 17개 파일로 평평하게 쌓이는 것도 해결됩니다.

`LoginRequest`/`SignupRequest`의 `@Setter`도 불필요합니다 — 요청 DTO는 불변이어야 하고, `record`로 바꾸면 Lombok 애너테이션 4개가 사라집니다.

---

## P2-2. 서비스 간 의존은 0건이지만 리포지토리 교차 접근에 규칙이 없음

순환 참조는 없습니다(확인). 대신 서비스가 다른 애그리거트의 리포지토리를 직접 씁니다.

| 서비스 | 자기 애그리거트 | 교차 접근 |
|---|---|---|
| `TodoService` | `TodoItemRepository` | **`WindowRepository`** |
| `TimerService` | `TimerRepository` | **`UserRepository`** |
| `RoomService` | `RoomRepository`, `MemberRepository` | (Member는 Room 애그리거트 내부로 볼 수 있음) |

### 왜 문제인가

이 자체가 나쁜 설계는 아닙니다. 서비스끼리 호출하면 순환 참조가 생기기 쉬우므로, **리포지토리를 공유하는 편이 실용적**인 경우가 많습니다. 문제는 규칙이 없어서 결과가 갈린다는 점입니다.

- `TodoService`의 `WindowRepository` 접근은 **소유권 검증 로직 중복**을 낳았습니다(P1-4). 이건 P1-4의 방식으로 제거 가능합니다.
- `TimerService`의 `UserRepository` 접근은 `dailyGoalHours` 한 필드를 읽기 위한 것이고, 요청마다 반복됩니다(`02-performance.md` P1-1). 프로젝션 쿼리로 그 값만 가져오면 `User` 엔티티 전체를 로드할 필요가 없습니다.

```java
// repository/UserRepository.java
@Query("select u.dailyGoalHours from User u where u.email = :email")
Optional<Integer> findDailyGoalHoursByEmail(@Param("email") String email);
```

### 수정 방향

규칙을 명시해 두세요. 지금 규모에서는 이 정도로 충분합니다.

> - 서비스는 다른 서비스를 주입받지 않는다 (현재 지켜지고 있음)
> - 다른 애그리거트의 데이터가 필요하면 **필요한 값만** 프로젝션으로 조회한다
> - 다른 애그리거트의 **불변식(소유권, 정원 등)은 그 애그리거트의 서비스가 판정**한다

`CLAUDE.md`나 `docs/`에 적어두면 다음 사람이 같은 판단을 반복하지 않습니다.

---

## P2-3. 시간 타입이 엔티티마다 다름

| 엔티티 | 필드 | 타입 | 위치 |
|---|---|---|---|
| `Window` | `createdAt` | **`LocalDateTime`** | `entity/Window.java:46` |
| `TodoItem` | `createdAt` | `Instant` | `entity/TodoItem.java:37` |
| `Room` | `createdAt` | `Instant` | `entity/Room.java:27` |
| `Member` | `joinedAt` | `Instant` | `entity/Member.java:28` |
| `Timer` | `date` | `LocalDate` | `entity/Timer.java:22` |

### 왜 문제인가

`Instant`는 **시간축의 한 점**입니다. 타임존 정보를 포함하지 않지만 UTC 기준 절대 시각이므로 해석에 모호함이 없습니다.

`LocalDateTime`은 **달력상의 날짜와 시각**이고 오프셋이 없습니다. `2026-09-02T14:30`이 어느 지역의 14시 30분인지 그 값만으로는 알 수 없습니다.

`Window.java:50`의 `createdAt = LocalDateTime.now()`는 **JVM 기본 타임존**을 사용합니다. `Dockerfile`에 `TZ` 설정이 없으므로(`Dockerfile:1-29`) 컨테이너 기본값(보통 UTC)이 쓰이지만, 이건 환경에 의존합니다. 베이스 이미지가 바뀌거나 호스트에서 `TZ`를 주면 값의 의미가 조용히 달라지고, **이미 저장된 행과 새 행의 기준이 달라집니다.**

`Timer.date`가 `LocalDate`인 것은 올바릅니다 — "사용자 로컬 기준 하루"가 도메인 개념이고, 그래서 서비스가 `ZoneId`를 받아 날짜를 계산합니다(`TimerService.java:35-36`).

### 수정 방향

저장은 `Instant`로 통일하고, 사용자에게 보여줄 때만 타임존을 적용합니다.

```diff
--- a/src/main/java/com/camstudy/backend/entity/Window.java
+++ b/src/main/java/com/camstudy/backend/entity/Window.java
-import java.time.LocalDateTime;
+import org.hibernate.annotations.CreationTimestamp;
+import java.time.Instant;
...
-    @Column(name = "created_at")
-    private LocalDateTime createdAt;
-
-    @PrePersist
-    protected void onCreate() {
-        createdAt = LocalDateTime.now();
-    }
+    @CreationTimestamp
+    @Column(name = "created_at", nullable = false, updatable = false)
+    private Instant createdAt;
```

`TodoItem`이 이미 `@CreationTimestamp` + `Instant`를 쓰고 있으므로(`entity/TodoItem.java:35-37`) 같은 형태로 맞추는 것입니다. `updatable = false`는 P0-1의 `created_at` NULL 덮어쓰기도 함께 막습니다.

**컬럼 타입 마이그레이션이 필요합니다.** `ddl-auto: update`는 타입을 바꾸지 않으므로(`01-jpa-query.md` P0-1) Flyway 도입 후에 진행하세요. `datetime`에서 `datetime(6)`으로 가는 변환이라 값 손실은 없지만, 기존 값이 어느 타임존 기준인지 확인해야 합니다.

`[[확인필요: 운영 컨테이너의 JVM 기본 타임존 — /actuator/env 또는 로그의 타임스탬프로 확인]]`

---

## P2-4. `WebConfig`가 deprecated API로 사실상 아무 일도 하지 않음

**위치** `config/WebConfig.java:11-21`

```java
    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        UrlPathHelper urlPathHelper = new UrlPathHelper();
        // urlPathHelper.setUrlDecode(false);      ← 주석 처리됨
        configurer.setUrlPathHelper(urlPathHelper);
        configurer.setUseTrailingSlashMatch(true);
    }
```

### 왜 문제인가

**(1) `setUrlPathHelper(new UrlPathHelper())`는 기본값을 다시 넣는 것입니다.** 아무 효과가 없습니다. 커스터마이즈 줄이 주석 처리되어 있어 원래 의도했던 설정도 적용되지 않습니다.

**(2) `setUseTrailingSlashMatch`는 Spring Framework 6.0에서 deprecated되었습니다.** 이 프로젝트는 Boot 3.4.4(= Spring 6.2)이므로 deprecated 상태입니다. 향후 제거되면 컴파일이 깨집니다.

deprecated된 이유는 **같은 리소스에 두 개의 URL이 생기는 것이 바람직하지 않다**는 판단입니다. `/windows`와 `/windows/`가 모두 동작하면:

- 캐시가 두 항목으로 분리됩니다
- `05-security.md` P1-4에서 본 `startsWith` 기반 필터와 Spring Security의 경로 매칭이 서로 다른 판단을 할 여지가 생깁니다
- 로그와 메트릭이 두 경로로 갈립니다

**(3) 이 설정이 왜 켜졌는지가 불명확합니다.** 프론트가 trailing slash를 붙여 호출하는 곳이 있어서 켰을 가능성이 큽니다.

### 수정 방향

프론트가 trailing slash를 쓰지 않는다면 파일을 삭제하세요.

`[[확인필요: 프론트에서 API 경로 끝에 / 를 붙여 호출하는 곳이 있는지]]`

쓰고 있다면 프론트를 고치는 편이 낫습니다. 서버 설정으로 흡수하면 deprecated API에 묶입니다. 당장 프론트를 못 고치면 유지하되, **P1-2에서 제안한 `UserTimezoneResolver` 등록 장소로 이 클래스를 재사용**하세요 — 그러면 빈 설정 클래스가 실제 역할을 갖게 됩니다.

---

## P2-5. `util` 패키지 오용과 불필요한 컨트롤러

**(1) `JwtUtil`이 `util` 패키지에 있지만 도메인 로직입니다** — `util/JwtUtil.java`

`@Component`로 등록된 스프링 빈이고, `@Value`로 시크릿을 주입받고, 토큰 발급·검증이라는 **인증 도메인의 핵심 규칙**을 담고 있습니다. `util`은 보통 상태 없는 정적 헬퍼를 두는 곳입니다.

`05-security.md` P1-1(토큰 `typ` 클레임), P1-9(`Key` 객체화)에서 이 클래스를 크게 고칠 예정이므로, 그때 `auth`나 `security` 패키지로 옮기세요. 이름도 `JwtUtil`보다 `JwtTokenProvider`처럼 역할이 드러나는 쪽이 낫습니다.

**(2) `SecurityUtil`은 죽은 코드이고, 쓰이면 버그입니다** — `util/SecurityUtil.java:6-15`

호출부 0건입니다. `05-security.md` P2-3에서 다룬 대로 익명 사용자를 `"anonymousUser"` 문자열로 반환합니다. 삭제하세요.

**(3) `HelloController`는 남길 이유가 없습니다** — `controller/HelloController.java:7-12`

```java
    @GetMapping("/")
    public String hello() {
        return "Hello from Dockerized Spring Boot!";
    }
```

`GET /`가 permitAll이 아니므로 실제로는 401이 나갑니다(`05-security.md` P1-4). 배포 확인용이었다면 `/actuator/health`가 그 역할을 하고, `springBoot { buildInfo() }`(`build.gradle:47`)가 이미 `/actuator/info`에 버전을 노출합니다. `banner.txt`의 `"THIS IS THE LATEST CODE"`도 같은 목적의 흔적으로 보입니다.

삭제하고 헬스체크를 `/actuator/health`로 통일하세요 → `12-docker-deploy.md`.

**(4) `RoomAdminController`를 분리한 근거가 약합니다** — `controller/RoomAdminController.java`

`RoomService`를 쓰고 엔드포인트가 하나(`/kick`)뿐입니다. `RoomController`에 `@PostMapping("/{roomId}/kick")`으로 두는 것과 차이가 없습니다. "admin" 컨트롤러를 분리하는 것이 의미를 가지려면 **그 컨트롤러 전체에 인가 규칙이 걸려야** 하는데(예: 클래스 레벨 `@PreAuthorize`), 지금은 아무 규칙이 없습니다(`05-security.md` P0-1).

분리를 유지하려면 인가를 클래스 레벨로 올리세요. 그러면 "이 컨트롤러의 모든 엔드포인트는 방장 전용"이 구조로 표현되고, 새 엔드포인트를 추가할 때 검사를 빠뜨릴 수 없습니다.

---

## 요약

| 심각도 | 개수 | 항목 |
|---|---|---|
| **P0** | 1 | `POST /windows`가 `Window` 엔티티를 요청 바디로 받아 `merge` 경로 → 임의 창 탈취 + 투두 삭제 + `created_at` 소실 |
| **P1** | 5 | 엔티티가 응답으로 나가는 곳 7군데(프로젝트 내 규칙 반반) / 컨트롤러의 오케스트레이션·"오늘" 계산·문자열 파싱 / 도메인 예외 타입 부재로 같은 의미에 5가지 예외 + 상태 코드를 문자열이 결정 / 창 소유권 검증 7군데 4방식 중복(한쪽은 NPE 가능) / Todo 서비스 Global·Window 메서드 8개 중복 |
| **P2** | 5 | DTO 위치·형식 불일치(`RecordRequest`가 Jackson 내부 동작에 의존) / 리포지토리 교차 접근 규칙 부재 / 시간 타입 불일치(`LocalDateTime` vs `Instant`) / `WebConfig`가 deprecated API로 빈 설정 / `util` 패키지 오용, 불필요한 컨트롤러 |

**순환 참조는 없습니다.** 서비스 간 직접 의존 0건입니다.

## 확인이 필요한 항목 (6개)

1. **`SELECT id, user_id, created_at FROM windows WHERE created_at IS NULL`** — P0-1 경로로 이미 탈취된 창이 있는지
2. `POST /timer`가 실제로 `startAt`/`endAt`을 받아 동작하는지 (Jackson 필드 fallback 의존)
3. 프론트가 `GET /windows`·`GET /timer` 응답의 `userId` 필드를 쓰는지
4. `merge` 시 `orphanRemoval`로 투두가 실제 삭제되는지
5. 운영 컨테이너의 JVM 기본 타임존
6. 프론트가 API 경로 끝에 `/`를 붙여 호출하는 곳이 있는지

## 05-security.md에 추가되어야 할 항목

**P0-1은 보안 취약점입니다.** 이전 카테고리에서 놓쳤습니다. 인증만 하면 누구나 타인의 창을 탈취하고 데이터를 파괴할 수 있으므로, `05-security.md`의 P0 목록(현재 2건)에 **3번째 항목**으로 추가하고 수정 순서에서도 최우선에 두세요. 수정은 요청 DTO 도입 또는 `window.setId(null)` 한 줄입니다.

## 이 카테고리의 관통 주제

세 가지 P1이 모두 같은 뿌리에서 나옵니다 — **경계에서 타입이 바뀌지 않는다는 것.**

- 엔티티가 그대로 HTTP를 넘어가서 → P0-1(입력), P1-1(출력)
- 도메인 의미가 타입 대신 문자열로 전달되어서 → P1-3
- 같은 규칙이 타입 하나에 모이지 않고 흩어져서 → P1-4, P1-5

DTO와 도메인 예외 타입을 도입하는 것이 이 카테고리 수정의 대부분이고, 그게 `02-performance.md`의 `open-in-view: false`와 `04-exception.md`의 핸들러 정리를 가능하게 하는 선행 작업입니다.
