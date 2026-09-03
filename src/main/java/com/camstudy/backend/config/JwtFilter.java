package com.camstudy.backend.config;

import com.camstudy.backend.util.JwtUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * AccessToken 쿠키가 유효하면 SecurityContext 에 인증 정보를 채운다.
 *
 * 이 필터는 요청을 거부하지 않는다. 토큰이 없거나 유효하지 않으면 그냥 통과시키고,
 * 그 요청을 허용할지는 SecurityConfig 의 authorizeHttpRequests 가 판단한다.
 *
 * 예전에는 이 필터 뒤에 AuthenticationCheckFilter 가 있어서 인가 단계보다 먼저
 * 401 을 보내고 있었다. 그 때문에 SecurityConfig 의 permitAll 선언이 평가되지
 * 않아 /actuator/health 같은 경로가 선언과 다르게 동작했다.
 */
@Component
public class JwtFilter extends OncePerRequestFilter {

    private static final String ACCESS_TOKEN_COOKIE = "AccessToken";

    private final JwtUtil jwtUtil;

    public JwtFilter(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String token = extractToken(request);

        // TODO(단계 3): validate 와 getEmail 이 같은 토큰을 두 번 파싱한다.
        //               Claims 를 반환하는 메서드로 통합하고 typ 클레임을 검증할 예정.
        if (token != null && jwtUtil.validate(token)) {
            String email = jwtUtil.getEmail(token);
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(
                            email,
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        }

        chain.doFilter(request, response);
    }

    /** 요청 쿠키에서 AccessToken 값을 추출한다 */
    private String extractToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (ACCESS_TOKEN_COOKIE.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
