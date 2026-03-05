package com.example.webflux.config;

import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.webflux.service.DateTimeService;
import com.example.webflux.service.DocumentSearchService;

@Configuration
public class McpConfig {

    @Bean
    public MethodToolCallbackProvider toolProvider(
        DateTimeService dateTimeService,
        DocumentSearchService documentSearchService
    ) {
        return MethodToolCallbackProvider.builder()
            .toolObjects(dateTimeService, documentSearchService)
            .build();
    }
}
