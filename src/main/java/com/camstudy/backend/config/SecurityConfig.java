package com.camstudy.backend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Configuration
public class SecurityConfig {

    private final JwtFilter jwtFilter;
    private final ObjectMapper objectMapper;

    public SecurityConfig(JwtFilter jwtFilter, ObjectMapper objectMapper) {
        this.jwtFilter = jwtFilter;
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(AbstractHttpConfigurer::disable)
            // JWT 인증이므로 세션이 필요 없다. 기본값(IF_REQUIRED)이면 요청마다
            // JSESSIONID 세션이 만들어질 수 있다.
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/auth/**", "/error").permitAll()
                // 와일드카드(/actuator/**)로 열어두면 나중에 노출 대상이 늘어날 때
                // /actuator/env 로 JWT_SECRET 이 함께 공개된다. 헬스체크만 허용한다.
                .requestMatchers("/actuator/health").permitAll()
                // WebSocket 핸드셰이크도 인증을 요구한다(기존 동작과 동일).
                .anyRequest().authenticated()
            )
            // 인증/인가 실패 응답을 여기서 한 번에 만든다.
            // sendError() 로 넘기면 컨테이너가 /error 로 포워딩해서
            // {"timestamp","status","error","path"} 형태가 되고 message 가 사라진다.
            .exceptionHandling(e -> e
                .authenticationEntryPoint((req, res, ex) ->
                        writeError(res, HttpServletResponse.SC_UNAUTHORIZED,
                                   "UNAUTHORIZED", "인증이 필요합니다."))
                .accessDeniedHandler((req, res, ex) ->
                        writeError(res, HttpServletResponse.SC_FORBIDDEN,
                                   "FORBIDDEN", "권한이 없습니다."))
            )
            // 토큰이 있으면 SecurityContext 를 채우기만 한다.
            // 인가 판단은 위 authorizeHttpRequests 가 담당한다.
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    private void writeError(HttpServletResponse res, int status, String code, String message)
            throws IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(res.getWriter(), Map.of("code", code, "message", message));
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
