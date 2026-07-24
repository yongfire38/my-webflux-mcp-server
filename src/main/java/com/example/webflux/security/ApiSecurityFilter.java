package com.example.webflux.security;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * REST 엔드포인트 접근 통제 필터.
 *
 * 규칙 A — localhost 전용 경로 (서버 로컬에서만 호출):
 *   POST /api/documents/reindex, GET /api/documents/status
 *   → RemoteAddr이 127.0.0.1 또는 ::1이 아니면 403
 *
 * 그 외 경로(/mcp, Swagger 등)는 관여하지 않고 다음 필터로 통과.
 */
@Slf4j
@Component
@Order(-100)
public class ApiSecurityFilter implements WebFilter {

    private static final String REINDEX_PATH = "/api/documents/reindex";
    private static final String STATUS_PATH  = "/api/documents/status";

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

        return chain.filter(exchange);
    }

    private boolean isLocalhostOnly(String path, String method) {
        return (REINDEX_PATH.equals(path) && "POST".equals(method))
                || (STATUS_PATH.equals(path) && "GET".equals(method));
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
