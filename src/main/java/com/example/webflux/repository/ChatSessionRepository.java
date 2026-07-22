package com.example.webflux.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.webflux.entity.ChatSessionEntity;

public interface ChatSessionRepository extends JpaRepository<ChatSessionEntity, String> {

    List<ChatSessionEntity> findByUserIdOrderByLastMessageAtDesc(String userId);

    @Query("SELECT s FROM ChatSessionEntity s WHERE s.sessionId = :sessionId AND s.userId = :userId")
    Optional<ChatSessionEntity> findBySessionIdAndUserId(
            @Param("sessionId") String sessionId,
            @Param("userId") String userId);

    boolean existsBySessionIdAndUserId(String sessionId, String userId);
}
