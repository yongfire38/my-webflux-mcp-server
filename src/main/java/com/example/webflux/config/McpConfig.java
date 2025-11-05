package com.example.webflux.config;

import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.webflux.service.DateTimeService;
import com.example.webflux.service.WeatherService;

@Configuration
public class McpConfig {

    @Bean
    public MethodToolCallbackProvider toolProvider(
        WeatherService weatherService,
        DateTimeService dateTimeService
    ) {
        return MethodToolCallbackProvider.builder()
            .toolObjects(weatherService, dateTimeService)
            .build();
    }
}
