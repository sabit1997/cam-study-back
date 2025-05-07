package com.camstudy.backend.controller;

import com.camstudy.backend.service.AuthService;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/signup")
    public String signup(@RequestBody AuthRequest request) {
        return authService.signup(request.getEmail(), request.getPassword());
    }

    @PostMapping("/login")
    public Map<String, String> login(@RequestBody AuthRequest request) {
        String token = authService.login(request.getEmail(), request.getPassword());

        Map<String, String> response = new HashMap<>();
        response.put("accessToken", token);
        return response;
    }

    @PostMapping("/logout")
    public String logout(@RequestHeader("Authorization") String authorizationHeader) {
        // Bearer 토큰 추출
        String token = authorizationHeader.replace("Bearer ", "");
        return authService.logout(token);
    }


    @Data
    static class AuthRequest {
        private String email;
        private String password;
    }
}
