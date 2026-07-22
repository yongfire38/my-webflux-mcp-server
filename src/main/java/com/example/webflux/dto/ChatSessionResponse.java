package com.example.webflux.dto;

import java.time.OffsetDateTime;

public record ChatSessionResponse(
        String sessionId,
        String title,
        OffsetDateTime createdAt,
        OffsetDateTime lastMessageAt
) {}
