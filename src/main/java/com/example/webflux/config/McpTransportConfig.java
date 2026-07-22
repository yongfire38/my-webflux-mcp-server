package com.example.webflux.config;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import io.modelcontextprotocol.server.transport.WebFluxStreamableServerTransportProvider;

/**
 * 자동 구성된 WebFluxStreamableServerTransportProvider를 재정의합니다.
 *
 * HTTP 요청의 X-MCP-API-Key 헤더를 McpTransportContext에 주입합니다.
 * Tool 메서드에서 ctx.transportContext().get("x-mcp-api-key")로 꺼내 검증합니다.
 *
 * spring.main.allow-bean-definition-overriding: true 가 활성화되어 있으므로
 * 자동 구성 빈 등록 이후 이 @Primary 빈이 자동으로 우선됩니다.
 */
@Configuration
public class McpTransportConfig {

    public static final String TRANSPORT_CTX_API_KEY = "x-mcp-api-key";
    public static final String HEADER_API_KEY = "X-MCP-API-Key";

    @Bean
    @Primary
    public WebFluxStreamableServerTransportProvider mcpWebFluxTransportProvider(
            McpServerStreamableHttpProperties props,
            ObjectMapper objectMapper) {

        McpTransportContextExtractor<org.springframework.web.reactive.function.server.ServerRequest> extractor =
                request -> {
                    String key = request.headers().firstHeader(HEADER_API_KEY);
                    Map<String, Object> ctx = new HashMap<>();
                    ctx.put(TRANSPORT_CTX_API_KEY, key != null ? key : "");
                    return McpTransportContext.create(ctx);
                };

        var builder = WebFluxStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
                .messageEndpoint(props.getMcpEndpoint())
                .contextExtractor(extractor);

        Duration keepAlive = props.getKeepAliveInterval();
        if (keepAlive != null) {
            builder.keepAliveInterval(keepAlive);
        }

        builder.disallowDelete(props.isDisallowDelete());

        return builder.build();
    }
}
