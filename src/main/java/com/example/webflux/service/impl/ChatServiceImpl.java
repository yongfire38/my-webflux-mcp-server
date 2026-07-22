package com.example.webflux.service.impl;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.example.webflux.service.ChatService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl extends EgovAbstractServiceImpl implements ChatService {

    private final ChatClient chatClient;
    private final ChatSessionServiceImpl chatSessionService;

    @Override
    public Flux<String> streamChat(String userId, String sessionId, String message) {
        return chatSessionService.verifyOwnership(userId, sessionId)
                .flatMapMany(owned -> {
                    if (!owned) {
                        return Flux.error(new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "세션을 찾을 수 없습니다."));
                    }
                    return chatClient.prompt()
                            .user(message)
                            .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                            .stream()
                            .content()
                            .doOnComplete(() ->
                                    chatSessionService.touchLastMessage(sessionId)
                                            .subscribe(v -> {}, e -> log.warn("[세션 시간 갱신 실패] {}", e.getMessage()))
                            );
                });
    }
}
