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
    // [추가] 새로 만든 필터를 주입받습니다.
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
                .requestMatchers("/auth/**", "/error").permitAll()
                .anyRequest().authenticated()
            )
            // [수정] 이전에 추가했던 exceptionHandling은 이제 필요 없습니다.
            // .exceptionHandling(...) 부분 제거

            // JwtFilter가 먼저 실행되어 토큰이 있으면 인증 정보를 설정하고,
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
            // [추가] 바로 뒤에 우리가 만든 인증 체크 필터를 실행합니다.
            // 이 필터가 인증 정보가 없으면 무조건 401을 반환하고 요청을 끝내버립니다.
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