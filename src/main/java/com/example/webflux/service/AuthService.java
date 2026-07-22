package com.example.webflux.service;

import com.example.webflux.dto.LoginRequest;
import com.example.webflux.dto.LoginResponse;
import com.example.webflux.dto.RegisterRequest;

import reactor.core.publisher.Mono;

public interface AuthService {

    Mono<String> register(RegisterRequest request);

    Mono<LoginResponse> login(LoginRequest request);
}
