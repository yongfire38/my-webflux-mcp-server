package com.example.webflux.service;

import reactor.core.publisher.Mono;

public interface DocumentSearchService {

    Mono<String> searchDocuments(String query);
}
