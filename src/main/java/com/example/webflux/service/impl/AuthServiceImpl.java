package com.example.webflux.service.impl;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.example.webflux.dto.LoginRequest;
import com.example.webflux.dto.LoginResponse;
import com.example.webflux.dto.RegisterRequest;
import com.example.webflux.entity.UserEntity;
import com.example.webflux.repository.UserRepository;
import com.example.webflux.security.JwtUtil;
import com.example.webflux.service.AuthService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl extends EgovAbstractServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    @Override
    public Mono<String> register(RegisterRequest request) {
        return Mono.fromCallable(() -> {
            if (request.username() == null || request.username().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "사용자명을 입력하세요.");
            }
            if (request.password() == null || request.password().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "비밀번호를 입력하세요.");
            }
            if (userRepository.existsByUsername(request.username())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 사용 중인 사용자명입니다.");
            }

            UserEntity user = UserEntity.builder()
                    .userId(UUID.randomUUID().toString())
                    .username(request.username())
                    .password(passwordEncoder.encode(request.password()))
                    .createdAt(OffsetDateTime.now())
                    .build();

            userRepository.save(user);
            log.info("[회원가입] 사용자 생성: {}", request.username());
            return "회원가입이 완료되었습니다.";
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<LoginResponse> login(LoginRequest request) {
        return Mono.fromCallable(() -> {
            if (request.username() == null || request.username().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "사용자명을 입력하세요.");
            }

            UserEntity user = userRepository.findByUsername(request.username())
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.UNAUTHORIZED, "사용자명 또는 비밀번호가 올바르지 않습니다."));

            if (!passwordEncoder.matches(request.password(), user.getPassword())) {
                throw new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "사용자명 또는 비밀번호가 올바르지 않습니다.");
            }

            String token = jwtUtil.generateToken(user.getUserId(), user.getUsername());
            log.info("[로그인] 성공: {}", request.username());
            return new LoginResponse(token, user.getUsername());
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
