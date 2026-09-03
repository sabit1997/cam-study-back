# 12. Docker / 배포

조사 범위: 레이어 캐시, 이미지 크기, 헬스체크, 컨테이너 리소스·로그 설정.

## 확정한 사실

| 확인 항목 | 결과 |
|---|---|
| `.dockerignore` | **없음** |
| `HEALTHCHECK` (Dockerfile) | **없음** |
| `healthcheck:` (compose) | **없음** |
| `USER` 지시어 | **없음** → root로 실행 |
| `ENV` 지시어 | **없음** |
| 컨테이너 리소스 제한 (`mem_limit` / `deploy.resources`) | **없음** |
| 로깅 드라이버 옵션 | **없음** (`09-logging.md` P1-2) |
| JVM 힙 옵션 (`-Xmx` / `-XX:MaxRAMPercentage`) | **없음** |
| `server.shutdown: graceful` | **없음** → 기본 `immediate` |
| `build.gradle`의 `jar` 태스크 제어 | **없음** → plain jar도 함께 생성 |
| OpenTelemetry 의존성 | **0건** (그런데 ENTRYPOINT에 OTel 옵션이 있음) |
| 이미지 태그 | `yejiii1997/cmas:latest` |

**Dockerfile 전문 (29줄)**

```dockerfile
FROM eclipse-temurin:17-jdk-jammy AS build          # :2
WORKDIR /app                                         # :5
ARG JAR_FILE=build/libs/*.jar                        # :8
COPY ${JAR_FILE} app.jar                             # :11

FROM eclipse-temurin:17-jdk-jammy                    # :14
WORKDIR /app                                         # :17
COPY src/main/resources/application.yml /app/application.yml   # :20
COPY --from=build /app/app.jar app.jar               # :23
EXPOSE 8080                                          # :26
ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom",
            "-Dotel.resource.attributes=service.name=auth-server",
            "-jar", "app.jar"]                       # :29
```

---

## P1-1. 빌드 스테이지가 아무것도 빌드하지 않고, plain jar 때문에 `docker build`가 실패할 수 있음

**위치** `Dockerfile:2-11`, `build.gradle:20-47`

```dockerfile
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /app
ARG JAR_FILE=build/libs/*.jar
COPY ${JAR_FILE} app.jar                # ← 이미 만들어진 jar를 복사만 함
```

### (a) 멀티스테이지 빌드의 형태만 있고 실질이 없습니다

`AS build` 스테이지가 하는 일은 **호스트에서 이미 빌드된 jar를 복사하는 것뿐**입니다. Gradle을 실행하지 않으므로, 이미지를 만들려면 사람이 먼저 `./gradlew bootJar`를 돌려야 합니다.

결과:

- **빌드 재현성이 없습니다.** 빌드한 사람의 JDK 버전·Gradle 캐시 상태에 따라 산출물이 달라질 수 있습니다. 이미지는 "누가 언제 어떤 환경에서 빌드했는지" 알 수 없는 jar를 담게 됩니다.
- **`docker build` 하나로 이미지를 만들 수 없습니다.** `build/libs`가 비어 있으면 COPY가 실패합니다. 실제로 지금 이 저장소에는 `build/libs`가 없습니다(확인).
- **스테이지를 나눈 이득이 없습니다.** 멀티스테이지의 목적은 "빌드 도구는 최종 이미지에서 제외"인데, 여기서는 빌드 도구를 쓰지 않으므로 두 스테이지가 그냥 같은 파일을 한 번 더 복사합니다.

### (b) `build/libs/*.jar` 와일드카드가 두 파일에 매칭됩니다

이게 더 실질적인 문제입니다.

Spring Boot Gradle 플러그인은 **2.5부터 `bootJar`와 `jar` 두 태스크를 모두 활성화**합니다. `./gradlew build`를 실행하면 `build/libs`에 두 개가 생깁니다.

```
backend-0.0.1-SNAPSHOT.jar          ← bootJar. 실행 가능한 fat jar
backend-0.0.1-SNAPSHOT-plain.jar    ← jar. 클래스만 든 일반 jar (실행 불가)
```

`build.gradle`에 `jar { enabled = false }`가 **없으므로**(확인) 이 동작이 그대로 적용됩니다.

그리고 Docker의 `COPY`는 **여러 소스를 하나의 파일 대상으로 복사할 수 없습니다.**

```
COPY failed: When using COPY with more than one source file,
the destination must be a directory and end with a /
```

즉 **`./gradlew build` 뒤에 `docker build`를 하면 이미지 빌드가 실패합니다.**

`./gradlew bootJar`만 실행하면 fat jar 하나만 생기므로 성공합니다. 하지만 이전에 `build`를 한 적이 있고 `clean`을 안 했다면 `-plain.jar`가 남아 있어 다시 실패합니다.

`[[확인필요: 실제 배포 시 어떤 Gradle 명령을 쓰는지 — ./gradlew build 인지 bootJar 인지, clean을 하는지]]`

**빌드가 "가끔 되고 가끔 안 되는" 상태**라면 원인이 이것일 가능성이 높습니다.

### 수정 방향

**1단계 — plain jar를 끕니다.** 이 프로젝트는 라이브러리가 아니라 실행 가능한 애플리케이션이므로 plain jar가 필요 없습니다.

```diff
--- a/build.gradle
+++ b/build.gradle
 tasks.named('test') { useJUnitPlatform() }
+
+// 실행 가능한 fat jar만 만든다 (Spring Boot 2.5+ 는 plain jar도 기본 생성)
+tasks.named('jar') { enabled = false }
+
+// 파일명을 고정해 Dockerfile의 와일드카드를 제거
+tasks.named('bootJar') { archiveFileName = 'app.jar' }
```

`archiveFileName`을 고정하면 Dockerfile에서 와일드카드를 쓸 필요가 없어집니다.

**2단계 — 컨테이너 안에서 빌드합니다.** 재현성이 생기고 `docker build` 한 번으로 끝납니다.

```dockerfile
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /workspace

# 의존성 해석에 필요한 파일만 먼저 복사 → 이 레이어는 의존성이 바뀔 때만 무효화
COPY gradlew ./
COPY gradle gradle
COPY build.gradle settings.gradle ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon

# 소스는 그다음. 코드만 바뀌면 위 레이어는 캐시가 유지된다
COPY src src
RUN ./gradlew bootJar --no-daemon -x test
```

`--no-daemon`이 필요한 이유: Gradle 데몬은 빌드 후에도 백그라운드에 남는데, 컨테이너 빌드 레이어에서는 무의미하고 종료를 지연시킵니다.

`-x test`로 테스트를 건너뛴 것은 **테스트가 실제 MySQL을 요구하기 때문**입니다(`11-test.md` P1-1). Testcontainers를 도입하면(`11-test.md` P1-3) 도커 인 도커 문제가 생기므로, **테스트는 이미지 빌드가 아니라 CI 단계에서** 실행하는 것이 맞습니다.

호스트 빌드를 유지하고 싶다면 최소한 1단계는 적용하세요. 와일드카드 충돌만 제거해도 빌드 실패가 사라집니다.

---

## P1-2. 레이어 캐시가 전혀 활용되지 않음 — 코드 한 줄 바뀌어도 전체 jar가 재전송됨

**위치** `Dockerfile:23`

```dockerfile
COPY --from=build /app/app.jar app.jar
```

### 왜 문제인가

Docker 이미지는 **레이어의 적층**이고, 각 레이어는 내용이 바뀌지 않으면 재사용됩니다. 푸시할 때도 **바뀐 레이어만** 전송됩니다.

지금은 애플리케이션 전체가 **jar 파일 하나 = 레이어 하나**입니다. 그 안에는 다음이 모두 들어 있습니다.

- Spring Boot, Hibernate, Jackson, MySQL 드라이버 등 **서드파티 의존성** (거의 안 바뀜)
- Spring Boot Loader 클래스 (버전 올릴 때만 바뀜)
- **여러분이 작성한 코드** (자주 바뀜)

`WindowController.java`의 한 줄만 고쳐도 jar 파일의 내용이 바뀌고, **레이어 전체가 무효화**되어 새로 빌드·푸시·풀됩니다. 자주 바뀌지 않는 의존성까지 매번 함께 움직입니다.

### 수정 방향

**Spring Boot의 layertools를 쓰세요.** fat jar를 성격별 레이어 4개로 분해합니다.

```dockerfile
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /workspace
# ... (P1-1의 빌드 단계)

# fat jar를 레이어별로 분해
RUN java -Djarmode=layertools -jar build/libs/app.jar extract --destination extracted


FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# 변경 빈도가 낮은 것부터 복사 → 앞쪽 레이어일수록 캐시가 오래 유지된다
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
```

**COPY 순서가 캐시 효율을 결정합니다.** Docker는 어떤 레이어가 바뀌면 **그 뒤의 모든 레이어를 무효화**합니다. 그래서 거의 안 바뀌는 `dependencies`를 맨 앞에, 매 빌드 바뀌는 `application`(여러분의 코드)을 맨 뒤에 둡니다.

코드만 고쳤을 때 재빌드·재전송되는 것은 `application` 레이어뿐입니다.

**진입점이 `-jar app.jar`가 아니라 `JarLauncher`로 바뀝니다.** jar를 풀어놓았으므로 fat jar 실행 방식이 아니라 런처를 직접 호출합니다. 클래스 경로가 Spring Boot 3.2부터 `org.springframework.boot.loader.launch.JarLauncher`로 변경되었으니(이전은 `org.springframework.boot.loader.JarLauncher`) 3.4.4인 이 프로젝트는 위 경로가 맞습니다.

개선 폭은 측정하지 않았습니다. 확실한 것은 **의존성 레이어가 코드 변경 시 재사용된다**는 구조적 사실입니다.

---

## P1-3. 런타임 이미지가 JDK — 실행에 필요 없는 개발 도구가 전부 포함됨

**위치** `Dockerfile:1-2`, `:14`

```dockerfile
# Use OpenJDK 17 slim image as base for the build stage      ← 주석과 실제가 다름
FROM eclipse-temurin:17-jdk-jammy AS build
...
FROM eclipse-temurin:17-jdk-jammy                             ← 런타임도 JDK
```

주석은 "slim image"라고 적혀 있지만 실제 태그는 `jdk-jammy`입니다. slim도 아니고 JRE도 아닙니다.

### 왜 문제인가

**(1) 실행에 필요 없는 것이 들어 있습니다.**

JDK 이미지에는 `javac`(컴파일러), `jdb`(디버거), `jlink`, `jshell`, `javadoc` 등이 포함됩니다. 애플리케이션 실행에는 **하나도 필요하지 않습니다** — fat jar는 이미 컴파일된 클래스를 담고 있으니까요.

**(2) 공격 표면이 넓어집니다.**

컨테이너에 침입한 공격자에게 **컴파일러가 있다는 것**은 의미가 큽니다. 익스플로잇 코드를 파일로 올리는 대신 현장에서 컴파일할 수 있고, 정적 분석 기반 탐지를 우회하기 쉬워집니다. 컨테이너 보안의 기본 원칙은 "실행에 필요한 것만 담는다"입니다.

`05-security.md`에서 P0 4건을 찾았고 그중 두 개는 원격에서 트리거 가능하므로, 침입 이후의 피해를 줄이는 조치도 값어치가 있습니다.

**(3) 이미지가 큽니다.**

정확한 크기는 측정하지 않았습니다. 확실한 것은 JRE 이미지가 JDK 이미지의 **부분집합**이라는 점입니다. 실제 차이는 직접 확인하세요.

```bash
docker pull eclipse-temurin:17-jdk-jammy
docker pull eclipse-temurin:17-jre-jammy
docker images eclipse-temurin
```

이미지 크기는 배포 속도(레지스트리 풀 시간)와 디스크 사용량에 직접 영향을 줍니다. `09-logging.md` P1-2에서 본 대로 이 호스트는 디스크 여유가 확인되지 않은 상태입니다.

### 수정 방향

**런타임 스테이지만 JRE로 바꾸면 됩니다.** 빌드 스테이지는 컴파일이 필요하므로 JDK를 유지합니다.

```diff
--- a/Dockerfile
+++ b/Dockerfile
-# Use OpenJDK 17 slim image as base for the build stage
 FROM eclipse-temurin:17-jdk-jammy AS build
 ...
-FROM eclipse-temurin:17-jdk-jammy
+FROM eclipse-temurin:17-jre-jammy
```

**주의**: JRE 이미지에는 `jcmd`, `jstack`, `jmap` 같은 진단 도구도 없습니다. 운영 중 스레드 덤프나 힙 덤프를 뜰 일이 있다면 Actuator의 `threaddump`/`heapdump` 엔드포인트를 쓰거나(`05-security.md` P1-5에 따라 인증 뒤에 두고), 필요 시 JDK 이미지의 사이드카를 붙이세요.

**더 줄이려면** `eclipse-temurin:17-jre-alpine`이나 `jlink`로 커스텀 런타임을 만드는 방법이 있습니다. Alpine은 musl libc를 쓰므로 네이티브 라이브러리 호환성 문제가 생길 수 있어(이 프로젝트에서는 Argon2가 순수 자바 구현이라 괜찮을 것으로 보이지만) 검증이 필요합니다. **먼저 JRE로 바꾸고, 더 필요하면 그때 검토하세요.**

---

## P1-4. 헬스체크가 없고, `depends_on`은 MySQL의 "준비 상태"를 기다리지 않음 — 그리고 헬스체크를 넣으려 해도 401이 납니다

**위치** `Dockerfile`(HEALTHCHECK 없음), `docker-compose.yml:29-30`

```yaml
  app:
    depends_on:
      - mysql
```

### (a) `depends_on`은 컨테이너 시작 순서만 보장합니다

이건 Docker Compose에서 가장 흔한 오해 중 하나입니다.

`depends_on: - mysql`은 **"mysql 컨테이너를 먼저 시작한다"** 만 보장합니다. MySQL **프로세스가 연결을 받을 준비가 됐는지는 기다리지 않습니다.** MySQL 8.0은 첫 기동 시 데이터 디렉터리를 초기화하느라 시간이 걸리는데, 그동안 app 컨테이너는 이미 시작되어 DB에 접속을 시도합니다.

그러면 이렇게 됩니다.

1. app이 부팅하면서 HikariCP가 커넥션을 얻으려 시도 → 실패
2. `application.yml:13`의 `ddl-auto: update`가 스키마를 읽어야 하므로 **SessionFactory 생성 단계에서 실패**
3. 컨텍스트 로딩 실패 → 애플리케이션 종료
4. `docker-compose.yml:41`의 `restart: always`가 재시작
5. MySQL이 준비될 때까지 1~4 반복

운 좋게 몇 번 만에 붙으면 정상 기동한 것처럼 보이지만, **첫 배포나 볼륨을 새로 만든 경우 재시작 루프가 길어집니다.** 그리고 `09-logging.md` P1-2에서 본 대로 로그 로테이션이 없으므로 재시작 로그가 계속 쌓입니다.

### (b) 헬스체크가 어디에도 없습니다

Dockerfile에도 compose에도 `HEALTHCHECK`/`healthcheck:`가 없습니다. 따라서 Docker는 컨테이너의 **프로세스가 살아 있는지**만 알고 **응답할 수 있는지**는 모릅니다.

이 차이가 결정적인 상황이 있습니다. `02-performance.md` P0-1의 무한 루프가 발동해 커넥션 풀이 고갈되면:

- 프로세스는 살아 있습니다 → Docker는 정상으로 판단
- 모든 요청이 30초 대기 후 실패합니다 → 실제로는 장애
- `restart: always`는 **프로세스가 죽어야** 동작하므로 아무 일도 하지 않습니다

**즉 앱이 응답 불능이 되어도 자동 복구되지 않고, 아무도 알지 못합니다.**

### (c) 헬스체크를 추가하려 해도 지금은 401이 납니다

`/actuator/health`가 `SecurityConfig.java:38`에서 permitAll로 선언되어 있지만, `AuthenticationCheckFilter`가 인가 단계보다 **앞에서** 401로 끊습니다(`05-security.md` P1-4). 따라서 헬스체크를 넣으면 **항상 unhealthy**가 됩니다.

**순서가 중요합니다.** `05-security.md` P1-5(actuator 노출 제한) → P1-4(커스텀 필터 제거) 를 먼저 적용해야 헬스체크가 동작합니다.

### 수정 방향

**1단계 — `05-security.md` P1-5, P1-4를 먼저 적용**해서 `/actuator/health`가 200을 반환하게 만듭니다.

**2단계 — MySQL에 헬스체크를, app에 조건부 의존을 겁니다.**

```diff
--- a/docker-compose.yml
+++ b/docker-compose.yml
   mysql:
     image: mysql:8.0
     ...
     restart: always
+    healthcheck:
+      test: ["CMD", "mysqladmin", "ping", "-h", "localhost", "-p${DB_ROOT_PASSWORD}"]
+      interval: 10s
+      timeout: 5s
+      retries: 10
+      start_period: 60s

   app:
     ...
-    depends_on:
-      - mysql
+    depends_on:
+      mysql:
+        condition: service_healthy
```

`condition: service_healthy`가 핵심입니다. MySQL의 헬스체크가 통과할 때까지 app 컨테이너를 **시작하지 않습니다.** 재시작 루프가 사라집니다.

`start_period: 60s`는 첫 기동 시 데이터 디렉터리 초기화 시간을 감안한 유예입니다. 이 구간의 실패는 재시도 횟수에 포함되지 않습니다.

**3단계 — app에도 헬스체크를 겁니다.**

```diff
   app:
     ...
     restart: always
+    healthcheck:
+      test: ["CMD-SHELL", "curl -sf http://localhost:8080/actuator/health || exit 1"]
+      interval: 15s
+      timeout: 3s
+      retries: 3
+      start_period: 60s
```

**`curl`이 이미지에 있어야 합니다.** `eclipse-temurin` 이미지에는 curl이 포함되어 있지만, JRE나 distroless로 바꾸면 없을 수 있습니다. 그 경우 Spring Boot 3.x가 제공하는 방법을 쓰세요.

```dockerfile
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=3 \
  CMD java -cp app.jar org.springframework.boot.loader.launch.JarLauncher --help > /dev/null || exit 1
```

이 방식은 무겁습니다. 더 실용적인 것은 이미지에 curl을 명시적으로 설치하거나, 오케스트레이터(Kubernetes 등)의 HTTP 프로브를 쓰는 것입니다.

**주의**: `restart: always` + 헬스체크만으로는 unhealthy 컨테이너가 자동 재시작되지 **않습니다.** Docker Compose 단독으로는 헬스 상태에 따른 재시작 기능이 없습니다(Swarm이나 Kubernetes에는 있습니다). 헬스체크는 **상태를 관찰 가능하게** 만들고 `depends_on` 조건에 쓰이는 것이 주 용도입니다. 자동 복구가 필요하면 오케스트레이터를 쓰거나 외부 모니터링을 붙이세요.

그래도 **관찰 가능해지는 것 자체가 큰 차이**입니다. `docker compose ps`에서 `unhealthy`가 보이고, 모니터링 도구가 그걸 읽을 수 있습니다.

---

## P1-5. 컨테이너 리소스 제한도, JVM 힙 설정도 없음

**위치** `docker-compose.yml:25-41`, `Dockerfile:29`

app 서비스에 `mem_limit`/`cpus`/`deploy.resources`가 없고, ENTRYPOINT에 `-Xmx`나 `-XX:MaxRAMPercentage`도 없습니다.

### 왜 문제인가

**(1) JVM이 호스트 전체 메모리를 기준으로 힙을 잡습니다.**

JDK 10 이상은 `UseContainerSupport`가 기본 활성이라, 컨테이너에 메모리 제한이 있으면 **그 제한을 기준으로** 최대 힙을 계산합니다(`MaxRAMPercentage` 기본 25%).

문제는 **제한이 없을 때**입니다. 그러면 JVM은 호스트 전체 메모리를 보고 그 25%를 최대 힙으로 잡습니다. 호스트가 8GB면 힙이 2GB까지 자랄 수 있고, 그 메모리를 MySQL과 다투게 됩니다.

**(2) 메모리를 크게 쓰는 경로가 이미 확인되어 있습니다.**

| 경로 | 메모리 | 출처 |
|---|---|---|
| Argon2 비밀번호 검증 | **호출당 16 MiB** | `05-security.md` P1-3 |
| `GET /todos` 상한 없는 목록 | 사용자 데이터 크기에 비례 | `01-jpa-query.md` P1-8 |
| OSIV로 요청 끝까지 유지되는 영속성 컨텍스트 | 읽은 엔티티 × 스냅샷 | `02-performance.md` P1-3 |

Argon2는 특히 직접적입니다. `POST /rooms/{id}/join`은 로그인만 하면 누구나 호출할 수 있고 레이트 리밋이 없으므로(`05-security.md` P1-3), 동시 요청 수 × 16 MiB가 순간 할당됩니다. Tomcat 기본 스레드 수가 200이라 상한이 높습니다.

**(3) OOM Killer가 무엇을 죽일지 예측할 수 없습니다.**

app과 mysql이 같은 호스트에서 제한 없이 메모리를 쓰다가 호스트 메모리가 고갈되면, 리눅스 OOM Killer가 개입합니다. 어느 프로세스가 죽을지는 OOM score에 따라 결정되며, **MySQL이 죽을 수도 있습니다.** 그 경우 데이터 손상 위험이 있습니다.

MySQL 쪽은 `innodb_buffer_pool_size=128M`만 설정되어 있는데(`docker-compose.yml:19`), 이건 InnoDB 버퍼 풀만 제한할 뿐 MySQL 프로세스 전체 메모리 상한이 아닙니다.

### 수정 방향

**두 층 모두에 제한을 겁니다. 컨테이너 제한과 JVM 힙을 함께 정해야 의미가 있습니다.**

```diff
--- a/docker-compose.yml
+++ b/docker-compose.yml
   mysql:
     ...
+    deploy:
+      resources:
+        limits:
+          memory: 512M
+    mem_limit: 512m          # Compose V2 non-swarm 호환

   app:
     ...
+    deploy:
+      resources:
+        limits:
+          memory: 1G
+    mem_limit: 1g
     environment:
+      JAVA_TOOL_OPTIONS: "-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
```

**`-Xmx` 대신 `MaxRAMPercentage`를 쓰는 이유**: 컨테이너 메모리 제한을 바꿀 때 JVM 옵션을 함께 고칠 필요가 없습니다. 제한의 70%를 힙으로 쓰고, 나머지 30%는 메타스페이스·스레드 스택·다이렉트 버퍼·Argon2 같은 힙 외 메모리에 남깁니다.

**`ExitOnOutOfMemoryError`가 중요합니다.** 기본 동작은 `OutOfMemoryError`가 나도 JVM이 계속 실행되는 것인데, 그 상태의 애플리케이션은 대개 정상 동작하지 못하면서 살아 있습니다 — P1-4 (b)에서 본 "프로세스는 살아 있는데 응답 불능"과 같은 상황입니다. 즉시 종료시키면 `restart: always`가 복구합니다.

`JAVA_TOOL_OPTIONS` 환경변수를 쓰면 Dockerfile의 ENTRYPOINT를 고치지 않고 주입할 수 있습니다. JVM이 시작 시 이 값을 자동으로 읽습니다.

**구체적 수치(512M / 1G / 70%)는 이 서비스의 실제 사용량을 모르고 제안한 출발점입니다.** 적용 후 `docker stats`로 관찰하고 조정하세요.

```bash
docker stats camstudy-backend mysql
```

`[[확인필요: 운영 호스트의 총 메모리와 현재 두 컨테이너의 실사용량 — free -h, docker stats]]`

---

## P2-1. `:latest` 태그 — 어떤 버전이 떠 있는지 알 수 없고 롤백할 수 없음

**위치** `docker-compose.yml:26`

```yaml
    image: yejiii1997/cmas:latest
```

### 왜 문제인가

**(1) 롤백 경로가 없습니다.** 배포 후 문제가 생겨도 "이전 버전으로 되돌린다"를 할 수 없습니다. 레지스트리에 있는 것은 `latest` 하나뿐이고, 이미 덮어써졌습니다.

**(2) 무엇이 떠 있는지 알 수 없습니다.** 실행 중인 컨테이너의 이미지 태그가 `latest`이므로, 그게 어제 빌드인지 지난달 빌드인지 구분할 방법이 없습니다. `banner.txt`에 `THIS IS THE LATEST CODE`를 넣은 것(`09-logging.md` P2-3)이 이 문제를 우회하려던 흔적으로 보이는데, 그 문구도 버전과 무관하게 항상 같습니다.

**(3) `docker compose up`이 새 이미지를 가져올지 예측할 수 없습니다.** 로컬에 같은 이름의 이미지가 있으면 그걸 씁니다. `docker compose pull`을 따로 하지 않으면 옛 이미지로 계속 실행될 수 있습니다.

### 수정 방향

**불변 태그를 쓰세요.** `build.gradle:47`의 `springBoot { buildInfo() }`가 이미 버전 정보를 만들고 있으므로 활용할 수 있습니다.

```yaml
    image: yejiii1997/cmas:${APP_VERSION:-latest}
```

```bash
# 배포 스크립트
export APP_VERSION=$(git rev-parse --short HEAD)
docker build -t yejiii1997/cmas:$APP_VERSION .
docker push yejiii1997/cmas:$APP_VERSION
docker compose up -d
```

git 커밋 해시를 태그로 쓰면 **이미지와 소스가 1:1로 연결**됩니다. 문제가 생기면 그 해시로 코드를 바로 찾을 수 있고, 이전 해시 태그로 롤백할 수 있습니다.

`latest`도 함께 태깅해두면(`docker tag`) 편의성은 유지하면서 이력이 남습니다.

---

## P2-2. root로 실행되고, `.dockerignore`가 없음

### (a) 컨테이너가 root로 실행됩니다

`Dockerfile`에 `USER` 지시어가 없습니다(확인). 따라서 JVM 프로세스가 **root(uid 0)** 로 실행됩니다.

애플리케이션은 root 권한이 필요하지 않습니다 — 8080 포트는 1024 이상이라 비특권 사용자도 바인딩할 수 있고, 파일 쓰기도 하지 않습니다.

root로 실행하면 컨테이너 런타임 취약점이나 잘못된 볼륨 마운트가 있을 때 피해 범위가 커집니다.

```diff
--- a/Dockerfile
+++ b/Dockerfile
 FROM eclipse-temurin:17-jre-jammy
 WORKDIR /app
 
+# 비루트 사용자 생성
+RUN groupadd --system --gid 1001 app \
+ && useradd --system --uid 1001 --gid app app
+
 COPY --from=build /app/app.jar app.jar
+RUN chown -R app:app /app
+USER app
 
 EXPOSE 8080
```

`chown`이 필요한 이유: `WORKDIR`과 복사된 파일이 root 소유이므로, 비루트 사용자로 전환하면 읽기는 되지만 쓰기가 필요한 경우(임시 파일, 힙 덤프) 실패합니다.

### (b) `.dockerignore`가 없습니다

파일이 존재하지 않습니다(확인). `docker build`는 **빌드 컨텍스트 전체**를 Docker 데몬으로 전송하므로, 다음이 모두 포함됩니다.

| 디렉터리 | 내용 |
|---|---|
| `.git/` | **커밋 히스토리 전체** — `05-security.md` P0-3의 DB 자격증명 포함 |
| `build/` | Gradle 산출물 |
| `.gradle/` | Gradle 캐시 |
| `docs/` | 이 리뷰 문서 (P0 취약점 재현 절차 포함) |

**이것들이 최종 이미지에 들어가지는 않습니다** — `COPY` 지시어가 명시한 것만 이미지에 담깁니다. 하지만 두 가지 문제가 있습니다.

1. **빌드가 느려집니다.** 컨텍스트 전송량이 커지고, 컨텍스트가 바뀔 때마다 캐시 판정에 영향을 줍니다.
2. **P1-1 2단계(컨테이너 안에서 빌드)로 전환하면 위험해집니다.** 그때는 `COPY src src` 같은 지시어를 쓰게 되는데, 실수로 `COPY . .`를 쓰면 `.git`이 이미지에 들어갑니다. 저장소가 PUBLIC이므로 히스토리 자체는 이미 공개돼 있지만, 이미지에까지 담을 이유는 없습니다.

```
# .dockerignore (신규)
.git
.gitignore
.gradle
build
docs
.idea
.vscode
*.md
.env
.env.*
docker-compose*.yml
```

`.env`를 넣는 것이 중요합니다 — 컨텍스트에 포함되면 `COPY . .` 실수 시 **시크릿이 이미지에 구워집니다.**

---

## P2-3. ENTRYPOINT에 다른 프로젝트의 잔재가 남아 있음

**위치** `Dockerfile:29`

```dockerfile
ENTRYPOINT ["java",
            "-Djava.security.egd=file:/dev/./urandom",
            "-Dotel.resource.attributes=service.name=auth-server",
            "-jar", "app.jar"]
```

### (a) `-Dotel.resource.attributes=service.name=auth-server`

**OpenTelemetry 관련 의존성이 프로젝트에 하나도 없습니다**(grep 0건 확인). `build.gradle`에 OTel SDK도, `micrometer-tracing`도, javaagent 설정도 없습니다.

즉 이 시스템 프로퍼티는 **아무것도 하지 않습니다.**

그리고 값이 `service.name=auth-server`입니다. 이 프로젝트의 이름은 `backend`(`settings.gradle`)이고 도메인은 camstudy입니다. **다른 프로젝트(인증 서버)의 Dockerfile을 복사해 온 흔적**으로 보입니다.

지금은 무해하지만, 나중에 OTel 에이전트를 붙이면 **모든 트레이스가 `auth-server`라는 이름으로 수집됩니다.** 다른 서비스와 섞이거나 엉뚱한 대시보드에 들어갑니다.

### (b) `-Djava.security.egd=file:/dev/./urandom`

JDK 8 시절 관용구입니다. 당시 `SecureRandom`이 `/dev/random`을 읽으면서 엔트로피 부족 시 **블로킹**되어 부팅이 느려지는 문제가 있었고, 이 옵션으로 우회했습니다.

JDK 17에서는 필요 없습니다. 리눅스 기본 `SecureRandom` 구현이 이미 논블로킹 소스를 사용합니다.

무해하지만, `file:/dev/./urandom`이라는 이상한 경로 표기(`/dev/./urandom`)까지 그대로 복사되어 있어 **의미를 모른 채 옮겨온 것**임이 드러납니다.

### (c) 잘 되어 있는 점

`ENTRYPOINT`가 **exec 형태**(JSON 배열)입니다. shell 형태(`ENTRYPOINT java -jar app.jar`)로 쓰면 `/bin/sh -c`가 PID 1이 되고 java는 자식 프로세스가 되어, **`docker stop`의 SIGTERM이 java에 전달되지 않습니다.** exec 형태는 java가 직접 PID 1이 되므로 시그널을 받습니다. 이건 올바릅니다.

### 수정 방향

```diff
--- a/Dockerfile
+++ b/Dockerfile
-ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom",
-            "-Dotel.resource.attributes=service.name=auth-server",
-            "-jar", "app.jar"]
+ENTRYPOINT ["java", "-jar", "app.jar"]
```

JVM 옵션이 필요해지면 `JAVA_TOOL_OPTIONS` 환경변수로 주입하세요(P1-5). 이미지를 다시 빌드하지 않고 조정할 수 있습니다.

나중에 OTel을 도입한다면 서비스 이름을 올바르게 지정하세요.

```yaml
    environment:
      OTEL_SERVICE_NAME: camstudy-backend
```

---

## P2-4. graceful shutdown이 꺼져 있어 배포 시 진행 중인 요청이 끊김

**위치** `src/main/resources/application.yml:19-21`

`server.shutdown` 설정이 없으므로(확인) 기본값 **`immediate`** 입니다.

### 왜 문제인가

`docker compose up -d`로 재배포하면 기존 컨테이너에 SIGTERM이 전달되고, Spring Boot가 컨텍스트를 닫습니다. `immediate` 모드에서는 **처리 중인 요청을 기다리지 않고 즉시 종료**합니다.

그 시점에 진행 중이던 요청은 클라이언트 쪽에서 연결이 끊긴 것으로 나타납니다. 그리고 트랜잭션이 커밋 직전이었다면 롤백됩니다 — `08-concurrency.md`에서 본 것처럼 여러 단계로 나뉜 작업(예: `POST /rooms/{id}/join`이 트랜잭션 5개)은 **부분만 커밋된 상태로 남을 수 있습니다.**

### 수정 방향

```diff
--- a/src/main/resources/application.yml
+++ b/src/main/resources/application.yml
 server:
   address: 0.0.0.0
   port: 8080
+  shutdown: graceful
+
+spring:
+  lifecycle:
+    timeout-per-shutdown-phase: 20s
```

`graceful`로 두면 SIGTERM 수신 후 **새 요청은 거부하고 진행 중인 요청은 끝날 때까지 기다린 뒤** 종료합니다. `timeout-per-shutdown-phase`가 최대 대기 시간입니다.

**Docker의 종료 유예 시간도 함께 맞춰야 합니다.** Docker는 SIGTERM 후 기본 10초를 기다리고 SIGKILL을 보냅니다. 애플리케이션 유예가 20초인데 Docker가 10초에 죽이면 의미가 없습니다.

```diff
--- a/docker-compose.yml
+++ b/docker-compose.yml
   app:
     ...
     restart: always
+    stop_grace_period: 30s
```

**주의**: `02-performance.md` P0-1의 무한 루프에 걸린 요청이 있으면 graceful shutdown이 그 요청을 기다리다 타임아웃까지 소모합니다. 그 버그를 먼저 고치는 것이 순서입니다.

---

## P2-5. compose 파일의 사소한 정리 — `version` 필드와 로깅 드라이버

### (a) `version: "3.9"`가 obsolete입니다

**위치** `docker-compose.yml:1`

Docker Compose V2부터 `version` 필드는 무시되며, 최근 버전은 실행 시 경고를 출력합니다.

```
WARN[0000] docker-compose.yml: `version` is obsolete
```

```diff
--- a/docker-compose.yml
+++ b/docker-compose.yml
-version: "3.9"
-
 services:
```

### (b) 로깅 드라이버 옵션이 없습니다

`09-logging.md` P1-2에서 상세히 다뤘습니다. 배포 관점에서 요약하면: **컨테이너 로그가 무제한 누적되고, MySQL 데이터 볼륨과 같은 파일시스템이므로 디스크가 차면 DB 쓰기가 실패합니다.**

```diff
   app:
     ...
+    logging:
+      driver: json-file
+      options:
+        max-size: "20m"
+        max-file: "5"

   mysql:
     ...
+    logging:
+      driver: json-file
+      options:
+        max-size: "20m"
+        max-file: "3"
```

### (c) `platform: linux/amd64` 고정

**위치** `docker-compose.yml:6`, `:27`

두 서비스 모두 amd64로 고정되어 있습니다. Apple Silicon 같은 arm64 머신에서는 에뮬레이션으로 동작해 느려집니다.

운영 호스트가 amd64라면 그대로 두어도 무방합니다. 다만 개발 머신이 arm64인 팀원이 있으면 `docker-compose.override.yml`(`10-config.md` P2-3)에서 제거하는 것이 낫습니다.

`[[확인필요: 운영 호스트와 개발 머신의 아키텍처]]`

---

## 정리한 Dockerfile 예시

위 항목들을 반영한 형태입니다. 그대로 쓰기보다 **적용 순서**를 참고하세요 — P1-4는 `05-security.md`의 선행 작업이 필요합니다.

```dockerfile
# ---------- build ----------
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /workspace

# 의존성 레이어 (build.gradle이 바뀔 때만 무효화)
COPY gradlew ./
COPY gradle gradle
COPY build.gradle settings.gradle ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon

# 소스 레이어
COPY src src
RUN ./gradlew bootJar --no-daemon -x test

# fat jar를 레이어별로 분해
RUN java -Djarmode=layertools -jar build/libs/app.jar extract --destination extracted

# ---------- runtime ----------
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

RUN groupadd --system --gid 1001 app \
 && useradd --system --uid 1001 --gid app app

# 변경 빈도가 낮은 것부터
COPY --from=build --chown=app:app /workspace/extracted/dependencies/ ./
COPY --from=build --chown=app:app /workspace/extracted/spring-boot-loader/ ./
COPY --from=build --chown=app:app /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /workspace/extracted/application/ ./

USER app
EXPOSE 8080

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
```

`build.gradle`에 다음이 함께 필요합니다.

```groovy
tasks.named('jar') { enabled = false }
tasks.named('bootJar') { archiveFileName = 'app.jar' }
```

**이 Dockerfile은 실행해 검증하지 않았습니다.** 특히 `JarLauncher` 클래스 경로(Spring Boot 3.2에서 변경됨)와 layertools 추출 경로는 실제 빌드로 확인하세요.

---

## 요약

| 심각도 | 개수 | 항목 |
|---|---|---|
| **P0** | 0 | — |
| **P1** | 5 | 빌드 스테이지가 빌드하지 않음 + plain jar 와일드카드 충돌로 `docker build` 실패 가능 / 레이어 캐시 미활용 (코드 한 줄에 전체 jar 재전송) / 런타임 이미지가 JDK (불필요한 컴파일러 포함) / 헬스체크 부재 + `depends_on`이 준비 상태를 안 기다림 + `/actuator/health`가 401 / 컨테이너 리소스 제한·JVM 힙 미설정 |
| **P2** | 5 | `:latest` 태그로 롤백 불가 / root 실행 + `.dockerignore` 없음 / ENTRYPOINT에 다른 프로젝트 잔재 (OTel `auth-server`, JDK 8용 egd) / graceful shutdown 미설정 / compose `version` obsolete + 로깅 드라이버 옵션 없음 |

## 사용자가 물은 항목에 대한 직접 답

| 질문 | 답 |
|---|---|
| 레이어 캐시 | **전혀 활용되지 않습니다.** fat jar 하나가 레이어 하나라, 코드 한 줄만 바뀌어도 의존성까지 포함한 전체가 재빌드·재전송됩니다. Spring Boot layertools로 4개 레이어로 분리하면 의존성 레이어가 재사용됩니다. |
| 이미지 크기 | **런타임이 JRE가 아니라 JDK입니다** (`17-jdk-jammy`). `javac`·`jdb`·`jshell` 등 실행에 불필요한 개발 도구가 전부 포함됩니다. 주석은 "slim image"라고 적혀 있지만 실제는 slim도 아닙니다. **크기는 측정하지 않았습니다** — `docker images`로 확인하세요. |
| 헬스체크 | **Dockerfile·compose 양쪽 모두 없습니다.** 그리고 `depends_on: - mysql`은 **컨테이너 시작 순서만** 보장하고 MySQL 준비 상태는 기다리지 않아, 첫 기동 시 재시작 루프가 생깁니다. 헬스체크를 추가하려 해도 `/actuator/health`가 현재 **401**을 반환하므로(`05-security.md` P1-4) 그것부터 고쳐야 합니다. |
| 컨테이너 리소스 | **제한이 없습니다.** JVM도 `-Xmx`가 없어 호스트 전체 메모리의 25%를 최대 힙으로 잡습니다. Argon2가 호출당 16 MiB를 쓰고 레이트 리밋이 없으므로(`05-security.md` P1-3) 메모리 압박 경로가 실재합니다. app과 mysql이 제한 없이 다투다 OOM Killer가 **MySQL을 죽일 수도** 있습니다. |
| 로그 설정 | **로깅 드라이버 옵션이 없습니다** → Docker 기본 `json-file`에 **무제한** 누적. MySQL 데이터 볼륨과 같은 파일시스템이라 디스크가 차면 DB 쓰기가 실패합니다. 상세는 `09-logging.md` P1-2. |

## 확인이 필요한 항목 (3개)

1. **실제 배포 시 쓰는 Gradle 명령** — `./gradlew build`인지 `bootJar`인지, `clean`을 하는지 (P1-1 (b)의 빌드 실패 여부)
2. **운영 호스트의 총 메모리와 컨테이너 실사용량** — `free -h`, `docker stats` (P1-5의 제한값 결정)
3. **운영 호스트와 개발 머신의 CPU 아키텍처** — `platform: linux/amd64` 고정이 적절한지

## 적용 순서

```
1. build.gradle: jar 비활성 + bootJar 파일명 고정 (P1-1 1단계)  ← 빌드 실패 제거
2. .dockerignore 추가 (P2-2b)                                    ← 파일 하나
3. compose에 logging 옵션 (P2-5b / 09 P1-2)                      ← 디스크 고갈 차단
4. compose에 mem_limit + JAVA_TOOL_OPTIONS (P1-5)
5. compose version 제거, :latest → 커밋 해시 태그 (P2-5a, P2-1)
6. 런타임 이미지를 JRE로, USER 추가, ENTRYPOINT 정리 (P1-3, P2-2a, P2-3)
7. 05 P1-5 → P1-4 적용해 /actuator/health를 200으로            ← 8번의 전제
8. mysql healthcheck + depends_on condition + app healthcheck (P1-4)
9. layertools 다단계 빌드 (P1-1 2단계, P1-2)                     ← 가장 큰 구조 변경
10. graceful shutdown + stop_grace_period (P2-4)                 ← 02 P0-1 수정 후
```

**1~3번은 각각 파일 하나를 건드리고 위험이 없습니다.** 특히 1번은 빌드가 간헐적으로 실패하는 원인일 수 있으므로 먼저 확인하세요.

`docker build`나 `docker compose up`을 실행하지 않았습니다. 위 판단은 Dockerfile·compose 파일의 내용과 Docker/Spring Boot의 문서화된 동작에 근거한 것이며, 특히 P1-1 (b)의 빌드 실패는 실제로 `./gradlew build && docker build .`를 실행해 확인해야 합니다.
