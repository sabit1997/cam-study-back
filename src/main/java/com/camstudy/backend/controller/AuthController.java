package com.camstudy.backend.controller;

import com.camstudy.backend.dto.LoginRequest; // 새로 생성한 LoginRequest DTO import
import com.camstudy.backend.dto.SignupRequest; // 기존 SignupRequest DTO import
import com.camstudy.backend.service.AuthService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;

@RestController // 이 클래스가 REST API를 처리하는 컨트롤러임을 명시
@RequestMapping("/auth") // 이 컨트롤러의 모든 핸들러 메서드는 "/auth" 경로 아래에 매핑됨
public class AuthController {

    private final AuthService authService; // AuthService 의존성 주입을 위한 필드

    // 생성자 주입 (권장 방식): Spring이 AuthService 빈을 자동으로 주입
    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    // 로그인 엔드포인트: POST /auth/login
    // @RequestBody: HTTP 요청 본문에 담긴 JSON 데이터를 LoginRequest 객체로 매핑
    @PostMapping("/login")
    public String login(@RequestBody LoginRequest loginRequest, HttpServletResponse response) {
        // LoginRequest DTO에서 이메일과 비밀번호를 추출하여 AuthService로 전달
        return authService.login(loginRequest.getEmail(), loginRequest.getPassword(), response);
    }

    // 회원가입 엔드포인트: POST /auth/signup
    // @RequestBody: HTTP 요청 본문에 담긴 JSON 데이터를 SignupRequest 객체로 매핑
    @PostMapping("/signup")
    public String signup(@RequestBody SignupRequest signupRequest) {
        // SignupRequest DTO에서 필요한 정보를 추출하여 AuthService로 전달
        return authService.signup(signupRequest.getEmail(), signupRequest.getPassword(), signupRequest.getUsername());
    }

    // 로그아웃 엔드포인트: POST /auth/logout
    @PostMapping("/logout")
    public String logout(HttpServletResponse response) {
        return authService.logout(response);
    }

    // 리프레시 토큰 갱신 엔드포인트: POST /auth/refresh
    // @CookieValue: 요청 쿠키에서 "RefreshToken" 값을 추출
    @PostMapping("/refresh")
    public String refresh(@CookieValue(value = "RefreshToken") String refreshToken, HttpServletResponse response) {
        if (refreshToken == null || refreshToken.isEmpty()) {
            throw new RuntimeException("리프레시 토큰이 존재하지 않습니다.");
        }
        return authService.refreshAccessToken(refreshToken, response);
    }
}