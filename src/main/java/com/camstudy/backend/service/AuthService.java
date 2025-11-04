package com.camstudy.backend.service;

import com.camstudy.backend.dto.LoginResponse;
import com.camstudy.backend.entity.User;
import com.camstudy.backend.repository.UserRepository;
import com.camstudy.backend.util.JwtUtil;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.http.ResponseCookie;
import jakarta.servlet.http.HttpServletResponse;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthService(UserRepository userRepository, JwtUtil jwtUtil) {
        this.userRepository = userRepository;
        this.jwtUtil = jwtUtil;
    }

    public LoginResponse login(String email, String password, HttpServletResponse response) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("존재하지 않는 사용자"));

        if (!encoder.matches(password, user.getPassword())) {
            throw new RuntimeException("비밀번호 불일치");
        }

        // 액세스 토큰과 리프레시 토큰 생성
        String accessToken = jwtUtil.createToken(user.getEmail());
        String refreshToken = jwtUtil.createRefreshToken(user.getEmail());

        // 액세스 토큰을 쿠키에 설정
        ResponseCookie accessTokenCookie = ResponseCookie.from("AccessToken", accessToken)
                .httpOnly(true)
                .secure(true) // 프로덕션 환경에서만 true
                .path("/")
                .maxAge(60 * 60) // 1시간
                .sameSite("None") // CORS를 사용할 경우 SameSite=None 설정
                .build();

        // 리프레시 토큰을 쿠키에 설정
        ResponseCookie refreshTokenCookie = ResponseCookie.from("RefreshToken", refreshToken)
                .httpOnly(true)
                .secure(true) // 프로덕션 환경에서만 true
                .path("/")
                .maxAge(60 * 60 * 24 * 7) // 7일
                .sameSite("None")
                .build();

        // 쿠키에 추가
        response.addHeader("Set-Cookie", accessTokenCookie.toString());
        response.addHeader("Set-Cookie", refreshTokenCookie.toString());

        // 유저 정보 반환
        return new LoginResponse(user.getId(), user.getUsername());
    }

    public String signup(String email, String password, String username) {
        if (userRepository.findByEmail(email).isPresent()) {
            throw new RuntimeException("이미 존재하는 이메일입니다.");
        }
        String hash = encoder.encode(password);
        userRepository.save(User.builder()
                .email(email)
                .password(hash)
                .username(username)
                .build());
        return "회원가입 완료";
    }

    public String logout(HttpServletResponse response) {
        // 쿠키를 삭제
        ResponseCookie accessTokenCookie = ResponseCookie.from("AccessToken", null)
                .httpOnly(true)
                .secure(true)
                .path("/")
                .maxAge(0) // 쿠키 만료
                .build();

        ResponseCookie refreshTokenCookie = ResponseCookie.from("RefreshToken", null)
                .httpOnly(true)
                .secure(true)
                .path("/")
                .maxAge(0) // 쿠키 만료
                .build();

        // 쿠키 삭제
        response.addHeader("Set-Cookie", accessTokenCookie.toString());
        response.addHeader("Set-Cookie", refreshTokenCookie.toString());

        return "로그아웃 완료";
    }

    // 리프레시 토큰을 사용하여 새로운 액세스 토큰 발급
    public String refreshAccessToken(String refreshToken, HttpServletResponse response) {
        String email = jwtUtil.getEmail(refreshToken); // 리프레시 토큰에서 이메일 추출
        if (email == null) {
            throw new RuntimeException("리프레시 토큰이 유효하지 않습니다.");
        }

        // 새로운 액세스 토큰 생성
        String newAccessToken = jwtUtil.createToken(email);

        // 새 액세스 토큰을 쿠키에 설정
        ResponseCookie accessTokenCookie = ResponseCookie.from("AccessToken", newAccessToken)
                .httpOnly(true)
                .secure(true)
                .path("/")
                .maxAge(60 * 60) // 1시간
                .sameSite("None")
                .build();

        response.addHeader("Set-Cookie", accessTokenCookie.toString());
        return "새로운 액세스 토큰이 발급되었습니다.";
    }
}
