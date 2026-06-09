# my-webflux-mcp-server

Spring AI MCP(Model Context Protocol) 기반의 RAG(Retrieval-Augmented Generation) 서버입니다.
PDF/마크다운 문서를 벡터 DB에 인덱싱하고, MCP 클라이언트(LLM 에이전트)의 요청에 따라 관련 문서를 검색해 제공합니다.

---

## 프로젝트 개요

이 서버는 두 가지 역할을 동시에 수행합니다.

1. **RAG 지식 베이스 서버**: 문서를 ONNX 임베딩 모델로 벡터화해 PostgreSQL(PGVector)에 저장하고, 의미적 유사도 기반으로 검색합니다.
2. **MCP 서버**: Spring AI MCP 프로토콜을 통해 AI 클라이언트가 도구(Tool), 리소스(Resource), 프롬프트(Prompt)를 호출할 수 있도록 노출합니다.

MCP 클라이언트(my-webflux-mcp-client)는 이 서버에 연결해 "문서 검색", "문서 업로드", "현재 시각 조회" 등의 도구를 자유롭게 호출합니다.
서버 로컬 디렉토리에 있는 PDF/마크다운 파일은 애플리케이션 시작 시 자동으로 인덱싱됩니다.

---

## 환경

| 항목 | 값 |
|------|-----|
| Java | 17 |
| Spring Boot | 3.2.x |
| Spring AI | 1.1.2 |
| 서버 포트 | 9090 |
| MCP 타입 | ASYNC / STREAMABLE |
| 벡터 DB | PostgreSQL + PGVector (localhost:5432, DB: ragdb) |
| 임베딩 모델 | ONNX (ko-sroberta 계열, 768차원, jar 외부 파일) |
| Ollama | http://localhost:11434 (문서 요약 Sampling 용도) |
| Swagger UI | http://localhost:9090/swagger-ui.html |

---

## 주요 기능

### MCP 도구 (Tool)

MCP 클라이언트가 LLM을 통해 자동으로 호출할 수 있는 기능들입니다.
`@McpTool` 어노테이션으로 등록되며, 모두 비동기(`Mono<String>`)로 동작합니다.
별도의 설정 빈(Bean) 없이 `@Service`/`@Component` + `@McpTool` 조합만으로 자동 등록됩니다.

| 도구 이름 | 구현 클래스 | 설명 |
|-----------|------------|------|
| `searchDocuments(query)` | DocumentSearchServiceImpl | 질문과 의미적으로 유사한 문서 청크를 벡터 DB에서 검색합니다. RAG의 핵심 기능입니다. |
| `describeKnowledgeBase()` | DocumentSearchServiceImpl | 현재 벡터 DB에 인덱싱된 문서 목록과 현황을 조회합니다. |
| `getCurrentDateTimeWithZone(zoneId)` | DateTimeServiceImpl | 지정한 타임존의 현재 날짜/시각을 반환합니다. |
| `uploadAndIndexDocument(ctx, jobId, filename, base64Content, mimeType)` | DocumentClientUploadServiceImpl | 클라이언트로부터 base64로 인코딩된 파일을 수신해 서버 벡터 DB에 임베딩합니다. Sampling으로 Ollama 문서 요약도 생성합니다. |

#### uploadAndIndexDocument 상세 동작

이 도구는 일반 도구와 달리 두 가지 특수 MCP 기능을 활용합니다.

- **Progress 알림**: 파일 수신 → 텍스트 추출 → 청킹 → 임베딩의 4단계 진행률을 클라이언트에 실시간으로 전송합니다.
- **Sampling**: 텍스트 추출 후 클라이언트 측 Ollama에 문서 요약 생성을 위임합니다. 서버가 LLM을 직접 호출하는 것이 아니라, MCP Sampling 메커니즘으로 클라이언트의 Ollama를 역방향 호출합니다.

### MCP 리소스 (Resource)

`@McpResource` 어노테이션으로 등록된 읽기 전용 데이터 자원입니다.

| URI | 설명 |
|-----|------|
| `resource://documents/index` | 현재 인덱싱된 문서 목록을 반환합니다. |

### MCP 프롬프트 (Prompt)

`@McpPrompt` 어노테이션으로 등록된 재사용 가능한 시스템 프롬프트 템플릿입니다.

| 이름 | 설명 |
|------|------|
| `rag_assistant` | RAG 기반 답변을 위한 시스템 프롬프트. `strict` 파라미터(true/false)로 엄격도 조정 가능합니다. |

### REST API (DocumentController)

Swagger UI 또는 직접 HTTP 요청으로 호출하는 문서 관리 API입니다.

| 메서드 | 경로 | 응답 코드 | 설명 |
|--------|------|-----------|------|
| `GET` | `/api/documents/status` | 200 | 현재 인덱싱 상태를 조회합니다. |
| `POST` | `/api/documents/reindex` | 202 / 409 | 서버 로컬 문서를 재인덱싱합니다. 이미 진행 중이면 409 반환. |
| `POST` | `/api/documents/upload` | 200 | 파일을 multipart/form-data (`files` 필드, 다중 파일 지원)로 직접 업로드해 임베딩합니다. |

---

## 프로젝트 구조

```
my-webflux-mcp-server/
└── src/main/
    ├── java/com/example/webflux/
    │   ├── McpServerApplication.java          # 시작 시 C:/workspace-test/upload/data 자동 인덱싱
    │   ├── config/
    │   │   ├── AsyncConfig.java               # 비동기 스레드풀 설정
    │   │   ├── EgovCommonConfig.java          # eGovFrame 공통 설정
    │   │   └── McpResourcePromptConfig.java   # @McpResource, @McpPrompt 등록
    │   ├── controller/
    │   │   └── DocumentController.java        # 문서 관리 REST API
    │   ├── dto/
    │   │   └── DocumentStatusResponse.java
    │   ├── etl/                               # ETL 파이프라인
    │   │   ├── readers/                       # MarkdownDocumentReader, PdfDocumentReader
    │   │   ├── transformers/                  # ContentFormatTransformer, DocumentChunkTransformer
    │   │   └── writers/                       # VectorStoreDocumentWriter
    │   ├── model/
    │   │   └── DocumentMetadata.java          # JPA 엔티티 (파일명, 해시, 인덱싱 시각)
    │   ├── repository/
    │   │   └── DocumentMetadataRepository.java
    │   ├── service/
    │   │   ├── DocumentManagementService.java
    │   │   ├── DocumentSearchService.java
    │   │   ├── DateTimeService.java
    │   │   └── impl/
    │   │       ├── DocumentManagementServiceImpl.java
    │   │       ├── DocumentSearchServiceImpl.java         # @McpTool: searchDocuments, describeKnowledgeBase
    │   │       ├── DateTimeServiceImpl.java               # @McpTool: getCurrentDateTimeWithZone
    │   │       └── DocumentClientUploadServiceImpl.java   # @McpTool: uploadAndIndexDocument
    │   └── util/
    │       └── DocumentHashUtil.java
    └── resources/
        └── application.yml
```

---

## 사전 준비

### 1. PostgreSQL + PGVector

```bash
docker run -d \
  --name pgvector \
  -e POSTGRES_DB=ragdb \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -p 5432:5432 \
  pgvector/pgvector:pg16
```

PGVector 익스텐션 활성화 (최초 1회):

```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

### 2. Ollama (선택)

문서 업로드 시 Sampling(LLM 요약) 기능에 필요합니다. 없어도 기본 검색/인덱싱은 정상 동작합니다.

```bash
ollama pull qwen3-4b:Q4_K_M
```

### 3. ONNX 임베딩 모델

모델 파일은 jar 외부 경로에 두어야 합니다. 기본 경로:

- Windows: `%USERPROFILE%\spring-ai-Config\model\`
- Linux/macOS: `~/spring-ai-Config/model/`

필요 파일:

- `model.onnx` : 임베딩 ONNX 모델 파일 (768차원, 한국어 지원 권장)
- `tokenizer.json` : 토크나이저 설정 파일

용량이 크므로 Git에 포함되지 않습니다. 별도로 복사해 두세요.

> 경로를 바꾸려면 환경변수 `EMBEDDING_MODEL_PATH` / `EMBEDDING_TOKENIZER_PATH`로 전체 경로를 오버라이드하거나 `application.yml`의 기본값을 수정하세요.

### 4. 서버 로컬 문서 디렉토리

시작 시 아래 경로를 자동 인덱싱합니다. 없으면 미리 생성하세요.

```
C:/workspace-test/upload/data/
```

---

## 빌드 및 실행

```bash
cd C:/workspace-team/my-webflux-mcp-server
mvn clean package -DskipTests
java -jar target/my-webflux-mcp-server-*.jar
```

---

## 접속 URL

| 용도 | URL |
|------|-----|
| MCP 엔드포인트 (클라이언트 연결) | `http://localhost:9090/mcp` |
| Swagger UI | `http://localhost:9090/swagger-ui.html` |
| 인덱싱 상태 | `http://localhost:9090/api/documents/status` |

---

## application.yml 주요 설정

```yaml
spring:
  ai:
    mcp:
      server:
        type: ASYNC           # 비동기 MCP 서버 (WebFlux 필수)
        protocol: STREAMABLE  # Streamable HTTP 프로토콜
    embedding.transformer.onnx:
      modelUri: file:${EMBEDDING_MODEL_PATH:${user.home}/spring-ai-Config/model/model.onnx}
    vectorstore.pgvector:
      dimensions: 768
      distance-type: COSINE_DISTANCE
server:
  port: 9090
springdoc:
  paths-to-exclude: /mcp/**  # Swagger에서 MCP 경로 제외
```

---

## 트러블슈팅

| 증상 | 원인 | 해결 |
|------|------|------|
| `model.onnx not found` | ONNX 모델 파일 누락 | `%USERPROFILE%\spring-ai-Config\model\`에 두 파일 복사 (재빌드 불필요) |
| PGVector 연결 오류 | PostgreSQL 미실행 또는 `vector` 익스텐션 없음 | Docker 확인, `CREATE EXTENSION IF NOT EXISTS vector;` 실행 |
| 인덱싱 중 409 Conflict | 이미 인덱싱 진행 중 | `/api/documents/status` 확인 후 완료 대기 |
| 벡터 차원 불일치 | 모델 출력 차원과 `dimensions` 설정 불일치 | `application.yml`의 `dimensions` 수정, PGVector 테이블 재생성 |
| Sampling 요약 미생성 | Ollama 미실행 | `http://localhost:11434` 확인. 요약 없이 임베딩만 진행됨 |
| Swagger에서 MCP API 없음 | 의도된 동작 | `/mcp/**`는 Swagger 제외. `/api/documents/**`만 표시됨 |
