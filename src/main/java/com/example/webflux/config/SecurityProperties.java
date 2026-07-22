package com.example.webflux.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

/**
 * app.security.api-keys 설정을 바인딩합니다.
 * 빈 문자열이 섞여 있을 수 있으므로, 유효 여부는 isValidKey()로 판정하세요.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.security")
public class SecurityProperties {

    private List<String> apiKeys = List.of();

    /** key가 설정 목록에 있고 비어 있지 않으면 true */
    public boolean isValidKey(String key) {
        if (key == null || key.isBlank()) return false;
        return apiKeys.stream().anyMatch(k -> !k.isBlank() && k.equals(key));
    }
}
