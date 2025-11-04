// src/main/java/com/camstudy/backend/controller/AuthController.java
package com.camstudy.backend.controller;

import com.camstudy.backend.dto.LoginRequest;
import com.camstudy.backend.dto.LoginResponse;
import com.camstudy.backend.dto.SignupRequest;
import com.camstudy.backend.service.AuthService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;
    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest loginRequest, HttpServletResponse response) {
        return authService.login(loginRequest.getEmail(), loginRequest.getPassword(), response);
    }

    @PostMapping("/signup")
    public String signup(@RequestBody SignupRequest signupRequest) {
        return authService.signup(signupRequest.getEmail(), signupRequest.getPassword(), signupRequest.getUsername());
    }

    @PostMapping("/logout")
    public String logout(HttpServletResponse response) {
        return authService.logout(response);
    }

    @PostMapping("/refresh")
    public String refresh(@CookieValue(value = "RefreshToken") String refreshToken,
                          HttpServletResponse response) {
        if (refreshToken == null || refreshToken.isEmpty()) {
            throw new RuntimeException("리프레시 토큰이 존재하지 않습니다.");
        }
        return authService.refreshAccessToken(refreshToken, response);
    }
}
