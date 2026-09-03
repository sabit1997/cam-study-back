# 11. 테스트

조사 범위: 커버리지 숫자가 아니라 "무엇을 검증하는지". 테스트 없는 핵심 로직 지목.

## 확정한 사실

| 확인 항목 | 결과 |
|---|---|
| 테스트 소스 파일 | **1개** — `src/test/java/com/camstudy/backend/BackendApplicationTests.java` |
| 그 파일의 내용 | `@SpringBootTest` + 본문이 비어 있는 `contextLoads()` |
| `src/test/resources` | **없음** (테스트 전용 설정 파일 없음) |
| CI 파이프라인 (`.github/workflows` 등) | **없음** |
| 커버리지 도구 (jacoco) | **없음** |
| 테스트 의존성 | `spring-boot-starter-test`, `spring-security-test`, `junit-platform-launcher` — **갖춰져 있음** |

```java
// BackendApplicationTests.java — 전부
@SpringBootTest
class BackendApplicationTests {
	@Test
	void contextLoads() {
	}
}
```

**비즈니스 로직을 검증하는 테스트는 0건입니다.**

**커버리지 수치는 측정하지 않았습니다.** 측정할 대상이 없기도 하고, 이 카테고리에서 의미 있는 질문은 "몇 퍼센트인가"가 아니라 **"어떤 결함이 통과했는가"** 이기 때문입니다. 그 답이 P1-2입니다.

---

## P1-1. 유일한 테스트가 아무것도 검증하지 않으며, 클린 체크아웃에서 통과할 수 없음

### (a) 검증하는 것이 없습니다

`contextLoads()`는 본문이 비어 있습니다. 이 테스트가 확인하는 것은 **"Spring 컨텍스트가 뜬다"** 뿐입니다.

그것도 가치가 없지는 않습니다 — 빈 순환 참조, 애너테이션 누락, 설정 오류를 잡습니다. 실제로 이 프로젝트에서는 `03-transaction.md` P2-1의 self-invocation이나 `04-exception.md` P2-3의 `@ExceptionHandler` 매핑 충돌 같은 것을 잡을 수 있습니다.

문제는 **그것 말고는 아무것도 잡지 못한다**는 점입니다. 지금까지 이 리뷰에서 찾은 P0 6건, P1 42건 중 **단 하나도** 이 테스트로 잡히지 않습니다.

### (b) 이 테스트는 지금 통과하지 못할 가능성이 높습니다

`@SpringBootTest`는 **전체 애플리케이션 컨텍스트**를 띄웁니다. 그 과정에서 다음이 필요합니다.

**1) 환경변수 6개**

`application.yml:7-9`, `:29`가 placeholder를 씁니다.

```yaml
    url: jdbc:mysql://${DB_HOST}:${DB_PORT}/${DB_NAME}?useSSL=false&serverTimezone=UTC
    username: ${DB_USER}
    password: ${DB_PASSWORD}
...
jwt:
  secretKey: ${JWT_SECRET}
```

기본값(`${DB_HOST:localhost}` 형태)이 없으므로, 환경변수가 없으면 placeholder 해석이 실패합니다.

```
IllegalArgumentException: Could not resolve placeholder 'DB_HOST' in value "jdbc:mysql://${DB_HOST}:..."
```

→ 컨텍스트 로딩 실패 → **테스트 실패**

**2) 실제로 접속 가능한 MySQL**

환경변수를 채워도 끝이 아닙니다. `application.yml:13`의 `ddl-auto: update`는 Hibernate가 부팅 시 **기존 스키마를 읽어서** 비교하도록 만듭니다. 즉 SessionFactory 생성 단계에서 DB 연결이 필요합니다. HikariCP의 `initializationFailTimeout` 기본값도 즉시 실패 쪽이라, DB가 없으면 컨텍스트 로딩이 실패합니다.

**3) `src/test/resources`가 없어 이를 우회할 방법이 없습니다**

보통은 `src/test/resources/application.yml`이나 `application-test.yml`을 두어 테스트용 DB 설정을 넣습니다. 그 디렉터리 자체가 없습니다.

### 이게 왜 중요한가

**아무도 이 사실을 모르고 있을 가능성이 높습니다.** CI가 없으므로(`.github` 없음) `./gradlew test`가 자동으로 실행되는 곳이 없고, 실패해도 알림이 가지 않습니다.

즉 이 프로젝트에는 **"테스트가 있다"는 외형은 있지만 실행되지 않는 테스트**가 하나 있는 상태입니다. 이 리뷰의 수정 사항을 적용할 때 `./gradlew build`가 이 테스트 때문에 실패하면, 그걸 우회하려고 `-x test`를 붙이거나 테스트를 지우는 방향으로 가기 쉽습니다.

`[[확인필요: 환경변수 없이 ./gradlew test 를 실행했을 때 실제로 실패하는지. 저는 실행하지 않았습니다 — 이 머신에 Gradle 의존성 캐시(~/.gradle)가 없어 오프라인 실행이 불가능합니다]]`

### 수정 방향

**테스트 전용 설정을 두고, 컨텍스트 테스트가 DB에 의존하지 않게 만듭니다.**

```yaml
# src/test/resources/application.yml (신규)
spring:
  datasource:
    url: jdbc:h2:mem:testdb;MODE=MySQL;DB_CLOSE_DELAY=-1
    username: sa
    password:
    driver-class-name: org.h2.Driver
  jpa:
    hibernate:
      ddl-auto: create-drop
    show-sql: false
  security:
    debug: false

jwt:
  secretKey: dGVzdC1zZWNyZXQta2V5LWZvci11bml0LXRlc3RzLW9ubHktMzJieXRlcyE=

logging:
  level:
    root: WARN
```

**주의**: 이건 `contextLoads()`를 통과시키기 위한 최소 조치이고, **실제 검증에는 H2를 쓰면 안 됩니다.** 이유는 P2-1에서 설명합니다.

`jwt.secretKey` 값은 테스트 전용 더미입니다 — 실제 시크릿을 테스트 설정에 넣지 마세요. Base64로 디코딩했을 때 32바이트 이상이어야 합니다(`05-security.md` P1-9).

---

## P1-2. 이 리뷰에서 찾은 결함 중 최소 9건은 테스트 하나로 잡혔을 것

"테스트가 없다"는 지적은 추상적입니다. 구체적으로 **어떤 결함이 어떤 테스트로 잡혔을지** 정리했습니다. 이게 이 카테고리의 핵심 산출물입니다.

| 결함 | 심각도 | 어떤 테스트면 잡혔나 | 테스트 층위 |
|---|---|---|---|
| `POST /timer` 자정 넘김 무한 루프 (`02` P0-1) | **P0** | `record()`에 자정 걸친 구간을 넣고 타임아웃 검증 | 순수 단위 |
| `POST /windows`로 남의 창 탈취 (`06` P0-1) | **P0** | 다른 사용자 인증으로 `{"id": 남의창}` POST | 통합 |
| `/rooms` 계열이 body userId 신뢰 (`05` P0-1) | **P0** | A로 인증하고 body에 B의 id를 넣어 호출 | 통합 |
| 전역 핸들러가 상태 코드를 버림 (`04` P0-1) | **P0** | 없는 방 조회 시 404 기대 | `@WebMvcTest` |
| `PATCH /todos/{id}`가 텍스트를 null로 삭제 (`07` P1-2) | **P1** | `{"done":true}`를 보내고 텍스트 보존 검증 | `@WebMvcTest` |
| 날짜 필터 경계 중복 (`01` P1-4) | **P1** | 자정 정각 생성 todo가 한 날짜에만 나오는지 | `@DataJpaTest` |
| `readOnly` 트랜잭션에서 조용한 저장 실패 (`03` P1-4) | **P1** | 저장 후 **재조회**로 반영 확인 | `@DataJpaTest` |
| `dailyGoalHours=0`일 때 `NaN` 응답 (`04` P1-1 17번) | **P1** | goal을 0으로 두고 analytics 조회 | 통합 |
| `Timer` 동시 생성 시 중복/500 (`08` P1-2) | **P1** | 유니크 제약 존재 여부 검증 | `@DataJpaTest` |

**공통점이 있습니다.** 이 9건 중 8건은 **실패가 조용합니다** — 예외가 나거나 화면이 깨지지 않고, 잘못된 값이 200과 함께 나가거나 데이터가 소리 없이 사라집니다. 사람이 손으로 클릭해서 테스트하면 **알아차리기 어려운 종류**입니다. 자동화된 검증이 특히 값어치를 하는 지점입니다.

### 실제로 이렇게 씁니다

가장 가치가 큰 것 세 개를 실제 코드로 적습니다. 나머지도 같은 패턴입니다.

**① 무한 루프 (`02` P0-1) — Spring 없이, 가장 빠르게**

```java
package com.camstudy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TimerServiceRecordTest {

    private final TimerRepository repo = mock(TimerRepository.class);
    private final UserRepository userRepo = mock(UserRepository.class);
    private final TimerService sut = new TimerService(repo, userRepo);

    @Test
    void 자정을_넘는_구간을_기록해도_종료된다() {
        when(repo.findByUserIdAndDate(any(), any())).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // KST 기준 9/2 23:30 ~ 9/3 00:30
        Instant start = Instant.parse("2026-09-02T14:30:00Z");
        Instant end   = Instant.parse("2026-09-02T15:30:00Z");

        assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
            sut.record("u@example.com", start, end, ZoneId.of("Asia/Seoul")));
    }

    @Test
    void 자정을_넘으면_두_날짜로_나뉘어_기록된다() {
        when(repo.findByUserIdAndDate(any(), any())).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        sut.record("u@example.com",
                Instant.parse("2026-09-02T14:30:00Z"),
                Instant.parse("2026-09-02T15:30:00Z"),
                ZoneId.of("Asia/Seoul"));

        ArgumentCaptor<Timer> saved = ArgumentCaptor.forClass(Timer.class);
        verify(repo, atLeastOnce()).save(saved.capture());

        Map<LocalDate, Long> byDate = saved.getAllValues().stream()
                .collect(Collectors.toMap(Timer::getDate, Timer::getTotalSeconds, (a, b) -> b));

        assertThat(byDate).containsOnlyKeys(LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 3));
        assertThat(byDate.get(LocalDate.of(2026, 9, 2))).isEqualTo(1800);  // 23:30~24:00
        assertThat(byDate.get(LocalDate.of(2026, 9, 3))).isEqualTo(1800);  // 00:00~00:30
    }
}
```

**`assertTimeoutPreemptively`가 핵심입니다.** `assertTimeout`(preemptive 아님)은 코드가 **끝날 때까지 기다린 뒤** 소요 시간을 확인합니다. 무한 루프에는 무용지물이고 테스트 자체가 영원히 멈춥니다. `assertTimeoutPreemptively`는 별도 스레드에서 실행하고 시간이 지나면 **중단**시킵니다.

두 번째 테스트가 더 중요합니다. 첫 번째는 "안 멈추는지"만 보지만, 두 번째는 **1800 + 1800 = 3600초가 올바르게 분할되는지**를 검증합니다. `02-performance.md` P0-1의 수정안(`atStartOfDay` 사용)을 적용하면 통과하고, 지금 코드로는 첫 번째부터 실패합니다.

Spring 컨텍스트가 필요 없으므로 DB도, 환경변수도 필요 없습니다. **P1-1의 문제와 무관하게 지금 당장 추가할 수 있습니다.**

**② 권한 검사 (`05` P0-1, `06` P0-1) — 가장 값어치가 큰 보안 테스트**

```java
@SpringBootTest
@AutoConfigureMockMvc
class WindowAuthorizationTest {

    @Autowired MockMvc mockMvc;
    @Autowired WindowRepository windowRepository;

    private Authentication as(String email) {
        return new UsernamePasswordAuthenticationToken(
                email, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    @Test
    void 남의_창_id를_보내도_소유자가_바뀌지_않는다() throws Exception {
        Window victims = windowRepository.save(Window.builder()
                .userId("victim@example.com").type(WindowType.todo).build());

        mockMvc.perform(post("/windows")
                        .with(authentication(as("attacker@example.com")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":" + victims.getId() + ",\"type\":\"todo\"}"))
                .andExpect(status().isCreated());

        Window after = windowRepository.findById(victims.getId()).orElseThrow();
        assertThat(after.getUserId()).isEqualTo("victim@example.com");   // ← 지금은 실패
    }

    @Test
    void 남의_창은_수정할_수_없다() throws Exception {
        Window victims = windowRepository.save(Window.builder()
                .userId("victim@example.com").x(10).build());

        mockMvc.perform(patch("/windows/" + victims.getId())
                        .with(authentication(as("attacker@example.com")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"x\":999}"))
                .andExpect(status().isNotFound());    // 지금은 500 (04 P0-1)

        assertThat(windowRepository.findById(victims.getId()).orElseThrow().getX())
                .isEqualTo(10);
    }
}
```

**주의할 점이 하나 있습니다.** `spring-security-test`가 이미 의존성에 있지만(`build.gradle:35`) **`@WithMockUser`를 쓰면 이 프로젝트에서는 동작하지 않습니다.**

`@WithMockUser(username = "a@b.com")`는 principal을 `org.springframework.security.core.userdetails.User` 객체로 만듭니다. 그런데 이 프로젝트의 컨트롤러는 `@AuthenticationPrincipal String userEmail`로 받고(`WindowController.java:22` 등), `JwtFilter.java:64-68`이 principal을 **`String`(이메일)** 으로 넣습니다.

```java
// JwtFilter.java:64-68 — principal이 String
new UsernamePasswordAuthenticationToken(email, null, ...)
```

타입이 맞지 않으면 `AuthenticationPrincipalArgumentResolver`가 **`null`을 주입**합니다. 즉 `@WithMockUser`로 테스트하면 `userEmail`이 `null`이 되어, 실제 동작과 다른 것을 테스트하게 됩니다.

위 코드처럼 `SecurityMockMvcRequestPostProcessors.authentication(...)`으로 **`JwtFilter`와 동일한 형태의 Authentication**을 만들어야 합니다. 재사용하려면 커스텀 애너테이션(`@WithMockEmail`)을 만드는 것도 방법입니다.

**③ 조용한 데이터 손실 (`07` P1-2)**

```java
@Test
void done만_보낸_PATCH가_텍스트를_지우지_않는다() throws Exception {
    TodoItem todo = /* "장보기" 텍스트로 저장 */;

    mockMvc.perform(patch("/todos/" + todo.getId())
                    .with(authentication(as("owner@example.com")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"done\":true}"))
            .andExpect(status().isBadRequest());     // @NotBlank 도입 후 기대값

    assertThat(todoItemRepository.findById(todo.getId()).orElseThrow().getText())
            .isEqualTo("장보기");                      // ← 지금은 null이 되어 실패
}
```

두 줄의 검증이 각각 다른 것을 잡습니다. `status().isBadRequest()`는 **검증이 동작하는지**, `assertThat(...getText())`는 **데이터가 보존되는지**를 봅니다. 전자만 있으면 검증을 우회하는 다른 경로가 생겼을 때 놓칩니다.

---

## P1-3. 테스트를 실행할 환경이 없음 — DB 의존, 테스트 설정 부재, CI 부재

P1-1에서 본 대로 `@SpringBootTest`가 실제 MySQL을 요구합니다. 그런데 그 MySQL을 어떻게 준비할지에 대한 장치가 없습니다.

| 필요한 것 | 현재 상태 |
|---|---|
| 테스트용 DB 설정 | `src/test/resources` 자체가 없음 |
| 테스트용 DB 실행 수단 | 없음 (개발자가 로컬 MySQL을 직접 띄워야 함) |
| 테스트 자동 실행 | CI 없음 |
| 실패 알림 | 없음 |

### 왜 문제인가

**(1) 실행 조건이 사람마다 다릅니다.**

로컬 MySQL의 포트·계정·데이터 상태에 따라 테스트 결과가 달라집니다. 테스트가 이전 실행의 데이터를 보고 통과하거나 실패할 수 있습니다(`ddl-auto: update`이므로 데이터가 남습니다).

**(2) CI가 없으면 테스트를 추가해도 실행되지 않습니다.**

P1-2의 테스트들을 다 작성해도, 누군가 로컬에서 돌려주지 않으면 아무 효과가 없습니다. 그리고 로컬에서 실패하기 시작하면 `-x test`로 우회하게 됩니다.

**(3) `docker-compose.yml`이 있는데 테스트에 활용되지 않습니다.**

MySQL 컨테이너 정의가 이미 있습니다. 하지만 그건 개발/운영 실행용이고, 테스트가 그것을 쓰려면 사람이 먼저 `docker compose up`을 해야 합니다.

### 수정 방향

**Testcontainers를 쓰세요.** 테스트가 실행될 때 MySQL 컨테이너를 자동으로 띄우고 끝나면 정리합니다. 사람이 준비할 것이 없어집니다.

```diff
--- a/build.gradle
+++ b/build.gradle
   testImplementation 'org.springframework.boot:spring-boot-starter-test'
   testImplementation 'org.springframework.security:spring-security-test'
+  testImplementation 'org.springframework.boot:spring-boot-testcontainers'
+  testImplementation 'org.testcontainers:junit-jupiter'
+  testImplementation 'org.testcontainers:mysql'
   testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
```

```java
// src/test/java/com/camstudy/backend/support/IntegrationTest.java
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
public abstract class IntegrationTest {

    @Container
    @ServiceConnection      // Boot 3.1+ : datasource 설정을 자동 주입
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");
}
```

`@ServiceConnection`이 핵심입니다. Spring Boot 3.1부터 지원하는 기능으로, 컨테이너의 JDBC URL·계정을 **자동으로 `spring.datasource.*`에 주입**합니다. `@DynamicPropertySource`로 손수 연결할 필요가 없습니다. 이 프로젝트는 3.4.4이므로 쓸 수 있습니다.

통합 테스트는 이 클래스를 상속하면 됩니다.

```java
class WindowAuthorizationTest extends IntegrationTest {
    @Autowired MockMvc mockMvc;
    ...
}
```

**컨테이너는 정적 필드이므로 여러 테스트 클래스가 재사용합니다** — 클래스마다 새로 띄우지 않습니다.

**Docker가 필요합니다.** 개발자 머신과 CI에 Docker가 있어야 합니다. 이 프로젝트는 이미 `docker-compose.yml`로 개발하므로 전제 조건이 충족됩니다.

**CI는 GitHub Actions로 시작하세요.** 저장소가 GitHub에 있고, Docker가 기본 제공되므로 Testcontainers가 바로 동작합니다.

```yaml
# .github/workflows/test.yml (신규)
name: test
on:
  push:
    branches: [main]
  pull_request:

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
          cache: gradle
      - run: ./gradlew test
      - uses: actions/upload-artifact@v4
        if: failure()
        with:
          name: test-report
          path: build/reports/tests/test/
```

`if: failure()`로 리포트를 올려두면 실패 원인을 CI 로그를 뒤지지 않고 볼 수 있습니다.

**저장소가 PUBLIC이므로**(`05-security.md` P0-3) Actions 로그도 공개됩니다. 테스트에 실제 시크릿을 쓰지 마세요 — Testcontainers는 임시 컨테이너에 임의 비밀번호를 쓰므로 이 문제가 없습니다.

---

## P1-4. 이 리뷰의 수정 목록을 안전하게 적용할 수 없음

이 리뷰는 카테고리 1~10에서 **P0 6건, P1 42건**을 지적했고, 상당수가 서로 얽혀 있습니다. 그런데 **변경이 무언가를 깨뜨렸는지 확인할 수단이 없습니다.**

특히 위험한 것은 **실패가 조용한 수정**들입니다.

| 수정 | 실패 시 증상 | 손으로 확인 가능한가 |
|---|---|---|
| `03` P1-4: 클래스에 `readOnly=true` 추가 | 쓰기 메서드에 `@Transactional`을 빠뜨리면 **저장이 예외 없이 무시됨. 응답은 200** | 응답만 보면 불가능. DB를 봐야 함 |
| `06` P1-1: 엔티티 → DTO 전환 | 필드 하나를 빠뜨리면 프론트에서 `undefined` | 화면을 다 눌러봐야 함 |
| `01` P1-4: 날짜 경계 수정 | 경계 케이스라 평상시엔 안 드러남 | 자정에 맞춰 테스트해야 함 |
| `08` P1-1: 원자적 UPDATE 전환 | `@Modifying(clearAutomatically)` 누락 시 같은 트랜잭션에서 낡은 값 조회 | 동시 요청 없이는 재현 안 됨 |
| `10` P1-1: profile 분리 | 설정 하나를 옮기다 빠뜨리면 운영에서만 드러남 | 배포 후에야 알 수 있음 |

`03-transaction.md` P1-4에서 "이 리팩터의 검증은 API 응답이 아니라 DB를 봐야 합니다"라고 적었는데, 그걸 매번 손으로 하는 것은 현실적이지 않습니다.

### 수정 방향

**수정 순서를 이렇게 잡으세요.**

```
1. log.error(..., e) 추가        (04 P0-1 / 09 P1-1)  ← 관찰 수단
2. 테스트 실행 환경 구축          (P1-3)               ← 검증 수단
3. 고칠 대상마다: 실패하는 테스트 먼저 → 수정 → 통과 확인
```

1번과 2번이 나머지 전부의 전제입니다. 지금은 고쳐도 고쳐졌는지 알 수 없는 상태입니다.

**3번은 P1-2의 표를 그대로 쓰면 됩니다.** 각 결함에 대해 **지금 실패하는 테스트**를 먼저 쓰세요. 그러면:

- 그 테스트가 실패하는 것으로 **결함이 실재함이 증명**됩니다 (이 리뷰의 지적이 맞는지 확인됨)
- 수정 후 통과하면 **고쳐졌음이 증명**됩니다
- 나중에 누가 되돌리면 **다시 실패**합니다

특히 `02` P0-1(무한 루프)과 `06` P0-1(창 탈취)은 이 리뷰가 **실행 없이 코드 경로만 읽고 판단한 것**이므로, 테스트로 재현해서 확인하는 것이 순서상 맞습니다.

---

## P2-1. 테스트 층위 선택 — 그리고 H2가 아니라 Testcontainers여야 하는 이유

Spring에는 목적별 테스트 방식이 여러 개 있고, 다 `@SpringBootTest`로 쓰면 느려서 결국 아무도 안 돌리게 됩니다.

| 방식 | 뜨는 것 | 속도 | 이 프로젝트에서 쓸 곳 |
|---|---|---|---|
| 순수 단위 (Spring 없음) | 없음 | 매우 빠름 | `TimerService.record` 날짜 분할, `JwtUtil` 토큰 파싱, `RoomService` 정원 판정 로직 |
| `@DataJpaTest` | JPA + DB | 빠름 | 쿼리 정확성, 유니크 제약, 날짜 경계, dirty checking |
| `@WebMvcTest` | 컨트롤러 + MockMvc (서비스는 mock) | 빠름 | 상태 코드, `@Valid` 검증, JSON 직렬화, 인증 필터 |
| `@SpringBootTest` | 전체 | 느림 | 권한 검사처럼 여러 층이 함께 동작해야 하는 것 |

**가장 값어치가 큰 것부터 쓰세요.** 이 프로젝트라면 순수 단위 테스트로 `TimerService.record`(P1-2 ①)를 먼저 쓰는 것이 비용 대비 효과가 가장 큽니다 — 인프라가 전혀 필요 없고 P0을 하나 잡습니다.

### H2를 쓰면 안 되는 이유

`@DataJpaTest`는 기본적으로 **내장 DB(H2 등)로 교체**하려 합니다. 편하지만 이 프로젝트에서는 **잘못된 안심**을 줍니다.

이 리뷰에서 찾은 문제 상당수가 **MySQL 고유 동작**에 의존합니다.

| 검증하려는 것 | H2에서 | 실제 MySQL에서 |
|---|---|---|
| `08` P1-2의 `INSERT ... ON DUPLICATE KEY UPDATE` | **문법 자체가 없음** (MySQL 모드에서도 제한적) | 동작 |
| `07` P1-1 2번의 NULL 이메일 중복 | 유니크 인덱스의 NULL 처리 방언 차이 | NULL은 중복으로 보지 않음 |
| `01` P1-1의 `varchar(255)` 초과 시 예외 | 방언에 따라 다름 | `DataIntegrityViolationException` |
| `01` P2-1의 `String[]` 컬럼 매핑 | H2는 네이티브 배열 지원 → **통과해버림** | MySQL은 다른 타입으로 매핑 |
| `08`의 REPEATABLE READ 동작 | 격리 수준 구현이 다름 | InnoDB MVCC |
| `01` P0-1의 `ddl-auto` 스키마 이력 | 재현 불가 (매번 create-drop) | 실제 문제의 원인 |

**특히 `String[]` 매핑이 위험합니다.** H2는 네이티브 배열 타입이 있어서 테스트가 통과하는데, MySQL에서는 다르게 동작합니다(`01-jpa-query.md` P2-1의 `[[확인필요]]`). 테스트가 초록불인데 운영이 깨지는 최악의 조합입니다.

그래서 P1-3에서 Testcontainers를 권했습니다. `@DataJpaTest`에도 적용할 수 있습니다.

```java
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)  // ← 내장 DB로 바꾸지 않기
@Testcontainers
class TimerRepositoryTest {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @Test
    void 같은_사용자_같은_날짜는_두_번_저장할_수_없다() {
        repo.saveAndFlush(timer("u@x.com", LocalDate.of(2026, 9, 2)));

        assertThatThrownBy(() ->
            repo.saveAndFlush(timer("u@x.com", LocalDate.of(2026, 9, 2))))
            .isInstanceOf(DataIntegrityViolationException.class);
    }
}
```

`@AutoConfigureTestDatabase(replace = NONE)`가 없으면 Spring이 H2로 갈아치웁니다. 이 한 줄이 빠지면 위 테스트는 **유니크 제약이 없어도 통과할 수** 있습니다.

이 테스트가 `08-concurrency.md` P1-2와 `01-jpa-query.md` P1-3의 `[[확인필요]]`(유니크 인덱스가 실제로 생성되는가)를 **자동으로 답해줍니다.** 확인이 필요하다고 적어둔 항목을 사람이 SQL로 확인하는 대신 테스트가 매번 확인하게 만드는 것입니다.

---

## P2-2. 커버리지 도구가 없음 — 단 숫자 목표는 두지 마세요

`build.gradle`에 jacoco 플러그인이 없습니다. 커버리지를 측정할 수단이 없습니다.

도입 자체는 한 줄입니다.

```diff
--- a/build.gradle
+++ b/build.gradle
 plugins {
   id 'java'
   id 'org.springframework.boot' version '3.4.4'
   id 'io.spring.dependency-management' version '1.1.7'
+  id 'jacoco'
 }
...
+tasks.named('test') {
+  useJUnitPlatform()
+  finalizedBy jacocoTestReport
+}
+tasks.named('jacocoTestReport') { dependsOn tasks.named('test') }
```

`build/reports/jacoco/test/html/index.html`에 리포트가 생깁니다.

### 다만 목표 수치를 세우지 마세요

**"커버리지 80%"** 같은 목표는 이 프로젝트에서 역효과가 납니다. 이유는 이 리뷰가 찾은 결함의 성격 때문입니다.

- `07` P1-2(텍스트가 null로 지워짐)는 **정상 경로 코드가 그대로 실행됩니다.** 커버리지는 이미 100%이고, 결함은 "실행되지 않은 줄"이 아니라 **"검증되지 않은 결과"** 입니다.
- `05` P0-1(body userId 신뢰)도 마찬가지입니다. 그 코드는 모든 요청에서 실행됩니다.
- `02` P0-1(무한 루프)은 오히려 **커버리지를 높입니다** — 루프가 무한히 돌면서 그 줄을 계속 실행하니까요.

즉 **커버리지는 "무엇을 안 돌려봤나"는 알려주지만 "무엇이 틀렸나"는 알려주지 않습니다.** 숫자를 목표로 삼으면 검증이 없는 껍데기 테스트(호출만 하고 `assertThat`이 없거나 `assertThat(result).isNotNull()`만 있는)가 늘어납니다.

**쓸모 있는 사용법은 하나입니다** — 커버리지 리포트를 열어서 **"한 번도 실행되지 않은 분기"** 를 찾고, 그게 중요한 로직이면 테스트를 씁니다. 예를 들어 `RoomService.verifyPassword`의 비밀번호 불일치 분기(`:50-52`)가 한 번도 실행되지 않았다면, 그건 테스트를 써야 할 신호입니다.

측정은 하지 않았습니다. 현재 테스트가 `contextLoads()` 하나이므로, 비즈니스 로직의 라인 커버리지는 사실상 0에 가까울 것으로 보이지만 **수치를 추정하지 않겠습니다.**

---

## P2-3. `spring-security-test`가 의존성에 있지만 이 코드 구조에 맞지 않음

`build.gradle:35`에 `spring-security-test`가 있는데 쓰이는 곳이 없습니다. 그리고 **가장 흔한 사용법인 `@WithMockUser`가 이 프로젝트에서는 잘못된 결과를 냅니다.**

P1-2 ②에서 설명한 대로, `@WithMockUser`는 principal을 `UserDetails` 객체로 만드는데 이 프로젝트는 principal이 `String`(이메일)입니다(`JwtFilter.java:64-68`). `@AuthenticationPrincipal String userEmail` 파라미터에는 **`null`이 주입됩니다.**

이걸 모르고 테스트를 쓰면 이렇게 됩니다.

```java
@Test
@WithMockUser(username = "owner@example.com")   // ← 동작하지 않음
void 내_창_목록을_조회한다() throws Exception {
    mockMvc.perform(get("/windows"))
           .andExpect(status().isOk());          // 통과함! userEmail은 null인데도
}
```

`WindowService.findAllByUser(null)`이 `findByUserId(null)`을 호출하고, SQL에서 `user_id = NULL`은 아무것도 매칭하지 않으므로 **빈 배열과 함께 200**이 나갑니다. 테스트는 통과하지만 **실제 인증 흐름을 전혀 검증하지 않았습니다.**

### 수정 방향

두 가지 방법이 있습니다.

**(a) 커스텀 애너테이션** — 반복이 많으면 이쪽

```java
@Retention(RetentionPolicy.RUNTIME)
@WithSecurityContext(factory = WithMockEmail.Factory.class)
public @interface WithMockEmail {
    String value() default "user@example.com";

    class Factory implements WithSecurityContextFactory<WithMockEmail> {
        @Override
        public SecurityContext createSecurityContext(WithMockEmail annotation) {
            SecurityContext ctx = SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(new UsernamePasswordAuthenticationToken(
                    annotation.value(), null,
                    List.of(new SimpleGrantedAuthority("ROLE_USER"))));  // JwtFilter와 동일
            return ctx;
        }
    }
}
```

```java
@Test
@WithMockEmail("owner@example.com")
void 내_창_목록을_조회한다() throws Exception { ... }
```

**(b) `RequestPostProcessor`** — 한 테스트에서 여러 사용자를 써야 하면 이쪽 (P1-2 ②의 방식)

**어느 쪽이든 `JwtFilter`가 만드는 Authentication과 같은 형태여야 합니다.** 그렇지 않으면 테스트와 운영이 다른 것을 검증하게 됩니다.

더 나은 방향은 **애초에 principal 타입을 정리하는 것**입니다. `01-jpa-query.md` P1-2에서 이메일 대신 `User.id`로 참조하자고 했는데, 그때 principal을 커스텀 `UserPrincipal` 타입으로 바꾸면 `@AuthenticationPrincipal UserPrincipal user` 형태가 되고 테스트 지원도 표준적으로 붙습니다.

---

## 요약

| 심각도 | 개수 | 항목 |
|---|---|---|
| **P0** | 0 | — |
| **P1** | 4 | 유일한 테스트가 검증하는 것이 없고 클린 체크아웃에서 통과 불가 / 이 리뷰의 P0·P1 중 최소 9건이 테스트 하나로 잡혔을 것 / 테스트 실행 환경 부재 (DB 의존·테스트 설정 없음·CI 없음) / 리팩터링 안전망 부재로 이 리뷰의 수정을 검증할 수 없음 |
| **P2** | 3 | 테스트 층위 선택 가이드 부재 + H2 사용 시 잘못된 안심 / 커버리지 도구 없음 (단 숫자 목표는 부적절) / `spring-security-test`의 `@WithMockUser`가 이 코드 구조에서 오작동 |

## 사용자가 물은 항목에 대한 직접 답

**"커버리지 숫자가 아니라 무엇을 검증하는지"** — 유일한 테스트 `contextLoads()`가 검증하는 것은 **"Spring 컨텍스트가 뜬다"** 하나입니다. 그마저도 환경변수 6개와 실제 MySQL이 있어야 통과하는데, 테스트 전용 설정도 CI도 없어 **실제로 실행되고 있지 않을 가능성이 높습니다.**

**테스트 없는 핵심 로직 지목** — 우선순위 순입니다.

| 순위 | 대상 | 왜 |
|---|---|---|
| 1 | `TimerService.record` (`:29-72`) | P0(무한 루프) 포함. 날짜 분할이라는 순수 계산 로직이라 **Spring 없이 즉시 테스트 가능**. 비용 대비 효과 최대 |
| 2 | 소유권 검사 전 경로 (`WindowService`, `TodoService`, `RoomService`) | P0 2건(창 탈취, 임의 강퇴). 검사가 7군데 4방식으로 흩어져 있어(`06` P1-4) 하나만 빠져도 뚫림 |
| 3 | `GlobalExceptionHandler` (`:13-25`) | 17개 500 경로(`04` P1-1). 상태 코드는 `@WebMvcTest`로 빠르게 검증 가능 |
| 4 | `TodoService`의 날짜 필터 (`:63-68`, `:112-117`) | 경계 버그(`01` P1-4). 손으로는 재현이 거의 불가능한 종류 |
| 5 | `Timer` 유니크 제약 | `01` P1-3, `08` P1-2의 `[[확인필요]]`를 테스트가 대신 답해줌 |
| 6 | `JwtUtil` 토큰 발급·검증 | 액세스/리프레시 구분(`05` P1-1) 수정 시 회귀 방지 |

## 적용 순서

```
1. src/test/resources/application.yml 추가 → contextLoads() 살리기 (P1-1)
2. TimerServiceRecordTest 작성 (P1-2 ①)     ← 인프라 0. 지금 당장 가능. P0 재현
3. Testcontainers + IntegrationTest 베이스 클래스 (P1-3)
4. GitHub Actions 워크플로 (P1-3)
5. 권한 검사 테스트 (P1-2 ②)                 ← P0 2건 재현
6. 이후: 고칠 때마다 실패하는 테스트 먼저
```

**2번은 지금 당장 할 수 있습니다.** Spring 컨텍스트도 DB도 환경변수도 필요 없고, `02-performance.md` P0-1이 실재하는지를 몇 분 안에 확정합니다. 이 리뷰에서 실행 없이 판단한 항목을 검증하는 가장 빠른 방법이기도 합니다.

테스트를 실행하지 않았습니다. 이 머신에 Gradle 의존성 캐시(`~/.gradle`)가 없어 오프라인 빌드가 불가능합니다. 위 테스트 코드는 컴파일·실행 검증을 거치지 않았으며, API 시그니처는 소스에서 읽은 것과 일치시켰지만 실제 작성 시 조정이 필요할 수 있습니다.
