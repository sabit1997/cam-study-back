package com.camstudy.backend.dto;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

// Lombok 애너테이션: @Getter와 @Setter로 필드의 Getter/Setter 자동 생성
// @NoArgsConstructor: 인자 없는 기본 생성자 자동 생성
// @AllArgsConstructor: 모든 필드를 인자로 받는 생성자 자동 생성
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class LoginRequest {
    private String email;
    private String password;
}