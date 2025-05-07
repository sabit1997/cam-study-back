// src/main/java/com/camstudy/backend/config/SecurityConfig.java
package com.camstudy.backend.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;

@SuppressWarnings("unused")
@Configuration
public class SecurityConfig {

    private final JwtFilter jwtFilter;

    public SecurityConfig(JwtFilter jwtFilter) {
        this.jwtFilter = jwtFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // CORS 설정: localhost, vercel, studycam.shop 허용
            .cors(cors -> cors.configurationSource(request -> {
                CorsConfiguration config = new CorsConfiguration();
                config.setAllowedOriginPatterns(List.of(
                    "http://localhost:3000",
                    "https://cam-study.vercel.app",
                    "https://studycam.shop"
                ));
                config.setAllowedMethods(List.of("*"));
                config.setAllowedHeaders(List.of("*"));
                config.setAllowCredentials(true);
                return config;
            }))
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                // 인증 없이 접근 허용
                .requestMatchers("/auth/**").permitAll()
                .requestMatchers(HttpMethod.GET,    "/windows/**").permitAll()          // 윈도우 조회
                .requestMatchers(HttpMethod.GET,    "/windows/*/todos").permitAll()    // ✔ todos 조회 허용
                .requestMatchers(HttpMethod.POST,   "/windows/*/todos").permitAll()    // (필요 시)
                .requestMatchers(HttpMethod.PATCH,  "/windows/*/todos/**").permitAll() // (필요 시)
                // 나머지는 인증 필요
                .anyRequest().authenticated()
            )
            // JWT 필터 삽입
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
