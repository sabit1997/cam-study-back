package com.camstudy.backend.controller;

import com.camstudy.backend.service.AuthService;
import lombok.Data;
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
    public String login(@RequestBody AuthRequest request) {
        return authService.login(request.getEmail(), request.getPassword());
    }

    @Data
    static class AuthRequest {
        private String email;
        private String password;
    }
}
