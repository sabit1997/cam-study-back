// src/main/java/com/camstudy/backend/dto/SignupRequest.java
package com.camstudy.backend.dto; // dto 패키지에 생성

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor; // 기본 생성자 추가
import lombok.AllArgsConstructor; // 모든 필드 포함 생성자 추가

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SignupRequest {
    private String email;
    private String password;
    private String username;
}