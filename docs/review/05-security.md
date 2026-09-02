# 05. 보안 (5단계)

조사 범위: Security 설정의 permitAll 범위, 인증/인가 누락 엔드포인트, 토큰 만료·갱신, 비밀번호 해싱, 커밋된 시크릿, CORS.

## grep으로 확정한 사실

| 확인 항목 | 결과 |
|---|---|
| `@PreAuthorize` / `@Secured` / `hasRole` / `@RolesAllowed` | **0건** — 메서드 수준 인가가 전혀 없음 |
| `configureClientInboundChannel` / `ChannelInterceptor` | **0건** — STOMP 채널 인증 인터셉터 없음 |
| `Role.HOST` / `Role.MEMBER`가 인가 판단에 쓰인 곳 | **0건** (`RoomController.java:70`은 응답 문자열 생성용) |
| 추적 중인 `.env` / secret 파일 | 0건 |
| `.gitignore`에 `.env` 항목 | **없음** |
| 원격 저장소 | GitHub `sabit1997/cam-study-back` — **PUBLIC** (`gh repo view`로 확인) |

**저장소가 공개되어 있습니다.** 따라서 아래 P0-3(구 P1-6)의 커밋된 DB 자격증명은 **이미 외부에 노출된 것으로 간주해야 합니다.**

**민감 파일 취급**: 이 리포트는 `.env`나 시크릿 값을 출력하지 않습니다. git 히스토리에서 발견한 하드코딩 자격증명은 "어떤 커밋의 어떤 키"까지만 기록합니다.

---

# P0

## P0-1. `/rooms` 계열이 인증 주체를 무시하고 요청 body의 `userId`를 신뢰 — 임의 사칭·임의 강퇴 가능

**위치** `controller/RoomController.java:29-33`, `:63-72`, `:74-80` / `controller/RoomAdminController.java:16-23`

```java
// RoomController.java:29-33
    @PostMapping
    public RoomDto create(@RequestBody CreateRoomReq req) {
        var room = roomService.create(req.name(), req.capacity(), req.password(), req.ownerId());
        //                                                                        ^^^^^^^^^^^^^ body
```

```java
// RoomController.java:63-72
    @PostMapping("/{roomId}/join")
    public JoinRes join(@PathVariable String roomId, @RequestBody JoinReq req) {
        ...
        var member = roomService.upsertActiveMember(room, req.userId());   // ← body
```

```java
// RoomController.java:74-80
    public record LeaveReq(String userId) {}
    @PostMapping("/{roomId}/leave")
    public void leave(@PathVariable String roomId, @RequestBody LeaveReq req) {
        ...
        roomService.deactivateMemberIfExists(room, req.userId());          // ← body
```

```java
// RoomAdminController.java:16-23
    public record KickReq(String userId) {}

    @PostMapping("/kick")
    public ResponseEntity<Void> kick(@PathVariable String roomId, @RequestBody KickReq req) {
        Room room = roomService.getOrThrow(roomId);
        roomService.deactivateMemberIfExists(room, req.userId());          // ← body, 권한 검사 0줄
        return ResponseEntity.noContent().build();
    }
```

이 네 개 엔드포인트에 `@AuthenticationPrincipal`이 **하나도 없습니다.** `Timer`·`Todo`·`Window` 컨트롤러는 전부 `@AuthenticationPrincipal String userEmail`을 쓰는데(`00-overview.md`의 엔드포인트 표), 룸 계열만 방식이 다릅니다.

### 왜 문제인가

**인증(authentication)과 인가(authorization)의 차이가 무너져 있습니다.**

- **인증**은 통과합니다. `/rooms/**`는 permitAll이 아니고(`SecurityConfig.java:34-39`), `AuthenticationCheckFilter`가 토큰 없는 요청을 401로 막습니다. 그래서 이 API를 부르려면 **유효한 로그인 토큰이 필요합니다.**
- **인가**는 없습니다. 서버는 "누가 요청했는가"(토큰의 `sub`)를 확인해 놓고도, "누구에 대한 작업인가"(body의 `userId`)를 요청자가 자유롭게 정하도록 허용합니다.

즉 **로그인한 사용자 아무나** 다음을 할 수 있습니다. 특별한 도구도 필요 없고 `curl` 한 줄입니다.

| 공격 | 방법 | 결과 |
|---|---|---|
| 타인 사칭 입장 | `POST /rooms/{id}/join` body에 `{"userId":"victim@x.com"}` | 피해자 이름으로 방에 들어가 있는 것으로 표시됨 |
| 타인 강제 퇴장 | `POST /rooms/{id}/leave` body에 피해자 `userId` | 피해자가 참가자 목록에서 사라짐 |
| **임의 강퇴** | `POST /rooms/{anyRoomId}/admin/kick` body에 피해자 `userId` | **아무 방에서든 아무나 강퇴 가능** |
| 소유권 위조 | `POST /rooms` body에 `{"ownerId":"victim@x.com"}` | 피해자가 만든 방으로 위장 |
| 방 소유권 탈취 | `ownerId`가 `null`인 방에 먼저 join | `RoomService.java:71-75`가 HOST 부여 |

`kick`이 가장 심각합니다. 경로가 `/rooms/{roomId}/admin/kick`이라 "admin" 의미가 담겨 있지만, **그 의미를 강제하는 코드가 한 줄도 없습니다.** 방장인지, 그 방의 멤버이긴 한지, 확인하지 않습니다. `Role.HOST` 열거형이 있지만 인가에 쓰이는 곳이 grep으로 0건입니다 — 응답 문자열을 만들 때만 읽습니다(`RoomController.java:70`).

이 부류의 취약점을 OWASP에서 Broken Access Control(또는 IDOR)로 분류하고, 실무에서 가장 흔하고 가장 쉽게 악용되는 유형입니다. **"URL을 모르면 안전하다"는 방어가 되지 않습니다** — `GET /rooms`가 방 id 목록을 전부 돌려주고(그것도 인가 검사 없이), userId는 이메일이므로 추측 가능합니다.

### 수정 방향

**모든 사용자 식별자를 body에서 제거하고 토큰에서만 가져옵니다.** body에 들어온 `userId`는 값이 아니라 공격 표면입니다.

```diff
--- a/src/main/java/com/camstudy/backend/dto/room/JoinReq.java
+++ b/src/main/java/com/camstudy/backend/dto/room/JoinReq.java
 public record JoinReq(
-        String userId,
         String password
 ) { }
```

```diff
--- a/src/main/java/com/camstudy/backend/controller/RoomController.java
+++ b/src/main/java/com/camstudy/backend/controller/RoomController.java
     @PostMapping("/{roomId}/join")
-    public JoinRes join(@PathVariable String roomId, @RequestBody JoinReq req) {
+    public JoinRes join(@PathVariable String roomId,
+                        @RequestBody JoinReq req,
+                        @AuthenticationPrincipal String userEmail) {
-        var member = roomService.join(roomId, req.userId(), req.password());
+        var member = roomService.join(roomId, userEmail, req.password());
```

```diff
     @PostMapping("/{roomId}/leave")
-    public void leave(@PathVariable String roomId, @RequestBody LeaveReq req) {
-        var room = roomService.getOrThrow(roomId);
-        roomService.deactivateMemberIfExists(room, req.userId());
+    public void leave(@PathVariable String roomId,
+                      @AuthenticationPrincipal String userEmail) {
+        roomService.leave(roomId, userEmail);
     }
```

`LeaveReq` record와 `CreateRoomReq.ownerId`도 함께 제거합니다. `create`는 `ownerId`를 `userEmail`로 채웁니다.

**`kick`은 방장 확인이 필요합니다.** 이건 제거가 아니라 추가입니다 — `userId`(강퇴 대상)는 body에 있어야 하고, **요청자**가 방장인지 검증해야 합니다.

```diff
--- a/src/main/java/com/camstudy/backend/controller/RoomAdminController.java
+++ b/src/main/java/com/camstudy/backend/controller/RoomAdminController.java
     @PostMapping("/kick")
-    public ResponseEntity<Void> kick(@PathVariable String roomId, @RequestBody KickReq req) {
-        Room room = roomService.getOrThrow(roomId);
-        roomService.deactivateMemberIfExists(room, req.userId());
+    public ResponseEntity<Void> kick(@PathVariable String roomId,
+                                     @RequestBody KickReq req,
+                                     @AuthenticationPrincipal String userEmail) {
+        roomService.kick(roomId, userEmail, req.userId());
         return ResponseEntity.noContent().build();
     }
```

```diff
--- a/src/main/java/com/camstudy/backend/service/RoomService.java
+++ b/src/main/java/com/camstudy/backend/service/RoomService.java
+    @Transactional
+    public void kick(String roomId, String requesterId, String targetUserId) {
+        Room room = getOrThrow(roomId);
+        if (!requesterId.equals(room.getOwnerId())) {
+            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not room owner");
+        }
+        if (requesterId.equals(targetUserId)) {
+            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cannot kick yourself");
+        }
+        deactivateMemberIfExists(room, targetUserId);
+    }
```

`ownerId` 대신 `Member.role == HOST`로 검사하는 것도 가능하지만, `03-transaction.md` P1-1에서 본 대로 **HOST 멤버가 없는 방이 만들어질 수 있으므로** 현재는 `room.getOwnerId()`가 더 안전한 기준입니다. 그 문제를 먼저 고친 뒤 role 기반으로 옮기세요.

**주의**: 지금 `Member.userId`에는 프론트가 보낸 값이 그대로 저장되어 있습니다. 토큰의 이메일로 바꾸면 **기존 `members` 행과 매칭되지 않습니다.**
`[[확인필요: members.user_id에 현재 어떤 형식의 값이 들어 있는지 (이메일인지 별도 id인지) — SELECT DISTINCT user_id FROM members LIMIT 20]]`
형식이 다르면 데이터 이관이 필요합니다.

---

## P0-2. WebSocket의 Origin 검사가 비활성화 — 임의 사이트가 피해자 세션으로 채팅을 감청·사칭할 수 있음

**위치** `config/WsConfig.java:12-16`, `controller/ChatController.java:14-19`

```java
// WsConfig.java:12-16
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*");   // ← Origin 검사 무력화
    }
```

```java
// ChatController.java:14-19
    @MessageMapping("/rooms/{roomId}/chat")
    @SendTo("/topic/rooms/{roomId}")
    public ChatMessage chat(@DestinationVariable String roomId, ChatMessage msg) {
        // TODO: 길이 제한, 금칙어, XSS 방지(plain text) 등
        return msg;                                                  // ← 검증 없이 그대로 브로드캐스트
    }
```

### 왜 문제인가 — WebSocket에는 CORS가 적용되지 않습니다

이게 핵심이고, 프론트 개발자가 가장 오해하기 쉬운 지점입니다.

`fetch`로 다른 도메인의 API를 부르면 브라우저가 CORS를 강제합니다. 서버가 `Access-Control-Allow-Origin`을 주지 않으면 **응답을 읽을 수 없습니다.** 그래서 "CORS 설정이 방어해 준다"는 감각이 생깁니다.

**WebSocket에는 그 메커니즘이 없습니다.** `new WebSocket("wss://api.example.com/ws")`는

- preflight를 보내지 않습니다
- `Access-Control-Allow-Origin` 응답 헤더를 확인하지 않습니다
- 그런데 **대상 호스트의 쿠키는 그대로 첨부합니다**

브라우저가 하는 일은 `Origin` 헤더를 요청에 실어 보내는 것뿐이고, **그 값을 검사할 책임은 전적으로 서버에 있습니다.** Spring은 이걸 위해 `setAllowedOrigins`/`setAllowedOriginPatterns`를 제공하고, 기본값은 동일 출처만 허용입니다. `"*"`는 그 검사를 끄는 설정입니다.

### 구체적 공격 흐름

전제: 피해자가 서비스에 로그인해 있고(`AccessToken` 쿠키 보유), 공격자 페이지를 방문합니다.

1. 공격자 페이지의 JS가 `new WebSocket("wss://<api-host>/ws")`를 실행합니다.
2. 브라우저가 핸드셰이크 HTTP GET을 보냅니다. **쿠키가 첨부됩니다** — `AuthService.java:42`가 쿠키를 `SameSite=None`으로 설정했으므로 cross-site 요청에도 붙습니다.
3. 서블릿 필터 체인을 통과합니다. `JwtFilter`가 쿠키의 토큰을 검증해 `SecurityContext`를 채우고, `AuthenticationCheckFilter`는 인증됐으므로 통과시킵니다. → **핸드셰이크 성공.**
4. `Origin` 헤더는 공격자 도메인이지만 `setAllowedOriginPatterns("*")`이라 거부되지 않습니다.
5. 공격자 JS가 STOMP `CONNECT` 후 `SUBSCRIBE`합니다.

여기서 두 가지가 더 나쁘게 작용합니다.

**(a) 룸 멤버십 검사가 없습니다.** `configureClientInboundChannel`이 없으므로(grep 0건) 어떤 구독도 검사되지 않습니다. 공격자는 `/topic/rooms/{임의의 roomId}`를 구독할 수 있습니다. roomId는 `GET /rooms`로 알 수 있고(그 API도 인가 검사가 없습니다 — P0-1), UUID라 추측은 어렵지만 목록 API가 열려 있어 무의미합니다.

**(b) 브로커가 목적지 패턴 구독을 허용합니다.** `WsConfig.java:23`의 `enableSimpleBroker`는 인메모리 `SimpleBrokerMessageHandler`를 쓰고, 그 구독 레지스트리는 목적지를 `AntPathMatcher`로 매칭합니다. 즉 **`SUBSCRIBE /topic/rooms/*` 또는 `/topic/**` 한 번으로 서비스 전체의 모든 방 채팅을 받을 수 있습니다.**
`[[확인필요: 실제로 /topic/** 와일드카드 구독이 모든 메시지를 수신하는지 — 실행해 확인하지 않았습니다]]`

**(c) 사칭 발신이 가능합니다.** `ChatMessage`(`dto/chat/ChatMessage.java`)는 `roomId, userId, content, ts`를 담은 record이고, `ChatController`는 **`msg`를 그대로 반환**합니다. `userId`가 페이로드에서 오므로 아무 이름으로나 메시지를 보낼 수 있습니다.

핸드셰이크 시점에 `JwtFilter`가 `SecurityContext`를 채우므로 STOMP 세션에는 `Principal`이 전달됩니다 — **쓸 수 있는 인증 주체가 있는데도 페이로드를 신뢰하고 있습니다.**

**(d) `content` 길이·내용 검증이 없습니다.** TODO 주석이 이미 인정하고 있습니다. 임의 길이 문자열이 브로커 메모리에 올라가고 모든 구독자에게 전달됩니다. XSS는 프론트의 렌더링 방식에 달렸지만, 서버가 원문을 그대로 중계하므로 방어선이 하나 없는 상태입니다.

### 수정 방향

**1단계 — Origin 검사 복구 (한 줄, 가장 중요)**

```diff
--- a/src/main/java/com/camstudy/backend/config/WsConfig.java
+++ b/src/main/java/com/camstudy/backend/config/WsConfig.java
     public void registerStompEndpoints(StompEndpointRegistry registry) {
-        registry.addEndpoint("/ws").setAllowedOriginPatterns("*");
+        registry.addEndpoint("/ws").setAllowedOrigins(
+                "https://oeyo-cam.site",
+                "https://www.oeyo-cam.site",
+                "http://localhost:3000"
+        );
     }
```

허용 목록을 `SecurityConfig.corsConfigurationSource()`(`:53-58`)와 **한 곳에서 관리**하세요. 지금은 두 곳에 따로 적혀 있고 내용이 다릅니다(한쪽은 `*`). `application.yml`의 프로퍼티로 빼서 양쪽이 같은 값을 읽게 하는 게 좋습니다 → `10-config.md`.

**2단계 — 발신자를 페이로드가 아니라 세션에서**

```diff
--- a/src/main/java/com/camstudy/backend/controller/ChatController.java
+++ b/src/main/java/com/camstudy/backend/controller/ChatController.java
     @MessageMapping("/rooms/{roomId}/chat")
     @SendTo("/topic/rooms/{roomId}")
-    public ChatMessage chat(@DestinationVariable String roomId, ChatMessage msg) {
-        return msg;
+    public ChatMessage chat(@DestinationVariable String roomId,
+                            ChatMessage msg,
+                            Principal principal) {
+        String content = msg.content();
+        if (content == null || content.isBlank() || content.length() > 500) {
+            throw new IllegalArgumentException("invalid chat content");
+        }
+        // userId, roomId, ts를 클라이언트 값으로 받지 않고 서버에서 채운다
+        return new ChatMessage(roomId, principal.getName(), content, System.currentTimeMillis());
     }
```

`ts`도 서버 시각으로 채우세요 — 클라이언트 시계를 신뢰하면 메시지 순서를 조작할 수 있습니다.

**3단계 — 구독 인가 (STOMP 채널 인터셉터)**

```java
@Override
public void configureClientInboundChannel(ChannelRegistration registration) {
    registration.interceptors(new ChannelInterceptor() {
        @Override
        public Message<?> preSend(Message<?> message, MessageChannel channel) {
            var accessor = StompHeaderAccessor.wrap(message);
            if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
                String dest = accessor.getDestination();
                Principal user = accessor.getUser();
                if (user == null) throw new AccessDeniedException("unauthenticated");
                // dest에서 roomId를 파싱해 그 방의 활성 멤버인지 확인
                if (!roomService.isActiveMember(extractRoomId(dest), user.getName())) {
                    throw new AccessDeniedException("not a room member");
                }
            }
            return message;
        }
    });
}
```

이 인터셉터가 없으면 1단계·2단계를 해도 **정상 로그인한 사용자가 남의 방을 구독하는 것**은 막지 못합니다. 와일드카드 목적지(`/topic/**`)를 거부하는 로직도 여기에 넣으세요.

---

# P1

## P1-1. AccessToken과 RefreshToken이 구별 불가 — 1시간 만료가 아무 효과 없음

**위치** `util/JwtUtil.java:17-34`, `service/AuthService.java:99-119`, `controller/AuthController.java:35-42`

```java
// JwtUtil.java:17-34
    public String createToken(String email) {              // AccessToken
        return Jwts.builder()
                .setSubject(email)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 60 * 60 * 1000))       // 1시간
                .signWith(SignatureAlgorithm.HS256, secretKey)
                .compact();
    }

    public String createRefreshToken(String email) {       // RefreshToken
        return Jwts.builder()
                .setSubject(email)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 60 * 60 * 24 * 7 * 1000)) // 7일
                .signWith(SignatureAlgorithm.HS256, secretKey)
                .compact();
    }
```

두 토큰의 페이로드는 `{sub, iat, exp}`로 **완전히 동일한 구조**이고, **같은 키로 서명**됩니다. 종류를 구분하는 클레임(`typ`, `scope`, `jti` 등)이 없습니다.

### 왜 문제인가

토큰을 검증하는 코드가 종류를 구분할 방법이 없습니다.

```java
// JwtFilter.java:48 — "AccessToken" 쿠키에 담긴 것이면 무엇이든 통과
if (token == null || !jwtUtil.validate(token)) { ... }
```

```java
// AuthService.java:100 — "RefreshToken" 쿠키에 담긴 것이면 무엇이든 통과
String email = jwtUtil.getEmail(refreshToken);
```

즉 **쿠키 이름이 유일한 구분자**이고, 쿠키 이름은 클라이언트가 마음대로 정할 수 있습니다. 두 방향 모두 통합니다.

**(1) RefreshToken을 AccessToken으로 쓸 수 있습니다.**
7일짜리 토큰을 `AccessToken` 쿠키에 넣으면 `JwtFilter`가 그대로 인증합니다. → **액세스 토큰을 1시간으로 짧게 잡은 의미가 완전히 사라집니다.** 짧은 액세스 토큰의 목적은 "토큰이 유출돼도 노출 창을 1시간으로 제한"인데, 유출되는 상황에서는 보통 두 쿠키가 함께 유출됩니다. 그러면 공격자는 7일짜리를 직접 써서 7일간 API 전체에 접근합니다. 갱신 요청을 보낼 필요조차 없습니다.

**(2) AccessToken을 RefreshToken으로 쓸 수 있습니다.**
`POST /auth/refresh`에 액세스 토큰을 `RefreshToken` 쿠키로 보내면 새 액세스 토큰이 발급됩니다. 액세스 토큰만 확보한 공격자가 만료 전에 갱신을 반복하면 **무기한 연장**됩니다.

**(3) 갱신에 회전(rotation)이 없습니다.**
`refreshAccessToken`(`:99-119`)은 새 액세스 토큰만 발급하고 리프레시 토큰은 그대로 둡니다. 같은 리프레시 토큰을 몇 번이든 재사용할 수 있어, "탈취된 토큰이 재사용되었다"는 것을 탐지할 방법이 없습니다.

**(4) 로그아웃해도 토큰이 죽지 않습니다.**
`logout`(`:75-96`)은 쿠키만 지웁니다. JWT는 서버에 상태를 두지 않는 방식이라 **발급된 토큰은 `exp`까지 유효합니다.** 이미 유출된 토큰이 있다면 로그아웃은 아무 방어가 되지 않고, 무효화할 수단(denylist, `jti` 저장소, 토큰 버전 컬럼)이 전혀 없습니다. 비밀번호를 바꿰도 기존 토큰이 삽니다.

이건 JWT를 무상태로 쓸 때의 알려진 트레이드오프지만, **7일짜리 토큰을 직접 인증에 쓸 수 있는 상태**에서는 감당하기 어려운 위험입니다.

### 수정 방향

**1단계 — 종류를 클레임에 박고 검증 지점에서 확인** (최소 변경, 즉시 (1)(2) 차단)

```diff
--- a/src/main/java/com/camstudy/backend/util/JwtUtil.java
+++ b/src/main/java/com/camstudy/backend/util/JwtUtil.java
     public String createToken(String email) {
         return Jwts.builder()
                 .setSubject(email)
+                .claim("typ", "access")
                 .setIssuedAt(new Date())
...
     public String createRefreshToken(String email) {
         return Jwts.builder()
                 .setSubject(email)
+                .claim("typ", "refresh")
                 .setIssuedAt(new Date())
```

```diff
+    /** typ이 일치하는 경우에만 Claims 반환, 아니면 null */
+    public Claims parseAs(String token, String expectedTyp) {
+        try {
+            Claims c = Jwts.parser().setSigningKey(secretKey)
+                           .parseClaimsJws(token).getBody();
+            return expectedTyp.equals(c.get("typ", String.class)) ? c : null;
+        } catch (JwtException | IllegalArgumentException e) {
+            return null;
+        }
+    }
```

`JwtFilter`는 `parseAs(token, "access")`, `refreshAccessToken`은 `parseAs(token, "refresh")`를 씁니다. `02-performance.md` P1-2의 "파싱 1회" 수정과 같은 메서드로 통합됩니다.

**기존 토큰과의 호환**: `typ`이 없는 기존 토큰은 전부 거부됩니다 → 모든 사용자가 재로그인해야 합니다. 액세스 토큰 만료가 1시간이라 영향은 짧지만, 배포 시점을 사용자가 적은 시간대로 잡으세요.

**2단계 — 리프레시 토큰 회전 + 무효화 저장소**

리프레시 토큰에 `jti`(고유 id)를 넣고 DB에 저장합니다. 갱신할 때 기존 `jti`를 폐기하고 새 토큰을 발급합니다. 폐기된 `jti`가 다시 오면 **탈취로 간주하고 그 사용자의 모든 토큰을 무효화**합니다. 로그아웃은 그 사용자의 `jti`를 전부 지우면 됩니다.

액세스 토큰은 짧으니 무상태로 두고, 리프레시 토큰만 상태를 갖는 게 일반적인 절충입니다.

**3단계 — `refreshAccessToken`의 예외를 401로**

`getEmail`이 만료·변조 시 `JwtException`을 던지고, 그건 `RuntimeException` 하위이므로 `GlobalExceptionHandler`(`:17-22`)의 switch에서 default로 떨어져 **500 + 내부 예외 메시지**가 나갑니다. 클라이언트가 "재로그인 필요"를 판단할 수 없습니다 → `04-exception.md`.

`:101-103`의 `if (email == null)` 검사는 사실상 도달하지 않습니다 — `getEmail`이 그 전에 예외를 던지기 때문입니다.

---

## P1-2. CSRF 비활성 + 쿠키 인증 + `SameSite=None` 조합

**위치** `config/SecurityConfig.java:31` / `service/AuthService.java:37-52`, `:109-115`

```java
// SecurityConfig.java:31
            .csrf(AbstractHttpConfigurer::disable)
```

```java
// AuthService.java:37-43
        ResponseCookie accessTokenCookie = ResponseCookie.from("AccessToken", accessToken)
                .httpOnly(true)
                .secure(true)
                .path("/")
                .maxAge(60 * 60)
                .sameSite("None")          // ← cross-site 요청에도 쿠키가 붙습니다
                .build();
```

### 왜 문제인가

CSRF 방어가 필요한 조건은 **"브라우저가 자동으로 첨부하는 자격증명"** 입니다. 토큰을 `Authorization` 헤더로 보내는 방식이라면 공격자 페이지가 그 헤더를 만들 수 없으므로 CSRF가 성립하지 않고, 그래서 "JWT를 쓰니 CSRF는 끄면 된다"는 관례가 생겼습니다.

**이 프로젝트는 JWT를 쿠키로 보냅니다.** 그러면 헤더 방식의 면역이 사라집니다. 브라우저는 요청의 출처와 무관하게 대상 호스트의 쿠키를 첨부합니다.

그리고 `SameSite=None`이 마지막 안전장치까지 끕니다. SameSite의 기본값(`Lax`)은 cross-site 요청 중 **top-level 내비게이션 GET에만** 쿠키를 허용합니다. `None`은 그 제한을 전부 해제합니다.

**CORS가 막아주지 않습니다.** CORS는 **응답을 읽는 것**을 막는 메커니즘입니다. 요청이 서버에 **도달해서 실행되는 것**은 막지 않습니다(preflight가 필요한 요청은 예외). 공격자는 응답을 읽을 필요가 없는 공격 — 상태 변경 — 을 노립니다.

### 실제로 지금 악용 가능한 범위 (정확히)

전부 열려 있는 건 아닙니다. JSON 바디를 요구하는 엔드포인트는 **우연히** 막혀 있습니다.

- `fetch`로 `Content-Type: application/json`을 보내면 **preflight(OPTIONS)** 가 발생합니다. 공격자 origin은 허용 목록에 없으므로 preflight가 실패하고, 브라우저가 본 요청을 아예 보내지 않습니다.
- `<form>`은 preflight 없이 POST할 수 있지만 Content-Type이 `application/x-www-form-urlencoded`/`multipart/form-data`/`text/plain`으로 제한됩니다. `@RequestBody`는 JSON만 처리하므로 **415**가 납니다.
- `DELETE`/`PATCH`는 form으로 보낼 수 없습니다.

그래서 실제로 통하는 것은 **바디가 없는 POST 엔드포인트**입니다.

| 엔드포인트 | 바디 | CSRF 가능 | 영향 |
|---|---|---|---|
| `POST /auth/logout` (`AuthController.java:30`) | 없음 | **가능** | 강제 로그아웃 |
| `POST /windows/{id}/focus` (`WindowController.java:45`) | 없음 | **가능** | 창 z-order 변경 |
| `POST /rooms/{id}/join`, `/leave`, `/admin/kick` | JSON 필수 | 막힘(415) | — |
| `PATCH`/`DELETE` 전부 | — | 막힘 | — |

**현재 영향은 낮습니다.** 문제는 이 방어가 **설계된 것이 아니라 우연**이라는 점입니다. 나중에 `@RequestParam`을 쓰는 엔드포인트나 바디 없는 POST/`GET` 기반 상태 변경을 하나 추가하는 순간, 아무 경고 없이 악용 가능해집니다. 그리고 P0-2에서 본 대로 **WebSocket에는 이 우연한 방어조차 없습니다.**

### 수정 방향

**1순위 — `SameSite`를 `Lax`로 바꿀 수 있는지 확인**

프론트와 API가 같은 사이트(같은 등록 도메인)라면 `Lax`로 충분하고, 이것만으로 CSRF 표면이 거의 사라집니다.

```diff
-                .sameSite("None")
+                .sameSite("Lax")
```

`[[확인필요: API가 어느 호스트에서 서비스되는지. oeyo-cam.site와 같은 등록 도메인이면 Lax 가능. 완전히 다른 도메인이면 None이 필요합니다]]`

`SecurityConfig.java:53-58`의 CORS 허용 목록에 `oeyo-cam.site`가 있는 것으로 보아 프론트와 API가 다른 출처일 가능성이 있습니다. `api.oeyo-cam.site`처럼 서브도메인 관계라면 SameSite 기준으로는 same-site이므로 `Lax`가 동작합니다.

**2순위 — `None`이 불가피하면 CSRF 토큰을 켭니다**

```diff
-            .csrf(AbstractHttpConfigurer::disable)
+            .csrf(csrf -> csrf
+                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
+                .ignoringRequestMatchers("/auth/login", "/auth/signup"))
```

`withHttpOnlyFalse()`는 `XSRF-TOKEN` 쿠키를 JS가 읽을 수 있게 두고, 프론트가 그 값을 `X-XSRF-TOKEN` 헤더로 되돌려 보내는 방식입니다(double-submit). 공격자 페이지는 다른 출처의 쿠키를 읽을 수 없으므로 헤더를 만들 수 없습니다. **프론트 수정이 필요합니다** — axios는 `xsrfCookieName`/`xsrfHeaderName` 기본값이 이 이름과 맞고, `fetch`는 직접 붙여야 합니다.

**3순위 — Origin/Referer 검증 필터**

토큰 방식이 부담되면, 상태 변경 메서드(POST/PUT/PATCH/DELETE)에 대해 `Origin` 헤더가 허용 목록에 있는지 확인하는 필터를 두는 방법도 있습니다. CORS 설정과 값을 공유할 수 있어 관리 지점이 하나로 유지됩니다.

---

## P1-3. 비밀번호 인코더가 두 개이고, 강한 쪽이 계정이 아니라 방 비밀번호에 쓰이고 있음

**위치** `config/PasswordConfig.java:11-17`, `service/AuthService.java:17`, `service/RoomService.java:26`

```java
// PasswordConfig.java:11-17 — 빈으로 등록된 것은 Argon2
    @Bean
    public PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }
```

```java
// AuthService.java:17 — 그런데 계정 비밀번호는 직접 만든 BCrypt를 씁니다
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
```

```java
// RoomService.java:26 — 주입받은 Argon2가 방 비밀번호에 쓰입니다
    private final PasswordEncoder passwordEncoder;
```

### 왜 문제인가

**(1) 의도가 반대로 적용되어 있습니다.**

`build.gradle:26`에 `spring-security-crypto`를 추가한 주석이 `// Argon2PasswordEncoder`입니다. 즉 Argon2를 도입한 목적은 사용자 계정 비밀번호였을 것입니다. 그런데 실제로는:

| 대상 | 사용 알고리즘 | 유출 시 영향 |
|---|---|---|
| **사용자 계정 비밀번호** (`users.password`) | BCrypt strength 10 | 계정 탈취, 타 서비스 비밀번호 재사용 피해 |
| 방 비밀번호 (`rooms.password_hash`) | **Argon2** (메모리 하드) | 그 방 입장 |

보호 가치가 훨씬 높은 쪽에 약한 알고리즘이 걸려 있습니다.

BCrypt strength 10이 당장 위험하다는 뜻은 아닙니다 — 여전히 널리 쓰이는 선택입니다. 요점은 **더 강한 옵션을 이미 등록해 두고도 계정에는 쓰지 않고 있다**는 것, 그리고 그게 의도가 아니라 실수로 보인다는 것입니다.

**(2) `new BCryptPasswordEncoder()`는 스프링 빈이 아닙니다.**

`private final ... = new ...`로 필드에서 직접 생성했으므로:
- strength를 바꾸려면 코드를 수정해야 합니다(설정으로 못 바꿉니다)
- 테스트에서 가벼운 인코더로 교체할 수 없습니다 → 모든 인증 테스트가 실제 BCrypt 계산 비용을 냅니다
- `PasswordConfig`의 빈이 있는데도 쓰이지 않아, 코드를 읽는 사람이 "어떤 인코더가 쓰이는지" 오판하게 됩니다

**(3) 알고리즘 마이그레이션 경로가 없습니다.**

`DelegatingPasswordEncoder`(Spring Security의 기본 권장)는 해시 앞에 `{bcrypt}`, `{argon2}` 같은 접두사를 붙여 저장합니다. 그래서 나중에 알고리즘을 바꿔도 **기존 해시를 계속 검증할 수 있고, 로그인 시점에 조용히 재해시**할 수 있습니다.

지금은 접두사 없이 순수 BCrypt 해시(`$2a$10$...`)가 저장되어 있습니다. 알고리즘을 Argon2로 바꾸면 **기존 사용자 전원이 로그인 불가**가 되고, 전체 비밀번호 재설정이 필요합니다.

**(4) Argon2 16MiB × 동시 요청 = 메모리 고갈 벡터**

`Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()`의 파라미터는 salt 16바이트, hash 32바이트, parallelism 1, **memory 16384 KiB(= 16 MiB)**, iterations 2입니다. Argon2는 "메모리를 많이 쓰는 것"이 설계 목표(memory-hard)이므로 이 16 MiB는 **호출마다 실제로 할당됩니다.**

이 인코더가 호출되는 지점:
- `RoomService.create:29` — 비밀번호 있는 방 생성
- `RoomService.verifyPassword:50` — 비공개 방 입장 시도

`POST /rooms/{id}/join`은 로그인만 하면 누구나, 횟수 제한 없이 호출할 수 있습니다(레이트 리밋 없음). 공격자가 비밀번호 있는 방을 하나 만들고 join을 동시에 다량 호출하면 요청 수 × 16 MiB가 순간 할당됩니다. Tomcat 기본 스레드 수가 200이므로 상한이 상당히 높습니다.

그리고 **`docker-compose.yml`의 app 서비스에 메모리 제한이 없습니다**(`mem_limit`/`deploy.resources` 없음 → `12-docker-deploy.md`). JVM 힙 옵션도 없습니다(`Dockerfile:29`의 ENTRYPOINT에 `-Xmx` 없음). 컨테이너가 호스트 메모리를 다 쓰면 OOM Killer가 프로세스를 죽입니다.

수치는 측정하지 않았습니다. 위 내용은 "문서화된 파라미터가 16 MiB이고, 호출 횟수 제한과 메모리 제한이 둘 다 없다"는 사실입니다.

### 수정 방향

**1단계 — `DelegatingPasswordEncoder`로 전환** (마이그레이션 경로 확보)

```diff
--- a/src/main/java/com/camstudy/backend/config/PasswordConfig.java
+++ b/src/main/java/com/camstudy/backend/config/PasswordConfig.java
     @Bean
     public PasswordEncoder passwordEncoder() {
-        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
+        // 접두사 없는 기존 해시는 BCrypt로 간주 → 무중단 전환 가능
+        DelegatingPasswordEncoder enc =
+                (DelegatingPasswordEncoder) PasswordEncoderFactories.createDelegatingPasswordEncoder();
+        enc.setDefaultPasswordEncoderForMatches(new BCryptPasswordEncoder());
+        return enc;
     }
```

`createDelegatingPasswordEncoder()`의 기본 인코딩 알고리즘은 BCrypt이므로, 새로 저장되는 해시는 `{bcrypt}$2a$10$...` 형태가 됩니다. `setDefaultPasswordEncoderForMatches`가 접두사 없는 **기존 해시**를 BCrypt로 검증해 줍니다. → **기존 사용자 로그인이 깨지지 않습니다.**

**2단계 — `AuthService`가 주입받아 쓰게**

```diff
--- a/src/main/java/com/camstudy/backend/service/AuthService.java
+++ b/src/main/java/com/camstudy/backend/service/AuthService.java
     private final UserRepository userRepository;
     private final JwtUtil jwtUtil;
-    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
+    private final PasswordEncoder encoder;
 
-    public AuthService(UserRepository userRepository, JwtUtil jwtUtil) {
+    public AuthService(UserRepository userRepository, JwtUtil jwtUtil, PasswordEncoder encoder) {
         this.userRepository = userRepository;
         this.jwtUtil = jwtUtil;
+        this.encoder = encoder;
     }
```

**3단계 — 방 비밀번호에 Argon2를 계속 쓸 이유가 없으면 통일**

방 비밀번호는 사용자 계정보다 보호 가치가 낮고, 대신 **호출 빈도가 높고 인증 없이 반복 시도가 가능**합니다. 메모리 하드 알고리즘을 여기에 쓰는 건 비용 구조가 거꾸로입니다. `DelegatingPasswordEncoder`로 통일하면 방 비밀번호도 BCrypt가 됩니다.
단 **기존 `rooms.password_hash`는 Argon2 형식(`$argon2id$...`)** 이므로, `DelegatingPasswordEncoder`가 접두사 없는 값을 BCrypt로 시도해 실패합니다. → 기존 비공개 방의 비밀번호 검증이 깨집니다.
`[[확인필요: rooms 테이블에 password_hash가 있는 행이 몇 건인지 — 적으면 방을 다시 만들게 하고, 많으면 앱에서 접두사를 붙이는 일회성 UPDATE 필요]]`

**4단계 — 레이트 리밋**
`POST /auth/login`, `POST /rooms/{id}/join`은 비밀번호 검증(=의도적으로 느린 연산)을 트리거하는 엔드포인트입니다. IP/계정 단위 시도 제한이 없으면 무차별 대입과 자원 고갈 양쪽에 열려 있습니다. 현재 레이트 리밋 코드는 없습니다.

---

## P1-4. `AuthenticationCheckFilter`가 `SecurityConfig`의 permitAll을 무력화

**위치** `config/SecurityConfig.java:32-45`, `config/AuthenticationCheckFilter.java:21`, `:45-49`, `config/JwtFilter.java:26-29`

```java
// SecurityConfig.java:32-45 — 선언된 공개 경로
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/auth/**", "/error", "/ws/**", "/actuator/**").permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(authenticationCheckFilter, JwtFilter.class);
```

```java
// AuthenticationCheckFilter.java:21 — 그런데 이 필터가 우회하는 경로는 2개뿐
    private static final List<String> EXCLUDE_URLS = List.of("/auth/", "/error");
```

### 왜 문제인가

**필터 순서가 결정적입니다.** Spring Security에서 인가 규칙(`authorizeHttpRequests`)을 실제로 집행하는 것은 필터 체인의 **거의 마지막**에 있는 `AuthorizationFilter`입니다. `addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)`로 등록된 필터들은 그보다 **앞**에 놓입니다.

```
요청 → CorsFilter → ... → JwtFilter → AuthenticationCheckFilter → ... → AuthorizationFilter → 컨트롤러
                                       └─ 여기서 이미 401로 끊김        └─ permitAll 규칙은 여기서 평가
```

`AuthenticationCheckFilter.doFilterInternal`(`:33-37`)은 SecurityContext가 비어 있으면 `response.sendError(401)` 후 `return`합니다 — 체인을 진행시키지 않습니다. 따라서 **`AuthorizationFilter`가 실행되지 않고, `permitAll` 선언은 평가되지 않습니다.**

결과:

| 경로 | SecurityConfig 선언 | 실제 동작 |
|---|---|---|
| `/auth/**` | permitAll | 통과 (필터 EXCLUDE에 있음) |
| `/error` | permitAll | 통과 (필터 EXCLUDE에 있음) |
| `/ws/**` | permitAll | **토큰 없으면 401** |
| `/actuator/**` | permitAll | **토큰 없으면 401** |
| `/` (`HelloController:8`) | (anyRequest → authenticated) | 401 |

**즉시 드러나는 피해는 헬스체크입니다.** `/actuator/health`가 401을 반환하므로, Docker `HEALTHCHECK`나 로드밸런서/오케스트레이터의 상태 확인이 전부 실패합니다. 지금은 `docker-compose.yml`에 헬스체크가 없어서(→`12-docker-deploy.md`) 증상이 안 보일 뿐이고, 추가하는 순간 컨테이너가 unhealthy로 판정됩니다.

**설정과 동작이 어긋나 있는 것 자체가 더 큰 문제입니다.** 보안 정책을 읽는 사람은 `SecurityConfig`를 봅니다. 거기에 `/actuator/**` permitAll이 적혀 있으면 "actuator는 공개"라고 이해하고 그 전제로 판단합니다. 실제 동작은 다릅니다. 방향이 안전한 쪽(더 막힘)이라 지금 사고는 안 났지만, **두 곳을 동기화해야 하는 구조 자체가 사고를 예약해 둔 상태**입니다. P1-5가 정확히 그 시나리오입니다.

### 부수 문제: 경로 매칭 방식

```java
// AuthenticationCheckFilter.java:46-48
        String path = request.getServletPath();
        return EXCLUDE_URLS.stream().anyMatch(path::startsWith);
```

`startsWith`는 Spring Security의 `RequestMatcher`와 매칭 규칙이 다릅니다.
- `/auth/**`(Security)는 `/auth` 자체도 매칭하지만, `"/auth".startsWith("/auth/")`는 **false**입니다.
- `WebConfig.java:20`이 `setUseTrailingSlashMatch(true)`를 켜두었으므로 `/auth/login/`도 같은 핸들러로 갑니다. 이 경우는 `startsWith`가 통과하므로 우연히 일치합니다.

`JwtFilter.java:94-96`의 주석이 이미 "정확한 Ant-style 패턴 매칭이 필요하다면..."이라고 인정하고 있습니다.

### 수정 방향

**근본 해결은 이 두 필터를 없애는 것입니다.** `AuthenticationCheckFilter`가 하는 일 — "인증 안 됐으면 401" — 은 Spring Security의 `anyRequest().authenticated()` + `AuthenticationEntryPoint`가 이미 하는 일입니다. 직접 만든 필터는 그 기능을 중복 구현하면서 순서 때문에 정책을 덮어쓰고 있습니다.

```diff
--- a/src/main/java/com/camstudy/backend/config/SecurityConfig.java
+++ b/src/main/java/com/camstudy/backend/config/SecurityConfig.java
             .authorizeHttpRequests(auth -> auth
                 .requestMatchers("/auth/**", "/error").permitAll()
+                .requestMatchers("/actuator/health").permitAll()
                 .anyRequest().authenticated()
             )
+            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
+            .exceptionHandling(e -> e
+                .authenticationEntryPoint((req, res, ex) ->
+                    res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "인증이 필요합니다.")))
             .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
-            .addFilterAfter(authenticationCheckFilter, JwtFilter.class);
```

그리고 `AuthenticationCheckFilter.java` 파일을 삭제합니다. `JwtFilter`는 남기지만 역할을 "토큰이 있으면 SecurityContext를 채운다"로 한정하고, `shouldNotFilter`도 지웁니다 — 토큰이 없으면 그냥 통과시키고 인가 판단은 Security에 맡기면 되므로 EXCLUDE 목록이 필요 없습니다. 그러면 **동기화해야 할 목록이 `SecurityConfig` 한 곳으로 줄어듭니다.**

`sessionCreationPolicy(STATELESS)`를 함께 넣는 이유: JWT 인증이므로 세션이 필요 없는데, 지금은 기본값(`IF_REQUIRED`)이라 요청마다 `JSESSIONID` 세션이 만들어질 수 있습니다.

`/ws/**`를 permitAll에서 뺀 것은 의도적입니다 — WebSocket 핸드셰이크도 인증을 요구하는 편이 안전합니다(P0-2 참고).

---

## P1-5. `/actuator/**` 전체 permitAll — 지금은 P1-4에 막혀 있지만, 그게 고쳐지면 열립니다

**위치** `config/SecurityConfig.java:38`

```java
                .requestMatchers(
                    "/auth/**", "/error", "/ws/**",
                    "/actuator/**"          // ← 와일드카드
                ).permitAll()
```

### 왜 문제인가

Spring Boot 3의 Actuator 기본 HTTP 노출은 `health`와 `info`뿐입니다. `management.endpoints.web.exposure.include`가 설정되지 않았으므로(`application.yml`에 `management` 블록 없음) 지금 공개되는 정보는 제한적입니다. `build.gradle:47`의 `springBoot { buildInfo() }` 때문에 `/actuator/info`가 group/artifact/version/빌드시각을 노출하는 정도입니다.

**문제는 `/**` 와일드카드가 미래의 설정 변경에 대해 무방비라는 점입니다.** 누군가 디버깅을 위해 한 줄을 추가하면:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: "*"
```

즉시 이런 것들이 **인증 없이** 공개됩니다.

| 엔드포인트 | 노출 내용 |
|---|---|
| `/actuator/env` | **모든 환경변수·프로퍼티** → `DB_PASSWORD`, `JWT_SECRET` |
| `/actuator/configprops` | 설정 바인딩 값 전체 |
| `/actuator/heapdump` | **힙 덤프 파일** → 메모리에 있던 토큰·비밀번호 평문 |
| `/actuator/threaddump` | 스레드 스택 |
| `/actuator/mappings` | 전체 엔드포인트 목록 |
| `/actuator/loggers` | (POST로) 런타임 로그 레벨 변경 |

`JWT_SECRET`이 노출되면 공격자가 **임의 사용자의 유효한 토큰을 직접 발급**할 수 있습니다. 인증 체계 전체가 무너집니다.

(Actuator는 값이 민감해 보이는 프로퍼티를 마스킹하는 기능이 있지만, `management.endpoint.env.show-values`/`show-details` 기본값에 의존하며 커스텀 키 이름은 놓칠 수 있습니다. 와일드카드 permitAll을 유지할 근거로는 부족합니다.)

지금 이게 터지지 않는 유일한 이유는 **P1-4의 버그가 `/actuator/**`를 막고 있기 때문**입니다. P1-4를 "설정대로 동작하게" 고치면 그 순간 이 지뢰가 활성화됩니다. **두 항목을 함께 수정해야 합니다.**

### 수정 방향

필요한 것만 열고, 나머지는 인증 뒤에 둡니다.

```diff
--- a/src/main/java/com/camstudy/backend/config/SecurityConfig.java
+++ b/src/main/java/com/camstudy/backend/config/SecurityConfig.java
                 .requestMatchers("/auth/**", "/error").permitAll()
-                .requestMatchers("/ws/**", "/actuator/**").permitAll()
+                .requestMatchers("/actuator/health").permitAll()
                 .anyRequest().authenticated()
```

그리고 노출 자체를 명시적으로 제한합니다.

```diff
--- a/src/main/resources/application.yml
+++ b/src/main/resources/application.yml
+management:
+  endpoints:
+    web:
+      exposure:
+        include: health,info
+  endpoint:
+    health:
+      show-details: never       # DB 상태 등 내부 정보를 익명에게 노출하지 않음
```

`show-details: never`가 필요한 이유: 기본값(`when-authorized`)에서도 인증되지 않은 요청에는 상태만 나가지만, 명시해 두면 설정 변경 시의 사고를 줄입니다. 헬스체크 용도로는 `{"status":"UP"}`만으로 충분합니다.

`/actuator/info`도 굳이 공개할 필요가 없으면 `include: health`로 줄이세요. 빌드 버전이 공격자에게 알려지면 알려진 취약점을 가진 라이브러리 버전을 특정하는 데 쓰입니다.

---

## P0-3. (원 P1-6) 공개 저장소의 git 히스토리에 DB 자격증명이 리터럴로 남아 있음 — `.gitignore`에 `.env`도 없음

> **심각도 상향**: 최초 작성 시 `origin`의 공개 여부를 확인하지 못해 P1로 분류했습니다.
> `gh repo view` 결과 **PUBLIC**으로 확인되어 **P0으로 올립니다.**

### 확인한 사실 (값은 출력하지 않았습니다)

**(1) 과거 커밋의 하드코딩 자격증명**

`src/main/resources/application.yml`의 `spring.datasource` 블록에 환경변수 placeholder가 아닌 **리터럴 값**이 들어 있는 커밋:

| 커밋 | 날짜 | 하드코딩된 키 |
|---|---|---|
| `ab1a1d9` | 2025-04-17 | `spring.datasource.url`, `.username`, `.password` |
| `e62e4d1` | 2025-04-17 | 동일 |
| `a6b9577` | 2025-05-07 | 동일 |

현재 HEAD(`bfe6988`)는 `${DB_HOST}`, `${DB_USER}`, `${DB_PASSWORD}`로 정리되어 있습니다(`application.yml:7-9`).

`jwt.secretKey`는 **모든 커밋에서 `${JWT_SECRET}` placeholder**였습니다. LiveKit의 `apiKey`/`apiSecret`(커밋 `8205491`~`be969e7`에 존재, 이후 제거)도 전부 placeholder였습니다. → **JWT 시크릿과 LiveKit 키는 커밋되지 않았습니다.**

**(2) `.gitignore`에 `.env`가 없습니다**

`.gitignore`(39줄)는 Gradle/IDE 산출물만 다루고 `env`라는 문자열이 등장하지 않습니다. 현재 `.env`가 추적되고 있지는 않고 작업트리에도 없습니다(확인). 하지만 `docker-compose.yml:11-14`, `:37-40`이 `${DB_NAME}`, `${DB_USER}`, `${DB_PASSWORD}`, `${JWT_SECRET}`를 참조하므로, **compose를 실행하는 환경에는 `.env`가 존재합니다.**

### 왜 문제인가

**(1) git 히스토리는 파일을 지워도 남습니다.**

현재 파일에서 값을 제거하고 커밋해도, 이전 커밋의 blob은 저장소에 그대로 있습니다. `git log -p`, `git show <commit>:<path>`로 누구나 읽을 수 있습니다. 저장소를 clone한 사람은 전부 사본을 갖고 있습니다.

**저장소는 PUBLIC입니다** (`sabit1997/cam-study-back`, `gh repo view`로 확인). 따라서 **이미 노출된 것으로 간주해야 합니다.** GitHub의 public 저장소는 자동 자격증명 스캐너의 상시 대상이고, 커밋된 비밀은 분 단위로 수집됩니다. 아카이브·미러 서비스에 이미 복제됐을 수 있어 **히스토리를 정리해도 회수되지 않습니다.**

`sabit1997/main`이라는 추가 remote(포크로 보임)에도 같은 히스토리가 남아 있습니다.

**(2) 그 자격증명이 지금도 유효할 가능성이 높습니다.**

`docker-compose.yml:12`는 `MYSQL_ROOT_PASSWORD`와 `MYSQL_PASSWORD`에 **같은 `${DB_PASSWORD}`** 를 씁니다. 즉 애플리케이션 계정 비밀번호가 곧 root 비밀번호입니다. 과거 커밋의 값을 지금도 쓰고 있다면, 그 값으로 **root 접근**이 가능합니다.

**(3) `.env`가 gitignore에 없으면 `git add .` 한 번으로 커밋됩니다.**

지금 안 되어 있는 건 운이 좋았던 것이고, 이 파일에는 DB 비밀번호와 **JWT 시크릿**이 함께 있습니다. JWT 시크릿이 유출되면 임의 사용자의 토큰을 발급할 수 있어(P1-5 참고) 인증 체계가 전부 무너집니다.

### 수정 방향

**1단계 — 자격증명 교체 (즉시. 히스토리 정리보다 먼저)**

히스토리를 다시 쓰는 것보다 **값을 무효화하는 것이 우선**입니다. 노출된 비밀은 지우는 게 아니라 바꾸는 것으로 대응합니다. 공개 저장소이므로 이 항목이 **이 리뷰 전체에서 가장 먼저 처리해야 할 작업**입니다.

```
① MySQL 사용자 비밀번호 변경 (그리고 root와 애플리케이션 계정을 분리)
② JWT_SECRET 교체 (전 사용자 재로그인 필요)
③ .env / 배포 환경 변수 갱신
④ 재배포
```

②는 `.env`에 있으므로 커밋되진 않았지만, ①과 함께 교체하는 편이 안전합니다.

`docker-compose.yml`에서 root와 앱 계정을 분리하세요.

```diff
--- a/docker-compose.yml
+++ b/docker-compose.yml
     environment:
       MYSQL_DATABASE: ${DB_NAME}
-      MYSQL_ROOT_PASSWORD: ${DB_PASSWORD}
+      MYSQL_ROOT_PASSWORD: ${DB_ROOT_PASSWORD}
       MYSQL_USER: ${DB_USER}
       MYSQL_PASSWORD: ${DB_PASSWORD}
```

**2단계 — `.gitignore` 보강**

```diff
--- a/.gitignore
+++ b/.gitignore
+### secrets ###
+.env
+.env.*
+!.env.example
+*.pem
+*.key
+src/main/resources/application-local.yml
+src/main/resources/application-prod.yml
```

`.env.example`은 **키 이름만** 적어 커밋해 두면 신규 참여자가 무엇을 채워야 하는지 알 수 있습니다.

```
DB_HOST=
DB_PORT=3306
DB_NAME=
DB_USER=
DB_PASSWORD=
DB_ROOT_PASSWORD=
JWT_SECRET=
```

**3단계 — 히스토리 정리 (선택, 1단계 이후)**

값을 교체했다면 히스토리에 남은 옛 값은 이미 무해합니다. 그래도 정리하고 싶으면 `git filter-repo`를 쓰지만, **모든 커밋 해시가 바뀌므로** 협업자 전원이 clone을 다시 해야 합니다. 커밋이 22개인 소규모 저장소라 부담은 적습니다.
`sabit1997/main`이라는 추가 remote가 있으므로(포크로 보입니다) **그쪽 사본에도 히스토리가 남습니다.** 정리한다면 양쪽을 함께 처리하세요.

**4단계 — 커밋 전 탐지 장치**

`gitleaks`나 `detect-secrets`를 pre-commit 훅으로 걸면 같은 사고가 재발하지 않습니다.

---

## P1-7. `spring.security.debug: true`가 운영 설정에 있음

**위치** `src/main/resources/application.yml:4-5`

```yaml
spring:
  ...
  security:
    debug: true
```

### 왜 문제인가

두 갈래인데, **어느 쪽이든 지워야 합니다.**

**갈래 A — 이 키가 Spring Boot의 설정 키가 아닌 경우 (가능성이 높다고 봅니다)**

Spring Boot의 `SecurityProperties`(`spring.security` 접두사)가 다루는 것은 `spring.security.filter.*`와 `spring.security.user.*`이고, `debug`는 제가 아는 범위에 없습니다. Security 필터 체인 디버그는 `@EnableWebSecurity(debug = true)` 애너테이션 속성으로 켭니다.

Spring Boot는 **알 수 없는 프로퍼티를 조용히 무시**합니다(바인딩 실패도, 경고도 없습니다). 그러면 이 설정은 아무 일도 하지 않는 죽은 설정이고, 코드를 읽는 사람에게 "디버그 모드가 켜져 있다"는 잘못된 정보를 줍니다.

**갈래 B — 실제로 동작하는 경우**

Security의 debug 모드는 필터 체인 앞에 `DebugFilter`를 끼워, 요청마다 **적용된 필터 목록과 요청 상세(헤더 포함)** 를 로그로 남깁니다. 헤더에는 `Cookie: AccessToken=...`이 들어 있습니다. → **유효한 인증 토큰이 평문으로 로그에 기록됩니다.** 컨테이너 stdout으로 나가고 로테이션 설정도 없으므로(`12-docker-deploy.md`) 무한히 쌓입니다. 로그 파일 접근 권한이 있는 사람은 누구나 세션을 탈취할 수 있습니다.

Spring Security 자체도 debug 모드를 켤 때 "운영에서 사용하지 말 것"을 경고합니다.

`[[확인필요: 부팅 로그에 필터 체인 목록(예: "Security filter chain: [ ... ]")이 출력되는지. 출력되면 갈래 B — 즉시 제거. 출력되지 않으면 갈래 A — 죽은 설정이므로 그래도 제거]]`

이건 앱을 한 번 띄워 부팅 로그를 보면 바로 확인됩니다.

### 수정 방향

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

Security 필터 체인을 확인해야 할 때는 로컬 profile에서 로그 레벨로 켜세요.

```yaml
# application-local.yml
logging:
  level:
    org.springframework.security: DEBUG
```

`application.yml:25`가 이 로거를 `WARN`으로 낮춰 둔 것은 적절합니다(커밋 `1b5edff`에서 TRACE → WARN으로 조정한 이력이 있습니다). 그런데 `security.debug`는 `DebugFilter`를 통해 별도 경로로 출력되므로 로그 레벨로 완전히 통제되지 않습니다 — 그래서 설정 자체를 지우는 게 맞습니다.

---

## P1-8. 운영 CORS 허용 목록에 `localhost`가 들어 있음

**위치** `config/SecurityConfig.java:50-66`

```java
        config.setAllowedOriginPatterns(List.of(
            "https://oeyo-cam.site",
            "https://www.oeyo-cam.site",
            "http://localhost:3000",       // ← 운영에도 적용됨
            "http://localhost:3001"
        ));
        config.setAllowedMethods(List.of("GET","POST","PUT","PATCH","DELETE","OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
```

이 설정은 `application-prod.yml`이 없으므로(`00-overview.md`) **운영에서 그대로 적용됩니다.**

### 왜 문제인가

`allowCredentials(true)` + `http://localhost:3000` 허용의 의미는 이렇습니다: **피해자의 머신에서 `http://localhost:3000`으로 서비스되는 무엇이든, 운영 API에 피해자의 인증 쿠키로 요청을 보내고 응답을 읽을 수 있습니다.**

`localhost`는 "내 컴퓨터"이므로 안전하게 느껴지지만, 그 포트에 무엇이 떠 있는지는 서버가 알 수 없습니다.

- 개발자가 다른 프로젝트의 개발 서버를 3000 포트로 띄우고 있고, 그 앱에 XSS가 있으면 → 운영 API 접근
- 로컬에서 도는 Electron 앱, 개발 툴, 임의의 npm 패키지가 띄운 서버
- 피해자가 무심코 실행한 스크립트가 3000 포트에 서버를 띄우고 피해자를 그 페이지로 유도

공격 난이도는 낮지 않습니다. 요점은 **운영 환경에서 이 출처를 허용해 얻는 이득이 0이라는 것**입니다. 로컬 개발자는 로컬 백엔드를 씁니다. 필요 없는 공격면입니다.

부수적으로:
- `setAllowedHeaders("*")` — 임의 커스텀 헤더를 허용합니다. 위험도는 낮지만, 필요한 헤더만(`Content-Type`, `X-User-Timezone`) 나열하는 편이 낫습니다. 이 프로젝트가 실제로 쓰는 커스텀 헤더는 `X-User-Timezone` 하나입니다(`TimerController`, `TodoController`, `GlobalTodoController`).
- `setAllowedOriginPatterns`는 와일드카드 패턴을 위한 API인데 여기서는 정확한 출처만 넣었습니다. 기능상 `setAllowedOrigins`와 동일합니다. `setAllowedOrigins`를 쓰면 `*`를 넣을 수 없어(credentials와 함께 쓰면 예외) 실수 방지가 됩니다.

### 수정 방향

출처 목록을 설정으로 빼고, profile별로 다르게 줍니다.

```diff
--- a/src/main/java/com/camstudy/backend/config/SecurityConfig.java
+++ b/src/main/java/com/camstudy/backend/config/SecurityConfig.java
+    @Value("${app.cors.allowed-origins}")
+    private List<String> allowedOrigins;
+
     @Bean
     public CorsConfigurationSource corsConfigurationSource() {
         CorsConfiguration config = new CorsConfiguration();
-        config.setAllowedOriginPatterns(List.of(
-            "https://oeyo-cam.site", "https://www.oeyo-cam.site",
-            "http://localhost:3000", "http://localhost:3001"
-        ));
+        config.setAllowedOrigins(allowedOrigins);
         config.setAllowedMethods(List.of("GET","POST","PUT","PATCH","DELETE","OPTIONS"));
-        config.setAllowedHeaders(List.of("*"));
+        config.setAllowedHeaders(List.of("Content-Type", "X-User-Timezone", "X-XSRF-TOKEN"));
         config.setAllowCredentials(true);
```

```yaml
# application.yml (기본 = 운영 안전값)
app:
  cors:
    allowed-origins: https://oeyo-cam.site,https://www.oeyo-cam.site

# application-local.yml
app:
  cors:
    allowed-origins: http://localhost:3000,http://localhost:3001
```

이 값을 `WsConfig`의 `setAllowedOrigins`(P0-2)와 **공유**하세요. 지금은 CORS 목록과 WebSocket 목록이 서로 다른 곳에 다른 내용으로 적혀 있습니다.

---

## P1-9. jjwt의 deprecated API 사용 — 시크릿이 Base64로 해석되고, 라이브러리 업그레이드 시 컴파일 불가

**위치** `util/JwtUtil.java:13-14`, `:22`, `:32`, `:39`, `:49` / `build.gradle:39-41`

```java
// JwtUtil.java:13-14
    @Value("${jwt.secretKey}")
    private String secretKey;              // 필드 주입

// :22, :32
                .signWith(SignatureAlgorithm.HS256, secretKey)     // deprecated
// :39, :49
            Jwts.parser().setSigningKey(secretKey)                 // deprecated
```

### 왜 문제인가

**(1) `signWith(SignatureAlgorithm, String)`은 문자열을 Base64로 디코딩합니다.**

jjwt에서 이 오버로드의 두 번째 인자는 "base64-encoded secret key"입니다. 내부적으로 Base64 디코딩을 거쳐 키 바이트를 만듭니다. 즉 `JWT_SECRET`에 넣은 문자열이 **그대로 키가 되는 것이 아닙니다.**

결과:
- 실제 키의 바이트 수가 입력 문자열 길이보다 작습니다(Base64는 4문자 → 3바이트).
- `JWT_SECRET`이 Base64 알파벳이 아닌 문자를 포함하면 디코딩 단계에서 예외가 발생합니다.
- 개발자가 "32자 이상 넣었으니 256비트 이상"이라고 판단하면 틀립니다.

jjwt 0.11.5는 HS256에 대해 256비트 미만의 키를 거부합니다(`WeakKeyException`). 앱이 정상 동작한다면 디코딩 후에도 32바이트 이상이라는 뜻이므로 **당장 취약하지는 않습니다.** 문제는 그 사실을 코드에서 알 수 없다는 것, 그리고 시크릿을 교체할 때(P1-6 1단계) 잘못된 형식을 넣으면 부팅 후 로그인 시점에 실패한다는 것입니다.

`[[확인필요: JWT_SECRET이 Base64 문자열인지, 평문 패스프레이즈인지 — 값은 확인하지 말고 형식만. 평문이면 P1-6의 교체 작업에서 Base64로 정리하세요]]`

**(2) 이 API들은 jjwt 0.12.x에서 제거되었습니다.**

`build.gradle:39-41`이 0.11.5에 고정되어 있습니다. 보안 라이브러리를 오래된 버전에 묶어두는 것 자체가 위험하고, 올리려면 이 파일을 다시 써야 합니다. 지금 정리해 두면 나중 업그레이드가 쉬워집니다.

**(3) `@Value` 필드 주입**

`secretKey`가 생성자 주입이 아니라 필드 주입이므로, 객체가 만들어진 직후에는 `null`입니다. 그래서 **부팅 시점에 "시크릿이 유효한가"를 검증할 수 없습니다.** `JWT_SECRET` 환경변수가 비어 있어도 앱은 정상 부팅하고, 첫 로그인 요청에서 실패합니다.

`@Value("${jwt.secretKey}")`는 프로퍼티가 아예 없으면 부팅에 실패하지만(기본값 미지정), 빈 문자열이면 통과합니다.

### 수정 방향

`Key` 객체를 부팅 시점에 한 번 만들고 검증합니다. `02-performance.md` P1-2(파싱 1회)와 P1-1(`typ` 클레임)의 수정을 한 번에 합치는 게 좋습니다.

```diff
--- a/src/main/java/com/camstudy/backend/util/JwtUtil.java
+++ b/src/main/java/com/camstudy/backend/util/JwtUtil.java
 @Component
 public class JwtUtil {
 
-    @Value("${jwt.secretKey}")
-    private String secretKey;
+    private final SecretKey key;
+
+    public JwtUtil(@Value("${jwt.secretKey}") String base64Secret) {
+        byte[] bytes = Decoders.BASE64.decode(base64Secret);
+        if (bytes.length < 32) {                       // HS256은 256비트 이상 필요
+            throw new IllegalStateException("jwt.secretKey must decode to >= 32 bytes");
+        }
+        this.key = Keys.hmacShaKeyFor(bytes);          // 부팅 시 1회
+    }
```

```diff
     public String createToken(String email) {
         return Jwts.builder()
                 .setSubject(email)
                 .setIssuedAt(new Date())
                 .setExpiration(new Date(System.currentTimeMillis() + 60 * 60 * 1000))
-                .signWith(SignatureAlgorithm.HS256, secretKey)
+                .signWith(key, SignatureAlgorithm.HS256)
                 .compact();
     }
```

생성자 주입으로 바꾸면 **시크릿이 잘못됐을 때 부팅이 실패합니다.** 이게 목적입니다 — 첫 로그인 요청에서 500이 나는 것보다 배포 시점에 실패하는 게 낫습니다.

만료 시간이 `60 * 60 * 24 * 7 * 1000` 같은 계산식으로 코드에 박혀 있는 것도 설정으로 빼세요. `int` 곱셈이라 값을 키우면 오버플로 위험이 있습니다(현재 `604800000`은 `int` 범위 안이라 안전합니다).

```yaml
jwt:
  secretKey: ${JWT_SECRET}
  access-token-ttl: PT1H
  refresh-token-ttl: P7D
```

---

# P2

## P2-1. 로그인 실패 메시지가 계정 존재 여부를 알려줌 (사용자 열거)

**위치** `service/AuthService.java:25-30`, `controller/GlobalExceptionHandler.java:17-24`

```java
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("존재하지 않는 사용자"));   // :26
        if (!encoder.matches(password, user.getPassword())) {
            throw new RuntimeException("비밀번호 불일치");                          // :29
        }
```

두 경우 모두 401로 매핑되지만(`GlobalExceptionHandler:18-19`) **응답 바디의 메시지가 다릅니다.**

### 왜 문제인가

공격자가 임의 비밀번호로 로그인을 시도하면 응답 메시지로 **그 이메일이 가입되어 있는지 판별**할 수 있습니다. 이메일 목록을 대입하면 가입자 명단을 만들 수 있고, 그 목록은 표적 피싱이나 크리덴셜 스터핑의 입력이 됩니다.

부수적으로 **타이밍 차이**도 신호를 줍니다. 존재하지 않는 사용자는 `findByEmail`에서 바로 예외가 나고, 존재하는 사용자는 BCrypt 검증(의도적으로 느린 연산)을 거칩니다. 메시지를 통일해도 응답 시간으로 구분될 수 있습니다.

`signup`의 `"이미 존재하는 이메일입니다."`(409)도 같은 정보를 주지만, 이건 UX상 필요하므로 보통 감수합니다(대신 레이트 리밋으로 완화).

### 수정 방향

```diff
         User user = userRepository.findByEmail(email)
-                .orElseThrow(() -> new RuntimeException("존재하지 않는 사용자"));
+                .orElseThrow(() -> new RuntimeException("이메일 또는 비밀번호가 올바르지 않습니다."));
         if (!encoder.matches(password, user.getPassword())) {
-            throw new RuntimeException("비밀번호 불일치");
+            throw new RuntimeException("이메일 또는 비밀번호가 올바르지 않습니다.");
         }
```

`GlobalExceptionHandler`의 switch 문자열도 함께 바꿔야 합니다(`:18-19`) — 안 바꾸면 **401이 아니라 500이 나갑니다.** 문자열 비교로 상태 코드를 정하는 이 구조 자체가 이런 결합을 만듭니다 → `04-exception.md`.

타이밍까지 맞추려면 사용자가 없을 때도 더미 해시로 `matches`를 한 번 수행합니다. 지금 단계에서 필수는 아닙니다.

---

## P2-2. 메서드 수준 인가와 역할 모델이 없음

grep 결과 `@PreAuthorize`, `@Secured`, `hasRole`, `@RolesAllowed` **0건**. `@EnableMethodSecurity`도 없습니다.

`JwtFilter.java:64-68`은 모든 사용자에게 동일하게 `ROLE_USER`를 부여합니다.

```java
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    email,
                    null,
                    Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"))
            );
```

토큰에는 역할 정보가 없고, DB에도 사용자 역할 컬럼이 없습니다(`entity/User.java`에 role 필드 없음). `Role.HOST`/`MEMBER` 열거형은 `Member` 엔티티의 데이터일 뿐 인가에 쓰이지 않습니다(grep 확인).

### 왜 문제인가

인가 판단이 전부 **서비스 메서드 안의 `if` 문**에 흩어져 있습니다.

| 검사 방식 | 위치 |
|---|---|
| `window.getUserId().equals(userEmail)` | `TodoService:34`, `:54`, `:153`, `:162` |
| `Objects.equals(window.getUserId(), userEmail)` | `WindowService:41`, `:63`, `:74` |
| 검사 없음 | `RoomController`, `RoomAdminController` (P0-1) |

이 방식의 문제는 **빠뜨렸을 때 알 수 없다**는 것입니다. P0-1이 정확히 그 결과입니다 — 룸 컨트롤러 4개에서 검사를 빠뜨렸고, 컴파일도 되고 테스트도(없지만) 통과합니다.

지금 규모에서 `@PreAuthorize`까지 도입할 필요는 없다고 봅니다. 소유권 검사가 리소스 조회와 붙어 있는 형태(`01-jpa-query.md` P2-3의 `findOwnedByUser`처럼 쿼리 조건에 소유자를 넣는 방식)가 더 실용적이고, "검사를 빠뜨리면 결과가 안 나온다"는 형태라 안전합니다.

### 수정 방향

소유권 검사를 **쿼리 조건으로** 내리세요. 그러면 검사를 잊는 것이 곧 "데이터가 안 나옴"이 됩니다.

```java
// 검사를 잊으면 결과가 비므로, 실패가 안전한 방향(fail-closed)입니다
Optional<Window> findByIdAndUserId(Long id, String userId);
```

향후 관리자 기능이 생기면 `User`에 역할 컬럼을 추가하고 토큰 클레임에 실어 `@EnableMethodSecurity` + `@PreAuthorize`를 도입하세요. 그때 P1-1의 `typ` 클레임 작업과 함께 하면 토큰 형식을 한 번만 바꿀 수 있습니다.

---

## P2-3. `SecurityUtil`이 죽은 코드이면서, 쓰이면 익명 사용자를 이메일로 반환

**위치** `util/SecurityUtil.java:6-15`

```java
public class SecurityUtil {
    public static String getCurrentEmail() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getPrincipal() == null) {
            throw new RuntimeException("인증 정보 없음");
        }
        return auth.getPrincipal().toString();
    }
}
```

grep 결과 **호출부 0건**입니다.

### 왜 문제인가

미사용 자체는 P2이지만, **쓰이는 순간 버그가 됩니다.**

Spring Security는 인증되지 않은 요청에 대해 `AnonymousAuthenticationToken`을 넣습니다. 그 토큰의 principal은 `null`이 아니라 문자열 `"anonymousUser"`입니다. 따라서:

- `auth == null` → 거짓
- `auth.getPrincipal() == null` → 거짓
- 반환값 → **`"anonymousUser"`**

이 값이 `Window.userId`나 `Timer.userId`의 조회 키로 쓰이면, **`userId = "anonymousUser"`인 공유 데이터 영역**이 생깁니다. 미인증 요청이 서로의 데이터를 보게 됩니다.

지금은 `AuthenticationCheckFilter`가 익명 요청을 401로 막아서(`AuthenticationCheckFilter:33-37`) 이 경로에 도달하지 않습니다. 하지만 P1-4에서 그 필터를 제거하면 표준 Security 흐름으로 바뀌고, 그때 이 코드가 쓰이면 문제가 됩니다.

### 수정 방향

파일을 삭제하세요. 컨트롤러가 이미 `@AuthenticationPrincipal`을 쓰고 있고 그게 더 명확합니다.

남긴다면 익명 여부를 반드시 확인해야 합니다.

```java
if (auth == null || !auth.isAuthenticated()
        || auth instanceof AnonymousAuthenticationToken) {
    throw new AccessDeniedException("인증 정보 없음");
}
```

---

## P2-4. DB 연결이 평문 (`useSSL=false`)

**위치** `src/main/resources/application.yml:7`

```yaml
    url: jdbc:mysql://${DB_HOST}:${DB_PORT}/${DB_NAME}?useSSL=false&serverTimezone=UTC
```

### 왜 문제인가

`useSSL=false`는 앱과 MySQL 사이의 트래픽을 암호화하지 않습니다. 그 트래픽에는 **연결 시 전송되는 인증 정보와 이후 모든 쿼리·결과**가 포함됩니다. 즉 이메일, 비밀번호 해시, 채팅 내용이 평문으로 흐릅니다.

현재 배포 형태에서는 위험이 제한적입니다 — `docker-compose.yml`에서 app과 mysql이 같은 Docker 네트워크에 있고, MySQL 포트가 호스트로 노출되어 있긴 하지만(`docker-compose.yml:8-9`의 `"3306:3306"`) 트래픽 자체는 컨테이너 간 통신입니다.

문제가 되는 시점은 **DB를 분리할 때**입니다. RDS 같은 관리형 DB나 다른 호스트로 옮기면 그 트래픽이 네트워크를 타고 평문으로 흐릅니다. 그때 이 파라미터를 기억해서 바꿔야 한다는 게 위험입니다.

부수적으로 `useSSL`은 MySQL Connector/J 5.x 시절 파라미터이고, 8.x에서는 `sslMode`로 대체되었습니다. 여전히 인식되지만 deprecated입니다.

또 하나: `docker-compose.yml:8-9`가 MySQL 3306을 **호스트에 노출**합니다. 앱이 같은 네트워크에 있으므로 이 포트 매핑은 필요하지 않습니다(로컬 개발에서 DB에 직접 붙을 때만 유용). 방화벽이 없으면 인터넷에서 DB에 직접 접근할 수 있습니다.

### 수정 방향

```diff
-    url: jdbc:mysql://${DB_HOST}:${DB_PORT}/${DB_NAME}?useSSL=false&serverTimezone=UTC
+    url: jdbc:mysql://${DB_HOST}:${DB_PORT}/${DB_NAME}?sslMode=${DB_SSL_MODE:DISABLED}&connectionTimeZone=UTC
```

로컬은 `DISABLED`, 운영은 `REQUIRED`로 환경변수를 주면 됩니다. `serverTimezone`도 8.x에서는 `connectionTimeZone`이 권장 이름입니다.

그리고 운영 compose에서 DB 포트 노출을 제거하세요.

```diff
   mysql:
     image: mysql:8.0
-    ports:
-      - "3306:3306"
+    expose:
+      - "3306"          # 같은 네트워크의 컨테이너에만 노출
```

`[[확인필요: 운영 서버에서 3306 포트가 외부에 열려 있는지 — 보안그룹/방화벽 설정]]`

---

## P2-5. `logout`의 쿠키 속성이 `login`과 다름

**위치** `service/AuthService.java:37-52` (login) vs `:77-89` (logout)

| 속성 | login | logout | refresh |
|---|---|---|---|
| `httpOnly` | true | true | true |
| `secure` | true | true | true |
| `path` | `/` | `/` | `/` |
| `sameSite` | **`None`** | **없음** | `None` |
| `maxAge` | 3600 / 604800 | 0 | 3600 |

쿠키 삭제는 (name, domain, path)로 매칭되므로 `sameSite`가 달라도 덮어쓰기 자체는 동작할 것으로 보입니다. 다만 브라우저별 SameSite 정책이 계속 변해 왔고, cross-site 응답에서 `Set-Cookie`를 처리하는 규칙이 속성에 따라 달라질 수 있습니다.

`[[확인필요: 실제 브라우저에서 cross-site 로그아웃 후 AccessToken 쿠키가 제거되는지 — DevTools > Application > Cookies로 확인]]`

### 왜 문제인가

세 곳에 쿠키 생성 코드가 복붙되어 있고 값이 미묘하게 다릅니다. 이런 중복은 한 곳만 고치고 다른 곳을 잊는 사고를 만듭니다 — P1-2에서 `sameSite`를 `Lax`로 바꾸려면 **세 곳을 모두** 찾아야 하고, `logout`은 애초에 그 속성이 없어서 놓치기 쉽습니다.

### 수정 방향

쿠키 생성을 한 곳으로 모읍니다(`03-transaction.md` P2-2의 `HttpServletResponse` 제거와 같은 작업).

```java
@Component
public class AuthCookieFactory {
    @Value("${app.cookie.same-site:Lax}") private String sameSite;
    @Value("${app.cookie.secure:true}")   private boolean secure;

    public ResponseCookie access(String token)  { return build("AccessToken", token, Duration.ofHours(1)); }
    public ResponseCookie refresh(String token) { return build("RefreshToken", token, Duration.ofDays(7)); }
    public ResponseCookie expire(String name)   { return build(name, "", Duration.ZERO); }

    private ResponseCookie build(String name, String value, Duration ttl) {
        return ResponseCookie.from(name, value)
                .httpOnly(true).secure(secure).path("/")
                .maxAge(ttl).sameSite(sameSite).build();
    }
}
```

TTL이 `JwtUtil`의 만료 시간과 따로 관리되고 있다는 점도 짚어둘 만합니다 — 쿠키 `maxAge`는 3600, 토큰 `exp`도 3600으로 지금은 일치하지만, 한쪽만 바꾸면 어긋납니다. P1-9에서 제안한 `jwt.access-token-ttl` 설정을 양쪽이 함께 읽게 하세요.

---

## P2-6. 채팅 메시지 무검증, 기술 스택 노출

**(1) 채팅 메시지 검증 없음** — `controller/ChatController.java:16-18`

P0-2에 포함된 내용이지만 별도로 기록합니다. `content` 길이 제한이 없어 임의 크기 문자열이 인메모리 브로커(`WsConfig.java:23`)에 올라가고 모든 구독자에게 전달됩니다. TODO 주석이 이미 인정하고 있습니다. 서버가 원문을 그대로 중계하므로 XSS 방어가 전적으로 프론트 렌더링에 달려 있습니다 — 방어선을 하나 더 두는 것이 맞습니다.

`enableSimpleBroker`는 인메모리 구현이라 인스턴스를 2개 이상 띄우면 **서로 다른 인스턴스에 붙은 사용자끼리 메시지가 전달되지 않습니다.** 보안 문제는 아니지만 확장 시 알아야 할 제약입니다.

**(2) 기술 스택 노출** — `controller/HelloController.java:8-11`, `src/main/resources/banner.txt`

```java
    @GetMapping("/")
    public String hello() {
        return "Hello from Dockerized Spring Boot!";
    }
```

`GET /`는 permitAll이 아니므로 실제로는 401이 나갑니다(P1-4). 즉 현재 노출되지 않지만, 남겨둘 이유가 없는 코드입니다.

`banner.txt`의 `"THIS IS THE LATEST CODE"`는 로그에만 나가므로 외부 노출은 아닙니다. 배포 확인용으로 쓴 흔적으로 보이는데, 버전 확인은 `/actuator/info`의 buildInfo로 하는 게 맞습니다.

`server.error.include-stacktrace` 기본값이 `never`이므로 스택트레이스가 응답에 실리지는 않습니다. 다만 `GlobalExceptionHandler`가 예외 메시지를 그대로 바디에 넣으므로 내부 정보가 새는 경로가 있습니다 → `04-exception.md`.

---

## 요약

| 심각도 | 개수 | 항목 |
|---|---|---|
| **P0** | 4 | ① `/rooms` 계열이 body의 `userId`를 신뢰 → 임의 사칭·임의 강퇴 (`kick`에 권한 검사 0줄) ② WebSocket Origin 검사 비활성화 → 임의 사이트가 피해자 세션으로 전 채팅 감청·사칭 ③ **공개 저장소** git 히스토리에 DB 자격증명 리터럴 (+ `.env`가 `.gitignore`에 없음) ④ `POST /windows`가 엔티티를 바디로 받아 임의 창 탈취·투두 삭제 (`06-architecture.md` P0-1) |
| **P1** | 7 | 액세스/리프레시 토큰 구분 불가 + 무효화 수단 없음 / CSRF 비활성 + `SameSite=None` / 인코더 이중화(계정에 약한 쪽) + Argon2 메모리 DoS / 커스텀 필터가 permitAll 무력화 / `/actuator/**` 와일드카드 permitAll / `spring.security.debug: true` / 운영 CORS에 localhost / jjwt deprecated API + 시크릿 Base64 해석 |
| **P2** | 6 | 사용자 열거 / 메서드 수준 인가·역할 모델 없음 / `SecurityUtil`이 익명을 이메일로 반환 / DB 평문 연결 + 3306 호스트 노출 / logout 쿠키 속성 불일치 / 채팅 무검증·스택 노출 |

## 확인이 필요한 항목 (7개)

1. ~~`origin` GitHub 저장소가 public인가~~ → **확인 완료: PUBLIC.** P1-6 → P0-3으로 상향
2. `members.user_id`에 저장된 값의 형식 (이메일인지 별도 id인지) — P0-1 수정 시 데이터 이관 필요 여부
3. `/topic/**` 와일드카드 STOMP 구독이 실제로 전체 메시지를 수신하는지 — P0-2 영향 범위
4. API 호스트가 `oeyo-cam.site`와 same-site인지 — P1-2에서 `SameSite=Lax` 가능 여부
5. 부팅 로그에 Security 필터 체인 목록이 출력되는지 — P1-7 갈래 판정
6. `JWT_SECRET`이 Base64 형식인지 평문인지 (**값은 확인하지 말고 형식만**) — P1-9
7. 운영 서버에서 3306 포트가 외부에 열려 있는지 — P2-4

## 수정 순서 (의존관계 있음)

```
0. P0-3  자격증명 교체(DB 비밀번호, JWT_SECRET) ← 공개 저장소이므로 최우선
1. P0-1  룸 계열 인가          ← 코드 변경만으로 가능
1b. P0-4 POST /windows의 setId(null) 또는 요청 DTO  ← 한 줄로 즉시 차단 가능
2. P0-2  WS Origin 한 줄        ← 그 다음 STOMP 인터셉터
4. P1-5  actuator 노출 제한  ┐
5. P1-4  커스텀 필터 제거    ┘  ← 반드시 이 순서. 4를 안 하고 5를 하면 actuator가 열립니다
6. P1-7  security.debug 제거
7. P1-8  CORS localhost 제거 (P0-2의 WS 목록과 값 공유)
8. P1-1  토큰 typ 클레임 + P1-9 시크릿/Key 정리 + 02 P1-2 파싱 1회  ← 한 번에
9. P1-2  SameSite / CSRF       ← 4번 항목 확인 후
10. P1-3 DelegatingPasswordEncoder  ← rooms.password_hash 이관 계획 필요
```

**5번이 4번보다 먼저 오면 안 됩니다.** `AuthenticationCheckFilter`를 제거하면 `SecurityConfig`의 `/actuator/**` permitAll이 실제로 발효되어, 지금 막혀 있던 경로가 공개됩니다.

침투 테스트는 하지 않았습니다. 위 공격 시나리오들은 소스에서 확인한 코드 경로와 브라우저/Spring Security의 동작 원리에 근거한 것이며, 실제 악용 가능성은 스테이징 환경에서 직접 시도해 확인해야 합니다.
