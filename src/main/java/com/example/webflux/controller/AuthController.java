package com.example.webflux.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.webflux.dto.LoginRequest;
import com.example.webflux.dto.LoginResponse;
import com.example.webflux.dto.RegisterRequest;
import com.example.webflux.service.AuthService;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public Mono<ResponseEntity<String>> register(@RequestBody RegisterRequest request) {
        return authService.register(request)
                .map(msg -> ResponseEntity.status(HttpStatus.CREATED).body(msg));
    }

    @PostMapping("/login")
    public Mono<ResponseEntity<LoginResponse>> login(@RequestBody LoginRequest request) {
        return authService.login(request)
                .map(ResponseEntity::ok);
    }
}
