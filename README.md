# my-webflux-mcp-server

Spring AI MCP(Model Context Protocol) 기반의 RAG(Retrieval-Augmented Generation) 서버입니다.
PDF/마크다운 문서를 ONNX 임베딩 모델로 벡터화해 PostgreSQL(PGVector)에 저장하고,
MCP 클라이언트의 요청에 따라 문서 검색·업로드 도구를 노출합니다.

---

## 환경

| 항목 | 값 |
|------|-----|
| Java | 17 |
| eGovFrame Boot | 5.0.0 (Spring Boot 3.5.6) |
| Spring AI | 1.1.8 |
| 서버 포트 | 9090 |
| MCP 타입 | ASYNC / STREAMABLE HTTP |
| 벡터 DB | PostgreSQL + PGVector (localhost:5432, DB: ragdb) |
| 임베딩 모델 | ONNX (ko-sroberta 계열, 768차원, jar 외부 경로) |
| Ollama | localhost:11434 (MCP Sampling — 문서 요약 위임용) |
| Swagger UI | http://localhost:9090/swagger-ui.html |

---

## 사전 준비

### 1. 인프라 기동 (Docker Compose)

```bash
cd C:/workspace-team/my-webflux-mcp-server
docker-compose up -d
```

PostgreSQL(ragdb:5432)과 pg_trgm 익스텐션이 자동으로 준비됩니다.

### 2. ONNX 임베딩 모델

모델 파일은 jar 외부 경로에 위치해야 합니다. 기본 경로:

- Windows: `%USERPROFILE%\spring-ai-Config\model\`
- Linux/macOS: `~/spring-ai-Config/model/`

필요 파일: `model.onnx`, `tokenizer.json`, `config.json`, `tokenizer_config.json`, `special_tokens_map.json`

> 경로 변경: 환경변수 `EMBEDDING_MODEL_PATH` / `EMBEDDING_TOKENIZER_PATH`로 오버라이드 가능

### 3. 환경변수

| 변수 | 설명 | 기본값 |
|------|------|--------|
| `MCP_API_KEY` | MCP `uploadAndIndexDocument` 도구 인증 키 | 빈 문자열 (MCP 업로드 전체 차단) |
| `EMBEDDING_MODEL_PATH` | ONNX 모델 파일 전체 경로 | `${user.home}/spring-ai-Config/model/model.onnx` |
| `EMBEDDING_TOKENIZER_PATH` | 토크나이저 파일 전체 경로 | `${user.home}/spring-ai-Config/model/tokenizer.json` |

`MCP_API_KEY`를 설정하지 않으면 빈 문자열이 기본값이 되어 업로드가 전체 차단됩니다(의도된 안전 기본값).

### 4. 문서 디렉토리 (REST 재인덱싱 경로)

```
C:/workspace-test/upload/data/
```

빌드 전 미리 생성하거나 `application.yml`의 `app.document.path` 경로를 변경하세요.

---

## 빌드 및 실행

```bash
cd C:/workspace-team/my-webflux-mcp-server
mvn clean compile
mvn spring-boot:run
```

---

## MCP 노출 기능

### 도구 (Tool)

`@McpTool` 어노테이션으로 자동 등록됩니다. 모두 `Mono<String>` 반환(ASYNC 서버 필수).

| 도구 이름 | 구현 클래스 | 설명 |
|-----------|------------|------|
| `searchDocuments(query)` | `DocumentSearchServiceImpl` | 벡터 유사도 기반 문서 검색. RAG의 핵심 도구. `hybrid.enabled: true` 시 dense + pg_trgm lexical RRF 융합. |
| `describeKnowledgeBase()` | `DocumentSearchServiceImpl` | 인덱싱된 파일 목록과 청크 수 조회. `searchDocuments` 호출 전 사전 확인용. |
| `uploadAndIndexDocument(ctx, jobId, filename, base64Content, mimeType)` | `DocumentClientUploadServiceImpl` | base64 파일 수신 → 임베딩. **X-MCP-API-Key 헤더 필수.** Progress 4단계 + MCP Sampling(Ollama 요약 위임) 사용. |
| `getCurrentDateTimeWithZone(zoneId)` | `DateTimeServiceImpl` | 지정 타임존의 현재 날짜/시각 반환. |

#### `uploadAndIndexDocument` 상세

- **인증**: `X-MCP-API-Key` 헤더 값을 `app.security.api-keys` 목록과 대조. 불일치 시 도구가 거부 메시지를 반환.
- **Progress**: 4단계(텍스트 추출 → 요약 → 청킹 → 임베딩) 진행률을 MCP 클라이언트에 실시간 전송.
- **Sampling**: 문서 요약을 서버가 직접 생성하지 않고 MCP Sampling으로 클라이언트 Ollama에 위임.
- **변경 감지**: SHA-256 해시 비교로 변경된 페이지만 선별 재임베딩. 미변경 페이지 스킵.
- **Stale 벡터 정리**: 재임베딩 전 기존 벡터를 페이지 단위로 선삭제.
- **소유권 보호**: 다른 클라이언트가 적재한 문서의 덮어쓰기 차단 (`source_client` 비교).
- **프롬프트 인젝션 탐지**: 텍스트 추출 직후 한/영 의심 패턴 검사 → 감지 시 업로드 거부.

### 리소스 (Resource)

| URI | 설명 |
|-----|------|
| `resource://documents/index` | 인덱싱된 파일 목록 (읽기 전용). `describeKnowledgeBase` 도구와 동일 내용. |

### 프롬프트 (Prompt)

| 이름 | 설명 |
|------|------|
| `rag_assistant` | RAG 어시스턴트 시스템 프롬프트 템플릿. `strict` 파라미터(true/false)로 검색 강제 엄격도 조정. VS Code·Claude Desktop 등 외부 MCP 클라이언트용. |

---

## REST API

MCP 경로(`/mcp/**`)와 별개로 서버 파일시스템 문서를 관리하는 REST API입니다.

| 메서드 | 경로 | 인증 | 설명 |
|--------|------|------|------|
| `GET` | `/api/documents/status` | localhost 전용 | ETL 진행 상태 조회 |
| `POST` | `/api/documents/reindex` | localhost 전용 | 서버 로컬 문서 전체 재인덱싱 (202/409) |

> localhost 전용 경로는 외부 IP에서 호출 시 `ApiSecurityFilter`가 403을 반환합니다.
>
> 문서 파일 추가는 서버의 `app.document.path` 경로에 직접 복사 후 `/api/documents/reindex` 호출로 적재하거나, MCP `uploadAndIndexDocument` 도구(base64 전송)를 사용합니다.

---

## 접속 URL

| 용도 | URL |
|------|-----|
| MCP 엔드포인트 | `http://localhost:9090/mcp` |
| Swagger UI | `http://localhost:9090/swagger-ui.html` |
| 인덱싱 상태 | `http://localhost:9090/api/documents/status` |

---

## 보안 구조

| 계층 | 구현 | 대상 |
|------|------|------|
| REST IP 제한 | `ApiSecurityFilter` (WebFilter) | `/api/documents/reindex`, `/api/documents/status` — localhost만 허용 |
| MCP 업로드 키 | `McpTransportConfig` + Tool 내부 검증 | `uploadAndIndexDocument` — `X-MCP-API-Key` 헤더 추출 후 Tool 내부에서 검증 |

MCP 읽기 도구(`searchDocuments`, `describeKnowledgeBase`, `getCurrentDateTimeWithZone`)는 인증 없이 개방됩니다.

---

## 프로젝트 구조

```
src/main/java/com/example/webflux/
├── McpServerApplication.java
├── config/
│   ├── AsyncConfig.java                  # 비동기 스레드풀
│   ├── ChatConfig.java                   # ChatClient (Sampling용 Ollama)
│   ├── EgovCommonConfig.java
│   ├── HybridIndexInitializer.java       # pg_trgm GIN 인덱스 자동 생성 (hybrid.enabled=true 시)
│   ├── McpResourcePromptConfig.java      # @McpResource, @McpPrompt 등록
│   ├── McpTransportConfig.java           # WebFluxStreamableServerTransportProvider 오버라이드
│   │                                     #   → X-MCP-API-Key 헤더를 transportContext에 주입
│   └── SecurityProperties.java           # app.security.api-keys 바인딩
├── controller/
│   └── DocumentController.java           # REST /api/documents/**
├── dto/
│   └── DocumentStatusResponse.java
├── entity/
│   └── (없음 — 서버는 DocumentMetadata JPA 엔티티 사용)
├── etl/
│   ├── ETLPipelineConfig.java
│   ├── readers/                          # MarkdownDocumentReader, PagePdfDocumentReader
│   ├── transformers/                     # ContentFormatTransformer, DocumentChunkTransformer
│   └── writers/                          # VectorStoreDocumentWriter
├── model/
│   └── DocumentMetadata.java             # JPA 엔티티 (filename, hash, sourceClient 등)
├── repository/
│   └── DocumentMetadataRepository.java
├── security/
│   └── ApiSecurityFilter.java            # WebFilter: REST IP·API 키 검증
├── service/
│   ├── DocumentManagementService.java
│   ├── DocumentSearchService.java
│   └── impl/
│       ├── DateTimeServiceImpl.java               # @McpTool: getCurrentDateTimeWithZone
│       ├── DocumentClientUploadServiceImpl.java   # @McpTool: uploadAndIndexDocument
│       ├── DocumentManagementServiceImpl.java     # REST reindex 처리 (ETL 파이프라인)
│       └── DocumentSearchServiceImpl.java         # @McpTool: searchDocuments, describeKnowledgeBase
└── util/
    ├── DocumentHashUtil.java              # SHA-256 해시 계산
    ├── DocumentOverwriteForbiddenException.java
    ├── PromptInjectionDetector.java       # 자연어 프롬프트 인젝션 패턴 탐지
    ├── PromptInjectionDetectedException.java
    ├── RrfFusion.java                     # Reciprocal Rank Fusion (하이브리드 검색)
    └── StaleVectorCleaner.java            # 재임베딩 전 기존 벡터 선삭제
```

---

## application.yml 주요 설정

```yaml
spring:
  ai:
    mcp:
      server:
        type: ASYNC
        protocol: STREAMABLE
        instructions: |
          이 서버는 RAG 지식 베이스 검색과 문서 업로드를 제공합니다.
          ...
        streamable-http:
          keep-alive-interval: 30s      # idle 연결 유지
    embedding.transformer.onnx:
      modelUri: file:${EMBEDDING_MODEL_PATH:${user.home}/spring-ai-Config/model/model.onnx}
    vectorstore.pgvector:
      dimensions: 768
      distance-type: COSINE_DISTANCE

server:
  port: 9090

app:
  security:
    api-keys:
      - "${MCP_API_KEY:}"               # 빈 문자열은 유효 키로 불인정 → 업로드 차단
  rag:
    similarity-threshold: 0.30
    top-k: 5
    hybrid:
      enabled: false                    # true 시 dense + lexical RRF 융합 검색
      lexical.word-similarity-threshold: 0.30

springdoc:
  paths-to-exclude: /mcp/**            # Swagger에서 MCP 경로 제외
```

---

## 트러블슈팅

| 증상 | 원인 | 해결 |
|------|------|------|
| `model.onnx not found` | ONNX 모델 파일 누락 | `%USERPROFILE%\spring-ai-Config\model\`에 모델 파일 복사 |
| PGVector 연결 오류 | PostgreSQL 미기동 | `docker-compose up -d` 실행 |
| MCP 업로드 인증 실패 | `MCP_API_KEY` 미설정 또는 클라이언트 키 불일치 | 서버·클라이언트 양쪽 `MCP_API_KEY` 환경변수 일치 여부 확인 후 재기동 |
| 인덱싱 중 409 Conflict | 재인덱싱 이미 진행 중 | `/api/documents/status` 확인 후 완료 대기 |
| Sampling 요약 미생성 | 클라이언트 Ollama 미기동 또는 Sampling 미지원 | Ollama 기동 확인. Sampling 불가 시 자체 Ollama로 대체 생성. |
| 하이브리드 검색 결과 수 < topK | RRF 결과 중 lexical 전용 문서는 본문 없어 제외 | 정상 동작. topK 증가 또는 dense 가중치 조정으로 완화 가능. |
| Swagger에 MCP API 없음 | 의도된 동작 | `/mcp/**`는 Swagger 제외. `/api/documents/**`만 표시. |
