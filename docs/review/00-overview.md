# 00. 전체 구조 요약 (1단계)

조사 방법: Read / Grep / Glob / git 읽기 명령만 사용. 빌드·실행·테스트는 하지 않았음
(로컬에 Gradle 의존성 캐시가 없어 `./gradlew` 실행 자체가 불가 — `~/.gradle` 없음).

---

## 1. 스택 (직접 확인한 값)

| 항목 | 값 | 근거 |
|---|---|---|
| Spring Boot | 3.4.4 | `build.gradle:3` |
| Gradle | 8.13 (wrapper) | `gradle/wrapper/gradle-wrapper.properties:3` |
| Java | 17 (toolchain) | `build.gradle:11` |
| 영속성 계층 | Spring Data JPA (Hibernate). MyBatis 없음 | `build.gradle:22` |
| DB | MySQL 8.0 / `mysql-connector-j` (runtimeOnly) | `build.gradle:32`, `docker-compose.yml:5` |
| 보안 | Spring Security + 직접 만든 JWT 필터 2개 | `build.gradle:23`, `config/SecurityConfig.java:43-45` |
| JWT 라이브러리 | jjwt 0.11.5 | `build.gradle:39-41` |
| 비밀번호 해시 | Argon2 빈 등록 + BCrypt 직접 생성(둘 다 존재) | `config/PasswordConfig.java:14`, `service/AuthService.java:17` |
| 실시간 | STOMP over WebSocket, 인메모리 SimpleBroker | `build.gradle:24`, `config/WsConfig.java:23` |
| 모니터링 | Spring Boot Actuator + buildInfo | `build.gradle:25,47` |
| devtools | `developmentOnly` 스코프 | `build.gradle:31` |

### 활성 profile
- **profile 전용 설정 파일이 하나도 없음.** `src/main/resources`에는 `application.yml`, `application.properties`만 존재.
- 그런데 `docker-compose.yml:34`에서 `SPRING_PROFILES_ACTIVE: prod`를 넣고 있음.
  → `prod` profile은 활성화되지만 `application-prod.yml`이 없으므로 **`application.yml` 하나가 로컬·운영 공용 설정**으로 쓰이는 구조.
  이 파일이 `show-sql: true`, `ddl-auto: update`, `spring.security.debug: true`를 담고 있음 (10번 카테고리에서 다룸).
- `application.properties`와 `application.yml`이 둘 다 `spring.application.name=backend`만 정의 → 중복.

### 외부 의존성
- **MySQL 단 하나.** Redis / 메시지 브로커 / 외부 HTTP API 호출 코드는 없음.
- LiveKit(화상 SFU)은 커밋 `be969e7`에서 전부 제거됨. 현재 소스에 흔적 없음.
  → 즉 "화상 스터디"의 미디어 처리는 현재 백엔드 밖에 있음(프론트 직결 또는 미구현). [[확인필요: 프론트가 지금 어떤 미디어 서버를 쓰는지]]

---

## 2. 패키지 구성

```
com.camstudy.backend
├── BackendApplication.java        진입점 (@SpringBootApplication 단독)
├── config/       (6)  SecurityConfig, JwtFilter, AuthenticationCheckFilter,
│                      PasswordConfig, WebConfig, WsConfig
├── controller/   (10) Auth, Room, RoomAdmin, Timer, Todo, GlobalTodo, Window,
│                      Hello, Chat(STOMP), GlobalExceptionHandler(@RestControllerAdvice)
├── service/      (5)  Auth, Room, Timer, Todo, Window
├── repository/   (7)  User, Room, Member, Timer, TodoItem, Window
│                      + ActiveCountProjection (인터페이스 프로젝션)
├── entity/       (8)  User, Room, Member, Timer, TodoItem, Window
│                      + Role, WindowType (enum)
├── dto/         (17)  record 위주, room/ chat/ 서브패키지
└── util/         (2)  JwtUtil, SecurityUtil
```

전형적인 3계층(Controller → Service → Repository → Entity) 구성. 도메인별 패키지 분리는 없고 **계층별 패키지**.

---

## 3. 도메인 4덩어리

프론트 관점에서 보면 서로 거의 독립된 4개 기능이 한 앱에 들어있습니다.

1. **인증** — `User` 엔티티. 이메일/비밀번호 → JWT 2종을 HttpOnly 쿠키(`AccessToken`, `RefreshToken`)로 발급.
2. **스터디룸** — `Room` + `Member`. 방 생성/목록/입장/퇴장/강퇴. `Member.active` 불리언으로 재실 인원 관리.
3. **윈도우 & 투두** — `Window`(사용자 데스크톱의 창 배치: x/y/w/h/zIndex/type/url) + `TodoItem`(창에 속한 투두).
4. **타이머** — `Timer` (userId + date 조합에 하루치 누적 초, 뽀모도로 사이클 수). 월별 조회·분석 API.

> 주의할 설계 특징: **1·2번과 3·4번의 사용자 식별 방식이 서로 다릅니다.**
> - 3·4번은 `Window.userId`, `Timer.userId`가 **String(이메일)** — JWT subject를 그대로 문자열 컬럼에 저장. `User` 엔티티와 FK 연관관계가 없음.
> - 2번은 `Member.userId`도 String이지만, 이 값은 **요청 body(`JoinReq.userId`)에서 그대로 받습니다** (`controller/RoomController.java:64-68`). 인증 주체와 무관.
>
> 이게 5번(보안), 6번(레이어 경계) 카테고리에서 가장 큰 문제로 이어집니다.

---

## 4. 요청 진입 경로 (레이어 경계)

### HTTP 필터 체인
`SecurityConfig.java:29-47`

```
CORS → CSRF(disable) → ... → JwtFilter → AuthenticationCheckFilter → 인가(authorizeHttpRequests) → DispatcherServlet
```

- `JwtFilter` — 쿠키에서 `AccessToken` 꺼내 검증 후 `SecurityContext`에 `UsernamePasswordAuthenticationToken(email, null, ROLE_USER)` 세팅.
- `AuthenticationCheckFilter` — SecurityContext가 비었거나 익명이면 즉시 401.
- 두 필터 모두 `shouldNotFilter()`로 `/auth/`, `/error` 프리픽스를 우회 (`JwtFilter.java:26-29`, `AuthenticationCheckFilter.java:21`).
- 인가 규칙 permitAll: `/auth/**`, `/error`, `/ws/**`, `/actuator/**` (`SecurityConfig.java:34-39`).

**여기 이미 불일치가 있습니다**: `/ws/**`와 `/actuator/**`는 인가 단계에서는 permitAll인데,
`AuthenticationCheckFilter`의 EXCLUDE 목록에는 없습니다. 필터가 인가 단계보다 **앞에** 있으므로
`/actuator/health`, `/ws` 요청도 토큰이 없으면 이 필터에서 401로 잘립니다.
→ 5번(보안), 12번(헬스체크) 카테고리에서 상세히 다룸.

### 컨트롤러가 인증 주체를 얻는 방식 (3가지가 섞여 있음)
1. `@AuthenticationPrincipal String userEmail` — Timer, Todo, GlobalTodo, Window 컨트롤러. (가장 많음)
2. **요청 body의 userId를 신뢰** — Room, RoomAdmin 컨트롤러.
3. `SecurityUtil.getCurrentEmail()` — **어디서도 호출되지 않는 죽은 코드** (grep 결과 정의만 존재).

### WebSocket 경로
`WsConfig.java:12-24`
- 엔드포인트 `/ws`, `setAllowedOriginPatterns("*")`
- 클라이언트 발행 prefix `/app`, 구독 prefix `/topic`, `/queue`
- `ChatController`는 받은 `ChatMessage`를 **검증 없이 그대로** `/topic/rooms/{roomId}`로 브로드캐스트 (`ChatController.java:16-18`, TODO 주석 남아있음).
- STOMP 채널에는 인증 인터셉터(`configureClientInboundChannel`)가 **없음**.

---

## 5. 엔드포인트 목록 (실제 매핑 기준)

| 메서드 | 경로 | 파일:라인 | 인증 주체 획득 |
|---|---|---|---|
| GET | `/` | `HelloController.java:8` | 없음 (하지만 permitAll 아님 → 401) |
| POST | `/auth/login` | `AuthController.java:20` | - |
| POST | `/auth/signup` | `AuthController.java:25` | - |
| POST | `/auth/logout` | `AuthController.java:30` | - |
| POST | `/auth/refresh` | `AuthController.java:35` | 쿠키 |
| POST | `/rooms` | `RoomController.java:29` | **body.ownerId** |
| GET | `/rooms` | `RoomController.java:37` | - |
| GET | `/rooms/{roomId}` | `RoomController.java:55` | - |
| POST | `/rooms/{roomId}/join` | `RoomController.java:63` | **body.userId** |
| POST | `/rooms/{roomId}/leave` | `RoomController.java:76` | **body.userId** |
| POST | `/rooms/{roomId}/admin/kick` | `RoomAdminController.java:18` | **body.userId** (권한 검사 없음) |
| GET | `/windows` | `WindowController.java:21` | `@AuthenticationPrincipal` |
| POST | `/windows` | `WindowController.java:26` | `@AuthenticationPrincipal` |
| PATCH | `/windows/{id}` | `WindowController.java:32` | `@AuthenticationPrincipal` |
| DELETE | `/windows/{id}` | `WindowController.java:39` | `@AuthenticationPrincipal` |
| POST | `/windows/{id}/focus` | `WindowController.java:45` | `@AuthenticationPrincipal` |
| POST | `/windows/{winId}/todos` | `TodoController.java:25` | `@AuthenticationPrincipal` |
| GET | `/windows/{winId}/todos` | `TodoController.java:33` | `@AuthenticationPrincipal` |
| PATCH | `/windows/{winId}/todos/{todoId}` | `TodoController.java:51` | `@AuthenticationPrincipal` |
| PATCH | `/windows/{winId}/todos/{todoId}/text` | `TodoController.java:69` | `@AuthenticationPrincipal` |
| DELETE | `/windows/{winId}/todos/{todoId}` | `TodoController.java:61` | `@AuthenticationPrincipal` |
| GET | `/todos` | `GlobalTodoController.java:24` | `@AuthenticationPrincipal` |
| PATCH | `/todos/{todoId}` | `GlobalTodoController.java:41` | `@AuthenticationPrincipal` |
| PATCH | `/todos/{todoId}/done` | `GlobalTodoController.java:51` | `@AuthenticationPrincipal` |
| DELETE | `/todos/{todoId}` | `GlobalTodoController.java:61` | `@AuthenticationPrincipal` |
| POST | `/timer` | `TimerController.java:24` | `@AuthenticationPrincipal` |
| GET | `/timer` | `TimerController.java:35` | `@AuthenticationPrincipal` |
| GET | `/timer/analytics` | `TimerController.java:48` | `@AuthenticationPrincipal` |
| GET | `/timer/today` | `TimerController.java:59` | `@AuthenticationPrincipal` |
| GET | `/timer/goal` | `TimerController.java:68` | `@AuthenticationPrincipal` |
| POST | `/timer/goal` | `TimerController.java:73` | `@AuthenticationPrincipal` |
| PATCH | `/timer/today/cycles` | `TimerController.java:79` | `@AuthenticationPrincipal` |
| DELETE | `/timer/day/{date}` | `TimerController.java:89` | `@AuthenticationPrincipal` |
| STOMP | `/app/rooms/{roomId}/chat` | `ChatController.java:14` | 없음 |

HTTP 엔드포인트 34개, STOMP 1개.

---

## 6. 죽은 코드 / 미사용 (grep으로 호출부 0건 확인)

| 대상 | 위치 |
|---|---|
| `SecurityUtil.getCurrentEmail()` | `util/SecurityUtil.java:8` |
| `UserRepository.findByUsername` | `repository/UserRepository.java:10` |
| `TodoItemRepository.findByWindowId` | `repository/TodoItemRepository.java:14` |
| `TodoItemRepository.findAllByWindow_UserId` | `repository/TodoItemRepository.java:23` |
| `Timer.addDuration(long)` | `entity/Timer.java:29` |
| `RoomDto.from(Room)` (1-arg) | `dto/room/RoomDto.java:13` |
| `WebConfig.configurePathMatch` 내용 | `config/WebConfig.java:12-21` — `UrlPathHelper` 기본값 그대로 주입, 실질 효과는 `setUseTrailingSlashMatch(true)` 뿐 |

---

## 7. 테스트 현황

- 테스트 파일 **1개**: `src/test/java/com/camstudy/backend/BackendApplicationTests.java`
- 내용은 `@SpringBootTest` + 빈 `contextLoads()` 1개.
- 즉 **비즈니스 로직 테스트 0건.** (11번 카테고리에서 상세)
- 참고: 이 테스트조차 `application.yml`이 `${DB_HOST}` 등 환경변수를 요구하므로,
  환경변수 없이는 컨텍스트 로딩이 실패할 것으로 보입니다. [[확인필요: CI에서 이 테스트가 실제로 통과하는지 — 실행해보지 않았음]]

---

## 8. 배포 형태

- `Dockerfile` — 2-stage처럼 보이지만 build 스테이지가 **미리 빌드된 jar를 COPY만** 함 (실제 컴파일은 컨테이너 밖).
- 런타임 이미지도 `eclipse-temurin:17-jdk-jammy` (JRE 아님).
- `application.yml`을 `/app/application.yml`로 별도 COPY하지만, jar 내부에도 같은 파일이 있음.
- `docker-compose.yml` — mysql 8.0 + app 컨테이너. 헬스체크·리소스 제한·로깅 드라이버 설정 없음.
- 이미지 태그 `yejiii1997/cmas:latest`.

---

## 다음 단계

2단계부터 아래 순서로 카테고리별 리포트를 `docs/review/`에 씁니다.

1. JPA / 쿼리 → `01-jpa-query.md`
2. 성능 → `02-performance.md`
3. 트랜잭션 → `03-transaction.md`
4. 예외 처리 → `04-exception.md`
5. 보안 → `05-security.md`
6. 아키텍처 / 레이어 경계 → `06-architecture.md`
7. API 설계 → `07-api-design.md`
8. 동시성 → `08-concurrency.md`
9. 로깅 → `09-logging.md`
10. 설정 / 환경 분리 → `10-config.md`
11. 테스트 → `11-test.md`
12. Docker / 배포 → `12-docker-deploy.md`
