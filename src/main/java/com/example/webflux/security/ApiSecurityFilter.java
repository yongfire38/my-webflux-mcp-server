package com.example.webflux.security;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import com.example.webflux.config.SecurityProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * REST 엔드포인트 접근 통제 필터.
 *
 * 규칙 A — localhost 전용 경로 (서버 로컬에서만 호출):
 *   POST /api/documents/reindex, GET /api/documents/status
 *   → RemoteAddr이 127.0.0.1 또는 ::1이 아니면 403
 *
 * 규칙 B — API 키 보호 경로 (신뢰 클라이언트만):
 *   POST /api/documents/upload
 *   → X-API-Key 헤더가 설정 키 목록에 없으면 401
 *
 * 그 외 경로(/mcp, Swagger 등)는 관여하지 않고 다음 필터로 통과.
 */
@Slf4j
@Component
@Order(-100)
@RequiredArgsConstructor
public class ApiSecurityFilter implements WebFilter {

    private static final String REINDEX_PATH  = "/api/documents/reindex";
    private static final String STATUS_PATH   = "/api/documents/status";
    private static final String UPLOAD_PATH   = "/api/documents/upload";
    private static final String API_KEY_HEADER = "X-API-Key";

    private final SecurityProperties securityProperties;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path   = exchange.getRequest().getPath().value();
        String method = exchange.getRequest().getMethod().name();

        // 규칙 A: localhost 전용
        if (isLocalhostOnly(path, method)) {
            String remoteAddr = resolveRemoteAddr(exchange);
            if (!isLocalhost(remoteAddr)) {
                log.warn("[보안] 외부 IP에서 localhost 전용 경로 접근 차단 — path: {}, addr: {}",
                        path, remoteAddr);
                exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                return exchange.getResponse().setComplete();
            }
        }

        // 규칙 B: API 키 보호
        if (isApiKeyProtected(path, method)) {
            String key = exchange.getRequest().getHeaders().getFirst(API_KEY_HEADER);
            if (!securityProperties.isValidKey(key)) {
                log.warn("[보안] 유효하지 않은 API 키로 업로드 시도 차단 — path: {}", path);
                exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                return exchange.getResponse().setComplete();
            }
        }

        return chain.filter(exchange);
    }

    private boolean isLocalhostOnly(String path, String method) {
        return (REINDEX_PATH.equals(path) && "POST".equals(method))
                || (STATUS_PATH.equals(path) && "GET".equals(method));
    }

    private boolean isApiKeyProtected(String path, String method) {
        return UPLOAD_PATH.equals(path) && "POST".equals(method);
    }

    private String resolveRemoteAddr(ServerWebExchange exchange) {
        // 리버스 프록시 환경 대비: X-Forwarded-For가 있으면 우선 사용
        String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        var addr = exchange.getRequest().getRemoteAddress();
        return addr != null ? addr.getAddress().getHostAddress() : "";
    }

    private boolean isLocalhost(String addr) {
        return "127.0.0.1".equals(addr) || "::1".equals(addr) || "0:0:0:0:0:0:0:1".equals(addr);
    }
}
