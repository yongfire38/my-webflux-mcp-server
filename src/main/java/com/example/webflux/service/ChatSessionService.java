package com.example.webflux.service;

import java.util.List;

import com.example.webflux.dto.ChatMessageResponse;
import com.example.webflux.dto.ChatSessionResponse;

import reactor.core.publisher.Mono;

public interface ChatSessionService {

    Mono<ChatSessionResponse> createSession(String userId);

    Mono<List<ChatSessionResponse>> listSessions(String userId);

    Mono<Void> deleteSession(String userId, String sessionId);

    Mono<Void> updateTitle(String userId, String sessionId, String title);

    Mono<Boolean> verifyOwnership(String userId, String sessionId);

    Mono<List<ChatMessageResponse>> getMessages(String userId, String sessionId);
}
