package com.camstudy.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class PasswordConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        // Spring Security 5.8 기준 합리적 디폴트 파라미터
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
        // 필요 시 커스텀:
        // return new Argon2PasswordEncoder(saltLength, hashLength, parallelism, memory, iterations);
    }
}
