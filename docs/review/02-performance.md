# 02. 성능 (3단계)

조사 범위: 반복 조회, 캐시 여지, 응답 크기, 커넥션 풀 / 스레드 풀 설정.
grep으로 확인한 사실: 프로젝트 전체에 `@Cacheable`, `@EnableCaching`, `hikari`,
`server.compression`, `server.tomcat`, `spring-boot-starter-cache` **사용 0건**.
즉 커넥션 풀·스레드 풀·응답 압축 모두 **전부 프레임워크 기본값**입니다.

측정은 하지 않았습니다. 벤치마크·프로파일링 없이, 소스에서 확정할 수 있는 "쿼리 몇 번 나가는가 / 무엇이 기본값인가"만 다룹니다.

---

## P0-1. `POST /timer`에서 자정을 넘는 구간을 보내면 무한 루프 — 스레드와 커넥션이 영구 점유됨

**위치** `src/main/java/com/camstudy/backend/service/TimerService.java:41-71`

```java
    @Transactional                                        // :28
    public void record(String userId, Instant startAt, Instant endAt, ZoneId userZone) {
        ZonedDateTime zStart = startAt.atZone(userZone);
        ZonedDateTime zEnd   = endAt.atZone(userZone);
        ZonedDateTime sliceStart = zStart;

        while (sliceStart.isBefore(zEnd)) {               // :41
            ZonedDateTime sliceEnd = sliceStart
                    .toLocalDate()
                    .atTime(LocalTime.MAX)                // :45  ← 23:59:59.999999999
                    .atZone(userZone);
            if (sliceEnd.isAfter(zEnd)) sliceEnd = zEnd;

            long secs = Duration.between(sliceStart, sliceEnd).getSeconds();
            LocalDate date = sliceStart.toLocalDate();
            Timer entry = repo.findByUserIdAndDate(userId, date)   // :57
                    .orElseGet(() -> { ... });
            entry.setTotalSeconds(entry.getTotalSeconds() + secs);
            repo.save(entry);                                      // :67

            sliceStart = sliceEnd;                                 // :70
        }
    }
```

### 왜 문제인가

의도는 "공부 구간이 날짜를 넘으면 날짜별로 쪼개서 저장"입니다. 그런데 **날짜 경계를 `LocalTime.MAX`(= 23:59:59.999999999)로 잡고, 다음 조각의 시작을 그 값으로 그대로 넘깁니다**(`:70`). 그래서 두 번째 반복부터 진행이 멈춥니다.

`zStart = 9/2 23:30`, `zEnd = 9/3 00:30` (자정을 넘는 공부 세션)을 넣고 손으로 돌려보면:

| 반복 | `sliceStart` | `sliceEnd` 계산 | `zEnd` 초과? | `secs` | 다음 `sliceStart` |
|---|---|---|---|---|---|
| 1 | `9/2 23:30:00` | `9/2 23:59:59.999999999` | 아니오 | 1799 | `9/2 23:59:59.999999999` |
| 2 | `9/2 23:59:59.999999999` | `sliceStart.toLocalDate()` = **9/2** → `9/2 23:59:59.999999999` | 아니오 | **0** | **그대로** |
| 3 | 동일 | 동일 | 동일 | 0 | 그대로 |
| … | **영원히 반복** | | | | |

핵심은 2번 행입니다. `sliceStart`가 이미 9/2의 마지막 순간이므로 `toLocalDate()`가 여전히 **9/2**를 반환하고, 그 날의 `LocalTime.MAX`는 `sliceStart` 자신입니다. `Duration`이 0이 되고 `sliceStart = sliceEnd`가 **자기 자신을 대입**하므로 루프 변수가 전진하지 않습니다. 종료 조건 `sliceStart.isBefore(zEnd)`는 계속 참입니다.

`zEnd`가 정확히 `9/3 00:00:00.000000000`이어도 마찬가지입니다 — `9/2 23:59:59.999999999`는 그보다 1나노초 이전이므로 `isBefore`가 참입니다.

### 이게 왜 P0인가 (자원 고갈 경로)

무한 루프가 CPU만 태우는 게 아닙니다. 루프 안에 DB 호출이 있습니다.

1. `repo.findByUserIdAndDate(...)`는 파생 쿼리(derived query)이므로 **매 반복마다 실제 SELECT가 DB로 나갑니다.** 영속성 컨텍스트(1차 캐시)는 `findById`류의 **식별자 조회만** 대신 처리해 줍니다. JPQL/파생 쿼리는 항상 DB에 갑니다(자세한 원리는 P1-1에서 설명).
2. 메서드에 `@Transactional`(`:28`)이 붙어 있으므로 **트랜잭션이 열린 채로 영원히 커밋되지 않습니다.**
3. Hibernate의 커넥션 처리 방식은 트랜잭션 시작 시 커넥션을 얻어 **커밋까지 붙잡고 있는** 모드입니다. 즉 이 요청 하나가 **커넥션 1개를 영구 점유**합니다.
4. 첫 반복에서 `Timer` 행을 INSERT했다면, 커밋되지 않은 그 행에 **배타 락**이 걸린 상태로 남습니다. `Timer`에는 `(userId, date)` 유니크 제약이 있으므로(`entity/Timer.java:9`) 같은 유저의 같은 날짜에 대한 다른 요청은 락 대기에 걸립니다.

HikariCP 기본 `maximum-pool-size`는 **10**입니다(아래 P1-3). 즉 이 요청이 **10번 들어오면 커넥션 풀이 완전히 고갈되어 앱 전체가 멈춥니다.** 그 시점부터 모든 API가 `connection-timeout`(기본 30초) 대기 후 예외로 떨어집니다.

그리고 `docker-compose.yml:41`의 `restart: always`는 이 상황을 복구해 주지 못합니다 — 프로세스는 죽지 않고 살아서 응답만 못 하는 상태이므로 Docker가 재시작 판단을 할 근거가 없습니다(헬스체크도 없습니다 → `12-docker-deploy.md`).

**트리거 난이도가 낮은 것도 문제입니다.** 이건 공부 시간 기록 앱입니다. 밤 11시 30분에 시작해 자정을 넘겨 공부하는 건 예외 상황이 아니라 **정상 사용 패턴**입니다. `TimerController.recordTime:24-33`은 클라이언트가 보낸 `startAt`/`endAt`을 검증 없이 그대로 넘깁니다.

### 수정 방향

경계를 `LocalTime.MAX`(그 날의 마지막 순간)가 아니라 **다음 날 00:00**(반열린 구간의 끝)으로 잡으면 `sliceStart`가 항상 전진합니다.

```diff
--- a/src/main/java/com/camstudy/backend/service/TimerService.java
+++ b/src/main/java/com/camstudy/backend/service/TimerService.java
         while (sliceStart.isBefore(zEnd)) {
-            ZonedDateTime sliceEnd = sliceStart
-                    .toLocalDate()
-                    .atTime(LocalTime.MAX)
-                    .atZone(userZone);
+            // 다음 날 자정 = 이 날짜 조각의 배타적 끝
+            ZonedDateTime sliceEnd = sliceStart.toLocalDate()
+                    .plusDays(1)
+                    .atStartOfDay(userZone);
             if (sliceEnd.isAfter(zEnd)) {
                 sliceEnd = zEnd;
             }
```

이렇게 하면 `9/2 23:30` → `sliceEnd = 9/3 00:00` → `secs = 1800` → `sliceStart = 9/3 00:00` → 다음 반복은 `9/3` 날짜로 진행되고 `zEnd`에서 정확히 종료됩니다. 자정 1초가 어느 날짜에도 이중 계산되지 않습니다(반열린 구간 `[00:00, 다음날 00:00)`).

**추가로 안전장치 두 개를 권합니다.**

```diff
     public void record(String userId, Instant startAt, Instant endAt, ZoneId userZone) {
+        if (startAt == null || endAt == null || !startAt.isBefore(endAt)) {
+            throw new IllegalArgumentException("startAt must be before endAt");
+        }
+        if (Duration.between(startAt, endAt).toDays() > 7) {
+            throw new IllegalArgumentException("recording span too long");
+        }
```

두 번째가 중요합니다. 클라이언트가 `startAt=1970-01-01`, `endAt=2026-09-02`를 보내면 위 수정 후에도 루프가 **약 2만 번** 돌면서 각 반복마다 SELECT + INSERT를 발행합니다. 무한은 아니지만 트랜잭션 하나로 2만 번의 왕복이 일어납니다. 상한이 필요합니다.

이 검증은 컨트롤러의 `@Valid`로 올리는 게 더 낫습니다 → `07-api-design.md`.

`[[확인필요: 운영 DB의 timer 테이블에 이미 이 버그로 생긴 이상 데이터가 있는지 — 자정 직전에 끝나는 부분 기록 등]]`

---

## P1-1. `GET /timer/analytics` 한 번에 같은 쿼리가 3번 반복 — 총 6번의 DB 왕복

**위치** `service/TimerService.java:85-135`

```java
    public TimerAnalyticsResponse getTimerAnalytics(String userEmail, int year, int month, ZoneId userZone) {
        User user = userRepository.findByEmail(userEmail)                        // :91  쿼리 ①
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        ...
        double achievementRateToday = repo.findByUserIdAndDate(userEmail, todayLocal)   // :97  쿼리 ②
                .map(...).orElse(0.0);

        long currentTotal  = getMonthlyTotal(userEmail, year, month, userZone);  // :101 쿼리 ③
        LocalDate prevMonthStart = LocalDate.of(year, month, 1).minusMonths(1);
        long previousTotal = getMonthlyTotal(userEmail, prevMonthStart.getYear(), ...);  // :103 쿼리 ④
        ...
        listByMonth(userEmail, year, month, userZone)                            // :108 쿼리 ⑤ = ③과 동일
            .forEach(e -> weekdayStats.merge(...));

        Optional<Timer> best = listByMonth(userEmail, year, month, userZone)     // :111 쿼리 ⑥ = ③과 동일
            .stream().max(Comparator.comparingLong(Timer::getTotalSeconds));
```

`getMonthlyTotal`(`:80-83`)은 내부에서 `listByMonth`를 호출하므로, `③`·`⑤`·`⑥`은 **완전히 동일한 SQL** 입니다:

```sql
select * from timer where user_id = ? and date between ? and ?
```

즉 요청 1건에 DB 왕복 **6회**이고, 그중 3회는 글자 하나 다르지 않은 같은 쿼리입니다. 필요한 건 2회(유저 1건 + 이번달/지난달)입니다.

### 왜 문제인가 — "같은 트랜잭션이니 캐시되지 않나?"

프론트에서 React Query 같은 걸 쓰면 같은 key의 요청이 자동으로 합쳐집니다. JPA의 1차 캐시(영속성 컨텍스트)를 그렇게 이해하기 쉬운데, **동작이 다릅니다.**

영속성 컨텍스트는 **엔티티를 식별자(PK)로 색인한 Map**입니다. 그래서:

| 호출 | DB 왕복 | 이유 |
|---|---|---|
| `em.find(Timer.class, 5L)` 두 번 | **1회** | 두 번째는 Map에서 꺼냄 |
| `findByUserIdAndDate(...)` 두 번 | **2회** | 이건 JPQL. "user_id=?, date=? 인 행"이 Map의 어디 있는지 알 방법이 없음 |

JPA는 "이 WHERE 조건을 만족하는 행이 전부 메모리에 있는가"를 판단할 수 없습니다. 다른 트랜잭션이 그 사이 행을 넣었을 수도 있으니까요. 그래서 **파생 쿼리·JPQL·Criteria는 예외 없이 DB로 나갑니다.**

1차 캐시가 해주는 일은 그다음입니다 — SQL 결과로 돌아온 각 행의 PK를 보고, 이미 Map에 있는 인스턴스면 **새 객체를 만들지 않고 기존 인스턴스를 돌려줍니다**(객체 동일성 보장). 그래서 `③`·`⑤`·`⑥`의 결과 리스트는 **같은 `Timer` 객체들을 가리키지만, SQL은 세 번 실행됐습니다.**

부가 비용도 있습니다. `③`은 `getMonthlyTotal` 안에서 최대 31개 엔티티를 만들어 합계만 구하고 리스트를 버립니다(`01-jpa-query.md` P1-5).

또 하나: 쿼리 ①(`findByEmail`)은 `users.email`에 인덱스가 없어 전체 스캔입니다(`01-jpa-query.md` P1-1). `docker-compose.yml:19`의 `--innodb_buffer_pool_size=128M`는 크지 않으므로, 반복되는 전체 스캔은 버퍼 풀에서 다른 테이블의 뜨거운 페이지를 밀어냅니다.

### 수정 방향

지역 변수로 한 번만 조회해서 재사용하면 끝입니다. 캐시를 붙일 문제가 아닙니다.

```diff
--- a/src/main/java/com/camstudy/backend/service/TimerService.java
+++ b/src/main/java/com/camstudy/backend/service/TimerService.java
-        long currentTotal  = getMonthlyTotal(userEmail, year, month, userZone);
+        List<Timer> currentMonth = listByMonth(userEmail, year, month, userZone);
+        long currentTotal = currentMonth.stream().mapToLong(Timer::getTotalSeconds).sum();
+
         LocalDate prevMonthStart = LocalDate.of(year, month, 1).minusMonths(1);
         long previousTotal = getMonthlyTotal(userEmail, prevMonthStart.getYear(), prevMonthStart.getMonthValue(), userZone);
         long diff = currentTotal - previousTotal;
         Double changeRate = previousTotal == 0 ? null : diff * 100.0 / previousTotal;
 
         Map<DayOfWeek, Long> weekdayStats = new EnumMap<>(DayOfWeek.class);
-        listByMonth(userEmail, year, month, userZone)
-            .forEach(e -> weekdayStats.merge(e.getDate().getDayOfWeek(), e.getTotalSeconds(), Long::sum));
+        currentMonth
+            .forEach(e -> weekdayStats.merge(e.getDate().getDayOfWeek(), e.getTotalSeconds(), Long::sum));
 
-        Optional<Timer> best = listByMonth(userEmail, year, month, userZone)
+        Optional<Timer> best = currentMonth
             .stream().max(Comparator.comparingLong(Timer::getTotalSeconds));
```

6회 → **3회**(유저 / 오늘 / 이번달)로 줄고, 지난달 합계는 `01-jpa-query.md` P1-5의 `sumTotalSeconds`를 쓰면 엔티티 적재 없이 스칼라 1건으로 끝납니다. 이번달은 요일별 통계·최고 집중일에 실제 행이 필요하므로 리스트로 가져오는 게 맞습니다.

쿼리 ②는 `todayLocal` 데이터가 이번달에 속하면 `currentMonth`에서 꺼낼 수 있지만, 과거 월을 조회하는 경우엔 못 꺼냅니다. 별도 조회를 유지하는 게 안전합니다.

---

## P1-2. 인증된 모든 요청이 JWT를 두 번 파싱·검증함

**위치** `config/JwtFilter.java:48`, `:57` / `util/JwtUtil.java:37-53`

```java
// JwtFilter.java:48
        if (token == null || !jwtUtil.validate(token)) {     // ← 1차 파싱 + 서명 검증
            chain.doFilter(request, response);
            return;
        }
        try {
            String email = jwtUtil.getEmail(token);          // :57  ← 2차 파싱 + 서명 검증
```

```java
// JwtUtil.java:37-53
    public boolean validate(String token) {
        try {
            Jwts.parser().setSigningKey(secretKey).parseClaimsJws(token);   // 파싱 + HMAC 검증
            return true;
        } catch (JwtException | IllegalArgumentException e) { return false; }
    }

    public String getEmail(String token) {
        return Jwts.parser()
                .setSigningKey(secretKey)
                .parseClaimsJws(token)       // ← 똑같은 작업을 처음부터 다시
                .getBody().getSubject();
    }
```

### 왜 문제인가

`parseClaimsJws`가 하는 일은 매번 전부 다시 실행됩니다.

1. 토큰 문자열을 `.`으로 3분할
2. header·payload를 Base64URL 디코드
3. header + payload에 대해 **HMAC-SHA256 계산**
4. 계산 결과를 토큰의 signature와 상수시간 비교
5. payload JSON을 Jackson으로 파싱해 `Claims` 맵 생성
6. `exp` 만료 검사

`validate`는 이걸 다 하고 **결과를 버리고 `true`만 반환합니다.** `getEmail`이 같은 일을 다시 해서 `sub`만 꺼냅니다. 즉 인증이 필요한 모든 요청마다 HMAC 계산과 JSON 파싱이 정확히 2배로 일어납니다. 이건 캐시로 가릴 문제가 아니라 **파싱 결과를 버린 게 원인**입니다.

`Jwts.parser()`도 매 호출마다 새 파서 빌더를 만듭니다. jjwt의 파서는 스레드 안전하게 재사용 가능한 형태로 한 번만 만들어 두는 게 정상 사용법입니다.

### 수정 방향

파싱을 한 번만 하고 `Claims`를 돌려주는 메서드로 합칩니다.

```diff
--- a/src/main/java/com/camstudy/backend/util/JwtUtil.java
+++ b/src/main/java/com/camstudy/backend/util/JwtUtil.java
+    /** 유효하면 Claims, 아니면 null */
+    public Claims parseOrNull(String token) {
+        try {
+            return Jwts.parser().setSigningKey(secretKey)
+                       .parseClaimsJws(token).getBody();
+        } catch (JwtException | IllegalArgumentException e) {
+            return null;
+        }
+    }
```

```diff
--- a/src/main/java/com/camstudy/backend/config/JwtFilter.java
+++ b/src/main/java/com/camstudy/backend/config/JwtFilter.java
         String token = extractToken(request);
-        if (token == null || !jwtUtil.validate(token)) {
+        Claims claims = (token == null) ? null : jwtUtil.parseOrNull(token);
+        if (claims == null) {
             chain.doFilter(request, response);
             return;
         }
-        try {
-            String email = jwtUtil.getEmail(token);
+        String email = claims.getSubject();
```

`try/catch`가 사라지는 부수 효과가 있습니다 — 지금 구조에서는 `catch` 블록(`:72-79`)이 사실상 도달 불가능합니다. `validate`가 이미 모든 `JwtException`을 삼켰기 때문에, 그 뒤 `getEmail`이 실패할 수 있는 경우가 거의 없습니다. 이 죽은 `catch`는 `04-exception.md`에서 다시 다룹니다.

`jwtUtil.secretKey`가 `@Value`로 주입되는 **필드**라는 점(`JwtUtil.java:13-14`)과 `signWith(SignatureAlgorithm.HS256, String)`의 키 취급 문제는 `05-security.md`에서 다룹니다.

---

## P1-3. 커넥션 풀·스레드 풀이 전부 기본값 — 스레드 200 대 커넥션 10

**위치** `src/main/resources/application.yml:6-10` (datasource 블록에 `hikari` 하위 설정 없음), `docker-compose.yml:17-22`

```yaml
# application.yml:6-10  — 이게 datasource 설정의 전부입니다
  datasource:
    url: jdbc:mysql://${DB_HOST}:${DB_PORT}/${DB_NAME}?useSSL=false&serverTimezone=UTC
    username: ${DB_USER}
    password: ${DB_PASSWORD}
    driver-class-name: com.mysql.cj.jdbc.Driver
```

따라서 실제 적용되는 값은 프레임워크 기본값입니다.

| 항목 | 적용값 | 출처 |
|---|---|---|
| HikariCP `maximum-pool-size` | **10** | Hikari 기본값 |
| HikariCP `minimum-idle` | 10 (max와 동일) | Hikari 기본값 |
| HikariCP `connection-timeout` | 30,000ms | Hikari 기본값 |
| HikariCP `max-lifetime` | 1,800,000ms (30분) | Hikari 기본값 |
| HikariCP `leak-detection-threshold` | **0 = 비활성** | Hikari 기본값 |
| Tomcat `threads.max` | **200** | Spring Boot 기본값 |
| MySQL `max_connections` | 50 | `docker-compose.yml:20` (명시) |
| MySQL `innodb_buffer_pool_size` | 128M | `docker-compose.yml:19` (명시) |

### 왜 문제인가

**(1) 스레드 200 : 커넥션 10 이라는 비율**

Tomcat은 동시 요청 200개까지 스레드를 배정합니다. 그 200개가 DB에 접근하려면 커넥션 10개를 두고 경쟁합니다. 11번째부터는 `connection-timeout` 30초 동안 큐에서 기다리고, 그 안에 못 얻으면 `SQLTransientConnectionException`을 던집니다.

여기서 나쁜 건 **30초**입니다. 요청이 실패할 거라면 빨리 실패해야 하는데(fail fast), 30초를 기다리는 동안 그 Tomcat 스레드는 아무 일도 못 하고 묶여 있습니다. 클라이언트가 타임아웃으로 재시도하면 대기 큐가 더 길어집니다. 프론트 관점으로는 `fetch`가 30초 동안 응답 없이 매달려 있는 상태이고, 사용자는 새로고침을 누릅니다.

숫자 자체는 10이 반드시 부족하다는 뜻이 아닙니다 — 애플리케이션이 커넥션을 짧게 쓰면 10으로도 충분합니다. 문제는 **아무도 의도적으로 고르지 않았다**는 것과, `MySQL max_connections=50`이 명시된 것과 짝이 맞지 않는다는 것입니다. 인스턴스를 5개 띄우면 50에 닿습니다.

**(2) `leak-detection-threshold`가 꺼져 있음**

P0-1의 무한 루프처럼 커넥션을 놓지 않는 코드가 있어도, 로그에 아무것도 남지 않습니다. 이 값을 켜두면 "커넥션을 N ms 이상 붙잡고 있다"는 경고와 **그 시점의 스택트레이스**가 찍힙니다. P0-1을 찾는 데 직접 도움이 되는 설정입니다.

**(3) OSIV가 켜져 있고, 그 실제 비용을 알아둘 필요가 있음**

`spring.jpa.open-in-view`가 설정되지 않았으므로 **기본값 `true`** 입니다(grep 0건). Spring Boot는 부팅 시 이 사실을 경고 로그로 남깁니다.

여기서 흔히 퍼진 설명 — "OSIV를 켜면 요청 내내 DB 커넥션을 붙잡는다" — 는 **Spring Boot 기본 구성에서는 정확하지 않습니다.** Hibernate의 커넥션 처리 모드가 비-JTA 환경에서 `DELAYED_ACQUISITION_AND_RELEASE_AFTER_TRANSACTION`이기 때문에, 커넥션은 **트랜잭션이 시작될 때 얻고 커밋 시 반납**됩니다. 트랜잭션 밖에서 실행되는 쿼리는 문장 단위로 얻고 반납합니다.

OSIV의 실제 비용은 다른 데 있습니다.

- **`EntityManager`가 요청 끝까지(JSON 직렬화 이후까지) 살아 있습니다.** 그 안의 1차 캐시에 이번 요청이 읽은 모든 엔티티와 그 스냅샷이 남아 있습니다. `GET /todos`처럼 상한 없는 목록(`01-jpa-query.md` P1-8)을 읽으면 그게 다 유지됩니다.
- **직렬화 도중에 지연로딩이 성공해 버립니다.** 원래라면 `LazyInitializationException`으로 즉시 드러날 실수가, OSIV 덕분에 조용히 쿼리를 하나 더 발행하고 넘어갑니다. 이 프로젝트는 `Window`·`Timer` 엔티티를 응답으로 직접 내보내므로(`06-architecture.md`) 이 함정에 노출되어 있습니다. 지금은 `@JsonIgnore`가 막고 있을 뿐입니다.
- **트랜잭션 없이 실행되는 리포지토리 호출이 문장마다 커넥션을 얻고 반납합니다.** `WindowService`가 `@Transactional` 없이 리포지토리를 3~4번 호출하는 구조(`01-jpa-query.md` P1-7)가 바로 이 경우입니다.

### 수정 방향

값을 명시해서 "의도한 값"으로 만드는 것이 1차 목표입니다.

```diff
--- a/src/main/resources/application.yml
+++ b/src/main/resources/application.yml
   datasource:
     url: jdbc:mysql://${DB_HOST}:${DB_PORT}/${DB_NAME}?useSSL=false&serverTimezone=UTC
     username: ${DB_USER}
     password: ${DB_PASSWORD}
     driver-class-name: com.mysql.cj.jdbc.Driver
+    hikari:
+      maximum-pool-size: 10
+      minimum-idle: 10
+      connection-timeout: 3000        # 30s → 3s. 못 얻으면 빨리 실패
+      max-lifetime: 1740000           # 29분. MySQL wait_timeout보다 짧게
+      leak-detection-threshold: 10000 # 10초 이상 점유하면 스택트레이스 경고
   jpa:
+    open-in-view: false
```

`open-in-view: false`로 바꾸면 서비스 계층 밖에서 지연로딩이 **실패하게** 됩니다. 그게 목적입니다 — 어디서 몰래 쿼리가 나가고 있었는지 드러납니다. 다만 `Window`/`Timer` 엔티티를 직접 응답으로 내보내는 현재 구조에서는 **바꾸는 순간 500이 날 수 있으므로**, `06-architecture.md`의 DTO 분리와 함께 적용하세요. 순서를 지키지 않으면 운영에서 터집니다.

`max-lifetime`을 MySQL `wait_timeout`(기본 28800초)보다 짧게 두는 이유: 서버가 먼저 커넥션을 끊으면 풀은 그걸 모른 채 죽은 커넥션을 내주고, 요청 하나가 `CommunicationsException`으로 실패합니다. 풀이 먼저 버리게 만드는 게 안전합니다. 지금은 30분 < 8시간이라 이미 안전하지만, 앞에 프록시나 로드밸런서가 끼면 그쪽 idle 타임아웃이 더 짧을 수 있습니다.

`[[확인필요: 운영 MySQL의 wait_timeout / 앱과 DB 사이에 프록시(ALB, HAProxy 등)가 있는지]]`

Tomcat 스레드 수는 지금 판단할 근거가 없습니다(동시 사용자 수 미지). 커넥션 풀을 정한 뒤 부하를 보고 맞추는 게 순서입니다.

---

## P1-4. 응답이 gzip 되지 않음

**위치** `src/main/resources/application.yml:19-21`

```yaml
server:
  address: 0.0.0.0
  port: 8080
```

`server.compression.enabled`가 없으므로 **기본값 `false`** 입니다. 모든 JSON 응답이 압축 없이 전송됩니다.

### 왜 문제인가

JSON은 키 이름이 모든 요소에 반복되는 형식이라 텍스트 압축률이 높습니다. 이 프로젝트의 응답은 특히 그렇습니다.

- `GET /timer?year&month` → `List<Timer>` 최대 31개. 각 요소가 `{"id":..,"userId":"user@example.com","date":"2026-09-02","totalSeconds":..,"pomoCycles":..}` 형태로, **같은 이메일 문자열이 31번 반복**됩니다.
- `GET /windows` → `List<Window>` 각 요소에 `userId`(이메일)가 반복됩니다.
- `GET /todos` → 상한 없음(`01-jpa-query.md` P1-8).

압축이 없으면 이 반복이 전부 실제 바이트로 나갑니다. gzip은 반복 문자열을 사전 참조로 바꾸는 방식이라 이런 데이터에 특히 잘 맞습니다.

압축률과 개선 폭은 측정하지 않았으므로 수치를 적지 않습니다. 다만 "설정 한 줄로 켤 수 있고, 켜지 않을 이유가 없는 항목"입니다.

### 수정 방향

```diff
--- a/src/main/resources/application.yml
+++ b/src/main/resources/application.yml
 server:
   address: 0.0.0.0
   port: 8080
+  compression:
+    enabled: true
+    mime-types: application/json,text/plain
+    min-response-size: 1024
```

`min-response-size`가 필요한 이유: 작은 응답은 압축해도 헤더 오버헤드 때문에 이득이 없거나 오히려 커집니다. 기본값이 2048이라 그대로 둬도 되지만, 명시해 두면 의도가 보입니다.

브라우저가 `Accept-Encoding: gzip`을 보낸 경우에만 압축되므로 클라이언트 변경은 필요 없습니다.

앱 앞에 리버스 프록시(nginx 등)가 있고 거기서 이미 압축한다면 중복이므로 켜지 마세요.
`[[확인필요: oeyo-cam.site 앞단에 압축을 하는 프록시가 있는지]]`

---

## P1-5. `show-sql: true` + `format_sql: true`가 운영에서 켜져 있음 — 요청 스레드가 `System.out`에서 직렬화됨

**위치** `src/main/resources/application.yml:14-17`

```yaml
  jpa:
    ...
    show-sql: true
    properties:
      hibernate:
        format_sql: true
```

`docker-compose.yml:34`가 `SPRING_PROFILES_ACTIVE: prod`를 주지만 `application-prod.yml`이 없으므로(`00-overview.md`), **이 설정이 운영에서 그대로 적용됩니다.**

### 왜 문제인가

`spring.jpa.show-sql=true`는 `hibernate.show_sql` 속성으로 전달되고, Hibernate는 이걸 **로거가 아니라 `System.out`으로 직접 출력**합니다. 이 차이가 중요합니다.

1. **로그 레벨로 끌 수 없습니다.** `application.yml:23-26`의 `logging.level` 설정은 SLF4J/Logback 로거에만 적용됩니다. `System.out`은 그 체계 밖입니다. 즉 "운영에서는 로그 레벨만 올리면 되겠지"가 통하지 않습니다.
2. **`System.out`은 `synchronized` 메서드를 가진 `PrintStream`입니다.** 여러 요청 스레드가 동시에 SQL을 출력하면 그 락에서 직렬화됩니다. 최대 200개 스레드가 DB 호출마다 같은 락을 두고 경쟁합니다. `format_sql: true`가 붙으면 출력량이 몇 배로 늘어나(줄바꿈·들여쓰기) 락 점유 시간이 길어집니다.
3. **컨테이너에서는 stdout이 곧 디스크 I/O입니다.** `docker-compose.yml`에 로깅 드라이버 설정이 없으므로 기본 `json-file`이 쓰입니다. 즉 모든 SQL 한 줄이 호스트 디스크에 JSON으로 기록됩니다. 로테이션 설정도 없어 무한히 쌓입니다(`12-docker-deploy.md`).
4. **비동기 로깅으로 완화할 수도 없습니다.** Logback의 `AsyncAppender`는 `System.out` 직접 출력에 적용되지 않습니다.

여기에 P1-1을 겹쳐 보면, `/timer/analytics` 한 번에 6개 SQL이 포맷팅되어 stdout으로 나갑니다. P0-1의 무한 루프가 걸린 상태라면 **stdout으로 SQL이 끝없이 쏟아집니다** — 디스크가 먼저 찰 수도 있습니다.

### 수정 방향

`show-sql`은 끄고, SQL을 봐야 할 때는 **로거**를 쓰세요. 로거로 찍으면 레벨로 제어되고, Logback 설정(비동기·롤링·포맷)이 적용됩니다.

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

로컬 개발용 파일을 따로 두고 거기서만 켭니다(`10-config.md`에서 profile 분리와 함께).

```yaml
# application-local.yml
logging:
  level:
    org.hibernate.SQL: DEBUG                        # 실행 SQL
    org.hibernate.orm.jdbc.bind: TRACE              # 바인딩 파라미터 (Hibernate 6)
spring:
  jpa:
    properties:
      hibernate:
        format_sql: true
```

`org.hibernate.SQL` 로거는 `show_sql`과 같은 내용을 SLF4J로 내보냅니다. 바인딩 파라미터까지 보고 싶을 때 `org.hibernate.orm.jdbc.bind`를 켜는데, 이건 **파라미터 값이 로그에 그대로 남으므로 운영에서는 절대 켜면 안 됩니다** — 이메일 같은 개인정보가 로그로 흘러갑니다(`09-logging.md`).

---

## P2-1. 캐시를 넣을 자리가 사실상 없음 — 지금 넣으면 문제를 덮음

grep 결과 `@Cacheable`, `@EnableCaching`, `spring-boot-starter-cache`, Hibernate 2차 캐시 **전부 0건**입니다.

이걸 "캐시가 없어서 느리다"로 읽으면 방향이 틀립니다. 이 프로젝트에서 반복 조회의 원인은 캐시 부재가 아닙니다.

| 반복 조회 지점 | 진짜 원인 | 올바른 해법 |
|---|---|---|
| `findByEmail` 요청마다 (`TimerService` 4곳) | `users.email` 인덱스 없음 + 필요한 값이 `dailyGoalHours` 하나뿐 | 인덱스 추가(`01` P1-1) / 쿼리로 필요한 값만 조회 |
| `listByMonth` 3중복 (`getTimerAnalytics`) | 지역 변수로 안 담고 재호출 | 변수 재사용 (P1-1) |
| JWT 2회 파싱 | 파싱 결과를 버림 | 파싱 1회 (P1-2) |
| `focusWindow` 목록 2회 조회 | 트랜잭션 경계 없음 | `@Transactional` + Java 정렬(`01` P1-7) |

**네 건 모두 캐시 없이 코드 수정만으로 사라집니다.** 캐시를 먼저 넣으면 이 결함이 성능 지표에서 안 보이게 되고, 캐시 무효화라는 새 문제만 생깁니다.

캐시가 정당한 후보가 되는 곳은 하나 있습니다.

- **`GET /rooms` 목록 + `activeCounts`** (`controller/RoomController.java:37-52`). 인증 없이 누구나 부를 수 있고(→`05-security.md`), 방 목록 화면에서 폴링될 가능성이 높습니다.
  다만 무효화가 까다롭습니다. `memberRepo`의 `active` 플래그는 `POST /rooms/{id}/join`, `/leave`, `/admin/kick`에서 바뀌고, WebSocket 연결이 끊길 때는 **아무것도 바뀌지 않습니다**(연결 종료 훅이 없음 → `08-concurrency.md`). 즉 캐시 TTL을 길게 잡으면 "이미 나간 사람이 계속 있는 것으로 보이는" 기존 문제가 더 오래 유지됩니다.
  → 먼저 `active` 플래그의 정확성을 해결하고, 그다음에 짧은 TTL(수 초) 캐시를 검토하는 순서가 맞습니다.

Hibernate 2차 캐시는 지금 도입할 이유가 없습니다. 엔티티 대부분이 사용자별 데이터라 캐시 적중률이 낮고, 클러스터로 확장하면 무효화 전파(분산 캐시)가 필요해집니다.

---

## P2-2. `Page<RoomDto>`를 그대로 직렬화 — 응답에 불필요한 메타데이터가 붙음

**위치** `controller/RoomController.java:37-52`

```java
    @GetMapping
    public Page<RoomDto> list(
      @PageableDefault(size = 9, sort= "id", direction = Sort.Direction.DESC) Pageable pageable
    ) { ... }
```

### 왜 문제인가

`Page`는 Spring Data의 **내부 인터페이스**이고, 실제 반환 객체는 `PageImpl`입니다. Jackson이 이걸 직렬화하면 `content` 배열 외에 `pageable`, `sort`, `totalPages`, `totalElements`, `last`, `first`, `numberOfElements`, `size`, `number`, `empty` 등이 중첩 구조로 함께 나갑니다. 방 9개를 담은 응답에 페이지 메타데이터가 상당한 비중으로 붙습니다.

크기보다 더 중요한 건 **이 JSON 구조가 API 계약으로 보장되지 않는다**는 점입니다. `PageImpl`은 라이브러리 내부 클래스이므로 Spring Data 버전이 올라가면 필드가 바뀔 수 있고, 그러면 프론트가 깨집니다. Spring Boot 3.3 이후로는 `PageImpl`을 JSON으로 직렬화하는 것에 대해 부팅 시 경고를 남기고, 명시적인 응답 모델을 쓰라고 안내합니다.

`[[확인필요: 부팅 로그에 PageImpl 직렬화 관련 경고가 실제로 찍히는지 — 앱을 실행해보지 않았습니다]]`

### 수정 방향

프론트가 이미 이 형태에 맞춰져 있다면 **당장 바꾸지 마세요** (깨집니다). 계약을 직접 정의하는 방향으로 가는 게 맞습니다.

```java
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
    public static <T> PageResponse<T> from(Page<T> p) {
        return new PageResponse<>(p.getContent(), p.getNumber(), p.getSize(),
                                  p.getTotalElements(), p.getTotalPages());
    }
}
```

```diff
-    public Page<RoomDto> list(...)
+    public PageResponse<RoomDto> list(...)
...
-        return roomPage.map(room -> RoomDto.from(room, counts.getOrDefault(room.getId(), 0L)));
+        return PageResponse.from(
+            roomPage.map(room -> RoomDto.from(room, counts.getOrDefault(room.getId(), 0L))));
```

응답 포맷 일관성은 `07-api-design.md`에서 함께 정리합니다.

한편 `roomRepo.findAll(pageable)`은 **`count` 쿼리를 추가로 실행합니다** (`totalElements`를 채우려고). 즉 `GET /rooms`는 목록 SELECT + COUNT + `countActiveByRoomIds` = 3쿼리입니다. `totalElements`가 프론트에서 안 쓰이면 `Slice`를 반환해 COUNT를 없앨 수 있습니다.
`[[확인필요: 프론트가 totalPages/totalElements를 쓰는지]]`

---

## P2-3. 응답에 사용자 이메일이 반복적으로 실려 나감

**위치** `controller/WindowController.java:22,27,33,46` / `controller/TimerController.java:35-46`

```java
// WindowController.java:22 — 엔티티를 그대로 반환
    public List<Window> getWindows(@AuthenticationPrincipal String userEmail) {
        return windowService.findAllByUser(userEmail);
    }
```

`Window.userId`(`entity/Window.java:42-43`)와 `Timer.userId`(`entity/Timer.java:16`)는 **이메일 문자열**을 담고 있고, `@JsonIgnore`가 없으므로 직렬화됩니다.

### 왜 문제인가

**(1) 크기** — 창 10개를 조회하면 요청자 자신의 이메일이 10번, 월별 타이머는 최대 31번 반복됩니다. 클라이언트가 이미 자기 이메일을 아는데도요.

**(2) 계약** — 엔티티를 응답으로 내보내면 엔티티에 필드를 추가할 때마다 API 응답이 조용히 바뀝니다. `urlTitles` 추가(커밋 `041e3c7`)가 실제로 그런 케이스였습니다. 프론트에서 타입 정의를 갖고 있다면 서버와 어긋날 수 있습니다.

**(3) 노출 범위** — 지금은 자기 데이터만 조회하므로 남의 이메일이 새지는 않습니다. 다만 나중에 "방 참가자 목록" 같은 API가 생기면 같은 패턴으로 타인의 이메일이 나갈 구조입니다.

이건 성능보다 아키텍처 문제입니다. 상세는 `06-architecture.md`에서 다룹니다. 여기서는 응답 크기 관점만 기록합니다.

---

## 요약

| 심각도 | 개수 | 항목 |
|---|---|---|
| **P0** | 1 | `POST /timer` 자정 넘김 시 무한 루프 → 커넥션·스레드 영구 점유, 풀 고갈 |
| **P1** | 5 | `/timer/analytics` 동일 쿼리 3중복(총 6쿼리) / JWT 요청마다 2회 파싱 / 커넥션 풀·스레드 풀 전부 기본값 + leak detection 꺼짐 / gzip 미적용 / `show-sql`이 운영에서 `System.out`으로 출력 |
| **P2** | 3 | 캐시 도입은 시기상 부적절(원인은 다른 곳) / `Page` 직접 직렬화 / 응답에 이메일 반복 |

**확인이 필요한 항목 5개**
- `timer` 테이블에 P0-1로 인한 이상 데이터(자정 직전에 끊긴 기록)가 있는지
- 운영 MySQL의 `wait_timeout`, 앱-DB 사이 프록시 유무
- `oeyo-cam.site` 앞단에 압축을 하는 리버스 프록시가 있는지
- 부팅 로그에 `PageImpl` 직렬화 경고가 찍히는지
- 프론트가 `Page` 응답의 `totalPages`/`totalElements`를 쓰는지

**적용 순서 주의**
`open-in-view: false`는 단독으로 적용하면 안 됩니다. `Window`/`Timer` 엔티티를 응답으로 직접 내보내는 구조를 DTO로 바꾼 뒤(`06-architecture.md`) 적용하세요. 순서를 바꾸면 직렬화 시점에 `LazyInitializationException`이 발생할 수 있습니다.

성능 수치는 측정하지 않았습니다. 위 항목들은 "쿼리가 6번 나간다", "기본값이 10이다", "압축이 꺼져 있다"처럼 **소스와 프레임워크 기본값으로 확정되는 사실**만 근거로 합니다. 개선 폭은 `EXPLAIN`과 부하 테스트로 확인해야 합니다.
