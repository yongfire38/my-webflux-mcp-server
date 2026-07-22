package com.example.webflux.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.webflux.dto.ChatMessageResponse;
import com.example.webflux.dto.ChatSessionResponse;
import com.example.webflux.service.ChatSessionService;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
public class ChatSessionController {

    private final ChatSessionService chatSessionService;

    @PostMapping
    public Mono<ResponseEntity<ChatSessionResponse>> createSession(Mono<Authentication> authMono) {
        return authMono.flatMap(auth -> chatSessionService.createSession(auth.getName()))
                .map(s -> ResponseEntity.status(HttpStatus.CREATED).body(s));
    }

    @GetMapping
    public Mono<ResponseEntity<List<ChatSessionResponse>>> listSessions(Mono<Authentication> authMono) {
        return authMono.flatMap(auth -> chatSessionService.listSessions(auth.getName()))
                .map(ResponseEntity::ok);
    }

    @DeleteMapping("/{sessionId}")
    public Mono<ResponseEntity<Void>> deleteSession(
            @PathVariable String sessionId,
            Mono<Authentication> authMono) {
        return authMono.flatMap(auth -> chatSessionService.deleteSession(auth.getName(), sessionId))
                .thenReturn(ResponseEntity.<Void>noContent().build());
    }

    @PatchMapping("/{sessionId}/title")
    public Mono<ResponseEntity<Void>> updateTitle(
            @PathVariable String sessionId,
            @RequestBody TitleRequest body,
            Mono<Authentication> authMono) {
        return authMono.flatMap(auth -> chatSessionService.updateTitle(auth.getName(), sessionId, body.title()))
                .thenReturn(ResponseEntity.<Void>noContent().build());
    }

    @GetMapping("/{sessionId}/messages")
    public Mono<ResponseEntity<List<ChatMessageResponse>>> getMessages(
            @PathVariable String sessionId,
            Mono<Authentication> authMono) {
        return authMono.flatMap(auth -> chatSessionService.getMessages(auth.getName(), sessionId))
                .map(ResponseEntity::ok);
    }

    public record TitleRequest(String title) {}
}
