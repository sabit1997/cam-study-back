# 09. 로깅

조사 범위: 운영에 DEBUG/TRACE가 남았는지, 민감정보 로깅, 요청 추적, 로테이션.

## grep으로 확정한 사실

| 확인 항목 | 결과 |
|---|---|
| `Logger` / `LoggerFactory` / `@Slf4j` 선언 | **0건** |
| `log.info` / `log.warn` / `log.error` / `log.debug` 호출 | **0건** |
| `System.out.println` / `System.err` / `printStackTrace` | **0건** |
| `logback-spring.xml` / `logback.xml` / log4j 설정 | **없음** (프로젝트에 XML 파일이 하나도 없음) |
| `logging.file.*` (파일 출력) | **미설정** → 콘솔 출력만 |
| `logging.pattern.*` (포맷) | **미설정** → Logback 기본 패턴 |
| `logging.structured.*` (구조화 로깅) | **미설정** |
| docker-compose의 `logging:` 드라이버 옵션 | **0건** |

`application.yml:23-26`의 전부:

```yaml
logging:
  level:
    org.springframework.security: WARN
    root: INFO
```

> **주의**: `docker-compose.yml:45`의 `driver: local`은 로깅 드라이버가 아니라 **볼륨 드라이버**입니다(`volumes: mysql-vl: driver: local`). 컨테이너 로깅 드라이버는 설정되지 않았습니다.

---

## 먼저: "운영에 DEBUG/TRACE가 남았는지"에 대한 정확한 답

**`logging.level`에는 DEBUG/TRACE가 없습니다.** `root: INFO`, `org.springframework.security: WARN`이고, 커밋 `1b5edff`("chore: 로그 레벨 조정 (security TRACE → WARN, root INFO)")에서 이미 정리된 상태입니다. 이 부분은 적절합니다.

**그런데 로그 레벨 체계 밖에서 DEBUG 수준 출력을 하는 설정이 두 개 있습니다.**

| 설정 | 위치 | 왜 레벨로 통제되지 않는가 |
|---|---|---|
| `spring.jpa.show-sql: true` | `application.yml:14` | Hibernate가 SLF4J가 아니라 **`System.out`으로 직접** 출력합니다. `logging.level`은 SLF4J/Logback 로거에만 적용되므로 이 출력에는 아무 영향이 없습니다 |
| `spring.security.debug: true` | `application.yml:4-5` | 동작한다면 `DebugFilter`를 통해 별도 경로로 출력합니다 (`05-security.md` P1-7의 갈래 B) |

즉 **"로그 레벨은 정리했지만 실제 출력량은 DEBUG 수준"** 입니다. 그리고 `application-prod.yml`이 없으므로 이 설정이 운영에 그대로 적용됩니다(`00-overview.md`).

`show-sql`의 성능 영향(`System.out`의 락 경합)은 `02-performance.md` P1-5에서, `security.debug`의 보안 영향은 `05-security.md` P1-7에서 다뤘습니다. 여기서는 **로그 부피와 민감정보** 관점으로 봅니다.

---

## P1-1. 애플리케이션 로그가 한 줄도 없음 — 운영에서 관찰할 수 있는 것이 없음

**위치** 프로젝트 전체 (`src/main/java` 42개 파일)

`Logger`를 선언한 클래스가 **하나도 없습니다.** 따라서 이 애플리케이션이 남기는 로그는 다음뿐입니다.

1. Spring Boot 부팅 로그 (배너, 빈 초기화, Tomcat 시작, Hibernate 스키마 작업)
2. Hibernate의 `show_sql` 출력 (`System.out`)
3. 프레임워크가 자체적으로 남기는 WARN/ERROR (드묾)

**비즈니스 로직이 무엇을 했는지에 대한 기록이 0입니다.**

### 왜 문제인가

**(1) 500이 나도 원인을 알 수 없습니다.**

`04-exception.md` P0-1 (4)에서 다룬 내용과 곱해집니다.

- `GlobalExceptionHandler`가 예외를 잡아 응답으로 바꾸지만 로그를 남기지 않습니다
- `@ExceptionHandler`로 **처리된** 예외는 Spring도 로그를 남기지 않습니다 (`spring.mvc.log-resolved-exception` 기본 `false`)

그리고 `04-exception.md` P1-1에서 정리한 대로 **500으로 흘러나가는 경로가 17개**입니다. 그 17개가 전부 **무성(silent)** 입니다. 사용자가 "오류 났어요"라고 하면 서버에서 확인할 수 있는 것이 아무것도 없습니다.

**(2) 보안 사건의 흔적이 남지 않습니다.**

`05-security.md`에서 P0 4건을 찾았습니다. 그 중 어느 것이 **이미 악용되었는지 확인할 방법이 없습니다.**

| 이벤트 | 지금 남는 기록 |
|---|---|
| 로그인 성공 | 없음 |
| 로그인 실패 (무차별 대입 시도) | 없음 |
| JWT 서명 불일치 (위조 시도) | 없음 — `JwtUtil.validate:41-43`이 삼킴 (`04-exception.md` P1-3) |
| 임의 강퇴 (`05` P0-1) | 없음 |
| 남의 창 탈취 (`06` P0-1) | 없음 |
| WebSocket 무단 구독 (`05` P0-2) | 없음 |
| 방 생성·입장·퇴장 | 없음 |

`06-architecture.md` P0-1에서 제안한 진단 쿼리(`SELECT ... WHERE created_at IS NULL`)가 필요했던 이유가 이것입니다 — 로그가 없어서 **DB 상태에서 흔적을 역추적**해야 합니다.

**(3) 08의 문제들을 관찰할 수 없습니다.**

- `Member.active` 누수(`08-concurrency.md` P1-4)가 얼마나 진행됐는지
- `Timer` 중복 행(`08` P1-2)이 생겼는지
- 무한 루프(`02` P0-1)에 걸린 요청이 있는지

전부 DB나 프로세스 상태를 직접 봐야 알 수 있습니다.

### 수정 방향

**로깅 라이브러리는 이미 있습니다.** `spring-boot-starter-web`이 `spring-boot-starter-logging`(Logback + SLF4J)을 포함하므로 의존성 추가가 필요 없습니다. Lombok도 있으니 `@Slf4j` 한 줄로 로거를 얻을 수 있습니다.

```java
@Service
@RequiredArgsConstructor
@Slf4j                       // private static final Logger log = ... 자동 생성
public class AuthService { ... }
```

**최소한 여기에는 남기세요.** 전부 넣으라는 게 아니라, 이 목록이 "없으면 조사가 불가능한" 지점입니다.

| 지점 | 레벨 | 남길 것 | 남기지 말 것 |
|---|---|---|---|
| 미처리 예외 (5xx) | **ERROR** | traceId, method, URI, **스택트레이스** | — |
| 처리된 4xx | WARN | 에러 코드, method, URI | 스택트레이스(불필요) |
| 로그인 실패 | WARN | 클라이언트 IP | **이메일 원문** (P1-3 참고) |
| JWT 서명 불일치 | **WARN** | IP, URI | **토큰 원문** |
| 토큰 만료 | DEBUG | — | 토큰 원문 |
| 강퇴 | INFO | roomId, 실행자 id, 대상 id | — |
| 방 생성 / 입장 / 퇴장 | INFO | roomId, userId | **방 비밀번호** |
| 정리 스케줄러 결과 (`08` P1-4) | INFO | 처리 건수 | — |
| 타이머 기록 (`POST /timer`) | (남기지 않음) | — | 호출이 너무 빈번 |

**우선순위 1번은 미처리 예외입니다.** `04-exception.md` P0-1의 핸들러에 `log.error(..., e)` 한 줄을 넣는 것이 이 리뷰 전체에서 가장 비용 대비 효과가 큰 변경입니다 — 이후 모든 수정의 검증 수단이 되기 때문입니다.

```java
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e, HttpServletRequest req) {
        log.error("unhandled exception at {} {}", req.getMethod(), req.getRequestURI(), e);
        //                                                                              ^ 마지막 인자가
        //                                        Throwable이면 SLF4J가 스택트레이스를 함께 출력
        ...
    }
```

마지막 인자를 `Throwable`로 두는 것이 포인트입니다. `log.error("msg: " + e.getMessage())`처럼 문자열로 붙이면 **스택트레이스가 사라집니다** — 어느 줄에서 터졌는지 알 수 없게 됩니다.

---

## P1-2. 로그 로테이션이 없어 디스크가 무한히 찬다

**위치** `src/main/resources/application.yml:23-26`, `docker-compose.yml:25-41`

두 층 모두 로테이션 설정이 없습니다.

**(1) 애플리케이션 층** — `logging.file.name`/`logging.file.path` 미설정 → Logback이 **콘솔에만** 출력합니다. 파일이 없으니 애플리케이션 차원의 로테이션 대상도 없습니다. (파일을 쓰지 않는 것 자체는 컨테이너 환경에서 올바른 선택입니다 — stdout으로 내보내고 수집은 런타임에 맡기는 것이 표준입니다.)

**(2) 컨테이너 층** — `docker-compose.yml`의 app 서비스에 `logging:` 블록이 없습니다.

```yaml
  app:
    image: yejiii1997/cmas:latest
    platform: linux/amd64
    container_name: camstudy-backend
    depends_on:
      - mysql
    ports:
      - "8080:8080"
    environment: { ... }
    restart: always
    # logging: 없음
```

Docker의 기본 로깅 드라이버는 **`json-file`** 이고, `max-size`/`max-file` 옵션의 기본값은 **무제한**입니다. 컨테이너의 stdout/stderr가 전부 `/var/lib/docker/containers/<id>/<id>-json.log`에 **무한히 누적**됩니다.

### 왜 문제인가

로그가 얼마나 빨리 쌓이는지가 문제입니다. 이 애플리케이션은 세 가지 이유로 stdout 출력량이 큽니다.

**(a) `show-sql: true`가 모든 SQL을 출력합니다.**

`format_sql: true`까지 켜져 있어 한 문장이 여러 줄로 포맷됩니다. `GET /timer/analytics` 한 번에 SQL 6개가 나가므로(`02-performance.md` P1-1) 요청 하나가 수십 줄을 만듭니다.

**(b) `02-performance.md` P0-1의 무한 루프가 발동하면 stdout이 폭주합니다.**

`POST /timer`에 자정을 넘는 구간을 보내면 `findByUserIdAndDate`가 무한 반복되고, 매번 SQL이 stdout으로 출력됩니다. 이 상태의 요청 하나가 **로그 파일을 채우기 시작하고, 멈추지 않습니다.**

**(c) `restart: always`가 상황을 악화시킵니다.**

컨테이너가 재시작되어도 `json-file` 로그는 컨테이너 id 기준으로 유지되고, 새 로그가 계속 추가됩니다.

**디스크가 차면 무엇이 죽는가** — 이게 핵심입니다.

`docker-compose.yml:15-16`의 MySQL 데이터 볼륨(`mysql-vl`)이 `driver: local`이므로 **호스트의 `/var/lib/docker/volumes/`에 저장됩니다.** 로그도 `/var/lib/docker/containers/`에 있습니다. 즉 **같은 파일시스템**입니다.

따라서 로그가 디스크를 채우면:
1. MySQL이 쓰기를 실패합니다 (InnoDB가 `No space left on device`로 오류)
2. 애플리케이션의 모든 쓰기 요청이 실패합니다
3. MySQL이 복구 불가 상태로 갈 수 있습니다

**로그 설정 누락이 DB 장애로 이어지는 경로**입니다.

`[[확인필요: 운영 호스트의 디스크 사용량과 현재 컨테이너 로그 크기 — du -sh /var/lib/docker/containers/*/ 및 df -h]]`

### 수정 방향

**즉시 조치 — compose에 로테이션 옵션을 추가합니다.**

```diff
--- a/docker-compose.yml
+++ b/docker-compose.yml
   app:
     image: yejiii1997/cmas:latest
     ...
     restart: always
+    logging:
+      driver: json-file
+      options:
+        max-size: "20m"
+        max-file: "5"
```

`max-size: 20m` × `max-file: 5` = 컨테이너당 최대 100MB로 상한이 생깁니다. 초과하면 가장 오래된 파일이 삭제됩니다.

**mysql 서비스에도 같은 설정을 넣으세요.** MySQL도 stdout으로 에러 로그를 냅니다.

```diff
   mysql:
     image: mysql:8.0
     ...
     restart: always
+    logging:
+      driver: json-file
+      options:
+        max-size: "20m"
+        max-file: "3"
```

**함께 해야 하는 것 — 출력량 자체를 줄입니다.**

로테이션만 걸면 로그가 잘리지 않고 순환하지만, SQL이 로그를 채우면 **정작 필요한 에러 로그가 밀려 나갑니다.** `02-performance.md` P1-5의 `show-sql` 제거가 함께 필요합니다.

```diff
--- a/src/main/resources/application.yml
+++ b/src/main/resources/application.yml
   jpa:
     hibernate:
       ddl-auto: update
-    show-sql: true
     properties:
       hibernate:
-        format_sql: true
+        format_sql: false
```

로컬에서 SQL을 봐야 하면 `application-local.yml`에서 **로거로** 켜세요 (`10-config.md`).

```yaml
# application-local.yml
logging:
  level:
    org.hibernate.SQL: DEBUG
```

이렇게 하면 레벨로 통제되고, Logback의 비동기 appender·포맷·로테이션이 적용됩니다. `show_sql`의 `System.out` 직접 출력은 그 어느 것도 적용받지 않습니다.

---

## P1-3. 민감정보가 로그로 나갈 수 있는 경로 — 지금 하나, 앞으로 셋

### (a) 지금 열려 있는 경로: `spring.security.debug: true`

**위치** `src/main/resources/application.yml:4-5`

`05-security.md` P1-7에서 두 갈래로 분석했습니다. **동작하는 경우**(갈래 B) Security의 `DebugFilter`가 요청마다 적용된 필터 목록과 **요청 상세를 로그로 남깁니다.** 요청 상세에는 헤더가 포함되고, 헤더에는 이것이 있습니다.

```
Cookie: AccessToken=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyQGV4YW1wbGUuY29tIiwi...
```

**유효한 인증 토큰이 평문으로 로그에 기록됩니다.** 그리고:

- 그 토큰은 발급 후 1시간 유효합니다
- 로그아웃해도 무효화되지 않습니다 (`05-security.md` P1-1)
- 로그 로테이션이 없으므로 **영구히 남습니다** (P1-2)
- `json-file` 로그는 호스트에서 평문으로 읽을 수 있습니다

즉 **호스트나 로그 수집 시스템에 접근할 수 있는 누구든 임의 사용자의 세션을 탈취할 수 있습니다.**

`[[확인필요: 부팅 로그에 Security 필터 체인 목록이 출력되는지. 출력되면 이 경로가 활성 상태입니다]]`

동작하지 않더라도(갈래 A) 죽은 설정이므로 **지워야 합니다.**

```diff
--- a/src/main/resources/application.yml
+++ b/src/main/resources/application.yml
 spring:
   application:
     name: backend
-  security:
-    debug: true
   datasource:
```

### (b) 절대 켜면 안 되는 것: Hibernate 파라미터 바인딩 로거

현재 `show-sql`은 파라미터를 `?`로 표시하므로 **값은 로그에 나가지 않습니다.** 이건 다행입니다.

그런데 SQL 디버깅을 하다 보면 "값이 안 보인다"는 이유로 이걸 켜게 됩니다.

```yaml
logging:
  level:
    org.hibernate.orm.jdbc.bind: TRACE     # Hibernate 6
    # 또는 org.hibernate.type.descriptor.sql: TRACE  (Hibernate 5)
```

켜면 모든 쿼리의 바인딩 값이 로그에 찍힙니다. 이 프로젝트에서는:

| 쿼리 | 로그에 나가는 값 |
|---|---|
| `findByEmail` | **이메일 전체** |
| `save(User)` (signup) | 이메일, **BCrypt 해시** |
| `findByUserIdAndDate` (Timer) | **이메일** (`Timer.userId`가 이메일) |
| `findByUserId` (Window) | **이메일** |
| `save(Room)` | **Argon2 방 비밀번호 해시** |

비밀번호 해시는 평문은 아니지만, 오프라인 무차별 대입의 입력이 됩니다. 그리고 이메일은 개인정보입니다.

**로컬에서만, 임시로 켜고 반드시 되돌리세요.** 운영 profile에 들어가지 않도록 `application-local.yml`에만 두는 것이 안전합니다(`10-config.md`).

### (c) 앞으로 추가할 로그에서 주의할 것

P1-1에서 로깅을 추가하라고 했는데, 이 프로젝트에는 함정이 있습니다. **사용자 식별자가 이메일입니다** (`01-jpa-query.md` P1-2).

```java
// 이렇게 쓰기 쉽습니다 — 그런데 이메일이 로그에 남습니다
log.info("window created by {}", userEmail);
```

`Window.userId`, `Timer.userId`, `Member.userId`가 모두 이메일이라, 로깅할 때 자연스럽게 이메일을 찍게 됩니다. 로그가 개인정보 저장소가 되면 보관 기간·접근 통제·삭제 요청 대응 의무가 따라옵니다.

**세 가지 대응이 있습니다.**

| 방법 | 예시 | 비고 |
|---|---|---|
| `User.id`(Long)를 로깅 | `log.info("window created by userId={}", user.getId())` | 가장 깔끔. `01-jpa-query.md` P1-2 해결이 선행 |
| 마스킹 | `use***@example.com` | 간단하지만 동일 사용자 추적이 어려움 |
| 해시 | `sha256(email)[0..8]` | 추적 가능하면서 원문 노출 없음 |

`01-jpa-query.md` P1-2(이메일 대신 `Long id`로 참조)를 해결하면 첫 번째가 자연스럽게 가능해집니다. 그 전까지는 해시나 마스킹을 쓰세요.

그리고 `04-exception.md` P1-3에서 제안한 JWT 로깅도 이 문제에 걸립니다.

```java
        } catch (ExpiredJwtException e) {
            log.debug("expired token for subject={}", e.getClaims().getSubject());  // ← 이메일
```

`DEBUG` 레벨이고 운영은 `INFO`이므로 실제로는 출력되지 않지만, 레벨을 내리는 순간 나갑니다. 애초에 넣지 않는 편이 안전합니다.

**절대 로깅하지 말 것 (체크리스트)**

- JWT 토큰 원문 (`AccessToken`, `RefreshToken`)
- `Cookie` 헤더 전체
- 비밀번호 평문 및 해시
- 방 비밀번호
- `Authorization` 헤더
- 요청 바디 전체 (로그인·회원가입 요청은 비밀번호를 담고 있습니다)

`04-exception.md` P0-1에서 예외 메시지를 응답에 넣지 말라고 한 것과 같은 이유로, **예외 메시지를 로그에 남기는 것은 괜찮습니다** — 로그는 서버 내부이고, 그게 디버깅 정보를 두는 올바른 위치입니다. 단 로그 접근 통제가 되어 있다는 전제가 필요합니다.

---

## P1-4. 요청 추적 수단이 없음 — 로그가 생겨도 하나의 요청으로 묶을 수 없음

`logging.pattern.*`이 미설정이므로 Logback 기본 콘솔 패턴이 쓰입니다. 대략 이런 형태입니다.

```
2026-09-03T11:03:22.123+09:00  INFO 1 --- [nio-8080-exec-3] c.c.b.service.RoomService : ...
```

포함된 것: 시각, 레벨, PID, **스레드명**, 로거명, 메시지.
없는 것: **요청 식별자**, 사용자 식별자, 인스턴스 식별자.

### 왜 문제인가

**(1) 스레드명으로는 요청을 묶을 수 없습니다.**

`[nio-8080-exec-3]`은 Tomcat 워커 스레드 이름이고 **재사용됩니다.** 요청 A가 끝나면 같은 스레드가 요청 B를 처리합니다. 동시 요청이 많은 시점의 로그에서 "이 ERROR와 이 WARN이 같은 요청에서 나왔는가"를 판단할 수 없습니다.

**(2) 사용자 신고와 로그를 연결할 수 없습니다.**

`04-exception.md` P2-4에서 이미 다룬 내용입니다. 사용자가 "3시쯤 오류 났어요"라고 하면 그 시각대의 모든 로그를 훑어야 합니다.

**(3) 인스턴스를 구분할 수 없습니다.**

지금은 컨테이너 1개지만, 스케일아웃하거나 무중단 배포 중이면 여러 인스턴스의 로그가 섞입니다. `08-concurrency.md` P2-4의 스케줄러 중복 실행도 인스턴스 식별자가 없으면 관찰할 수 없습니다.

### 수정 방향

**MDC(Mapped Diagnostic Context)** 를 씁니다. SLF4J가 제공하는 스레드 로컬 맵이고, 로그 패턴에서 `%X{key}`로 꺼낼 수 있습니다.

```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String requestId = Optional.ofNullable(req.getHeader("X-Request-Id"))
                .filter(s -> !s.isBlank())
                .orElseGet(() -> UUID.randomUUID().toString().substring(0, 8));
        MDC.put("requestId", requestId);
        res.setHeader("X-Request-Id", requestId);          // 클라이언트도 알 수 있게
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.clear();                                    // ← 반드시 finally에서
        }
    }
}
```

`MDC.clear()`를 `finally`에 두는 것이 중요합니다. **MDC는 스레드 로컬이고 Tomcat 스레드는 재사용되므로**, 지우지 않으면 다음 요청의 로그에 이전 요청의 `requestId`가 붙습니다. 이건 요청 추적을 하려다 오히려 잘못된 추적 정보를 만드는 흔한 실수입니다.

패턴에 반영합니다.

```diff
--- a/src/main/resources/application.yml
+++ b/src/main/resources/application.yml
 logging:
   level:
     org.springframework.security: WARN
     root: INFO
+  pattern:
+    console: "%d{yyyy-MM-dd HH:mm:ss.SSS} %5p [%X{requestId:-}] [%t] %-40.40logger{39} : %m%n"
```

`%X{requestId:-}`가 MDC 값을 꺼내고, 없으면 빈 문자열입니다(`:-` 뒤가 기본값). 부팅 로그처럼 요청 컨텍스트가 없는 로그에서도 깨지지 않습니다.

그리고 응답 헤더로 `X-Request-Id`를 돌려주면, 사용자가 개발자 도구에서 그 값을 알려줄 수 있습니다. `04-exception.md` P2-4에서 제안한 "오류 메시지에 traceId 포함"과 같은 값을 쓰면 됩니다.

**인스턴스 식별자**는 환경변수로 주입해 패턴에 넣습니다.

```yaml
# docker-compose.yml
    environment:
      INSTANCE_ID: "app-1"
```
```yaml
  pattern:
    console: "... [${INSTANCE_ID:-local}] [%X{requestId:-}] ..."
```

**사용자 식별자**를 MDC에 넣고 싶다면 P1-3 (c)의 주의사항을 지키세요 — 이메일 원문이 아니라 `User.id`나 해시를 넣습니다.

---

## P2-1. 로그 설정이 profile 분리 없이 단일 파일에 고정

`application.yml` 하나가 로컬·운영 공용이므로(`00-overview.md`), 로그 설정도 한 벌뿐입니다. 그 결과:

- 로컬에서 SQL을 보려면 `show-sql: true`를 켜야 하고, 그게 **운영에도 적용**됩니다 (P1-2)
- 운영에서 특정 패키지를 DEBUG로 잠시 올리려면 `application.yml`을 수정해 **재배포**해야 합니다
- 커밋 `1b5edff`가 `security: TRACE → WARN`으로 내린 이력은, 개발 중 켜둔 TRACE가 **운영까지 그대로 갔다가** 나중에 정리된 흔적으로 보입니다

`10-config.md`의 profile 분리와 함께 해결됩니다. 방향만 적습니다.

```yaml
# application.yml — 운영 안전 기본값
logging:
  level:
    root: INFO
    com.camstudy.backend: INFO
    org.springframework.security: WARN
```

```yaml
# application-local.yml — 로컬 전용
logging:
  level:
    com.camstudy.backend: DEBUG
    org.hibernate.SQL: DEBUG
    # org.hibernate.orm.jdbc.bind: TRACE   ← 필요할 때만, P1-3 (b) 참고
```

`com.camstudy.backend`를 명시적으로 항목에 두는 이유: 이 프로젝트의 코드에서 나오는 로그만 레벨을 따로 조절할 수 있게 됩니다. `root`만 있으면 프레임워크 로그까지 함께 움직입니다.

**운영 중 재배포 없이 레벨을 바꾸는 방법**도 있습니다 — Actuator의 `loggers` 엔드포인트로 런타임 변경이 가능합니다. 다만 `05-security.md` P1-5에서 다룬 대로 그 엔드포인트를 노출하면 위험하므로, 인증 뒤에 두어야 합니다.

---

## P2-2. 구조화 로깅이 없어 집계·검색이 어려움 — Boot 3.4는 기본 지원함

기본 패턴은 사람이 읽기 위한 형식입니다. 로그 수집 시스템(Loki, CloudWatch Logs, ELK 등)에 넣으려면 정규식으로 파싱해야 하고, 스택트레이스처럼 여러 줄인 로그는 파싱이 자주 깨집니다.

**Spring Boot 3.4에는 구조화 로깅이 정식으로 들어 있습니다.** 이 프로젝트가 3.4.4이므로(`build.gradle:3`) 별도 의존성 없이 쓸 수 있습니다.

```yaml
# application.yml (운영)
logging:
  structured:
    format:
      console: ecs      # ecs | logstash | gelf
```

이렇게 하면 콘솔 출력이 한 줄 JSON이 됩니다.

```json
{"@timestamp":"2026-09-03T02:03:22.123Z","log.level":"ERROR","message":"unhandled exception at POST /timer","service.name":"backend","requestId":"a3f9c201","error.stack_trace":"..."}
```

MDC 값이 자동으로 필드가 되므로, P1-4의 `requestId`로 검색·필터가 가능해집니다. 스택트레이스도 하나의 필드에 들어가 파싱이 깨지지 않습니다.

**지금 당장 필요한지는 다릅니다.** 로그 수집 시스템이 없다면(현재 `docker logs`로만 보는 것으로 보입니다) 사람이 읽기 어려워지는 손실만 있습니다. 로컬은 기본 패턴, 운영은 JSON으로 나누는 것이 실용적입니다.

```yaml
# application-local.yml — 로컬은 사람이 읽는 형식 유지
logging:
  structured:
    format:
      console: ""
```

`[[확인필요: 운영에서 로그를 어떻게 보고 있는지 — docker logs 직접 조회인지, 수집 시스템이 있는지]]`
수집 시스템이 없다면 P1-2(로테이션)가 우선이고 이 항목은 나중입니다.

---

## P2-3. `banner.txt`가 배포 확인 수단으로 쓰인 흔적

**위치** `src/main/resources/banner.txt`

```
====================================
     THIS IS THE LATEST CODE
=====================================
```

부팅 로그에만 출력되므로 외부 노출은 없습니다. "배포된 코드가 최신인지" 확인하려고 넣은 것으로 보입니다.

그 목적에는 맞지 않습니다 — 이 문구는 **어떤 버전이든 항상 같게** 출력되므로, 실제로 최신 코드인지 알 수 없습니다.

`build.gradle:47`의 `springBoot { buildInfo() }`가 이미 빌드 정보를 생성하고 있으므로, 배너에서 그 값을 참조할 수 있습니다.

```
====================================
  camstudy-backend ${application.version}
  built: ${application.formatted-version}
  spring boot ${spring-boot.version}
====================================
```

또는 배너를 지우고 `/actuator/info`로 확인하세요(`05-security.md` P1-5에서 노출 범위를 제한한 뒤).

우선순위는 낮습니다. 다만 이 파일이 남아 있는 것 자체가 **"배포가 반영됐는지 확인할 수단이 없어서 임시로 만든 흔적"** 이고, 그 근본 문제는 P1-1(로그 부재)과 `12-docker-deploy.md`에서 다룹니다.

---

## 요약

| 심각도 | 개수 | 항목 |
|---|---|---|
| **P0** | 0 | — |
| **P1** | 4 | 애플리케이션 로그 0건 → 500 17경로가 전부 무성, 보안 사건 흔적 없음 / 로그 로테이션 없음 → 디스크 고갈 시 MySQL 동반 장애 / `spring.security.debug`로 인증 토큰이 평문 로깅될 수 있음 + 이메일 로깅 함정 / 요청 추적 수단(MDC/traceId) 없음 |
| **P2** | 3 | 로그 설정이 profile 분리 없이 고정 / 구조화 로깅 미사용 (Boot 3.4 기본 지원) / `banner.txt`가 배포 확인 수단으로 부적합 |

## 사용자가 물은 항목에 대한 직접 답

| 질문 | 답 |
|---|---|
| 운영에 DEBUG/TRACE 남았는지 | **`logging.level`에는 없습니다.** `root: INFO`, `security: WARN`으로 커밋 `1b5edff`에서 정리됐습니다. **그런데 레벨 체계 밖에서 DEBUG 수준 출력을 하는 설정이 둘 있습니다** — `show-sql: true`(Hibernate가 `System.out`으로 직접 출력해 `logging.level`이 적용되지 않음)와 `spring.security.debug: true`. 둘 다 `application-prod.yml`이 없어 운영에 적용됩니다. |
| 민감정보 로깅 | **현재 열려 있는 경로 1건**: `spring.security.debug`가 동작하면 `Cookie` 헤더가 로깅되어 **유효한 JWT가 평문으로** 남습니다(로테이션이 없으니 영구히). 현재 `show-sql`은 파라미터를 `?`로 표시해 값은 안 나갑니다. **앞으로의 함정**: 사용자 식별자가 이메일이라 로깅을 추가하면 자연히 개인정보가 남습니다. Hibernate 바인딩 로거(`org.hibernate.orm.jdbc.bind`)를 켜면 이메일·비밀번호 해시가 나가므로 운영에서 켜지 마세요. |
| 요청 추적 | **없습니다.** 로그 패턴에 요청 식별자가 없고 MDC를 쓰지 않습니다. 스레드명(`[nio-8080-exec-3]`)은 재사용되므로 요청 구분에 쓸 수 없습니다. MDC + 필터로 `requestId`를 넣는 방법을 적었습니다 — `MDC.clear()`를 `finally`에 두는 것이 필수입니다. |
| 로테이션 | **양쪽 층 모두 없습니다.** 애플리케이션은 콘솔만 출력(파일 없음 — 컨테이너 환경에선 올바른 선택), 컨테이너는 `logging:` 옵션이 없어 Docker 기본 `json-file`에 **무제한** 누적됩니다. `docker-compose.yml:45`의 `driver: local`은 볼륨 드라이버라 무관합니다. MySQL 데이터 볼륨과 로그가 **같은 파일시스템**이므로, 디스크가 차면 DB 쓰기가 실패합니다. |

## 확인이 필요한 항목 (3개)

1. **`df -h` 및 `du -sh /var/lib/docker/containers/*/`** — 현재 디스크 여유와 컨테이너 로그 크기 (P1-2가 이미 진행 중인지)
2. 부팅 로그에 Security 필터 체인 목록이 출력되는지 — `spring.security.debug` 실효 여부 (P1-3 (a))
3. 운영에서 로그를 어떻게 보고 있는지 (`docker logs` 직접 조회 / 수집 시스템 유무) — P2-2 우선순위 판단

## 적용 순서

```
1. docker-compose에 logging max-size/max-file 추가 (P1-2)    ← 즉시. 한 블록
2. spring.security.debug 제거 (P1-3a)                          ← 즉시. 두 줄 삭제
3. show-sql / format_sql 끄기 (P1-2, 02 P1-5)                  ← 즉시
4. GlobalExceptionHandler에 log.error(..., e) (P1-1)           ← 04 P0-1과 동일 작업
5. RequestIdFilter + MDC + 로그 패턴 (P1-4)
6. 보안 감사 로그 추가 — 로그인 실패, 서명 불일치, 강퇴 (P1-1)
7. profile 분리 후 로컬/운영 레벨 나누기 (P2-1, 10-config)
8. 구조화 로깅 (P2-2) — 수집 시스템 도입 시
```

1~3번은 **코드 변경 없이 설정만**으로 끝나고, 디스크 고갈과 토큰 유출이라는 가장 큰 두 위험을 제거합니다. 4번은 이후 모든 카테고리 수정의 검증 수단이므로 그다음입니다.

실행·측정은 하지 않았습니다. 로그 증가 속도나 디스크 여유는 확인 항목 1번으로 직접 봐야 합니다.
