package com.example.webflux.service.impl;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.example.webflux.dto.ChatMessageResponse;
import com.example.webflux.dto.ChatSessionResponse;
import com.example.webflux.entity.ChatSessionEntity;
import com.example.webflux.repository.ChatSessionRepository;
import com.example.webflux.service.ChatSessionService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatSessionServiceImpl extends EgovAbstractServiceImpl implements ChatSessionService {

    private final ChatSessionRepository sessionRepository;
    private final ChatMemory chatMemory;

    @Override
    public Mono<ChatSessionResponse> createSession(String userId) {
        return Mono.fromCallable(() -> {
            OffsetDateTime now = OffsetDateTime.now();
            ChatSessionEntity session = ChatSessionEntity.builder()
                    .sessionId(UUID.randomUUID().toString())
                    .userId(userId)
                    .title("새 채팅")
                    .createdAt(now)
                    .lastMessageAt(now)
                    .build();
            sessionRepository.save(session);
            log.debug("[세션 생성] userId={}, sessionId={}", userId, session.getSessionId());
            return toResponse(session);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<List<ChatSessionResponse>> listSessions(String userId) {
        return Mono.fromCallable(() ->
                sessionRepository.findByUserIdOrderByLastMessageAtDesc(userId)
                        .stream().map(this::toResponse).collect(Collectors.toList())
        ).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Void> deleteSession(String userId, String sessionId) {
        return Mono.fromCallable(() -> {
            sessionRepository.findBySessionIdAndUserId(sessionId, userId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "세션을 찾을 수 없습니다."));
            sessionRepository.deleteById(sessionId);
            log.debug("[세션 삭제] userId={}, sessionId={}", userId, sessionId);
            return null;
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Override
    public Mono<Void> updateTitle(String userId, String sessionId, String title) {
        return Mono.fromCallable(() -> {
            ChatSessionEntity session = sessionRepository.findBySessionIdAndUserId(sessionId, userId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "세션을 찾을 수 없습니다."));
            session.setTitle(title != null && !title.isBlank() ? title : "새 채팅");
            sessionRepository.save(session);
            return null;
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Override
    public Mono<Boolean> verifyOwnership(String userId, String sessionId) {
        return Mono.fromCallable(() ->
                sessionRepository.existsBySessionIdAndUserId(sessionId, userId)
        ).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<List<ChatMessageResponse>> getMessages(String userId, String sessionId) {
        return verifyOwnership(userId, sessionId)
                .flatMap(owned -> {
                    if (!owned) {
                        return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "세션을 찾을 수 없습니다."));
                    }
                    return Mono.fromCallable(() ->
                            chatMemory.get(sessionId).stream()
                                    .filter(m -> m.getMessageType() == MessageType.USER
                                              || m.getMessageType() == MessageType.ASSISTANT)
                                    .map(m -> new ChatMessageResponse(
                                            m.getMessageType().getValue(),
                                            m.getText() != null ? m.getText() : ""))
                                    .collect(Collectors.toList())
                    ).subscribeOn(Schedulers.boundedElastic());
                });
    }

    public Mono<Void> touchLastMessage(String sessionId) {
        return Mono.fromCallable(() -> {
            sessionRepository.findById(sessionId).ifPresent(s -> {
                s.setLastMessageAt(OffsetDateTime.now());
                sessionRepository.save(s);
            });
            return null;
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    private ChatSessionResponse toResponse(ChatSessionEntity s) {
        return new ChatSessionResponse(s.getSessionId(), s.getTitle(), s.getCreatedAt(), s.getLastMessageAt());
    }
}
