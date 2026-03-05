package com.example.webflux.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;

import com.example.webflux.dto.DocumentStatusResponse;
import com.example.webflux.service.DocumentManagementService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
@CrossOrigin
public class DocumentController {

    private final DocumentManagementService documentManagementService;

    @GetMapping("/status")
    public Mono<DocumentStatusResponse> getStatus() {
        return Mono.just(documentManagementService.getStatusResponse());
    }

    @PostMapping("/reindex")
    public Mono<ResponseEntity<String>> reindexDocuments() {
        return Mono.fromCallable(documentManagementService::reindexDocuments)
                .map(msg -> ResponseEntity.accepted().<String>body(msg))     // 202 Accepted
                .onErrorResume(IllegalStateException.class, e ->
                        Mono.just(ResponseEntity.status(HttpStatus.CONFLICT).<String>body(e.getMessage()))); // 409 Conflict
    }

    @PostMapping("/upload")
    public Mono<ResponseEntity<Map<String, Object>>> uploadFiles(
            @RequestPart("files") Flux<FilePart> files) {
        return documentManagementService.uploadMarkdownFiles(files)
            .map(result -> {
                boolean success = Boolean.TRUE.equals(result.get("success"));
                if (success) {
                    return ResponseEntity.ok(result);
                } else {
                    return ResponseEntity.badRequest().body(result);
                }
            });
    }
}
