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
                .requestMatchers(
                    "/auth/**",
                    "/error",
                    "/ws/**"
                ).permitAll()
                // 와일드카드(/actuator/**)로 열어두면 나중에 노출 대상이 늘어날 때
                // /actuator/env 로 JWT_SECRET 이 함께 공개된다. 헬스체크만 허용한다.
                .requestMatchers("/actuator/health").permitAll()
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
        config.setAllowedOriginPatterns(List.of(
            "https://oeyo-cam.site",
            "https://www.oeyo-cam.site",
            "http://localhost:3000",
            "http://localhost:3001"
        ));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
