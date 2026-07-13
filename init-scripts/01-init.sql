-- pgvector 확장 (pgvector 이미지에서는 이미 설치되어 있지만 명시적 선언)
CREATE EXTENSION IF NOT EXISTS vector;

-- pg_trgm 확장 — 하이브리드 lexical 검색(app.rag.hybrid.enabled=true)에서
-- GIN trigram 인덱스를 사용하기 위해 필요.
-- 애플리케이션 기동 시에는 인덱스만 생성하며, 확장 설치는 여기서만 수행한다.
CREATE EXTENSION IF NOT EXISTS pg_trgm;
