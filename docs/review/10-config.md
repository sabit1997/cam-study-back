# 10. 설정 / 환경 분리

조사 범위: profile별 설정, 환경변수 주입, 로컬 설정 혼입.

## 확정한 사실

| 확인 항목 | 결과 |
|---|---|
| `src/main/resources/` 내용 | `application.properties`, `application.yml`, `banner.txt` **3개뿐** |
| profile별 설정 파일 (`application-*.yml`) | **없음** |
| `@Profile` 애너테이션 | **0건** |
| `@ConfigurationProperties` | **0건** |
| `@Value` | **1건** (`util/JwtUtil.java:13`) |
| `SPRING_PROFILES_ACTIVE` | `docker-compose.yml:34`에서 `prod` |
| `.env` / `.env.example` | 작업트리에 **없음**, `.gitignore`에도 **없음** |
| 자바 코드에 하드코딩된 환경 의존 값 | CORS origin 4개 (`SecurityConfig.java:54-57`), WS origin `"*"` (`WsConfig.java:14`) |

환경변수 placeholder는 6개입니다 — `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`(`application.yml:7-9`), `JWT_SECRET`(`:29`).

---

## P1-1. `prod` profile은 활성화되는데 `application-prod.yml`이 없음 — 다른 카테고리 5개 항목의 공통 원인

**위치** `docker-compose.yml:34`, `src/main/resources/`

```yaml
# docker-compose.yml:33-40
    environment:
      SPRING_PROFILES_ACTIVE: prod
      DB_HOST: mysql
      ...
```

그런데 `src/main/resources/`에는 `application-prod.yml`도 `application-prod.properties`도 **없습니다.**

### 왜 문제인가 — 조용히 아무 일도 일어나지 않습니다

Spring Boot의 profile 처리는 이렇게 동작합니다.

1. `SPRING_PROFILES_ACTIVE=prod`를 읽어 `prod` profile을 활성화합니다
2. 설정 위치마다 `application-prod.{properties,xml,yml,yaml}`를 찾습니다
3. **찾지 못하면 그냥 건너뜁니다**

3번이 핵심입니다. profile별 설정 파일은 기본적으로 **optional**이라, 없어도 예외도 경고도 나지 않습니다. 부팅 로그에 `The following 1 profile is active: "prod"`라고 찍히므로, 로그만 보면 **운영 설정이 적용된 것처럼 보입니다.**

실제로는 `application.yml` 한 장이 로컬·운영 공용 설정 전부입니다.

### 그 결과 운영에서 실제로 적용되고 있는 값들

이 파일 하나가 다른 카테고리에서 발견된 문제 다섯 개의 **공통 원인**입니다.

| 설정 | 값 | 어디서 다뤘나 | 결과 |
|---|---|---|---|
| `spring.jpa.hibernate.ddl-auto` | `update` | `01-jpa-query.md` **P0-1** | 배포가 곧 스키마 변경. 타입 변경 미반영으로 엔티티-DB 불일치 |
| `spring.jpa.show-sql` + `format_sql` | `true` | `02-performance.md` P1-5, `09-logging.md` P1-2 | `System.out` 락 경합, 로그 폭증 |
| `spring.security.debug` | `true` | `05-security.md` P1-7, `09-logging.md` P1-3 | 동작 시 인증 쿠키가 평문 로깅 |
| CORS 허용 origin | `localhost:3000/3001` 포함 | `05-security.md` P1-8 | 운영 API에 불필요한 공격면 |
| Hikari / 압축 / actuator 노출 | 전부 기본값 | `02` P1-3·P1-4, `05` P1-5 | 의도하지 않은 값으로 운영 |

**개별 심각도는 각 카테고리에서 이미 매겼으므로 여기서 중복 계상하지 않습니다.** 다만 수정 관점에서는 이게 가장 지렛대가 큰 항목입니다 — 파일 하나를 만들면 다섯 개가 동시에 정리됩니다.

### 수정 방향

**1단계 — 기본값을 운영 안전값으로 뒤집습니다.**

지금은 `application.yml`이 개발 편의 설정을 담고 있습니다. 방향이 반대여야 합니다: **기본은 안전하게, 로컬에서 완화**. 설정 파일을 깜빡했을 때 안전한 쪽으로 실패해야 하기 때문입니다.

```diff
--- a/src/main/resources/application.yml
+++ b/src/main/resources/application.yml
 spring:
   application:
     name: backend
-  security:
-    debug: true
   datasource:
     url: jdbc:mysql://${DB_HOST}:${DB_PORT}/${DB_NAME}?useSSL=false&serverTimezone=UTC
     username: ${DB_USER}
     password: ${DB_PASSWORD}
     driver-class-name: com.mysql.cj.jdbc.Driver
+    hikari:
+      maximum-pool-size: 10
+      connection-timeout: 3000
+      leak-detection-threshold: 10000
   jpa:
     hibernate:
-      ddl-auto: update
+      ddl-auto: validate
-    show-sql: true
+    open-in-view: false        # ← 06의 DTO 분리 이후에 켜세요
     properties:
       hibernate:
-        format_sql: true
+        format_sql: false
 
 server:
   address: 0.0.0.0
   port: 8080
+  compression:
+    enabled: true
+    mime-types: application/json
+
+management:
+  endpoints:
+    web:
+      exposure:
+        include: health
+  endpoint:
+    health:
+      show-details: never
 
 logging:
   level:
     org.springframework.security: WARN
     root: INFO
 
 jwt:
   secretKey: ${JWT_SECRET}
+  access-token-ttl: PT1H
+  refresh-token-ttl: P7D
+
+app:
+  cors:
+    allowed-origins: https://oeyo-cam.site,https://www.oeyo-cam.site
+  cookie:
+    same-site: None
+    secure: true
```

**`open-in-view: false`는 `06-architecture.md` P1-1(엔티티 대신 DTO 반환)을 먼저 적용한 뒤에 켜세요.** 순서를 바꾸면 직렬화 시점에 `LazyInitializationException`이 납니다.

**2단계 — 로컬 profile을 만듭니다.**

```yaml
# src/main/resources/application-local.yml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/${DB_NAME:camstudy}?useSSL=false&serverTimezone=UTC
  jpa:
    hibernate:
      ddl-auto: update          # 로컬에서만 스키마 자동 갱신
    open-in-view: true
    properties:
      hibernate:
        format_sql: true

logging:
  level:
    com.camstudy.backend: DEBUG
    org.hibernate.SQL: DEBUG
    # org.hibernate.orm.jdbc.bind: TRACE   ← 이메일·해시가 찍힙니다. 임시로만 (09 P1-3)

app:
  cors:
    allowed-origins: http://localhost:3000,http://localhost:3001
  cookie:
    same-site: Lax
    secure: false

springdoc:
  swagger-ui:
    enabled: true
```

`show-sql` 대신 `org.hibernate.SQL: DEBUG`를 쓴 것이 포인트입니다 — 로거를 거치므로 레벨로 통제되고 Logback 설정이 적용됩니다(`09-logging.md` P1-2).

**3단계 — `application-prod.yml`을 만듭니다.**

기본값이 이미 운영 안전값이면 이 파일은 거의 비어 있어도 됩니다. 그래도 **명시적으로 만드는 것**이 중요합니다 — 파일이 존재해야 "운영 설정이 있다"는 사실이 코드에서 보이고, 나중에 운영 전용 값을 넣을 자리가 생깁니다.

```yaml
# src/main/resources/application-prod.yml
spring:
  jpa:
    hibernate:
      ddl-auto: validate
  datasource:
    url: jdbc:mysql://${DB_HOST}:${DB_PORT}/${DB_NAME}?sslMode=REQUIRED&connectionTimeZone=UTC

logging:
  structured:
    format:
      console: ecs     # 09 P2-2. 로그 수집 시스템이 있을 때만
```

**4단계 — 설정 누락을 부팅 시점에 잡습니다.**

이게 가장 중요합니다. profile 파일이 없어도 조용히 넘어가는 것이 문제였으므로, **운영에서 위험한 값이면 부팅을 실패시키는** 장치를 둡니다.

```java
package com.camstudy.backend.config;

@Component
@Profile("prod")
@RequiredArgsConstructor
public class ProductionConfigGuard {

    private final Environment env;

    @PostConstruct
    void assertSafeProductionConfig() {
        String ddlAuto = env.getProperty("spring.jpa.hibernate.ddl-auto", "none");
        if (!ddlAuto.equals("validate") && !ddlAuto.equals("none")) {
            throw new IllegalStateException(
                "prod에서 ddl-auto=" + ddlAuto + " 는 허용되지 않습니다. validate 또는 none이어야 합니다.");
        }
        assertFalse("spring.jpa.show-sql");
        assertFalse("spring.security.debug");

        String origins = env.getProperty("app.cors.allowed-origins", "");
        if (origins.contains("localhost")) {
            throw new IllegalStateException("prod CORS 허용 목록에 localhost가 포함되어 있습니다: " + origins);
        }
    }

    private void assertFalse(String key) {
        if (env.getProperty(key, Boolean.class, false)) {
            throw new IllegalStateException("prod에서 " + key + "=true 는 허용되지 않습니다.");
        }
    }
}
```

`@PostConstruct`에서 예외를 던지면 **애플리케이션 부팅이 실패합니다.** 그게 목적입니다 — 잘못된 설정으로 운영에 뜨는 것보다 배포가 실패하는 편이 낫습니다.

`@Profile("prod")`가 붙어 있으므로 로컬에서는 이 빈이 생성되지 않고, 로컬의 `ddl-auto: update`는 그대로 동작합니다.

**5단계 — `docker-compose.yml`에 활성 profile을 유지합니다.**

이미 `SPRING_PROFILES_ACTIVE: prod`가 있으므로 그대로 두면 됩니다. 로컬 실행 시에는 `SPRING_PROFILES_ACTIVE=local`을 주거나 IDE 실행 구성에 넣으세요.

---

## P1-2. 환경마다 달라야 하는 값이 자바 코드에 하드코딩됨

grep 결과 `SecurityConfig`와 `WsConfig`에 환경 의존 값이 박혀 있습니다. 이 값들은 **설정 파일로도 환경변수로도 바꿀 수 없고, 코드를 고쳐 다시 빌드해야** 합니다.

### (a) CORS 허용 origin

**위치** `config/SecurityConfig.java:52-58`

```java
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of(
            "https://oeyo-cam.site",
            "https://www.oeyo-cam.site",
            "http://localhost:3000",       // ← 로컬 개발 설정이 운영 코드에
            "http://localhost:3001"
        ));
```

운영과 로컬 값이 **한 리스트에 섞여 있습니다.** 그래서 운영에서도 `localhost`가 허용되고(`05-security.md` P1-8), 로컬 포트를 3002로 바꾸려면 코드를 수정해야 합니다.

### (b) WebSocket 허용 origin

**위치** `config/WsConfig.java:14`

```java
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*");
```

`05-security.md` P0-2에서 다룬 CSWSH의 원인입니다. 설정 관점에서 보면 **같은 성격의 값(허용 origin)이 두 곳에 서로 다른 내용으로** 적혀 있다는 점이 문제입니다. 하나는 목록, 하나는 `*`입니다.

이런 구조에서는 origin 하나를 추가할 때 **두 곳을 모두 고쳐야 한다는 사실을 알아야** 하고, 모르면 한쪽만 고쳐서 WebSocket만 안 되거나 반대가 됩니다.

### (c) 토큰 만료 시간과 쿠키 속성

**위치** `util/JwtUtil.java:21`, `:31` / `service/AuthService.java:37-52`, `:77-89`, `:109-115`

```java
// JwtUtil.java:21, :31
                .setExpiration(new Date(System.currentTimeMillis() + 60 * 60 * 1000))          // 1시간
                .setExpiration(new Date(System.currentTimeMillis() + 60 * 60 * 24 * 7 * 1000)) // 7일
```

```java
// AuthService.java:41-42, :50-51 — 같은 값이 쿠키에도 별도로
                .maxAge(60 * 60)          // 토큰 exp와 맞아야 함
                .sameSite("None")         // ← 환경마다 달라야 하는 값
                .secure(true)             // ← 환경마다 달라야 하는 값
```

**두 종류의 문제가 겹칩니다.**

1. **같은 개념이 두 곳에 중복** — 토큰 만료(`JwtUtil`)와 쿠키 `maxAge`(`AuthService`)가 각각 하드코딩되어 있어, 한쪽만 바꾸면 어긋납니다. 쿠키는 살아 있는데 토큰이 만료된 상태가 됩니다.
2. **`secure`와 `sameSite`는 환경에 따라 달라야 합니다** — 로컬 `http://localhost`와 운영 HTTPS는 요구 조건이 다릅니다. `05-security.md` P1-2에서 `SameSite=Lax`로 바꾸는 것을 검토하라고 했는데, 지금은 **세 곳**(`login`, `logout`, `refresh`)을 각각 찾아 고쳐야 하고 `logout`에는 `sameSite`가 아예 없습니다(`05-security.md` P2-5).

### 수정 방향

**타입 안전한 설정 객체로 묶으세요.** `@Value`를 여기저기 뿌리면 오타가 런타임에야 드러납니다.

```java
package com.camstudy.backend.config;

@ConfigurationProperties(prefix = "app")
@Validated
public record AppProperties(
        @Valid Cors cors,
        @Valid Cookie cookie
) {
    public record Cors(@NotEmpty List<String> allowedOrigins) {}

    public record Cookie(
            @NotBlank String sameSite,
            boolean secure
    ) {}
}
```

```java
@ConfigurationProperties(prefix = "jwt")
@Validated
public record JwtProperties(
        @NotBlank String secretKey,
        @NotNull Duration accessTokenTtl,
        @NotNull Duration refreshTokenTtl
) {}
```

`@EnableConfigurationProperties(...)` 또는 `@ConfigurationPropertiesScan`을 메인 클래스에 붙여 등록합니다.

```diff
--- a/src/main/java/com/camstudy/backend/BackendApplication.java
+++ b/src/main/java/com/camstudy/backend/BackendApplication.java
 @SpringBootApplication
+@ConfigurationPropertiesScan
 public class BackendApplication {
```

**`@Validated`가 핵심입니다.** 설정이 누락되거나 형식이 틀리면 **부팅이 실패합니다.** `@Value` 필드 주입은 값이 빈 문자열이어도 통과하고 첫 요청에서야 실패합니다(`05-security.md` P1-9).

단 **`spring-boot-starter-validation` 의존성이 필요합니다** — `07-api-design.md` P1-1에서 추가하기로 한 것과 같은 의존성입니다. 없으면 `@Validated`가 조용히 무시됩니다.

```java
// application.yml의 PT1H, P7D는 java.time.Duration으로 자동 변환됩니다 (ISO-8601)
jwt:
  access-token-ttl: PT1H      // 1시간
  refresh-token-ttl: P7D      // 7일
```

그리고 사용처를 바꿉니다.

```diff
--- a/src/main/java/com/camstudy/backend/config/SecurityConfig.java
+++ b/src/main/java/com/camstudy/backend/config/SecurityConfig.java
+    private final AppProperties appProps;
...
     public CorsConfigurationSource corsConfigurationSource() {
         CorsConfiguration config = new CorsConfiguration();
-        config.setAllowedOriginPatterns(List.of(
-            "https://oeyo-cam.site", "https://www.oeyo-cam.site",
-            "http://localhost:3000", "http://localhost:3001"));
+        config.setAllowedOrigins(appProps.cors().allowedOrigins());
```

```diff
--- a/src/main/java/com/camstudy/backend/config/WsConfig.java
+++ b/src/main/java/com/camstudy/backend/config/WsConfig.java
+    private final AppProperties appProps;
...
     public void registerStompEndpoints(StompEndpointRegistry registry) {
-        registry.addEndpoint("/ws").setAllowedOriginPatterns("*");
+        registry.addEndpoint("/ws")
+                .setAllowedOrigins(appProps.cors().allowedOrigins().toArray(String[]::new));
     }
```

**같은 값을 두 곳이 읽으므로 더 이상 어긋날 수 없습니다.** `05-security.md` P0-2와 P1-8이 함께 해결됩니다.

쿠키 생성도 한 곳으로 모으세요 — `05-security.md` P2-5의 `AuthCookieFactory`가 `AppProperties.Cookie`와 `JwtProperties.accessTokenTtl`을 함께 읽으면, 토큰 만료와 쿠키 `maxAge`가 **하나의 설정에서 나옵니다.**

---

## P1-3. Dockerfile이 `application.yml`을 이미지에 중복 배치하고, 외부 파일이 jar 안의 것을 덮어씀

**위치** `Dockerfile:16-23`

```dockerfile
WORKDIR /app                                                    # :17

COPY src/main/resources/application.yml /app/application.yml    # :20  ← 외부 복사본
COPY --from=build /app/app.jar app.jar                          # :23  ← jar 안에도 있음
```

`ENTRYPOINT`가 `/app`에서 `java -jar app.jar`를 실행합니다(`:29`).

### 왜 문제인가 — 어느 쪽이 이기는지가 직관과 다릅니다

Spring Boot의 기본 설정 파일 탐색 위치는 이 순서입니다.

```
optional:classpath:/          ← jar 안의 application.yml
optional:classpath:/config/
optional:file:./              ← 실행 디렉터리 = /app
optional:file:./config/
optional:file:./config/*/
```

**뒤에 오는 위치가 앞을 덮어씁니다.** `WORKDIR /app`이므로 `file:./`는 `/app/`이고, 따라서 **`Dockerfile:20`이 복사한 `/app/application.yml`이 jar 안의 것을 이깁니다.**

지금은 두 파일이 같은 소스에서 왔으므로 **동작 차이가 없습니다.** 문제는 이 구조가 만드는 위험입니다.

**(1) 아무도 예상하지 못하는 우선순위입니다.**
설정을 고치러 온 사람은 `src/main/resources/application.yml`을 보고 "이게 적용된다"고 판단합니다. 맞긴 한데, **jar 안의 것이 아니라 이미지에 별도로 복사된 사본이 적용되고 있다**는 사실은 `Dockerfile`을 읽어야만 알 수 있습니다.

**(2) 컨테이너 안에서 수정하면 재시작해도 유지됩니다.**
운영 중 디버깅하려고 `docker exec`로 `/app/application.yml`을 고치면, 그 변경이 **컨테이너 재시작 후에도 남습니다**(이미지 레이어가 아니라 컨테이너 쓰기 레이어에). `restart: always`(`docker-compose.yml:41`)와 결합하면 임시 수정이 영구화되고, 저장소를 읽는 누구도 그 사실을 알 수 없습니다.

**(3) 파일이 하나만 복사됩니다.**
P1-1에서 `application-local.yml`, `application-prod.yml`을 추가하면 그것들은 **jar 안에만** 존재하고 `/app/`에는 없습니다.

다행히 이 경우는 안전합니다 — Spring Boot 문서가 명시하듯 **profile별 파일은 위치와 무관하게 비-profile 파일보다 항상 우선**합니다. 즉 jar 안의 `application-prod.yml`이 `/app/application.yml`을 덮습니다. 하지만 "왜 어떤 건 덮고 어떤 건 안 덮는지"를 매번 따져야 하는 구조 자체가 부담입니다.

**(4) 설정이 이미지에 구워집니다.**
컨테이너 이미지는 환경 중립이어야 하고, 환경 차이는 실행 시점의 환경변수로 주입하는 것이 원칙입니다. 지금은 환경변수 placeholder를 쓰고 있어 실질적 문제는 없지만, `Dockerfile:20`이 그 원칙을 깨는 방향으로 열려 있습니다.

### 수정 방향

**그 줄을 지우세요.** jar 안에 이미 있으므로 필요가 없습니다.

```diff
--- a/Dockerfile
+++ b/Dockerfile
 WORKDIR /app
 
-# Copy the application configuration file to the container
-COPY src/main/resources/application.yml /app/application.yml
-
 # Copy the JAR file from the build stage to the runtime stage
 COPY --from=build /app/app.jar app.jar
```

**운영에서 설정을 덮어써야 할 일이 생기면** 파일을 굽는 대신 환경변수를 쓰세요. Spring Boot는 `SPRING_JPA_SHOW_SQL=false` 같은 형태의 환경변수를 자동으로 프로퍼티에 매핑합니다(relaxed binding).

```yaml
# docker-compose.yml — 재빌드 없이 특정 값만 조정
    environment:
      SPRING_PROFILES_ACTIVE: prod
      LOGGING_LEVEL_COM_CAMSTUDY_BACKEND: DEBUG    # 임시 디버깅
```

정말 파일로 주입해야 하면 **볼륨 마운트**를 쓰세요 — 이미지에 굽지 않고, 무엇이 마운트됐는지가 compose 파일에 보입니다.

```yaml
    volumes:
      - ./config/application-prod.yml:/app/config/application-prod.yml:ro
```

`/app/config/`는 `file:./config/`에 해당해 `file:./`보다 우선순위가 높습니다.

`Dockerfile`의 다른 문제(빌드 스테이지가 실제로 빌드하지 않음, JRE 대신 JDK 사용 등)는 `12-docker-deploy.md`에서 다룹니다.

---

## P1-4. `.env` 관리 체계가 없음 — 새로 받은 사람이 실행할 수 없고, 실수로 커밋될 수 있음

### 확정한 사실

- 작업트리에 `.env` **없음**
- git이 추적 중인 `.env` **없음**
- `.gitignore`에 `env` 문자열 **없음** (39줄 전부 Gradle/IDE 산출물)
- `.env.example` 같은 템플릿 **없음**

그런데 `docker-compose.yml`은 `.env`를 전제로 동작합니다.

```yaml
# docker-compose.yml:11-14, :37-40
      MYSQL_DATABASE: ${DB_NAME}
      MYSQL_ROOT_PASSWORD: ${DB_PASSWORD}
      MYSQL_USER: ${DB_USER}
      MYSQL_PASSWORD: ${DB_PASSWORD}
...
      DB_NAME: ${DB_NAME}
      DB_USER: ${DB_USER}
      DB_PASSWORD: ${DB_PASSWORD}
      JWT_SECRET: ${JWT_SECRET}
```

Docker Compose는 compose 파일과 같은 디렉터리의 `.env`를 자동으로 읽습니다. 즉 **운영·개발 머신에는 `.env`가 존재하고, 거기에 DB 비밀번호와 JWT 시크릿이 들어 있습니다.**

### 왜 문제인가

**(1) 커밋 사고가 한 명령어 거리에 있습니다.**

`.gitignore`에 없으므로 `git add .` 한 번이면 커밋됩니다. 그리고 저장소는 **PUBLIC입니다**(`05-security.md` P0-3). `.env`에는 `JWT_SECRET`이 있고, 그게 유출되면 **임의 사용자의 유효한 토큰을 발급할 수 있어** 인증 체계 전체가 무너집니다.

과거에 `application.yml`에 DB 자격증명을 하드코딩해 커밋한 이력이 이미 있습니다(`ab1a1d9`, `e62e4d1`, `a6b9577`). 같은 부류의 사고가 재발할 조건이 그대로 남아 있습니다.

**(2) 새 참여자가 무엇을 채워야 하는지 알 수 없습니다.**

저장소를 받은 사람이 필요한 환경변수 목록을 알려면 `docker-compose.yml`과 `application.yml`을 **역으로 읽어야** 합니다. 하나라도 빠뜨리면:

- `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` 누락 → placeholder 해석 실패 → **부팅 실패** (이건 올바른 동작입니다)
- `JWT_SECRET`을 **빈 문자열로** 설정 → `@Value`가 `""`를 주입 → 부팅은 성공하고 **첫 로그인에서 실패** (`05-security.md` P1-9)

두 번째가 나쁩니다. 실패가 배포 시점이 아니라 사용자 요청 시점에 일어납니다.

**(3) `.env`가 로컬과 운영에서 같은 이름·같은 형식이라 섞이기 쉽습니다.**

`docker-compose.yml` 한 벌이 로컬과 운영 양쪽에서 쓰이므로(P2-3), `.env`만 바꿔가며 실행하게 됩니다. 로컬 `.env`로 운영 compose를 올리는 실수를 막는 장치가 없습니다.

### 수정 방향

**1단계 — `.gitignore` 보강** (`05-security.md` P0-3과 동일)

```diff
--- a/.gitignore
+++ b/.gitignore
+### secrets / local config ###
+.env
+.env.*
+!.env.example
+*.pem
+*.key
+src/main/resources/application-local.yml
```

`application-local.yml`을 무시 목록에 넣을지는 판단이 필요합니다. **로컬 DB 주소처럼 팀 공통이면 커밋하는 편이 낫고**(온보딩이 쉬워짐), 개인별로 다르면 무시하고 `.example`을 두세요. 어느 쪽이든 **비밀은 절대 넣지 마세요** — 로컬 파일도 커밋 대상이 될 수 있습니다.

**2단계 — `.env.example`을 커밋합니다.** 키 이름만, 값은 비웁니다.

```bash
# .env.example
# docker-compose 실행에 필요한 값. 복사해서 .env로 만들고 채우세요.
#   cp .env.example .env

DB_HOST=mysql
DB_PORT=3306
DB_NAME=camstudy
DB_USER=camuser
DB_PASSWORD=            # 필수. 앱 계정 비밀번호
DB_ROOT_PASSWORD=       # 필수. root 비밀번호 (DB_PASSWORD와 다르게)

# HS256용. 32바이트 이상으로 디코딩되는 Base64 문자열이어야 합니다 (05 P1-9)
#   생성: openssl rand -base64 48
JWT_SECRET=
```

주석에 **생성 방법**을 적어두는 것이 중요합니다. `05-security.md` P1-9에서 다룬 대로 jjwt가 이 값을 Base64로 디코딩하므로, 평문 패스프레이즈를 넣으면 실제 키 길이가 예상과 달라집니다.

`DB_ROOT_PASSWORD`를 분리한 것은 `05-security.md` P0-3의 조치입니다 — 지금은 root와 앱 계정이 같은 비밀번호입니다.

**3단계 — 빈 값을 부팅 시점에 거부합니다.**

P1-2의 `@ConfigurationProperties` + `@Validated`가 이걸 해결합니다. `@NotBlank String secretKey`면 빈 문자열에서 **부팅이 실패**합니다.

`05-security.md` P1-9에서 제안한 생성자 검증도 같은 목적입니다.

```java
    public JwtUtil(@Value("${jwt.secretKey}") String base64Secret) {
        byte[] bytes = Decoders.BASE64.decode(base64Secret);
        if (bytes.length < 32) {
            throw new IllegalStateException("jwt.secretKey must decode to >= 32 bytes");
        }
        ...
    }
```

**4단계 — 커밋 전 탐지 장치**

`gitleaks`를 pre-commit 훅으로 걸면 `.gitignore`를 우회한 경우도 잡힙니다. 저장소가 public이므로 이중 방어가 정당합니다.

---

## P2-1. `application.properties`와 `application.yml`이 공존하며 같은 키를 정의

**위치** `src/main/resources/application.properties`, `application.yml:2-3`

```properties
# application.properties (전부)
spring.application.name=backend
```

```yaml
# application.yml:1-3
spring:
  application:
    name: backend
```

같은 키를 같은 값으로 정의하고 있어 **현재는 결과가 같습니다.**

문제는 두 형식을 함께 두는 것 자체입니다. 같은 위치(`classpath:/`)에 `application.properties`와 `application.yml`이 모두 있을 때 어느 쪽이 이기는지는 직관적이지 않고, 값이 갈리는 순간 원인을 찾기 어렵습니다.

`application.properties`는 Spring Initializr가 만든 기본 파일이고, 나중에 `application.yml`을 추가하면서 지우지 않은 것으로 보입니다(`application.yml`이 훨씬 많은 내용을 담고 있음).

### 수정 방향

```bash
git rm src/main/resources/application.properties
```

`spring.application.name`은 이미 `application.yml:2-3`에 있으므로 잃는 것이 없습니다. **한 형식만 쓰세요.**

---

## P2-2. 설정 주입이 `@Value` 하나뿐이고 타입 검증이 없음

`@ConfigurationProperties`가 **0건**이고 `@Value`가 **1건**(`util/JwtUtil.java:13`)입니다.

지금은 외부화된 설정이 사실상 `jwt.secretKey` 하나뿐이라 이 상태가 문제되지 않습니다. 짚어두는 이유는 **P1-1과 P1-2를 적용하면 설정 항목이 늘어나기 때문**입니다. 그때 `@Value`를 여기저기 뿌리면 다음 문제가 생깁니다.

| `@Value` 방식 | `@ConfigurationProperties` 방식 |
|---|---|
| 키 오타가 런타임에야 드러남 (또는 조용히 기본값) | 부팅 시 바인딩 실패로 즉시 드러남 |
| 값 검증이 수동 | `@Validated` + `@NotBlank` 등으로 선언적 |
| 필드 주입이라 불변성 없음 | `record`로 불변 |
| 관련 설정이 여러 클래스에 흩어짐 | 한 타입에 응집 |
| IDE 자동완성·문서화 어려움 | 메타데이터 생성 가능 |

`@Value`의 조용한 실패가 특히 위험합니다.

```java
@Value("${jwt.secretkey:}")     // ← 오타(대소문자) + 기본값 빈 문자열
private String secretKey;       // 아무 오류 없이 "" 가 주입됨
```

P1-2의 diff가 이 항목의 수정안입니다. **`spring-boot-starter-validation` 의존성이 선행 조건**입니다(`07-api-design.md` P1-1).

메타데이터 생성기를 추가하면 IDE에서 `application.yml` 자동완성이 됩니다.

```diff
--- a/build.gradle
+++ b/build.gradle
+  annotationProcessor 'org.springframework.boot:spring-boot-configuration-processor'
```

---

## P2-3. `docker-compose.yml` 한 벌이 로컬·운영 공용

**위치** `docker-compose.yml`

```yaml
  app:
    image: yejiii1997/cmas:latest        # ← 레지스트리 이미지 (운영용)
    ports:
      - "8080:8080"
    environment:
      SPRING_PROFILES_ACTIVE: prod       # ← 운영 profile 고정
```

```yaml
  mysql:
    ports:
      - "3306:3306"                      # ← 호스트 노출 (로컬 개발용)
```

한 파일에 운영 성격(`:latest` 이미지, `prod` profile)과 로컬 성격(MySQL 포트 호스트 노출)이 섞여 있습니다.

### 왜 문제인가

- **로컬에서 코드를 고쳐 실행할 수 없습니다.** `image:`를 쓰므로 로컬 소스가 아니라 레지스트리의 이미지가 뜹니다. 로컬 변경을 확인하려면 매번 이미지를 빌드해 푸시해야 합니다.
- **MySQL 3306이 호스트에 노출됩니다.** 로컬에서 DB 클라이언트로 붙기 위한 설정인데, 같은 파일로 운영에 올리면 **DB가 인터넷에 열립니다**(`05-security.md` P2-4). 방화벽이 유일한 방어선이 됩니다.
- **`SPRING_PROFILES_ACTIVE: prod`가 고정**이라 로컬에서 `local` profile을 쓰려면 파일을 고쳐야 하고, 그 수정이 실수로 커밋될 수 있습니다.

### 수정 방향

Compose의 **override 파일** 기능을 쓰세요. `docker-compose.override.yml`은 `docker compose up` 시 **자동으로 병합**됩니다.

```yaml
# docker-compose.yml — 공통 + 운영 안전값
services:
  mysql:
    image: mysql:8.0
    environment:
      MYSQL_DATABASE: ${DB_NAME}
      MYSQL_ROOT_PASSWORD: ${DB_ROOT_PASSWORD}
      MYSQL_USER: ${DB_USER}
      MYSQL_PASSWORD: ${DB_PASSWORD}
    expose:
      - "3306"                    # 같은 네트워크에만. 호스트 노출 없음
    volumes:
      - mysql-vl:/var/lib/mysql
    restart: always
    logging:                      # 09 P1-2
      driver: json-file
      options: { max-size: "20m", max-file: "3" }

  app:
    image: yejiii1997/cmas:latest
    environment:
      SPRING_PROFILES_ACTIVE: ${SPRING_PROFILES_ACTIVE:-prod}
      ...
    restart: always
    logging:
      driver: json-file
      options: { max-size: "20m", max-file: "5" }
```

```yaml
# docker-compose.override.yml — 로컬 전용. .gitignore에 넣지 말고 커밋하세요
services:
  mysql:
    ports:
      - "3306:3306"               # 로컬에서만 호스트 노출

  app:
    build: .                      # 레지스트리 대신 로컬 소스로 빌드
    environment:
      SPRING_PROFILES_ACTIVE: local
```

운영 서버에서는 override 파일을 두지 않거나, 명시적으로 기본 파일만 지정합니다.

```bash
docker compose -f docker-compose.yml up -d
```

`SPRING_PROFILES_ACTIVE: ${SPRING_PROFILES_ACTIVE:-prod}`처럼 기본값을 두면, 환경변수로 덮어쓸 수 있으면서 **아무것도 주지 않으면 안전한 쪽(prod)** 으로 갑니다. P1-1의 "기본은 안전하게"와 같은 원칙입니다.

---

## P2-4. 도메인 기본값이 코드에 흩어진 매직 넘버

설정으로 뺄 후보들입니다.

| 값 | 위치 | 의미 |
|---|---|---|
| `6` | `service/RoomService.java:32` (`orElse(6)`) | 방 기본 정원 |
| `6` | `entity/Room.java:33` (`capacity = 6`) | **같은 값이 두 곳에** |
| `9` | `controller/RoomController.java:38` (`size = 9`) | 방 목록 페이지 크기 |
| `2` | `entity/User.java:23` (`dailyGoalHours = 2`) | 기본 일일 목표 시간 |
| `1시간` / `7일` | `util/JwtUtil.java:21`, `:31` | 토큰 만료 (P1-2에서 다룸) |
| `500` | `01-jpa-query.md` P1-8에서 제안한 조회 상한 | 아직 없음 |

**정원 기본값 `6`이 두 곳에 있는 것**이 가장 눈에 띕니다. `RoomService.create:32`가 `Optional.ofNullable(capacity).orElse(6)`으로 채우고, `Room.prePersist:33`이 `if (capacity == null) capacity = 6`으로 또 채웁니다. 한쪽만 바꾸면 경로에 따라 다른 값이 됩니다.

### 왜 P2인가

이 값들은 환경마다 달라야 하는 값이 아니라 **도메인 기본값**입니다. 설정 파일로 빼는 이득이 크지 않고, 오히려 설정 항목이 늘어나 관리 부담이 됩니다.

다만 **중복은 제거해야** 합니다. 그리고 이름 없는 숫자는 상수로 뽑는 편이 읽기 좋습니다.

```diff
--- a/src/main/java/com/camstudy/backend/service/RoomService.java
+++ b/src/main/java/com/camstudy/backend/service/RoomService.java
     public Room create(String name, Integer capacity, String rawPassword, String ownerId) {
         ...
         var room = Room.builder()
                 .name(name)
-                .capacity(Optional.ofNullable(capacity).orElse(6))
+                .capacity(capacity)          // null이면 @PrePersist가 기본값을 채운다
                 .passwordHash(hash)
```

기본값 결정을 `Room.prePersist` 한 곳으로 모읍니다. 거기서도 상수로 뽑으세요.

```diff
--- a/src/main/java/com/camstudy/backend/entity/Room.java
+++ b/src/main/java/com/camstudy/backend/entity/Room.java
+    public static final int DEFAULT_CAPACITY = 6;
+
     @PrePersist
     public void prePersist() {
         ...
-        if (capacity == null) capacity = 6;
+        if (capacity == null) capacity = DEFAULT_CAPACITY;
```

`07-api-design.md` P1-1에서 `@Min(2) @Max(12)` 검증을 제안했는데, 그 상한값과 이 기본값이 같은 개념 영역에 있으므로 함께 관리하세요.

---

## 제안하는 최종 파일 구성

```
src/main/resources/
├── application.yml           운영 안전 기본값 (ddl-auto: validate, show-sql 없음,
│                             CORS는 운영 origin만, actuator는 health만)
├── application-local.yml     로컬 완화 (ddl-auto: update, SQL 로깅, localhost CORS,
│                             swagger 활성, secure 쿠키 off)
├── application-prod.yml      운영 전용 (sslMode=REQUIRED, 구조화 로깅 등)
└── banner.txt

프로젝트 루트
├── .env                      비밀 값. gitignore 대상
├── .env.example              키 이름과 생성 방법. 커밋
├── docker-compose.yml        공통 + 운영 안전값
├── docker-compose.override.yml  로컬 전용 (build, 포트 노출, local profile)
└── .gitignore                .env, *.pem, *.key 추가

src/main/java/.../config/
├── AppProperties.java        @ConfigurationProperties("app") + @Validated
├── JwtProperties.java        @ConfigurationProperties("jwt") + @Validated
└── ProductionConfigGuard.java  @Profile("prod") 부팅 시 위험 설정 차단
```

`application.properties`는 삭제, `Dockerfile:20`의 `COPY application.yml`도 삭제입니다.

---

## 요약

| 심각도 | 개수 | 항목 |
|---|---|---|
| **P0** | 0 | — (P1-1의 결과 중 하나인 `ddl-auto: update`는 `01-jpa-query.md`에서 P0으로 계상) |
| **P1** | 4 | `application-prod.yml` 부재로 개발 설정이 운영에서 그대로 동작 (다른 카테고리 5개 항목의 공통 원인) / 환경 의존 값(CORS·WS origin·쿠키 속성·토큰 TTL)이 자바 코드에 하드코딩 / Dockerfile이 `application.yml`을 중복 배치, 외부 파일이 jar를 덮어씀 / `.env` 관리 체계 부재 (gitignore·example·검증 전부 없음) |
| **P2** | 4 | `application.properties`와 `.yml` 공존 / `@ConfigurationProperties` 미사용으로 설정 검증 없음 / `docker-compose.yml`이 로컬·운영 공용 / 도메인 기본값 매직 넘버 중복 |

## 사용자가 물은 항목에 대한 직접 답

| 질문 | 답 |
|---|---|
| profile별 설정 | **없습니다.** `docker-compose.yml:34`가 `SPRING_PROFILES_ACTIVE: prod`를 주지만 `application-prod.yml`이 존재하지 않고, Spring Boot는 **경고 없이 건너뜁니다.** 부팅 로그에는 `profile is active: "prod"`가 찍혀서 적용된 것처럼 보입니다. `@Profile` 애너테이션도 0건입니다. |
| 환경변수 주입 | placeholder 6개(`DB_*` 5개, `JWT_SECRET`)로 **비밀은 코드 밖에 있습니다** — 이건 잘 되어 있습니다. 다만 `.env`가 `.gitignore`에 없고 `.env.example`도 없으며, **빈 문자열 검증이 없어** `JWT_SECRET=""`이면 부팅은 성공하고 첫 로그인에서 실패합니다. |
| 로컬 설정 혼입 | **3건.** ① `SecurityConfig.java:56-57`의 `localhost:3000/3001`이 운영 CORS 목록에 포함 ② `application.yml`의 `ddl-auto: update`·`show-sql: true`·`security.debug: true`가 운영 적용 ③ `docker-compose.yml:8-9`가 MySQL 3306을 호스트에 노출(로컬용 설정이 운영 파일에). |

## 적용 순서

```
1. .gitignore에 .env 추가 + .env.example 커밋 (P1-4 1~2단계)   ← 즉시. 공개 저장소
2. application.properties 삭제 (P2-1)                          ← 즉시. 한 파일
3. Dockerfile:20 COPY 줄 삭제 (P1-3)                           ← 즉시. 한 줄
4. application.yml 기본값을 운영 안전값으로 (P1-1 1단계)
   └ open-in-view: false 는 06 P1-1 이후로 미룰 것
5. application-local.yml 생성 (P1-1 2단계)
6. spring-boot-starter-validation 추가 (07 P1-1과 동일)
7. AppProperties / JwtProperties 도입 (P1-2, P2-2)
   └ CORS·WS origin이 한 설정을 공유 → 05 P0-2, P1-8 동시 해결
8. ProductionConfigGuard (P1-1 4단계)                          ← 재발 방지
9. docker-compose.override.yml 분리 (P2-3)
10. 매직 넘버 중복 제거 (P2-4)
```

**1~3번은 파일 하나씩 건드리는 작업이고 위험이 없습니다.** 4~5번이 이 카테고리의 본체이며, 다른 카테고리 5개 항목이 여기서 함께 정리됩니다.

애플리케이션을 실행해보지 않았습니다. 위 profile 동작과 설정 파일 우선순위는 Spring Boot의 문서화된 규칙에 근거한 것이며, 실제 적용 값은 부팅 후 `/actuator/env`(인증 뒤에 두고) 또는 `--debug` 옵션의 자동 설정 리포트로 확인할 수 있습니다.
