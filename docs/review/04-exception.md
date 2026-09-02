# 04. 예외 처리

조사 범위: 전역 핸들러, 삼켜지는 예외, 500으로 흘러나가는 경로, 클라이언트에 스택트레이스 노출.

## 확정한 사실

- `@RestControllerAdvice`는 **1개**, 그 안의 `@ExceptionHandler`도 **1개**뿐입니다 (`controller/GlobalExceptionHandler.java`).
- `application.yml`에 `server.error.*` 설정이 **없습니다** → 전부 Spring Boot 기본값.
  - `server.error.include-stacktrace: never`
  - `server.error.include-message: never`
  - `server.error.include-binding-errors: never`
- 따라서 **스택트레이스가 응답 바디에 실려 나가지는 않습니다.** (사용자가 물은 항목에 대한 직접 답)
  단, 아래 P0-1에서 보듯 **예외 메시지는 노출되고, 그 안에 SQL 전문과 클래스 경로가 들어갑니다.**

---

## P0-1. 전역 핸들러가 `RuntimeException` 전체를 가로채고 메시지 문자열로 상태 코드를 정함 — 정상 오류가 대부분 500이 됨

**위치** `src/main/java/com/camstudy/backend/controller/GlobalExceptionHandler.java:13-25`

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

### 왜 문제인가 (1) — 이 핸들러가 Spring의 기본 예외 처리보다 먼저 실행됩니다

Spring MVC는 예외를 세 개의 리졸버에 **순서대로** 넘깁니다.

| 순서 | 리졸버 | 하는 일 |
|---|---|---|
| 1 | `ExceptionHandlerExceptionResolver` | `@ExceptionHandler` 메서드를 찾아 실행 |
| 2 | `ResponseStatusExceptionResolver` | `ResponseStatusException`과 `@ResponseStatus`를 상태 코드로 변환 |
| 3 | `DefaultHandlerExceptionResolver` | Spring 표준 예외를 적절한 상태 코드로 변환 (400/404/405/415 등) |

`@ExceptionHandler(RuntimeException.class)`는 1번 단계에서 **`RuntimeException`의 모든 하위 타입**에 매칭됩니다. 매칭되면 2·3번은 실행되지 않습니다.

문제는 Spring의 표준 예외 상당수가 `RuntimeException` 하위라는 점입니다.

| 예외 | 원래 상태 코드 | 이 핸들러를 거친 후 |
|---|---|---|
| `ResponseStatusException` | 지정한 코드 (403/404 등) | **500** |
| `HttpMessageNotReadableException` (JSON 파싱 실패) | 400 | **500** |
| `DataIntegrityViolationException` | (500이 맞지만) | 500 + SQL 노출 |
| `JwtException` | — | **500** |

**`ResponseStatusException`이 특히 아픕니다.** `RoomService`는 이걸 제대로 쓰고 있습니다.

```java
// RoomService.java:41, :51, :59
    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "room not found"));
    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "wrong password");
    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "room is full");
```

`ResponseStatusException.getMessage()`는 `403 FORBIDDEN "wrong password"` 형태의 문자열을 반환합니다. switch의 어느 case에도 없으므로 `default` → **500**입니다.

즉 **방 비밀번호를 틀리면 403이 아니라 500**, **방이 꽉 차면 500**, **없는 방을 조회하면 404가 아니라 500**입니다. 서비스 코드는 올바르게 상태 코드를 지정했는데 핸들러가 그걸 버립니다.

### 왜 문제인가 (2) — 상태 코드가 한국어 문자열에 묶여 있습니다

`switch (message)`가 의미하는 바는, **예외의 의미가 타입이 아니라 메시지 문자열에 담겨 있다는 것**입니다(`06-architecture.md` P1-3의 근본 원인). 결과:

- 서비스에서 메시지를 오타 내면 그 경로가 401에서 500으로 **조용히** 바뀝니다.
- `05-security.md` P2-1에서 "로그인 실패 메시지를 통일하라"고 했습니다. 그걸 적용하면 `"존재하지 않는 사용자"`/`"비밀번호 불일치"`가 사라지므로, **이 switch도 함께 고쳐야 하고 안 고치면 로그인 실패가 500이 됩니다.**
- 같은 의미의 예외가 서비스마다 다른 타입·다른 문자열로 던져지고 있어(`06-architecture.md` P1-3의 표: 5가지 방식) 어느 것도 매칭되지 않습니다.

### 왜 문제인가 (3) — 내부 정보가 응답 바디로 나갑니다

`default` 분기에서도 `body(Map.of("message", message))`로 **예외 메시지를 그대로** 실어 보냅니다. 스택트레이스는 아니지만, 예외 메시지 자체가 내부 구조를 담고 있습니다.

| 예외 | 클라이언트가 받는 `message` |
|---|---|
| `DataIntegrityViolationException` | `could not execute statement [Duplicate entry 'a@b.com' for key 'users.uq_users_email'] [insert into users (...) values (...)]` → **테이블명·컬럼명·제약명·SQL 전문** |
| `JwtException` | `JWT expired at 2026-09-02T10:00:00Z. Current time: 2026-09-02T11:03:22Z` → **서버 시각과 토큰 만료 시각** |
| `HttpMessageNotReadableException` | `JSON parse error: Cannot deserialize value of type com.camstudy.backend.dto.LoginRequest ...` → **패키지 구조와 클래스 경로** |
| `LazyInitializationException` | `could not initialize proxy [com.camstudy.backend.entity.Window#42] - no Session` → **엔티티 경로와 id** |

Spring Boot는 `server.error.include-message`를 기본 `never`로 두어 정확히 이걸 막습니다. **이 커스텀 핸들러가 그 정책을 우회하고 있습니다.**

### 왜 문제인가 (4) — 예외가 로그에 남지 않습니다

이게 실무에서 가장 아픕니다.

`handleRuntimeException`은 예외를 응답으로 변환하기만 하고 **로그를 남기지 않습니다.** 그리고 `@ExceptionHandler`로 **처리된** 예외는 Spring도 로그를 남기지 않습니다 — `spring.mvc.log-resolved-exception` 기본값이 `false`이기 때문입니다.

결과: **운영에서 500이 나가도 서버 로그에 스택트레이스가 없습니다.** 클라이언트가 "500 났어요"라고 알려주면, 어느 코드 줄에서 무엇이 터졌는지 알 방법이 없습니다. `application.yml:26`의 `root: INFO`로도 안 보입니다 — 레벨 문제가 아니라 **로그 호출 자체가 없기** 때문입니다.

### 왜 문제인가 (5) — `message`가 `null`이면 핸들러가 터집니다

Java의 `switch`에 `null`을 넣으면 `NullPointerException`입니다. 메시지 없는 예외(`new RuntimeException()` 같은)가 오면 핸들러 안에서 NPE가 발생하고, Spring은 그 상황에서 원래 예외를 다시 전파합니다 → 컨테이너 기본 오류 처리 → `/error` → **응답 포맷이 달라집니다.**

프로젝트 코드에서 직접 던지는 예외는 모두 메시지가 있으므로 지금 이 경로를 확실히 타는 사례를 특정하지는 못했습니다. 다만 라이브러리·프레임워크 예외의 메시지가 `null`이 아니라는 보장은 없고, 방어가 한 줄이면 되는데 없습니다.

### 수정 방향

**타입으로 분기하고, 로그를 남기고, 메시지를 걸러 내보냅니다.**

1단계 — 도메인 예외 타입 (`06-architecture.md` P1-3과 같은 작업)

```java
// exception/ 패키지 신규
public abstract class AppException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    protected AppException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
}

public class NotFoundException   extends AppException { public NotFoundException(String m)   { super(HttpStatus.NOT_FOUND, "NOT_FOUND", m); } }
public class ForbiddenException  extends AppException { public ForbiddenException(String m)  { super(HttpStatus.FORBIDDEN, "FORBIDDEN", m); } }
public class UnauthorizedException extends AppException { public UnauthorizedException(String m) { super(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", m); } }
public class ConflictException   extends AppException { public ConflictException(String m)   { super(HttpStatus.CONFLICT, "CONFLICT", m); } }
public class BadRequestException extends AppException { public BadRequestException(String m) { super(HttpStatus.BAD_REQUEST, "BAD_REQUEST", m); } }
```

2단계 — 핸들러 재작성

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 우리가 의도적으로 던진 예외: 메시지를 그대로 클라이언트에 보여줘도 안전 */
    @ExceptionHandler(AppException.class)
    public ResponseEntity<ErrorResponse> handleApp(AppException e) {
        log.warn("handled: {} {} - {}", e.getStatus().value(), e.getCode(), e.getMessage());
        return ResponseEntity.status(e.getStatus())
                .body(new ErrorResponse(e.getCode(), e.getMessage()));
    }

    /** 요청 바디/파라미터가 잘못된 경우: 400 */
    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MethodArgumentNotValidException.class,
        MethodArgumentTypeMismatchException.class,
        IllegalArgumentException.class,
        DateTimeException.class
    })
    public ResponseEntity<ErrorResponse> handleBadRequest(Exception e) {
        log.warn("bad request: {}", e.toString());
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("BAD_REQUEST", "요청 형식이 올바르지 않습니다."));
    }

    /** 그 외 전부: 500. 메시지는 절대 노출하지 않고, 로그에 전체를 남긴다 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e, HttpServletRequest req) {
        log.error("unhandled exception at {} {}", req.getMethod(), req.getRequestURI(), e);
        return ResponseEntity.internalServerError()
                .body(new ErrorResponse("INTERNAL_ERROR", "서버 오류가 발생했습니다."));
    }
}
```

```java
public record ErrorResponse(String code, String message) {}
```

**세 가지가 달라집니다.**

- `ResponseStatusException`은 `AppException`이 아니고 `Exception` 핸들러보다 구체적인 매칭이 없으므로... 여기서 주의가 필요합니다. `@ExceptionHandler(Exception.class)`도 여전히 `ResponseStatusException`을 가로챕니다. `RoomService`를 `AppException` 계열로 전환하는 것이 정석이지만, 과도기에는 `ResponseStatusException`을 명시적으로 넘겨주는 핸들러가 필요합니다.

```java
    /** 과도기: RoomService가 아직 ResponseStatusException을 쓰는 동안 */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleRse(ResponseStatusException e) {
        log.warn("handled: {} - {}", e.getStatusCode(), e.getReason());
        return ResponseEntity.status(e.getStatusCode())
                .body(new ErrorResponse("ERROR", e.getReason()));
    }
```

- `log.error(..., e)`의 마지막 인자가 `Throwable`이면 SLF4J가 **스택트레이스를 함께 출력**합니다. 500이 났을 때 원인을 볼 수 있게 되는 지점입니다.
- 500 응답 바디에서 예외 메시지가 사라집니다. 대신 로그에 남습니다. **디버깅 정보는 서버에, 사용자에게는 일반 메시지** — 이게 원칙입니다.

3단계 — 서비스의 `throw`를 교체 (`06-architecture.md` P1-3의 표 참고)

```diff
--- a/src/main/java/com/camstudy/backend/service/WindowService.java
+++ b/src/main/java/com/camstudy/backend/service/WindowService.java
-                .orElseThrow(() -> new RuntimeException("Window not found"));
+                .orElseThrow(() -> new NotFoundException("window not found: " + id));
...
-            throw new RuntimeException("접근 권한이 없습니다.");
+            throw new ForbiddenException("접근 권한이 없습니다.");
```

**적용 순서 주의**: 2단계 핸들러를 먼저 넣고 3단계를 진행하면, 미전환 예외는 500으로 떨어지지만 **로그가 남아서** 무엇이 남았는지 알 수 있습니다. 순서를 반대로 하면 전환 중간 상태에서 원인을 못 봅니다.

---

## P1-1. 500으로 흘러나가는 경로 17개 — 대부분 클라이언트 입력이 트리거

아래는 소스에서 코드 경로를 추적해 정리한 것입니다. 실행해 확인하지는 않았습니다.

| # | 트리거 | 발생 예외 | 실제 응답 | 올바른 응답 | 위치 |
|---|---|---|---|---|---|
| 1 | 방 비밀번호 틀림 | `ResponseStatusException(403)` | **500** | 403 | `RoomService.java:51` |
| 2 | 방 정원 초과 | `ResponseStatusException(403)` | **500** | 409 | `RoomService.java:59` |
| 3 | 없는 방 조회 | `ResponseStatusException(404)` | **500** | 404 | `RoomService.java:41` |
| 4 | 남의 창 수정/삭제/포커스 | `RuntimeException("접근 권한이 없습니다.")` | **500** | 403 또는 404 | `WindowService.java:42`, `:64`, `:75` |
| 5 | 없는 창 | `RuntimeException("Window not found")` | **500** | 404 | `WindowService.java:38`, `:61`, `:72` |
| 6 | 남의 투두 수정 | `SecurityException("Permission denied")` | **500** | 403 또는 404 | `TodoService.java:35`, `:154`, `:163` |
| 7 | 없는 투두 | `IllegalArgumentException("TodoItem not found")` | **500** | 404 | `TodoService.java:151`, `:161` |
| 8 | 타이머 API 호출 시 유저 조회 실패 | `IllegalArgumentException("User not found")` | **500** | 401 | `TimerService.java:92`, `:139`, `:161`, `:168` |
| 9 | 만료·변조된 RefreshToken으로 갱신 | `JwtException` | **500** + 만료시각 노출 | 401 | `AuthService.java:100` |
| 10 | `X-User-Timezone: Asia/NoSuchCity` | `ZoneRulesException` | **500** | 400 | `TimerController.java:28`, `:42`, `:55`, `:64`, `:84`, `:96` / `TodoController.java:42` / `GlobalTodoController.java:32` |
| 11 | `GET /todos?date=2026-13-99` | `DateTimeParseException` | **500** | 400 | `TodoService.java:64`, `:113` |
| 12 | `GET /timer?year=2026&month=13` | `DateTimeException` | **500** | 400 | `TimerService.java:75` |
| 13 | `DELETE /timer/day/notadate` | `DateTimeParseException` | **500** | 400 | `TimerController.java:95` |
| 14 | `POST /timer` startAt/endAt 형식 오류 | `DateTimeParseException` | **500** | 400 | `TimerController.java:30-31` |
| 15 | 256자 이상 투두 텍스트 | `DataIntegrityViolationException` | **500** + SQL 노출 | 400 | `TodoItem.java:28` (`varchar(255)`) |
| 16 | 잘못된 JSON 바디 | `HttpMessageNotReadableException` | **500** | 400 | 전 엔드포인트 |
| 17 | `dailyGoalHours=0` 상태에서 analytics 조회 | (예외는 아님) `NaN` 응답 | 200 + 깨진 값 | 400 (입력 검증) | `TimerService.java:95-98` |

### 왜 문제인가

**(1) 4~8번이 특히 나쁩니다 — 정상적인 사용자 오류가 500입니다.**

"없는 창을 지우려 했다", "남의 투두를 수정하려 했다"는 **예상 가능한 상황**입니다. 500은 "서버가 고장났다"는 뜻이므로:
- 프론트가 재시도해야 할지 사용자에게 알려야 할지 판단할 수 없습니다
- 모니터링에서 실제 장애와 구분되지 않아, 알람이 울려도 무시하게 됩니다
- 404/403이면 프론트가 "이미 삭제된 항목입니다"처럼 안내할 수 있는데, 500이면 "오류가 발생했습니다"밖에 못 합니다

**(2) 10~14번은 클라이언트가 임의로 유발할 수 있습니다.**

`ZoneId.of()`, `LocalDate.parse()`, `Instant.parse()`가 컨트롤러/서비스에서 직접 호출되고(`06-architecture.md` P1-2), 실패 시 예외가 그대로 올라갑니다. 헤더 하나만 바꿔서 500을 만들 수 있으므로, 로그와 모니터링이 노이즈로 채워집니다.

10번은 8곳에 반복되어 있어 한 곳만 고쳐도 나머지가 남습니다.

**(3) 17번은 예외가 아니라 잘못된 값이 나가는 경우입니다.**

```java
// TimerService.java:95-98
        long dailyGoalSec = user.getDailyGoalHours() * 3600L;

        double achievementRateToday = repo.findByUserIdAndDate(userEmail, todayLocal)
                .map(e -> Math.min(100.0, (e.getTotalSeconds() * 100.0) / dailyGoalSec))
                .orElse(0.0);
```

`POST /timer/goal`에 `{"hour": 0}`을 보내면(`TimerGoalRequest`는 `record TimerGoalRequest(int hour)`이고 검증이 없습니다) `dailyGoalSec = 0`이 됩니다. 그 후:

- 오늘 타이머 행이 있고 `totalSeconds > 0` → `양수 / 0.0` = `Infinity` → `Math.min(100.0, Infinity)` = `100.0` (우연히 괜찮음)
- 오늘 타이머 행이 있고 `totalSeconds == 0` → `0.0 / 0.0` = **`NaN`** → `Math.min(100.0, NaN)` = **`NaN`**

`totalSeconds == 0`인 행은 실제로 만들어집니다 — `updatePomoCycles`(`:174-185`)나 `resetDailyTimer`(`:188-195`)가 그렇게 둡니다.

`double NaN`을 Jackson이 직렬화하면 `JsonGenerator.Feature.QUOTE_NON_NUMERIC_NUMBERS`가 기본 활성이라 **`"achievementRateToday": "NaN"`** — 즉 숫자 자리에 **문자열**이 나갑니다. JSON 문법은 유효하지만 타입이 바뀌므로, 프론트에서 `.toFixed()`를 호출하면 런타임 에러입니다.

`{"hour": -5}`면 `dailyGoalSec`이 음수가 되어 달성률이 **음수**로 나갑니다. 진행률 바가 뒤로 갑니다.

`[[확인필요: Jackson이 NaN을 "NaN" 문자열로 쓰는지 (QUOTE_NON_NUMERIC_NUMBERS 기본값) — 실행해 확인하지 않았습니다. 어느 쪽이든 프론트에서 number로 다룰 수 없는 값입니다]]`

### 수정 방향

**즉시 효과가 큰 것부터.**

**(a) 타입 변환을 프레임워크에 맡깁니다** — 10~14, 16번을 한 번에 해결

`String`으로 받아서 직접 파싱하는 대신, 파라미터 타입 자체를 시간 타입으로 선언하면 Spring이 변환하고 **실패 시 `MethodArgumentTypeMismatchException`(400)** 이 됩니다.

```diff
--- a/src/main/java/com/camstudy/backend/controller/TimerController.java
+++ b/src/main/java/com/camstudy/backend/controller/TimerController.java
     @DeleteMapping("/day/{date}")
     public void resetDailyTimer(
-            @PathVariable String date,
+            @PathVariable LocalDate date,
             @RequestHeader(value = "X-User-Timezone", defaultValue = "UTC") String timezone,
             @AuthenticationPrincipal String userEmail) {
-        LocalDate localDate = LocalDate.parse(date);
         ZoneId userZone = ZoneId.of(timezone);
-        timerService.resetDailyTimer(userEmail, localDate, userZone);
+        timerService.resetDailyTimer(userEmail, date, userZone);
     }
```

`POST /timer`의 요청 DTO도 `Instant` 타입으로 (`06-architecture.md` P2-1):

```java
public record TimerRecordRequest(Instant startAt, Instant endAt) {}
```

Jackson이 파싱하고, 실패하면 `HttpMessageNotReadableException`입니다. 그 예외를 400으로 매핑하는 것이 P0-1 수정에 포함되어 있습니다.

`X-User-Timezone`은 `06-architecture.md` P1-2의 `UserTimezoneResolver`에서 `IllegalArgumentException`으로 변환하면 400이 됩니다.

**(b) 서비스 예외를 도메인 타입으로** — 1~9번. P0-1의 3단계와 동일.

**(c) 입력 검증** — 15, 17번

`spring-boot-starter-validation`이 **build.gradle에 없습니다**(`build.gradle:20-43` 확인). Spring Boot 2.3부터 `starter-web`에서 분리되었으므로, 지금 `@Valid`를 붙여도 검증기가 없어 **조용히 무시됩니다.** 먼저 의존성을 추가해야 합니다.

```diff
--- a/build.gradle
+++ b/build.gradle
   implementation 'org.springframework.boot:spring-boot-starter-web'
+  implementation 'org.springframework.boot:spring-boot-starter-validation'
```

그다음 DTO에 제약을 걸고 컨트롤러에 `@Valid`를 붙입니다. 상세는 `07-api-design.md`에서 다룹니다.

17번의 방어적 수정도 함께 하세요 — 검증을 통과했더라도 0으로 나누지 않게:

```diff
--- a/src/main/java/com/camstudy/backend/service/TimerService.java
+++ b/src/main/java/com/camstudy/backend/service/TimerService.java
-        long dailyGoalSec = user.getDailyGoalHours() * 3600L;
+        long dailyGoalSec = Math.max(1L, user.getDailyGoalHours() * 3600L);
```

---

## P1-2. 에러 응답 포맷이 두 가지 — 프론트가 두 형태를 모두 처리해야 함

**위치** `controller/GlobalExceptionHandler.java:24` / `config/AuthenticationCheckFilter.java:35` / `config/JwtFilter.java:77`

**(a) 컨트롤러에서 발생한 예외** → `GlobalExceptionHandler`

```json
{ "message": "존재하지 않는 사용자" }
```

**(b) 필터에서 발생한 401** → `response.sendError(...)` → 컨테이너가 `/error`로 포워딩 → `BasicErrorController`

```java
// AuthenticationCheckFilter.java:35
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "인증이 필요합니다.");
```

```json
{ "timestamp": "2026-09-02T11:03:22.123+00:00", "status": 401, "error": "Unauthorized", "path": "/windows" }
```

### 왜 문제인가

**두 번째 형태에는 `message` 필드가 아예 없습니다.** `sendError`의 두 번째 인자로 넘긴 `"인증이 필요합니다."`는 `server.error.include-message`가 기본 `never`이므로 응답에 포함되지 않습니다. 즉 그 문자열을 쓴 의도가 실현되지 않습니다.

프론트 입장에서는 이렇게 됩니다.

```js
const err = await res.json();
alert(err.message);   // 컨트롤러 오류면 동작, 401 인증 오류면 undefined
```

401은 **가장 자주 처리해야 하는 오류**(토큰 만료 → 재로그인 유도)인데, 그게 다른 포맷입니다. 그리고 `05-security.md` P1-1에서 본 대로 액세스 토큰 만료와 인증 실패가 구분되지 않으므로, 프론트가 "갱신 시도"와 "재로그인"을 판단할 근거가 응답에 없습니다.

여기에 P0-1의 `include-message: never` 우회가 겹쳐서, **같은 서버가 어떤 오류는 내부 SQL까지 노출하고 어떤 오류는 메시지를 아예 안 주는** 상태입니다.

### 수정 방향

필터에서도 같은 JSON을 직접 씁니다. `sendError`는 컨테이너의 오류 처리로 넘기므로 포맷을 통제할 수 없습니다.

P0-1의 수정으로 `AuthenticationCheckFilter`가 제거되면(`05-security.md` P1-4), 인증 실패는 `AuthenticationEntryPoint` 한 곳에서 나가게 됩니다. 거기서 JSON을 직접 쓰세요.

```java
// SecurityConfig
.exceptionHandling(e -> e
    .authenticationEntryPoint((req, res, ex) -> {
        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"인증이 필요합니다.\"}");
    })
    .accessDeniedHandler((req, res, ex) -> {
        res.setStatus(HttpServletResponse.SC_FORBIDDEN);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"code\":\"FORBIDDEN\",\"message\":\"권한이 없습니다.\"}");
    }))
```

`ErrorResponse` record와 `ObjectMapper`를 주입해 쓰면 문자열 조립을 피할 수 있습니다.

그리고 `/error` 자체도 커스터마이즈하려면 `ErrorController`를 구현하거나, 최소한 프론트가 두 포맷을 모두 처리하도록 계약을 명시하세요. **어느 쪽이든 문서에 적혀 있어야 합니다** — 지금은 어디에도 없습니다.

---

## P1-3. 삼켜지는 예외 — 인증 실패 원인을 조사할 수 없음

**위치** `util/JwtUtil.java:37-44`, `config/JwtFilter.java:44-53`, `:72-79`

```java
// JwtUtil.java:37-44
    public boolean validate(String token) {
        try {
            Jwts.parser().setSigningKey(secretKey).parseClaimsJws(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;      // ← 무엇이 왜 실패했는지 사라짐
        }
    }
```

```java
// JwtFilter.java:48-53
        if (token == null || !jwtUtil.validate(token)) {
            chain.doFilter(request, response);      // ← 그냥 통과. 로그 없음
            return;
        }
```

### 왜 문제인가

`parseClaimsJws`가 던지는 예외는 실패 원인을 구분해서 알려줍니다.

| 예외 | 의미 | 대응 |
|---|---|---|
| `ExpiredJwtException` | 만료 | 클라이언트가 refresh 해야 함 |
| `SignatureException` | 서명 불일치 | **위조 시도** — 조사 필요 |
| `MalformedJwtException` | 형식 오류 | 클라이언트 버그 |
| `UnsupportedJwtException` | 지원하지 않는 형식 | 클라이언트 버그 |

`validate`는 이 넷을 모두 `false` 하나로 뭉갭니다. 그리고 `JwtFilter`는 `false`일 때 **로그도 남기지 않고** 체인을 통과시킵니다.

결과: **"사용자가 401을 받는다"는 신고가 들어와도, 그것이 만료인지 위조인지 클라이언트 버그인지 서버에서 알 수 없습니다.** 서명 불일치가 반복되고 있어도(공격 신호) 아무 기록이 없습니다.

`05-security.md` P1-6의 `JWT_SECRET` 교체 작업을 하면, 기존 토큰이 전부 `SignatureException`이 됩니다. 그때 "왜 다들 로그아웃됐는지"를 로그로 확인할 수 없습니다.

### 부수 문제: 도달 불가능한 catch 블록

```java
// JwtFilter.java:55-79
        try {
            String email = jwtUtil.getEmail(token);      // validate()가 이미 통과한 토큰
            ...
        } catch (Exception e) {
            SecurityContextHolder.clearContext();
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "유효하지 않거나 만료된 토큰");
            return;
        }
```

`validate(token)`이 바로 위에서 성공했으므로 `getEmail(token)`이 같은 파싱을 다시 해서 실패할 여지가 거의 없습니다(두 호출 사이에 만료되는 극단적 경우 정도). 즉 **주석이 설명하는 "토큰 만료, 변조" 처리는 실제로는 위쪽 `if`에서 이미 걸러집니다.**

그리고 `catch (Exception e)`는 너무 넓습니다 — `SecurityContextHolder` 조작이나 그 아래 코드에서 발생하는 예상 밖 예외까지 401로 바꿉니다. 실제 서버 오류가 인증 오류로 위장됩니다.

`JwtFilter.java:44-47`의 주석("현재 로직은 토큰이 없거나 유효하지 않으면 아래 try-catch 블록에서 401을 보냅니다")은 **코드와 일치하지 않습니다** — 실제로는 `chain.doFilter()`로 통과시킵니다. 주석과 코드가 어긋나 있는 것 자체가 이 부분을 손대기 어렵게 만듭니다.

### 수정 방향

`02-performance.md` P1-2(파싱 1회)와 `05-security.md` P1-1(`typ` 클레임)의 수정을 합치면서 원인을 살립니다.

```diff
--- a/src/main/java/com/camstudy/backend/util/JwtUtil.java
+++ b/src/main/java/com/camstudy/backend/util/JwtUtil.java
-    public boolean validate(String token) {
-        try {
-            Jwts.parser().setSigningKey(secretKey).parseClaimsJws(token);
-            return true;
-        } catch (JwtException | IllegalArgumentException e) {
-            return false;
-        }
-    }
-
-    public String getEmail(String token) { ... }
+    private static final Logger log = LoggerFactory.getLogger(JwtUtil.class);
+
+    /** 유효하면 Claims, 아니면 null (원인은 로그로 남긴다) */
+    public Claims parseAccessToken(String token) {
+        try {
+            Claims c = Jwts.parser().setSigningKey(secretKey).parseClaimsJws(token).getBody();
+            if (!"access".equals(c.get("typ", String.class))) {
+                log.warn("token type mismatch: expected=access actual={}", c.get("typ"));
+                return null;
+            }
+            return c;
+        } catch (ExpiredJwtException e) {
+            log.debug("expired token for subject={}", e.getClaims().getSubject());
+            return null;
+        } catch (SignatureException e) {
+            log.warn("JWT signature mismatch - possible forgery");   // ← 조사 대상
+            return null;
+        } catch (JwtException | IllegalArgumentException e) {
+            log.warn("invalid JWT: {}", e.getClass().getSimpleName());
+            return null;
+        }
+    }
```

로그 레벨을 나눈 것이 요점입니다.

- **만료는 `DEBUG`** — 정상적인 일상이므로 운영에서 로그를 채우면 안 됩니다.
- **서명 불일치는 `WARN`** — 드물어야 하고, 반복되면 공격 신호입니다.

로그에 **토큰 원문을 넣지 않은 것**도 의도적입니다. 토큰이 로그에 남으면 그 자체가 자격증명 유출입니다(`09-logging.md`). `e.getClaims().getSubject()`는 `ExpiredJwtException`에서만 안전하게 꺼낼 수 있습니다 — 만료는 서명 검증을 통과한 뒤에 판정되므로 클레임을 신뢰할 수 있기 때문입니다. 다만 이메일이 로그에 남는 문제는 별개로 검토하세요(`09-logging.md`).

그리고 `JwtFilter`의 `try/catch`(`:55-79`)를 제거합니다.

```diff
--- a/src/main/java/com/camstudy/backend/config/JwtFilter.java
+++ b/src/main/java/com/camstudy/backend/config/JwtFilter.java
         String token = extractToken(request);
-        if (token == null || !jwtUtil.validate(token)) {
-            chain.doFilter(request, response);
-            return;
-        }
-        try {
-            String email = jwtUtil.getEmail(token);
-            ...
-        } catch (Exception e) {
-            SecurityContextHolder.clearContext();
-            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "유효하지 않거나 만료된 토큰");
-            return;
-        }
-        chain.doFilter(request, response);
+        Claims claims = (token == null) ? null : jwtUtil.parseAccessToken(token);
+        if (claims != null) {
+            SecurityContextHolder.getContext().setAuthentication(
+                new UsernamePasswordAuthenticationToken(
+                    claims.getSubject(), null,
+                    List.of(new SimpleGrantedAuthority("ROLE_USER"))));
+        }
+        chain.doFilter(request, response);      // 인가 판단은 Spring Security에 맡긴다
```

필터의 책임이 "토큰이 유효하면 SecurityContext를 채운다" 하나로 줄고, 401을 보내는 책임은 `AuthenticationEntryPoint`(P1-2)로 넘어갑니다.

---

## P2-1. 조용히 성공하는 경로 — 클라이언트가 실패를 알 수 없음

**위치** `service/RoomService.java:95-100`, `service/TimerService.java:188-195`

```java
// RoomService.java:95-100
    public void deactivateMemberIfExists(Room room, String userId) {
        memberRepo.findByRoomAndUserId(room, userId).ifPresent(m -> {
            m.setActive(false);
            memberRepo.save(m);
        });
    }
```

```java
// TimerService.java:188-195
    @Transactional
    public void resetDailyTimer(String userEmail, LocalDate date, ZoneId userZone) {
        repo.findByUserIdAndDate(userEmail, date)
            .ifPresent(timer -> { ... });
    }
```

`ifPresent`는 대상이 없으면 **아무 일도 하지 않고 정상 반환**합니다. 따라서:

- `POST /rooms/{id}/leave`에 없는 `userId`를 보내면 **204** (그 방 멤버가 아니어도)
- `POST /rooms/{id}/admin/kick`에 없는 `userId`를 보내면 **204**
- `DELETE /timer/day/2020-01-01`에 기록 없는 날짜를 보내면 **200**

### 왜 문제인가

삭제·비활성화 연산이 멱등(idempotent)하다는 것은 좋은 속성입니다. "이미 없으면 성공"이 REST에서 통상적인 처리이기도 합니다.

문제는 **클라이언트 버그를 숨긴다**는 점입니다. 프론트가 잘못된 `userId`(예: 이메일 대신 내부 id)를 보내고 있어도 서버는 계속 204를 반환합니다. `05-security.md` P0-1에서 `members.user_id`에 어떤 값이 들어 있는지 확인이 필요하다고 했는데, 이 조용한 성공 때문에 **불일치가 지금까지 드러나지 않았을 가능성**이 있습니다.

`kick`의 경우 방장이 "강퇴했다"는 응답을 받았는데 실제로는 아무 일도 안 일어난 상황이 가능합니다.

### 수정 방향

멱등성을 유지하되 **로그를 남기세요.** 상태 코드를 바꾸면 프론트 계약이 변합니다.

```diff
     public void deactivateMemberIfExists(Room room, String userId) {
-        memberRepo.findByRoomAndUserId(room, userId).ifPresent(m -> {
-            m.setActive(false);
-            memberRepo.save(m);
-        });
+        memberRepo.findByRoomAndUserId(room, userId).ifPresentOrElse(
+            m -> m.setActive(false),
+            () -> log.info("deactivate skipped: no member roomId={} userId={}", room.getId(), userId)
+        );
     }
```

`kick`처럼 "대상이 있어야 의미가 있는" 연산은 404를 주는 편이 낫습니다 — 방장이 오타를 냈을 때 알 수 있어야 합니다. `leave`는 멱등으로 남기세요(중복 호출이 정상 시나리오).

---

## P2-2. `AuthController.refresh`의 검증 코드가 도달 불가능하고, 의도한 상태 코드가 나오지 않음

**위치** `controller/AuthController.java:35-42`, `controller/GlobalExceptionHandler.java:19`

```java
    @PostMapping("/refresh")
    public String refresh(@CookieValue(value = "RefreshToken") String refreshToken,
                          HttpServletResponse response) {
        if (refreshToken == null || refreshToken.isEmpty()) {
            throw new RuntimeException("리프레시 토큰이 존재하지 않습니다.");
        }
        return authService.refreshAccessToken(refreshToken, response);
    }
```

### 왜 문제인가

`@CookieValue`는 `required`의 기본값이 **`true`** 입니다. 쿠키가 없으면 Spring이 `MissingRequestCookieException`을 던지고, 이건 `ServletException` 계열(**checked exception**)이라 `@ExceptionHandler(RuntimeException.class)`에 잡히지 않습니다. `DefaultHandlerExceptionResolver`가 처리해 **400**을 반환합니다.

따라서:

- `if (refreshToken == null ...)`은 **절대 실행되지 않습니다.** `null`이 넘어올 수 없기 때문입니다.
- `GlobalExceptionHandler.java:19`가 `"리프레시 토큰이 존재하지 않습니다."`를 **401로 매핑해 두었지만 그 경로가 없습니다.** 실제로는 400이 나갑니다.
- 프론트가 "401이면 로그인 페이지로"라는 규칙을 갖고 있다면, 쿠키 없는 refresh 시도는 400이라 그 분기를 타지 않습니다.

빈 문자열(`""`) 쿠키는 넘어올 수 있으므로 `isEmpty()` 검사만 유효합니다. 그 경우 `RuntimeException` → 401(switch 매칭됨). 즉 **같은 "리프레시 토큰 없음" 상황이 쿠키 부재면 400, 빈 값이면 401**입니다.

그리고 `AuthService.refreshAccessToken:100`의 `jwtUtil.getEmail(refreshToken)`이 만료·변조 시 `JwtException`을 던지므로 → P0-1에 의해 **500**입니다(P1-1의 9번). 즉 이 엔드포인트 하나가 상황에 따라 **400 / 401 / 500** 세 가지를 냅니다.

### 수정 방향

```diff
--- a/src/main/java/com/camstudy/backend/controller/AuthController.java
+++ b/src/main/java/com/camstudy/backend/controller/AuthController.java
     @PostMapping("/refresh")
-    public String refresh(@CookieValue(value = "RefreshToken") String refreshToken,
-                          HttpServletResponse response) {
-        if (refreshToken == null || refreshToken.isEmpty()) {
-            throw new RuntimeException("리프레시 토큰이 존재하지 않습니다.");
-        }
-        return authService.refreshAccessToken(refreshToken, response);
+    public ResponseEntity<Void> refresh(
+            @CookieValue(value = "RefreshToken", required = false) String refreshToken) {
+        if (refreshToken == null || refreshToken.isBlank()) {
+            throw new UnauthorizedException("리프레시 토큰이 없습니다.");
+        }
+        String newAccess = authService.refreshAccessToken(refreshToken);
+        return ResponseEntity.noContent()
+                .header(HttpHeaders.SET_COOKIE, cookieFactory.access(newAccess).toString())
+                .build();
     }
```

`required = false`로 바꿔야 `null` 검사가 의미를 갖고, 401로 통일됩니다.

`AuthService`에서 `JwtException`을 `UnauthorizedException`으로 변환하세요.

```diff
--- a/src/main/java/com/camstudy/backend/service/AuthService.java
+++ b/src/main/java/com/camstudy/backend/service/AuthService.java
-    public String refreshAccessToken(String refreshToken, HttpServletResponse response) {
-        String email = jwtUtil.getEmail(refreshToken);
-        if (email == null) {
-            throw new RuntimeException("리프레시 토큰이 유효하지 않습니다.");
-        }
+    public String refreshAccessToken(String refreshToken) {
+        Claims claims = jwtUtil.parseRefreshToken(refreshToken);   // null 반환, 로그는 내부에서
+        if (claims == null) {
+            throw new UnauthorizedException("리프레시 토큰이 유효하지 않습니다.");
+        }
+        String email = claims.getSubject();
```

`String` 반환("새로운 액세스 토큰이 발급되었습니다.")도 없앴습니다 — `text/plain` 응답이라 프론트가 `res.json()`으로 파싱할 수 없습니다. 상세는 `07-api-design.md`.

---

## P2-3. `@ExceptionHandler(Exception.class)`가 없어 프레임워크 예외가 다른 포맷으로 나감

`GlobalExceptionHandler`에는 `RuntimeException` 핸들러 하나뿐입니다. 따라서 checked exception과 `Error`, 그리고 Spring의 checked 계열 예외는 컨테이너 기본 처리로 갑니다.

| 상황 | 예외 | 처리 경로 | 응답 포맷 |
|---|---|---|---|
| 없는 경로 호출 | `NoResourceFoundException` | `DefaultHandlerExceptionResolver` | `{"timestamp","status":404,...}` |
| 잘못된 HTTP 메서드 | `HttpRequestMethodNotSupportedException` | 동일 | 405, 동일 포맷 |
| `Content-Type` 불일치 | `HttpMediaTypeNotSupportedException` | 동일 | 415, 동일 포맷 |
| 필수 쿠키/헤더 누락 | `MissingRequestCookieException` | 동일 | 400, 동일 포맷 |
| `@RequestParam` 누락 | `MissingServletRequestParameterException` | 동일 | 400, 동일 포맷 |

이들은 대부분 **상태 코드가 올바릅니다** — `DefaultHandlerExceptionResolver`가 제대로 매핑합니다. 문제는 응답 포맷이 P1-2의 (b) 형태라 `message` 필드가 없다는 점입니다.

`ResponseEntityExceptionHandler`를 상속하면 이 표준 예외들을 오버라이드해서 통일된 포맷으로 낼 수 있습니다.

```java
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        return ResponseEntity.status(status)
                .body(new ErrorResponse(status.toString(), "요청을 처리할 수 없습니다."));
    }
    // ... P0-1의 핸들러들
}
```

**주의**: `ResponseEntityExceptionHandler`를 상속하면 그 클래스가 이미 갖고 있는 `@ExceptionHandler`들이 함께 적용됩니다. `HttpMessageNotReadableException` 등을 직접 처리하려면 오버라이드해야 하고, 상속과 직접 선언이 겹치면 매핑 충돌로 **부팅이 실패**할 수 있습니다. P0-1의 핸들러를 먼저 안정화한 뒤 별도 단계로 진행하세요.

---

## P2-4. 요청 추적 수단이 없어 오류를 신고와 연결할 수 없음

응답에 요청 식별자가 없고, 로그에도 없습니다(`application.yml:23-26`에 패턴 설정 없음 → Logback 기본 패턴, MDC 없음).

즉 사용자가 "오류 났어요"라고 하면, **그 요청의 로그를 찾을 방법이 시각뿐**입니다. P0-1 (4)에서 본 대로 처리된 예외는 로그도 안 남으므로 찾을 것도 없습니다.

최소 조치는 오류 응답에 요청 id를 넣고 같은 값을 로그에 남기는 것입니다.

```java
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e, HttpServletRequest req) {
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        log.error("[{}] unhandled at {} {}", traceId, req.getMethod(), req.getRequestURI(), e);
        return ResponseEntity.internalServerError()
                .body(new ErrorResponse("INTERNAL_ERROR", "서버 오류가 발생했습니다. (" + traceId + ")"));
    }
```

사용자가 화면의 8자리 코드를 알려주면 로그를 바로 찾을 수 있습니다. MDC와 필터를 이용한 전 요청 추적은 `09-logging.md`에서 다룹니다.

---

## 요약

| 심각도 | 개수 | 항목 |
|---|---|---|
| **P0** | 1 | 전역 핸들러가 `RuntimeException` 전체를 가로채고 한국어 메시지로 상태 코드를 결정 → `ResponseStatusException`·`HttpMessageNotReadableException`까지 500, 예외 메시지(SQL 전문 포함) 응답 노출, **처리된 예외가 로그에 전혀 남지 않음** |
| **P1** | 3 | 500으로 흘러나가는 경로 17개(대부분 클라이언트 입력이 트리거) / 에러 응답 포맷 2종(401에는 `message` 필드가 없음) / JWT 실패 원인이 삼켜져 만료·위조·형식오류를 구분 불가 |
| **P2** | 4 | 조용히 성공하는 삭제·비활성화 / `refresh`의 도달 불가 코드 + 400·401·500 혼재 / `Exception.class` 핸들러 부재로 프레임워크 예외 포맷 불일치 / 요청 추적 식별자 없음 |

## 사용자가 물은 항목에 대한 직접 답

| 질문 | 답 |
|---|---|
| 전역 핸들러 | 있습니다. 다만 `@ExceptionHandler` 1개이고 문자열 switch로 분기해 **의도한 상태 코드를 대부분 버립니다.** |
| 삼켜지는 예외 | `JwtUtil.validate:41-43`(원인 4종 → `false` 하나), `JwtFilter:48-53`(무효 토큰을 로그 없이 통과), `RoomService:96`·`TimerService:189`(`ifPresent`로 조용히 성공). |
| 500으로 흘러나가는 경로 | **17개** 확인. 표로 정리했습니다. "남의 투두 수정", "없는 창 삭제", "잘못된 타임존 헤더" 같은 정상적 사용자 오류가 전부 500입니다. |
| 스택트레이스 노출 | **응답 바디에는 노출되지 않습니다.** `server.error.include-stacktrace` 기본값이 `never`입니다. 단 **예외 메시지는 노출되며**, 그 안에 SQL 전문·제약명·클래스 전체 경로·서버 시각이 들어갑니다. Spring Boot가 `include-message: never`로 막아둔 것을 이 커스텀 핸들러가 우회합니다. |

## 수정 순서

```
1. P0-1 (2단계) 핸들러 재작성 — 로그부터 남기게 만들기   ← 먼저. 이후 작업의 관찰 수단
2. build.gradle에 spring-boot-starter-validation 추가
3. P1-1 (a) 파라미터 타입 변환을 프레임워크에 위임 (10~14, 16번)
4. P0-1 (1·3단계) 도메인 예외 타입 도입 + 서비스 throw 교체 (1~9번)
5. P1-3 JwtUtil 파싱 통합 (02 P1-2, 05 P1-1과 함께)
6. P1-2 AuthenticationEntryPoint로 401 포맷 통일 (05 P1-4와 함께)
7. P2-2 refresh 정리
8. P2-3 ResponseEntityExceptionHandler 상속 — 별도 단계로
```

**1번을 가장 먼저** 하세요. 지금은 500이 나도 원인을 볼 수 없어서, 이후 모든 수정의 검증이 불가능합니다. `log.error(..., e)` 한 줄이 그걸 바꿉니다.

실행·테스트는 하지 않았습니다. 위 상태 코드 판정은 `HandlerExceptionResolver` 실행 순서와 각 예외의 상속 계층에 근거한 것이며, 실제 응답은 각 엔드포인트를 호출해 확인해야 합니다.
