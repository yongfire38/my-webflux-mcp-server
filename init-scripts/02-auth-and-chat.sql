-- 문서 메타데이터 테이블 (해시 변경 감지용)
CREATE TABLE IF NOT EXISTS document_metadata (
    id             BIGSERIAL    PRIMARY KEY,
    filename       VARCHAR(255) NOT NULL,
    chunk_index    INTEGER      NOT NULL,
    content_hash   VARCHAR(255) NOT NULL,
    indexed_at     TIMESTAMP    NOT NULL,
    owner_user_id  VARCHAR(255),
    CONSTRAINT uq_document_metadata_filename_chunk UNIQUE (filename, chunk_index)
);

-- 사용자 테이블
CREATE TABLE IF NOT EXISTS users (
    user_id    VARCHAR(36)  PRIMARY KEY,          -- UUID
    username   VARCHAR(100) NOT NULL UNIQUE,
    password   VARCHAR(255) NOT NULL,             -- BCrypt 해시
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 채팅 세션 테이블
CREATE TABLE IF NOT EXISTS spring_ai_chat_sessions (
    session_id      VARCHAR(36)  PRIMARY KEY,     -- 순수 UUID
    user_id         VARCHAR(36)  NOT NULL,
    title           VARCHAR(500) NOT NULL DEFAULT '새 채팅',
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_message_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_chat_sessions_user FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_chat_sessions_user_id      ON spring_ai_chat_sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_chat_sessions_last_message ON spring_ai_chat_sessions(last_message_at);
