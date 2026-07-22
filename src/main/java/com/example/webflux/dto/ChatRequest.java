package com.example.webflux.dto;

public record ChatRequest(
        String sessionId,
        String message
) {}
