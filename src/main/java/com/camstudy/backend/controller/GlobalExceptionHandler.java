package com.camstudy.backend.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> handleRuntimeException(RuntimeException e) {
        String message = e.getMessage();

        HttpStatus status = switch (message) {
            case "존재하지 않는 사용자", "비밀번호 불일치",
                 "리프레시 토큰이 유효하지 않습니다.", "리프레시 토큰이 존재하지 않습니다." -> HttpStatus.UNAUTHORIZED;
            case "이미 존재하는 이메일입니다." -> HttpStatus.CONFLICT;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };

        return ResponseEntity.status(status).body(Map.of("message", message));
    }
}
