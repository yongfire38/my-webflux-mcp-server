package com.example.webflux.service;

import reactor.core.publisher.Flux;

public interface ChatService {

    Flux<String> streamChat(String userId, String sessionId, String message);
}
