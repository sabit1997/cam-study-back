package com.camstudy.backend.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class AuthenticationCheckFilter extends OncePerRequestFilter {

    // 이 필터를 건너뛸 경로 목록 (JwtFilter와 동일하게 설정)
    private static final List<String> EXCLUDE_URLS = List.of("/auth/", "/error");

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        // SecurityContext에서 현재 인증 정보를 가져옵니다.
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        // 인증 정보가 없거나(null), 익명 사용자(AnonymousAuthenticationToken)인 경우
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken) {
            // 즉시 401 Unauthorized 에러를 보내고 필터 체인을 중단합니다.
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "인증이 필요합니다.");
            return;
        }

        // 위 조건에 해당하지 않으면 (즉, JwtFilter를 통해 인증 정보가 설정된 경우)
        // 다음 필터로 정상적으로 진행합니다.
        filterChain.doFilter(request, response);
    }

    // JwtFilter와 동일하게, 인증이 필요 없는 경로는 이 필터를 실행하지 않도록 설정
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return EXCLUDE_URLS.stream().anyMatch(path::startsWith);
    }
}