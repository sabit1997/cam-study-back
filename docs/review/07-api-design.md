# 07. API 설계

조사 범위: URI 규칙, 메서드/상태코드, `@Valid` 검증, 응답 포맷 일관성.
대상: HTTP 엔드포인트 34개 (`00-overview.md`의 엔드포인트 표).

## grep으로 확정한 사실

| 확인 항목 | 결과 |
|---|---|
| `@Valid` / `@Validated` | **0건** |
| `@NotNull` / `@NotBlank` / `@Size` / `@Min` / `@Max` / `@Email` / `@Pattern` / `@Positive` | **0건** |
| `jakarta.validation` 임포트 | **0건** |
| `build.gradle`의 `spring-boot-starter-validation` | **없음** |
| API 문서 도구 (springdoc / swagger) | **없음** |

즉 **요청 값 검증이 프로젝트 전체에 존재하지 않습니다.**

---

## P1-1. 요청 검증이 0건 — 검증 의존성 자체가 없어서 `@Valid`를 붙여도 무시됨

**위치** `build.gradle:20-43`, 요청 DTO 전체

```gradle
dependencies {
  implementation 'org.springframework.boot:spring-boot-starter-web'
  implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
  implementation 'org.springframework.boot:spring-boot-starter-security'
  implementation 'org.springframework.boot:spring-boot-starter-websocket'
  implementation 'org.springframework.boot:spring-boot-starter-actuator'
  ...
  // spring-boot-starter-validation 없음
}
```

### 먼저 알아야 할 것: `@Valid`를 붙이는 것만으로는 동작하지 않습니다

Spring Boot **2.3부터 `spring-boot-starter-web`에서 검증 기능이 분리**되었습니다. 그전에는 web starter가 Hibernate Validator를 함께 가져왔지만, 지금은 별도 starter를 명시해야 합니다.

그리고 검증기가 없을 때의 동작이 문제입니다. `@Valid`는 **런타임에 조용히 무시됩니다.**

- Spring Boot의 `ValidationAutoConfiguration`은 클래스패스에 검증 구현체(Hibernate Validator)가 있을 때만 `Validator` 빈을 만듭니다.
- `Validator` 빈이 없으면 컨트롤러 파라미터 바인딩 시 검증 단계가 그냥 통과합니다.
- **컴파일 에러도, 부팅 경고도, 런타임 예외도 없습니다.**

즉 지금 상태에서 DTO에 `@NotBlank`를 달고 컨트롤러에 `@Valid`를 붙이면, **검증이 되는 것처럼 보이는 코드가 되고 실제로는 아무 검증도 하지 않습니다.** 이게 가장 위험한 형태입니다 — 코드를 읽으면 방어가 있다고 판단하게 되니까요.

**따라서 의존성 추가가 반드시 선행되어야 합니다.**

```diff
--- a/build.gradle
+++ b/build.gradle
   implementation 'org.springframework.boot:spring-boot-starter-web'
+  implementation 'org.springframework.boot:spring-boot-starter-validation'
   implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
```

### 현재 검증 부재로 인한 구체적 피해

소스에서 코드 경로를 추적해 정리했습니다. 실행해 확인하지는 않았습니다.

| # | 엔드포인트 | 입력 | 결과 |
|---|---|---|---|
| 1 | `POST /auth/signup` | `{"email":"","password":"","username":""}` | **가입 성공.** 빈 문자열 계정 생성 |
| 2 | `POST /auth/signup` | `{}` (전부 null) | `email=null`로 저장. SQL에서 `email = NULL`은 절대 매칭되지 않으므로 `findByEmail(null)`이 항상 빈 결과 → **같은 요청을 반복해도 계속 성공** → NULL 이메일 계정이 무한히 쌓임 |
| 3 | `POST /auth/signup` | `{"email":"not-an-email"}` | 성공 (형식 검증 없음) |
| 4 | `POST /rooms` | `{"capacity": 2147483647}` | 정원 사실상 무제한 |
| 5 | `POST /rooms` | `{"capacity": 0}` 또는 음수 | `ensureCapacity`가 `active >= 0`을 평가 → **항상 "room is full"** → 아무도 입장할 수 없는 방이 생성됨 |
| 6 | `POST /rooms` | `{"name": null}` | 이름 없는 방 |
| 7 | `POST /windows/{id}/todos` | 256자 이상 `text` | `text` 컬럼이 `varchar(255)`(`TodoItem.java:28`) → `DataIntegrityViolationException` → **500 + SQL 전문 노출** (`04-exception.md` P1-1의 15번) |
| 8 | `POST /windows/{id}/todos` | `{"text": null}` | `text=null` 투두 생성 |
| 9 | `PATCH /todos/{todoId}` | `{"done": true}` | **텍스트가 null로 지워지고 200** (아래 P1-2) |
| 10 | `PATCH /windows/{w}/todos/{t}` | `{"text": "..."}` | **done이 false로 바뀌고 200** (아래 P1-2) |
| 11 | `POST /timer/goal` | `{"hour": 0}` | 달성률 응답이 `NaN` (`04-exception.md` P1-1의 17번) |
| 12 | `POST /timer/goal` | `{"hour": -5}` | 달성률이 음수 |
| 13 | `PATCH /timer/today/cycles` | `{"cycles": -1}` | 음수 사이클 저장 |
| 14 | `POST /timer` | `endAt` < `startAt` | 조용히 아무 일도 하지 않고 200 |
| 15 | `POST /timer` | 자정을 넘는 구간 | **무한 루프** (`02-performance.md` P0-1) |
| 16 | `GET /timer?year=0&month=13` | | `DateTimeException` → **500** |
| 17 | `GET /todos?date=garbage` | | `DateTimeParseException` → **500** |
| 18 | `GET /todos?order=DESCENDING` | | 조용히 오름차순 (아래 P2-5) |

2번과 5번이 특히 나쁩니다 — **한 번 만들어지면 정상 경로로 되돌릴 수 없는 데이터**를 만듭니다.

2번은 `01-jpa-query.md` P1-1(유니크 제약)과 겹칩니다. 유니크 제약을 걸면 두 번째 NULL 행부터는... MySQL의 유니크 인덱스는 **NULL을 중복으로 보지 않습니다**(`NULL != NULL`). 따라서 유니크 제약만으로는 2번이 막히지 않습니다. `nullable = false`가 함께 필요합니다.

### 수정 방향

**1단계 — 의존성 추가** (위 diff)

**2단계 — 요청 DTO에 제약 선언**

```diff
--- a/src/main/java/com/camstudy/backend/dto/SignupRequest.java
+++ b/src/main/java/com/camstudy/backend/dto/SignupRequest.java
-@Getter @Setter @NoArgsConstructor @AllArgsConstructor
-public class SignupRequest {
-    private String email;
-    private String password;
-    private String username;
-}
+public record SignupRequest(
+        @NotBlank @Email @Size(max = 254) String email,
+        @NotBlank @Size(min = 8, max = 72) String password,
+        @NotBlank @Size(min = 1, max = 30) String username
+) {}
```

`password`의 `max = 72`는 BCrypt의 제약입니다 — BCrypt는 입력의 **앞 72바이트만** 사용하므로, 그보다 긴 비밀번호는 뒷부분이 조용히 무시됩니다. 상한을 명시하지 않으면 사용자가 100자 비밀번호를 설정해도 실제로는 72바이트만 검증됩니다.

```diff
--- a/src/main/java/com/camstudy/backend/dto/room/CreateRoomReq.java
+++ b/src/main/java/com/camstudy/backend/dto/room/CreateRoomReq.java
 public record CreateRoomReq(
-        String name,
-        Integer capacity,
-        String password,
-        String ownerId
+        @NotBlank @Size(max = 50) String name,
+        @Min(2) @Max(12) Integer capacity,        // null 허용 → 기본 6
+        @Size(max = 72) String password            // null 허용 → 공개 방
 ) { }
```

`ownerId`는 제거합니다 — `05-security.md` P0-1에서 다룬 대로 인증 주체로 대체합니다.

```diff
--- a/src/main/java/com/camstudy/backend/dto/AddTodoRequest.java
+++ b/src/main/java/com/camstudy/backend/dto/AddTodoRequest.java
-public record AddTodoRequest(String text) {
-}
+public record AddTodoRequest(@NotBlank @Size(max = 255) String text) {}
```

`max = 255`가 DB 컬럼 길이와 일치해야 합니다(7번). 컬럼을 늘리려면 엔티티에 `@Column(length = ...)`을 명시하고 마이그레이션하세요 — `ddl-auto: update`는 길이 변경을 반영하지 않습니다(`01-jpa-query.md` P0-1).

```diff
--- a/src/main/java/com/camstudy/backend/dto/TimerGoalRequest.java
+++ b/src/main/java/com/camstudy/backend/dto/TimerGoalRequest.java
-public record TimerGoalRequest(int hour) {
-}
+public record TimerGoalRequest(@Min(1) @Max(24) int hour) {}
```

`@Min(1)`이 11번(`NaN`)과 12번(음수)을 한 번에 막습니다.

**3단계 — 컨트롤러에 `@Valid`**

```diff
--- a/src/main/java/com/camstudy/backend/controller/AuthController.java
+++ b/src/main/java/com/camstudy/backend/controller/AuthController.java
     @PostMapping("/signup")
-    public String signup(@RequestBody SignupRequest signupRequest) {
+    public ResponseEntity<Void> signup(@Valid @RequestBody SignupRequest req) {
```

`@RequestBody`가 붙은 모든 파라미터(11곳)에 `@Valid`를 추가합니다.

**4단계 — 검증 실패 응답 처리**

`@Valid` 실패는 `MethodArgumentNotValidException`을 던집니다. 이건 `BindException` → `Exception` 계열(**checked**)이라 `04-exception.md` P0-1의 `@ExceptionHandler(RuntimeException.class)`에 잡히지 않고, `DefaultHandlerExceptionResolver`가 **400**을 반환합니다. 상태 코드는 맞지만 응답 포맷이 `{"timestamp","status","error","path"}`이고 **어느 필드가 왜 틀렸는지 나오지 않습니다** (`server.error.include-binding-errors` 기본값 `never`).

`04-exception.md` P0-1의 핸들러에 이걸 추가하세요.

```java
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ValidationErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        List<FieldErrorDetail> errors = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldErrorDetail(fe.getField(), fe.getDefaultMessage()))
                .toList();
        return ResponseEntity.badRequest()
                .body(new ValidationErrorResponse("VALIDATION_FAILED", "입력값이 올바르지 않습니다.", errors));
    }
```

이렇게 하면 프론트가 필드별로 오류를 표시할 수 있습니다. 지금은 "400이 났다"는 것만 알 수 있습니다.

**5단계 — DB 제약도 함께** (검증은 우회될 수 있으므로)

```diff
--- a/src/main/java/com/camstudy/backend/entity/User.java
+++ b/src/main/java/com/camstudy/backend/entity/User.java
-    private String email;
+    @Column(nullable = false, unique = true, length = 254)
+    private String email;
+
+    @Column(nullable = false)
+    private String password;
```

애플리케이션 검증은 "친절한 오류 메시지", DB 제약은 "최후 방어선"입니다. 둘 다 필요합니다 — `03-transaction.md` P1-2에서 다룬 이유와 같습니다.

---

## P1-2. 같은 필드를 수정하는 PATCH의 경로 규칙이 두 컨트롤러에서 정확히 반대 — 잘못 부르면 조용히 데이터가 지워짐

**위치** `controller/TodoController.java:51`, `:69` / `controller/GlobalTodoController.java:41`, `:51`

```java
// TodoController (경로: /windows/{winId}/todos)
    @PatchMapping("/{todoId}")        public ... updateDone(...)   // :51  루트 = done
    @PatchMapping("/{todoId}/text")   public ... updateText(...)   // :69  하위 = text
```

```java
// GlobalTodoController (경로: /todos)
    @PatchMapping("/{todoId}")        public ... updateTodoTextGlobal(...)  // :41  루트 = text
    @PatchMapping("/{todoId}/done")   public ... toggleDoneGlobal(...)      // :51  하위 = done
```

표로 보면 명확합니다.

| 바꾸려는 필드 | window-scoped | global |
|---|---|---|
| `done` | `PATCH /windows/{w}/todos/{t}` **(루트)** | `PATCH /todos/{t}/done` (하위) |
| `text` | `PATCH /windows/{w}/todos/{t}/text` (하위) | `PATCH /todos/{t}` **(루트)** |

**완전히 반대입니다.** 그리고 두 루트 경로는 서로 다른 요청 바디를 기대합니다.

- `PATCH /windows/{w}/todos/{t}` → `UpdateDoneRequest(boolean done)`
- `PATCH /todos/{t}` → `UpdateTodoRequest(String text)`

### 왜 문제인가 — 틀리면 예외가 아니라 데이터 손실입니다

Spring Boot는 Jackson의 `FAIL_ON_UNKNOWN_PROPERTIES`를 **기본적으로 비활성화**합니다. 즉 DTO에 없는 JSON 필드는 조용히 버려집니다. 그리고 record의 누락된 필드는 기본값(`boolean`→`false`, `String`→`null`)으로 채워집니다.

**시나리오 A** — 프론트가 텍스트를 수정하려고 global 루트 경로를 씁니다.

```
PATCH /todos/5
{"text": "장보기"}
→ 정상 동작
```

**시나리오 B** — 같은 프론트 개발자가 window-scoped 경로에서도 같은 규칙을 기대합니다.

```
PATCH /windows/1/todos/5
{"text": "장보기"}
```

- `UpdateDoneRequest`로 역직렬화됩니다
- `text`는 알 수 없는 필드 → 버려짐
- `done`은 누락 → `boolean` 기본값 **`false`**
- `TodoService.updateDone`이 `todoItem.setDone(false)` 실행
- **응답 200**, 바디는 `{"id":5,"text":"기존 텍스트","done":false,...}`

→ **텍스트는 바뀌지 않았고, 완료 상태가 미완료로 되돌아갔습니다.** 오류 응답이 없습니다.

**시나리오 C** — 반대 방향이 더 나쁩니다.

```
PATCH /todos/5
{"done": true}
```

- `UpdateTodoRequest(String text)`로 역직렬화 → `text = null`
- `TodoService.updateTextGlobal:129-133`이 `todoItem.setText(null)` 실행
- `@Transactional` 안이므로 dirty checking이 `UPDATE todo_items SET text = NULL`을 발행
- `TodoItem.text`에 `nullable = false`가 없으므로(`entity/TodoItem.java:28`) **성공**
- 응답 200, 바디는 `{"id":5,"text":null,"done":false,...}`

→ **투두 텍스트가 영구히 삭제됩니다.** 사용자는 완료 체크를 눌렀을 뿐입니다.

이건 P1-1(검증 부재)과 P1-2(경로 규칙 반전)가 곱해진 결과입니다. `@NotBlank`가 `UpdateTodoRequest.text`에 있으면 시나리오 C가 400으로 막힙니다.

### 수정 방향

**우선 P1-1의 검증을 넣어 데이터 손실을 막습니다.** 이게 급합니다.

```diff
--- a/src/main/java/com/camstudy/backend/dto/UpdateTodoRequest.java
+++ b/src/main/java/com/camstudy/backend/dto/UpdateTodoRequest.java
-public record UpdateTodoRequest(String text) {
-}
+public record UpdateTodoRequest(@NotBlank @Size(max = 255) String text) {}
```

```diff
--- a/src/main/java/com/camstudy/backend/dto/UpdateDoneRequest.java
+++ b/src/main/java/com/camstudy/backend/dto/UpdateDoneRequest.java
-public record UpdateDoneRequest(boolean done) {
-}
+// primitive boolean은 누락 시 false가 되어 구분이 불가능하므로 래퍼 + @NotNull
+public record UpdateDoneRequest(@NotNull Boolean done) {}
```

`Boolean`(래퍼)으로 바꾼 것이 핵심입니다. `boolean`(primitive)은 **"false를 보냈다"와 "안 보냈다"를 구분할 수 없습니다.** 래퍼면 누락 시 `null`이 되고 `@NotNull`이 400을 냅니다. `TodoService`의 파라미터 타입도 `Boolean`으로 바꾸거나 컨트롤러에서 언박싱하세요.

**그다음 경로 규칙을 통일합니다.** `06-architecture.md` P1-5에서 제안한 대로 window-scoped 쓰기 엔드포인트를 제거하는 것이 가장 깔끔합니다. 유지해야 한다면 규칙을 하나로 맞추세요.

```
PATCH /todos/{todoId}         → 부분 수정 (text, done 모두 optional)
PATCH /windows/{w}/todos/{t}  → 위와 동일한 동작
```

즉 **하나의 PATCH가 여러 필드를 optional로 받는 형태**가 REST의 PATCH 시맨틱에 맞습니다. `WindowPatchDto`(`dto/WindowPatchDto.java`)가 이미 그 방식입니다 — 모든 필드가 nullable이고 `null`이 아닌 것만 반영합니다(`WindowService.java:46-53`). 같은 패턴을 투두에도 쓰면 됩니다.

```java
public record TodoPatchRequest(
        @Size(max = 255) String text,   // null이면 변경 안 함
        Boolean done                     // null이면 변경 안 함
) {}
```

```java
    @Transactional
    public TodoItem patch(String userEmail, Long todoId, TodoPatchRequest req) {
        TodoItem item = findOwned(userEmail, todoId);
        if (req.text() != null) item.setText(req.text());
        if (req.done() != null) item.setDone(req.done());
        return item;
    }
```

`/text`와 `/done` 하위 경로 4개가 사라지고, 서비스 메서드 6개가 1개가 됩니다.

**주의**: 이 방식은 "명시적으로 null로 설정"을 표현할 수 없습니다(JSON `null`과 "필드 없음"을 Jackson 기본 설정으로는 구분하지 못합니다). 투두 텍스트를 null로 만들 필요가 없으므로 여기서는 문제되지 않습니다.

---

## P1-3. 상태 코드가 대부분 200 — 생성에 201이, 빈 응답에 204가 쓰이지 않음

전수 조사 결과입니다.

| 엔드포인트 | 반환 타입 | 실제 상태 | 적절한 상태 |
|---|---|---|---|
| `POST /auth/login` | `LoginResponse` | 200 | 200 ✓ |
| `POST /auth/signup` | `String` | 200 | **201** |
| `POST /auth/logout` | `String` | 200 | **204** |
| `POST /auth/refresh` | `String` | 200 | **204** |
| `POST /rooms` | `RoomDto` | 200 | **201** + `Location` |
| `POST /rooms/{id}/leave` | `void` | 200 | **204** |
| `POST /rooms/{id}/admin/kick` | `ResponseEntity<Void>` | 204 ✓ | 204 ✓ |
| `POST /windows` | `Window` | 200 | **201** + `Location` |
| `DELETE /windows/{id}` | `void` | 200 | **204** |
| `POST /windows/{winId}/todos` | `ResponseEntity` | **201** ✓ | 201 ✓ |
| `DELETE /windows/{w}/todos/{t}` | `ResponseEntity<Void>` | **204** ✓ | 204 ✓ |
| `DELETE /todos/{todoId}` | `ResponseEntity<Void>` | **204** ✓ | 204 ✓ |
| `POST /timer` | `void` | 200 | **204** |
| `POST /timer/goal` | `TimerGoalResponse` | 200 | 200 (단 **PUT**이 적절) |
| `PATCH /timer/today/cycles` | `void` | 200 | **204** |
| `DELETE /timer/day/{date}` | `void` | 200 | **204** |
| `GET` 계열 12개 | | 200 | 200 ✓ |

**`TodoController`와 `GlobalTodoController`는 전부 올바릅니다.** `RoomAdminController`도 204를 씁니다. 나머지 4개 컨트롤러(`Auth`, `Room`, `Window`, `Timer`)가 200으로 통일되어 있습니다. → **같은 프로젝트에서 규칙이 갈려 있습니다.**

### 왜 문제인가

**(1) `void` 반환은 `200 + 빈 바디`입니다. 이게 204와 다릅니다.**

Spring이 `void` 반환 메서드를 처리하면 상태 200에 `Content-Length: 0`을 보냅니다. 프론트에서:

```js
const res = await fetch('/timer', { method: 'POST', ... });
const data = await res.json();   // SyntaxError: Unexpected end of JSON input
```

`res.json()`이 빈 바디를 파싱하려다 실패합니다. 200이니까 성공인데, 응답 처리 코드가 터집니다.

204는 **"본문이 없다"는 것이 HTTP 프로토콜 수준의 계약**입니다. `res.status === 204`로 분기할 수 있고, 많은 HTTP 클라이언트가 204를 자동으로 "바디 없음"으로 처리합니다. 200+빈바디는 "성공했지만 바디가 있을 수도 있고 없을 수도 있다"는 모호한 상태입니다.

**(2) 201에는 `Location` 헤더가 함께 가야 의미가 있습니다.**

`POST /rooms`가 201을 주고 `Location: /rooms/{id}`를 함께 주면, 클라이언트가 생성된 리소스의 위치를 응답 바디 파싱 없이 알 수 있습니다. 지금은 200이고 `Location`도 없습니다.

`POST /windows/{winId}/todos`는 201을 주지만(`TodoController.java:29`) `Location`은 없습니다.

**(3) `POST /timer/goal`은 멱등이므로 PUT이 맞습니다.**

`updateTimerGoal`(`TimerService.java:166-171`)은 `user.setDailyGoalHours(newHour)` — 같은 요청을 몇 번 보내도 결과가 같습니다. POST는 "멱등하지 않을 수 있음"을 의미하므로, 클라이언트나 프록시가 재시도를 꺼립니다. PUT이면 네트워크 오류 시 안전하게 재시도할 수 있습니다.

`GET /timer/goal`과 짝을 이루므로 `PUT /timer/goal`이 자연스럽습니다.

### 수정 방향

```diff
--- a/src/main/java/com/camstudy/backend/controller/TimerController.java
+++ b/src/main/java/com/camstudy/backend/controller/TimerController.java
     @PostMapping
-    public void recordTime(@RequestBody RecordRequest req, ...) {
+    public ResponseEntity<Void> recordTime(@Valid @RequestBody TimerRecordRequest req, ...) {
         ...
+        return ResponseEntity.noContent().build();
     }
```

`ResponseEntity`를 쓰지 않고 애너테이션으로 처리할 수도 있습니다.

```diff
     @PostMapping
+    @ResponseStatus(HttpStatus.NO_CONTENT)
     public void recordTime(...)
```

`@ResponseStatus`가 더 짧지만, `ResponseEntity`는 `Location` 헤더 같은 것을 함께 실을 수 있습니다. 생성 엔드포인트는 `ResponseEntity`, 단순 204는 `@ResponseStatus`로 나누는 것이 실용적입니다.

```diff
--- a/src/main/java/com/camstudy/backend/controller/RoomController.java
+++ b/src/main/java/com/camstudy/backend/controller/RoomController.java
     @PostMapping
-    public RoomDto create(@RequestBody CreateRoomReq req) {
-        var room = roomService.create(req.name(), req.capacity(), req.password(), req.ownerId());
-        return RoomDto.from(room, 0L);
+    public ResponseEntity<RoomDto> create(@Valid @RequestBody CreateRoomReq req,
+                                          @AuthenticationPrincipal String userEmail) {
+        var room = roomService.create(req.name(), req.capacity(), req.password(), userEmail);
+        return ResponseEntity
+                .created(URI.create("/rooms/" + room.getId()))
+                .body(RoomDto.from(room, 0L));
     }
```

**프론트 변경이 필요합니다.** 200을 기대하는 코드가 201/204를 오류로 처리하고 있을 수 있습니다.
`[[확인필요: 프론트가 res.ok(200-299)로 판단하는지, res.status === 200으로 판단하는지]]`
전자면 안전하게 바꿀 수 있습니다. 후자면 프론트와 동시에 배포해야 합니다.

---

## P1-4. 응답 포맷이 6종 — 그중 문자열 응답 3곳은 Content-Type이 클라이언트에 따라 달라짐

| # | 형태 | 예시 | 엔드포인트 수 |
|---|---|---|---|
| 1 | JSON 객체 | `{"userId":1,"username":"..."}` | 12 |
| 2 | JSON 배열 | `[{...},{...}]` | 3 |
| 3 | Spring Data `Page` 랩핑 | `{"content":[...],"pageable":{...},...}` | 1 |
| 4 | 엔티티 직렬화 | `Window`, `Timer` (`06-architecture.md` P1-1) | 7 |
| 5 | **문자열** | `회원가입 완료` | 4 |
| 6 | 빈 바디 (`void`) | | 6 |

### 5번(문자열 응답)이 가장 문제입니다

**위치** `controller/AuthController.java:26`, `:31`, `:36` / `controller/HelloController.java:9`

```java
    @PostMapping("/signup")
    public String signup(@RequestBody SignupRequest signupRequest) {
        return authService.signup(...);      // "회원가입 완료"
    }
```

`@RestController`에서 `String`을 반환하면 응답 Content-Type이 **클라이언트가 보낸 `Accept` 헤더에 따라 달라집니다.**

Spring MVC는 `Accept` 헤더와 등록된 메시지 컨버터가 지원하는 타입을 교차시켜 응답 타입을 정합니다. `String`을 쓸 수 있는 컨버터가 둘 있습니다.

- `StringHttpMessageConverter` → `text/plain`, `*/*` 지원. 컨버터 목록에서 **앞쪽**
- `MappingJackson2HttpMessageConverter` → `application/json` 지원

따라서:

| 클라이언트 | `Accept` | 예상 응답 |
|---|---|---|
| `fetch` (Accept 미지정) | 브라우저가 `*/*` 전송 | `text/plain` → 바디 `회원가입 완료` (따옴표 없음) |
| axios (기본값) | `application/json, text/plain, */*` | `application/json` → 바디 `"회원가입 완료"` (JSON 문자열) |

`[[확인필요: 실제 응답의 Content-Type — curl -i -H 'Accept: */*' 와 -H 'Accept: application/json' 두 경우를 비교해 확인하세요. 실행해보지 않았습니다]]`

**어느 쪽이든 문제입니다.**

- `text/plain`이면 `res.json()`이 실패합니다. 프론트가 이 엔드포인트만 `res.text()`로 처리해야 합니다.
- `application/json`이면 파싱은 되지만 결과가 **문자열**입니다. 다른 모든 엔드포인트는 객체를 주는데 여기만 문자열이라, `data.message` 같은 접근이 안 됩니다.
- 그리고 이 문자열이 **API 계약**이 됩니다. `"회원가입 완료"`를 `"가입이 완료되었습니다"`로 바꾸면 프론트가 문자열 비교를 하고 있었다면 깨집니다. 한국어 사용자 메시지를 서버가 결정하는 구조라 다국어 지원도 막힙니다.

### 수정 방향

**성공 응답에 알릴 내용이 없으면 204를, 있으면 객체를 주세요.**

```diff
--- a/src/main/java/com/camstudy/backend/controller/AuthController.java
+++ b/src/main/java/com/camstudy/backend/controller/AuthController.java
     @PostMapping("/signup")
-    public String signup(@RequestBody SignupRequest signupRequest) {
-        return authService.signup(signupRequest.getEmail(), signupRequest.getPassword(), signupRequest.getUsername());
+    public ResponseEntity<Void> signup(@Valid @RequestBody SignupRequest req) {
+        authService.signup(req.email(), req.password(), req.username());
+        return ResponseEntity.status(HttpStatus.CREATED).build();
     }
```

```diff
     @PostMapping("/logout")
-    public String logout(HttpServletResponse response) {
-        return authService.logout(response);
+    public ResponseEntity<Void> logout() {
+        return ResponseEntity.noContent()
+                .header(HttpHeaders.SET_COOKIE, cookieFactory.expire("AccessToken").toString())
+                .header(HttpHeaders.SET_COOKIE, cookieFactory.expire("RefreshToken").toString())
+                .build();
     }
```

`AuthService`가 `String`을 반환하지 않게 되면 `HttpServletResponse` 의존도 함께 제거됩니다(`03-transaction.md` P2-2, `05-security.md` P2-5).

**사용자에게 보여줄 메시지는 프론트가 결정하는 것이 맞습니다.** 서버는 "무슨 일이 일어났는지"(상태 코드 + 필요시 코드값)를 알리고, 표현은 클라이언트가 합니다.

4번(엔티티 직렬화)은 `06-architecture.md` P1-1, 3번(`Page`)은 `02-performance.md` P2-2에서 다뤘습니다.

---

## P2-1. URI 명명 규칙이 일관되지 않음

**(1) 단수/복수 혼용**

| 리소스 | 경로 |
|---|---|
| 방 | `/rooms` (복수) |
| 창 | `/windows` (복수) |
| 투두 | `/todos` (복수) |
| 타이머 | **`/timer`** (단수) |

`/timer`는 컬렉션(월별 목록)도 반환하고(`GET /timer`) 개별 날짜도 다룹니다(`DELETE /timer/day/{date}`). `/timers`가 규칙에 맞습니다.

엔티티 테이블명도 같은 불일치가 있습니다 — `timer`만 단수(`01-jpa-query.md` P2-4).

**(2) 같은 리소스를 지정하는 방식이 두 가지**

```
PATCH  /timer/today/cycles       ← 경로에 "today", 실제 날짜는 서버가 계산
DELETE /timer/day/{date}         ← 경로에 날짜
GET    /timer/today              ← 경로에 "today"
```

"하루치 타이머"라는 같은 리소스를 `today`(상대)와 `{date}`(절대) 두 방식으로 가리킵니다. `today`는 클라이언트의 타임존 헤더에 의존하므로 같은 URL이 사용자마다 다른 리소스를 의미합니다.

일관되게 하려면 날짜를 항상 경로에 두고, `today`는 클라이언트가 계산합니다.

```
GET    /timers/{date}
PATCH  /timers/{date}            ← cycles, totalSeconds 등을 부분 수정
DELETE /timers/{date}
GET    /timers?year=&month=
```

이렇게 하면 `PATCH /timer/today/cycles`가 `PATCH /timers/2026-09-02` + `{"pomoCycles": 3}`이 되고, `06-architecture.md` P1-2에서 지적한 "컨트롤러가 오늘을 계산하는 문제"도 사라집니다.

**프론트 변경이 큽니다.** 우선순위는 낮게 두세요.

**(3) 동사가 URI에 있는 경우**

`/auth/login`, `/auth/logout`, `/auth/signup`, `/auth/refresh` — 인증은 관례적으로 동사 URI를 씁니다. 그대로 두세요.

`/rooms/{id}/join`, `/leave`, `/windows/{id}/focus` — 상태 전이 액션입니다. 실무에서 널리 쓰이고, "멤버 리소스 생성/삭제"로 바꾸는 것보다 의도가 명확합니다. 그대로 두는 것을 권합니다.

`/rooms/{roomId}/admin/kick` — 이건 다릅니다. `admin`이라는 세그먼트가 **인가 경계를 암시하는데 그걸 강제하는 코드가 없습니다**(`05-security.md` P0-1). 그리고 `kick`은 "다른 사용자의 멤버십 제거"이므로 리소스 표현이 가능합니다.

```
DELETE /rooms/{roomId}/members/{userId}
```

이 형태의 장점은 **대상이 경로에 드러난다**는 것입니다. 지금은 body의 `userId`에 있어서 로그와 접근 제어에서 보이지 않습니다.

**(4) `GET /`**

`HelloController`(`controller/HelloController.java:8-11`)는 삭제 대상입니다(`06-architecture.md` P2-5). 헬스체크는 `/actuator/health`로 통일하세요.

---

## P2-2. `DELETE /timer/day/{date}`가 리소스를 삭제하지 않음

**위치** `controller/TimerController.java:89-98`, `service/TimerService.java:188-195`

```java
    @DeleteMapping("/day/{date}")
    public void resetDailyTimer(@PathVariable String date, ...) { ... }
```

```java
    @Transactional
    public void resetDailyTimer(String userEmail, LocalDate date, ZoneId userZone) {
        repo.findByUserIdAndDate(userEmail, date)
            .ifPresent(timer -> {
                timer.setTotalSeconds(0);      // ← 행을 지우지 않고 값을 초기화
                timer.setPomoCycles(0);
                repo.save(timer);
            });
    }
```

### 왜 문제인가

DELETE는 "리소스를 제거한다"는 의미입니다. 그 결과로 이후 `GET`이 404를 반환할 것이라는 기대가 따라옵니다.

실제로는 행이 남고 값만 0이 됩니다. 따라서 `GET /timer/today`는 삭제 후에도 **그 리소스를 200으로 반환합니다**(`totalSeconds: 0`). 클라이언트가 "삭제됐으니 없겠지"라고 가정하면 어긋납니다.

또한 `ifPresent`라서 기록이 없는 날짜를 DELETE해도 200입니다(`04-exception.md` P2-1).

### 수정 방향

**의미를 맞추는 두 가지 선택.**

(a) 실제로 삭제합니다.

```diff
     @Transactional
     public void resetDailyTimer(String userEmail, LocalDate date) {
-        repo.findByUserIdAndDate(userEmail, date)
-            .ifPresent(timer -> { timer.setTotalSeconds(0); timer.setPomoCycles(0); repo.save(timer); });
+        repo.deleteByUserIdAndDate(userEmail, date);
     }
```

`Timer` 행은 `(userId, date)`마다 하나이고 다른 테이블이 참조하지 않으므로 삭제해도 안전합니다. 그리고 `TimerService.getTodayTime:141-148`이 행이 없으면 0으로 채운 응답을 만들어 주므로(`orElseGet`) 프론트 동작이 달라지지 않습니다.

(b) 초기화라는 의미를 URI에 드러냅니다.

```
POST /timers/{date}/reset
```

(a)가 더 단순하고 REST 시맨틱에 맞습니다. `01-jpa-query.md`에서 지적한 "행을 남겨 `totalSeconds=0`이 되는 것"이 `04-exception.md` P1-1의 17번(`NaN`) 조건 중 하나였으므로, (a)를 택하면 그 경로도 하나 줄어듭니다.

또한 `userZone` 파라미터가 본문에서 쓰이지 않습니다(`:188`) — 제거하세요.

---

## P2-3. `ResponseEntity` 사용이 컨트롤러 단위로 갈림

| 컨트롤러 | 스타일 |
|---|---|
| `TodoController` | `ResponseEntity<T>` (5개 전부) |
| `GlobalTodoController` | `ResponseEntity<T>` (4개 전부) |
| `RoomAdminController` | `ResponseEntity<Void>` |
| `AuthController` | 직접 반환 (4개) |
| `RoomController` | 직접 반환 (5개) |
| `TimerController` | 직접 반환 (8개) |
| `WindowController` | 직접 반환 (5개) |

두 스타일 자체는 모두 유효합니다. 문제는 **어느 쪽을 쓸지 판단 기준이 없다**는 것이고, 그래서 P1-3의 상태 코드 불일치가 생겼습니다 — `ResponseEntity`를 쓴 컨트롤러들이 201/204를 제대로 쓰고, 직접 반환하는 컨트롤러들이 전부 200입니다.

기준을 정하세요.

> - 상태 코드가 200이 아니거나 헤더를 실어야 하면 `ResponseEntity`
> - 그 외 단순 조회는 직접 반환 + 필요시 `@ResponseStatus`

P1-3을 적용하면 생성·삭제 엔드포인트가 `ResponseEntity`로, 조회는 직접 반환으로 정리됩니다.

---

## P2-4. API 문서가 없음

`build.gradle`에 `springdoc-openapi`나 `swagger` 관련 의존성이 없습니다. `docs/` 디렉터리도 이 리뷰 전에는 없었습니다.

즉 **프론트가 API 계약을 알 수 있는 유일한 방법이 백엔드 소스를 읽는 것**입니다. 이 리뷰의 `00-overview.md` 엔드포인트 표가 현재 존재하는 유일한 목록입니다.

이게 P1-2(경로 규칙 반전)와 P1-4(문자열 응답)가 발견되지 않은 채 남아 있던 이유이기도 합니다 — 두 컨트롤러의 PATCH 규칙이 반대라는 사실은 문서가 있으면 표로 나란히 보이면서 즉시 드러납니다.

### 수정 방향

```diff
--- a/build.gradle
+++ b/build.gradle
+  implementation 'org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.5'
```

`/swagger-ui.html`과 `/v3/api-docs`가 자동 생성됩니다. 코드에서 스펙을 뽑으므로 문서가 코드와 어긋나지 않습니다.

**보안 주의**: 이 경로들을 permitAll에 넣으면 API 전체 구조가 공개됩니다. `05-security.md` P1-5의 actuator와 같은 문제입니다.

```yaml
# application-local.yml — 로컬에서만 활성화
springdoc:
  api-docs:
    enabled: true
  swagger-ui:
    enabled: true
```

```yaml
# application.yml (운영 기본값)
springdoc:
  api-docs:
    enabled: false
  swagger-ui:
    enabled: false
```

기본을 끄고 로컬에서만 켜는 방향을 권합니다. `10-config.md`의 profile 분리와 함께 진행하세요.

---

## P2-5. `order` 파라미터가 잘못된 값을 조용히 무시

**위치** `service/TodoService.java:72-74`, `:121-123`

```java
        Sort sort = "desc".equalsIgnoreCase(order)
            ? Sort.by(Sort.Direction.DESC, "createdAt")
            : Sort.by(Sort.Direction.ASC,  "createdAt");
```

`?order=desc`가 아닌 모든 값(`DESCENDING`, `descend`, `oldest`, 오타, `null`)이 **오름차순으로 조용히 처리**됩니다.

### 왜 문제인가

프론트가 `?order=descending`을 보내면 서버는 200과 오름차순 결과를 반환합니다. 개발자는 "정렬이 안 되네"라고 판단하고, 원인이 파라미터 이름인지 값인지 서버 버그인지 알 수 없습니다.

`done` 파라미터는 이 문제가 없습니다 — `@RequestParam(required = false) Boolean done`이므로 `?done=maybe`는 Spring의 타입 변환이 실패해 **400**이 됩니다. 즉 같은 엔드포인트 안에서 한 파라미터는 검증되고 다른 하나는 조용히 무시됩니다.

### 수정 방향

enum으로 받으면 Spring이 변환을 검증합니다.

```java
public enum TodoOrder { ASC, DESC }
```

```diff
--- a/src/main/java/com/camstudy/backend/controller/GlobalTodoController.java
+++ b/src/main/java/com/camstudy/backend/controller/GlobalTodoController.java
-            @RequestParam(required = false) String order,
+            @RequestParam(defaultValue = "ASC") TodoOrder order,
```

`?order=garbage`는 `MethodArgumentTypeMismatchException` → 400이 됩니다. 대소문자 무관 매칭이 필요하면 `Converter`를 등록하거나 enum 상수를 소문자로 두세요.

`Sort` 계산도 서비스에서 enum으로 분기합니다.

```java
Sort sort = Sort.by(order == TodoOrder.DESC ? Sort.Direction.DESC : Sort.Direction.ASC, "createdAt");
```

이 로직이 `getTodosByWindow`와 `getAllTodosByUser`에 복붙되어 있으므로(`06-architecture.md` P1-5) 통합하면 한 곳만 남습니다.

---

## 요약

| 심각도 | 개수 | 항목 |
|---|---|---|
| **P0** | 0 | — (단 `06-architecture.md` P0-1의 `@RequestBody Window`가 API 설계 문제이기도 합니다) |
| **P1** | 4 | 요청 검증 0건 + validation 의존성 없음(`@Valid`를 붙여도 무시됨) / PATCH 경로 규칙이 두 컨트롤러에서 반대 → 잘못 부르면 텍스트가 null로 지워지고 200 / 상태 코드 6곳이 200(201·204 미사용) / 응답 포맷 6종, 문자열 응답의 Content-Type이 클라이언트 `Accept`에 따라 달라짐 |
| **P2** | 5 | URI 단수·복수 혼용 및 날짜 지정 방식 2가지 / `DELETE`가 삭제하지 않음 / `ResponseEntity` 사용 불일치 / API 문서 없음 / `order` 파라미터 조용한 무시 |

## 이 카테고리에서 가장 급한 것

**P1-2의 시나리오 C입니다.**

```
PATCH /todos/5
{"done": true}
→ 200 OK, 그리고 투두 텍스트가 NULL로 영구 삭제
```

`UpdateTodoRequest(String text)`에 `@NotBlank` 한 줄이면 막히는데, 그러려면 **`spring-boot-starter-validation` 의존성이 먼저 추가되어야 합니다**(없으면 애너테이션이 무시됨). 이 두 변경이 이 카테고리에서 가장 비용 대비 효과가 큽니다.

`[[확인필요: 운영 DB에 이미 텍스트가 지워진 투두가 있는지 — SELECT id, window_id, created_at FROM todo_items WHERE text IS NULL OR text = '']]`

## 확인이 필요한 항목 (3개)

1. `POST /auth/signup` 응답의 실제 `Content-Type` — `curl -i -H 'Accept: */*'` vs `-H 'Accept: application/json'`
2. 프론트가 성공 판정을 `res.ok`로 하는지 `res.status === 200`으로 하는지 — P1-3 적용 시 동시 배포 필요 여부
3. `todo_items`에 `text`가 NULL이거나 빈 문자열인 행이 있는지

## 적용 순서

```
1. build.gradle에 spring-boot-starter-validation 추가        ← 2번의 전제
2. UpdateTodoRequest / UpdateDoneRequest 검증 (P1-2)          ← 데이터 손실 차단
3. 나머지 요청 DTO 검증 + @Valid 부착 (P1-1)
4. MethodArgumentNotValidException 핸들러 (04 P0-1과 함께)
5. 상태 코드 정리 (P1-3) — 프론트와 동시 배포
6. 문자열 응답 제거 (P1-4) — 03 P2-2의 HttpServletResponse 제거와 함께
7. springdoc 도입 (P2-4) — 로컬 전용으로
8. URI 정리 (P2-1, P2-2) — 프론트 변경이 크므로 마지막
```

실행·테스트는 하지 않았습니다. 위 입력별 결과는 소스의 코드 경로와 Jackson/Spring MVC의 기본 설정(`FAIL_ON_UNKNOWN_PROPERTIES` 비활성, 메시지 컨버터 우선순위)에 근거한 것이며, 각 엔드포인트를 실제로 호출해 확인해야 합니다.
