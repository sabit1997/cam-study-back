package com.camstudy.backend.config;

import com.camstudy.backend.util.JwtUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken; // 인증 토큰 클래스
import org.springframework.security.core.context.SecurityContextHolder; // 보안 컨텍스트 관리 클래스
import org.springframework.security.core.authority.SimpleGrantedAuthority; // 권한 부여를 위한 클래스
import org.springframework.stereotype.Component; // Spring 빈으로 등록하기 위한 애너테이션
import org.springframework.web.filter.OncePerRequestFilter; // 요청당 한 번만 실행되는 필터를 위한 기본 클래스

import java.io.IOException;
import java.util.Collections; // 싱글톤 리스트 생성을 위해 사용
import java.util.List;

@Component // 이 클래스를 Spring 빈으로 등록하여 Spring 컨테이너가 관리하도록 함
public class JwtFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil; // JWT 관련 유틸리티 클래스 의존성 주입

    // JwtFilter가 적용되지 않을 (인증이 필요 없는) URL 경로 목록
    // SecurityConfig의 permitAll() 경로와 일치시키는 것이 중요합니다.
    private static final List<String> EXCLUDE_URLS = List.of(
            "/auth/", // "/auth"로 시작하는 모든 경로 (예: /auth/login, /auth/signup)
            "/error" // Spring Boot의 기본 에러 처리 경로
    );

    // 생성자 주입: Spring이 JwtUtil 빈을 자동으로 주입
    public JwtFilter(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    // 실제 필터 로직이 구현되는 메서드
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        // 1. 요청에서 JWT AccessToken 쿠키 추출
        String token = extractToken(request);

        // 2. 토큰이 없거나 유효하지 않은 경우 (혹은 shouldNotFilter에서 이미 걸러지지 않은 경우)
        // 다음 필터로 넘기고 종료 (여기서 401을 직접 보내면 Spring Security의 다른 에러 처리와 충돌할 수 있음)
        // 현재 로직은 토큰이 없거나 유효하지 않으면 아래 try-catch 블록에서 401을 보냅니다.
        // shouldNotFilter를 통해 필터 자체를 건너뛰는 것이 더 효율적입니다.
        if (token == null || !jwtUtil.validate(token)) {
             // 토큰이 없거나 유효하지 않지만, shouldNotFilter에서 걸러지지 않은 (인증이 필요한) 요청인 경우
             // Spring Security의 기본 ExceptionTranslationFilter가 처리하도록 chain.doFilter()만 호출
            chain.doFilter(request, response);
            return; // 필터 체인 진행 후 메서드 종료
        }

        try {
            // 3. 토큰에서 사용자 이메일(principal) 추출
            String email = jwtUtil.getEmail(token);

            // 4. Spring Security의 SecurityContextHolder에 인증 정보 설정
            // UsernamePasswordAuthenticationToken: 사용자 ID와 권한 정보를 담는 Authentication 구현체
            // email: 인증된 사용자의 주체 (Principal)
            // null: 자격 증명 (비밀번호 등. 인증 후에는 필요 없으므로 null)
            // Collections.singletonList(...): 사용자에게 부여할 권한 목록 (예: "ROLE_USER")
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    email,
                    null, // 인증 후이므로 비밀번호는 null
                    Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")) // 사용자 권한 설정
            );
            // 현재 요청의 보안 컨텍스트에 인증 객체를 설정
            SecurityContextHolder.getContext().setAuthentication(authentication);

        } catch (Exception e) {
            // 5. 토큰 검증 중 예외 발생 (예: 토큰 만료, 변조 등)
            // 보안 컨텍스트를 비워서 인증 상태를 제거
            SecurityContextHolder.clearContext();
            // 클라이언트에 401 Unauthorized 응답 전송
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "유효하지 않거나 만료된 토큰");
            return; // 필터 체인 진행을 중단하고 응답 반환
        }

        // 6. 모든 검증 및 설정이 완료되면 다음 필터로 요청 전달
        chain.doFilter(request, response);
    }

    // 이 메서드를 오버라이드하여 특정 URL 경로에 대해 필터가 동작하지 않도록 설정
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) throws ServletException {
        String path = request.getServletPath(); // 요청 경로 (예: "/auth/login", "/error")

        // EXCLUDE_URLS 목록에 있는 경로로 시작하거나 정확히 일치하는 경우 필터 적용 안 함
        // (즉, 해당 경로들은 JwtFilter를 건너뛰고 Spring Security의 permitAll()에 의해 처리됨)
        return EXCLUDE_URLS.stream().anyMatch(excludePath -> path.startsWith(excludePath));

        // 참고: 정확한 Ant-style 패턴 매칭이 필요하다면 PathPatternMatcher (Spring 5.3+) 또는 AntPathMatcher를 사용해야 합니다.
        // 현재는 "/auth/"와 "/error"에 대한 단순 startsWith()로도 충분할 수 있습니다.
        // `path.startsWith("/auth/") || path.equals("/error")` 와 동일한 로직입니다.
    }

    // 요청 쿠키에서 "AccessToken" 값을 추출하는 헬퍼 메서드
    private String extractToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if ("AccessToken".equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }
}