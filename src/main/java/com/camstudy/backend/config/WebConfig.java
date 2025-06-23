package com.camstudy.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.util.UrlPathHelper; // 이 임포트도 추가되었는지 확인

@Configuration // 이 클래스가 스프링 설정 클래스임을 명시합니다.
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        // UrlPathHelper를 사용하여 URL 디코딩 방식 등을 설정할 수 있습니다.
        // 여기서는 기본값을 사용해도 대부분 문제 없습니다.
        UrlPathHelper urlPathHelper = new UrlPathHelper();
        // urlPathHelper.setUrlDecode(false); // 필요하다면 URL 인코딩/디코딩 방식을 제어할 수 있습니다.

        configurer.setUrlPathHelper(urlPathHelper);
        // 이 부분이 핵심입니다. 요청 URL 끝의 슬래시 유무와 관계없이 매핑을 허용합니다.
        configurer.setUseTrailingSlashMatch(true); 
    }
}