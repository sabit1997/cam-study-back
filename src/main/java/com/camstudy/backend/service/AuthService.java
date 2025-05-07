package com.camstudy.backend.service;

import com.camstudy.backend.entity.User;
import com.camstudy.backend.repository.UserRepository;
import com.camstudy.backend.util.JwtUtil;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthService(UserRepository userRepository, JwtUtil jwtUtil) {
        this.userRepository = userRepository;
        this.jwtUtil = jwtUtil;
    }

    public String signup(String email, String password) {
        if (userRepository.findByEmail(email).isPresent()) {
            throw new RuntimeException("이미 존재하는 이메일입니다.");
        }
        String hash = encoder.encode(password);
        userRepository.save(User.builder().email(email).password(hash).build());
        return "회원가입 완료";
    }

    public String login(String email, String password) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("존재하지 않는 사용자"));

        if (!encoder.matches(password, user.getPassword())) {
            throw new RuntimeException("비밀번호 불일치");
        }

        return jwtUtil.createToken(user.getEmail());
    }

    public String logout(String token) {
        // JWT는 만료시킬 수 없기 때문에,
        // 블랙리스트 처리하지 않는 한 서버 쪽에서 할 수 있는 건 없음.
        // 여기에선 클라이언트에게 토큰 삭제 요청만 안내함.
        return "로그아웃 완료 — 토큰을 클라이언트에서 삭제해주세요.";
    }
}
