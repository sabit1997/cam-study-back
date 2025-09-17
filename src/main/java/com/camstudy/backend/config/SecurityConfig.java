package com.camstudy.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
public class SecurityConfig {

    private final JwtFilter jwtFilter;
    // 커스텀 인증 체크 필터
    private final AuthenticationCheckFilter authenticationCheckFilter;

    public SecurityConfig(JwtFilter jwtFilter, AuthenticationCheckFilter authenticationCheckFilter) {
        this.jwtFilter = jwtFilter;
        this.authenticationCheckFilter = authenticationCheckFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth
                // ⬇️ permitAll 경로들 추가
                .requestMatchers(
                    "/auth/**",
                    "/error",
                    "/ws/**",                 // STOMP 핸드셰이크
                    "/livekit/webhook",  // LiveKit 웹훅 수신
                    "/actuator/**"           // 헬스/인포 (필요 시)
                ).permitAll()
                .anyRequest().authenticated()
            )
            // JwtFilter가 먼저 실행되어 토큰이 있으면 인증 정보를 설정
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
            // 그 다음 커스텀 인증 체크 필터
            .addFilterAfter(authenticationCheckFilter, JwtFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
