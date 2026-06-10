# Spring AI MCP 어노테이션 마이그레이션 가이드

> 프로젝트: `my-webflux-mcp-server`
> Spring AI 버전: `1.1.7` (최초 작성: `1.1.2`, 2026-03-05 / 업데이트: `1.1.7`, 2026-06-10)
> 작성일: 2026-03-05

---

## 1. 배경 — 두 개의 독립적인 어노테이션 시스템

Spring AI 1.1.x에는 "도구(Tool)"를 정의하는 어노테이션이 **두 가지** 존재한다.
헷갈리기 쉬운 개념이므로 먼저 정확히 구분한다.

### 1-1. `@Tool` — Spring AI LLM 도구 호출 API

| 항목 | 내용 |
|------|------|
| 패키지 | `org.springframework.ai.tool.annotation.Tool` |
| 목적 | Spring AI 내부에서 **LLM이 Java 메서드를 함수 호출**하게 하는 것 |
| 역사 | 이전 `FunctionCallback` API의 후계자 (FunctionCallback이 deprecated됨) |
| 현재 상태 | **deprecated 아님**, Spring AI 공식 권장 |
| 사용 맥락 | Spring AI **클라이언트** 측 코드에서 LLM에게 도구를 제공할 때 |

```java
// 예: Spring AI 클라이언트가 LLM에게 도구를 제공하는 경우
@Tool(description = "현재 시간 반환")
public String getCurrentTime() { ... }
```

### 1-2. `@McpTool` — MCP 서버 전용 어노테이션 (신규)

| 항목 | 내용 |
|------|------|
| 패키지 | `org.springaicommunity.mcp.annotation.McpTool` |
| 라이브러리 | `org.springaicommunity:mcp-annotations:0.8.0` |
| 목적 | MCP 프로토콜에 메서드를 **MCP Tool로 직접 노출**하는 것 |
| 역사 | Spring AI Community 프로젝트 → Spring AI 1.1.x에 공식 통합 |
| 현재 상태 | Spring AI 1.1.x 권장 방식 |
| 사용 맥락 | Spring AI **MCP 서버** 측 코드에서 MCP Tool을 선언할 때 |

```java
// 예: MCP 서버가 MCP 프로토콜로 도구를 노출하는 경우
@McpTool(name = "searchDocuments", description = "...")
public String searchDocuments(@McpToolParam(description="...") String query) { ... }
```

### 1-3. 핵심 차이

```
@Tool       : Spring AI 내부 LLM 도구 호출 → ToolCallback → Spring AI ChatClient
@McpTool    : MCP 프로토콜 도구 노출 → AsyncToolSpecification → MCP Client (모든 MCP 클라이언트)
```

MCP 서버를 만들 때는 `@McpTool`이 더 의미적으로 정확하다.
단, 기존 `@Tool` + `MethodToolCallbackProvider` 방식도 Spring AI MCP Server 자동구성이
`ToolCallback` → `AsyncToolSpecification`으로 변환해주기 때문에 **동작은 동일**하다.

---

## 2. 의존성 구조

`spring-ai-starter-mcp-server-webflux` 의존성 트리:

```
spring-ai-starter-mcp-server-webflux:1.1.7
├── spring-ai-autoconfigure-mcp-server-webflux:1.1.7
│   └── spring-ai-autoconfigure-mcp-server-common:1.1.7   ← 자동구성 핵심
├── spring-ai-mcp:1.1.7                                    ← ToolCallback 브릿지
│   └── io.modelcontextprotocol.sdk:mcp:0.18.2
│       └── mcp-core:0.18.2                                ← McpServerFeatures 클래스
├── spring-ai-mcp-annotations:1.1.7                        ← Spring AI → Community 브릿지
│   └── org.springaicommunity:mcp-annotations:0.9.0        ← @McpTool 등 실제 어노테이션
└── io.modelcontextprotocol.sdk:mcp-spring-webflux:0.18.2
```

### 어노테이션별 실제 패키지

| 어노테이션 | 실제 클래스 위치 |
|-----------|----------------|
| `@McpTool` | `org.springaicommunity.mcp.annotation.McpTool` |
| `@McpToolParam` | `org.springaicommunity.mcp.annotation.McpToolParam` |
| `@McpResource` | `org.springaicommunity.mcp.annotation.McpResource` |
| `@McpPrompt` | `org.springaicommunity.mcp.annotation.McpPrompt` |
| `@McpArg` | `org.springaicommunity.mcp.annotation.McpArg` |
| `@McpLogging` | `org.springaicommunity.mcp.annotation.McpLogging` |
| `@McpSampling` | `org.springaicommunity.mcp.annotation.McpSampling` |

---

## 3. 자동 구성 동작 원리

### 어노테이션 스캐너 활성화 조건

`spring-ai-autoconfigure-mcp-server-common` 의 `McpServerAnnotationScannerAutoConfiguration`이
`@McpTool` 등의 어노테이션을 자동 감지하여 MCP 서버에 등록한다.

```yaml
# application.yml — 기본값이 true이므로 별도 설정 불필요
spring:
  ai:
    mcp:
      server:
        annotation-scanner:
          enabled: true   # 기본값
```

### 등록 흐름 (ASYNC 서버 기준)

```
@McpTool 어노테이션 감지
  → AsyncMcpAnnotationProviders$SpringAiAsyncMcpToolProvider
  → McpServerFeatures.AsyncToolSpecification 생성
  → McpAsyncServer에 등록
  → MCP 프로토콜로 클라이언트에 노출
```

---

## 4. 마이그레이션 내역

### 4-1. 변경 파일 목록

| 파일 | 변경 유형 | 설명 |
|------|----------|------|
| `DateTimeServiceImpl.java` | 수정 | `@Tool`/`@ToolParam` → `@McpTool`/`@McpToolParam` |
| `DocumentSearchServiceImpl.java` | 수정 | `@Tool`/`@ToolParam` → `@McpTool`/`@McpToolParam` |
| `McpConfig.java` | **삭제** | `MethodToolCallbackProvider` Bean 불필요 |
| `McpResourcePromptConfig.java` | 전면 재작성 | Bean 방식 → `@McpResource`/`@McpPrompt` 어노테이션 방식 |

---

### 4-2. DateTimeServiceImpl.java

#### Before
```java
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

@Tool(
    name = "getCurrentDateTimeWithZone",
    description = "Get the current date and time from the specified timezone"
)
public String getCurrentDateTimeWithZone(
    @ToolParam(description = "Zone Id (예: Asia/Seoul, ...)") String zoneId
) { ... }
```

#### After
```java
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;

@McpTool(
    name = "getCurrentDateTimeWithZone",
    description = "Get the current date and time from the specified timezone"
)
public String getCurrentDateTimeWithZone(
    @McpToolParam(description = "Zone Id (예: Asia/Seoul, ...)") String zoneId
) { ... }
```

---

### 4-3. DocumentSearchServiceImpl.java

#### Before
```java
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

@Tool(name = "searchDocuments", description = "...")
public String searchDocuments(@ToolParam(description = "...") String query) { ... }

@Tool(name = "describeKnowledgeBase", description = "...")
public String describeKnowledgeBase() { ... }
```

#### After
```java
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;

@McpTool(name = "searchDocuments", description = "...")
public String searchDocuments(@McpToolParam(description = "...") String query) { ... }

@McpTool(name = "describeKnowledgeBase", description = "...")
public String describeKnowledgeBase() { ... }
```

---

### 4-4. McpConfig.java — 삭제

기존 파일이 하던 역할:

```java
@Configuration
public class McpConfig {
    @Bean
    public MethodToolCallbackProvider toolProvider(
        DateTimeService dateTimeService,
        DocumentSearchService documentSearchService
    ) {
        return MethodToolCallbackProvider.builder()
            .toolObjects(dateTimeService, documentSearchService)
            .build();
    }
}
```

`@McpTool` 방식으로 전환 후 `McpServerAnnotationScannerAutoConfiguration`이
컨텍스트 전체를 자동 스캔하므로 이 설정 파일이 불필요해진다.

**삭제 후 동작 차이**:

| 항목 | MethodToolCallbackProvider (구) | @McpTool 자동 스캔 (신) |
|------|--------------------------------|------------------------|
| 등록 방법 | 명시적: `toolObjects(A, B)` 지정 | 자동: `@McpTool` 있는 모든 빈 |
| 추가 도구 등록 | McpConfig 수정 필요 | `@McpTool` 추가만으로 자동 반영 |
| 제어 범위 | 선택적 노출 가능 | 전체 자동 스캔 |

---

### 4-5. McpResourcePromptConfig.java — 전면 재작성

#### Before (Bean 방식 — `@Configuration`)

```java
@Configuration
@RequiredArgsConstructor
public class McpResourcePromptConfig {

    @Bean
    public List<McpServerFeatures.AsyncResourceSpecification> mcpResources() {
        var resource = McpSchema.Resource.builder()
                .uri("resource://documents/index")
                .name("인덱싱된 문서 목록")
                .mimeType("text/plain")
                .build();

        var specification = new McpServerFeatures.AsyncResourceSpecification(
                resource,
                (McpAsyncServerExchange exchange, McpSchema.ReadResourceRequest request) ->
                        Mono.fromCallable(() -> buildDocumentIndex())
                                .subscribeOn(Schedulers.boundedElastic())
                                .map(content -> new McpSchema.ReadResourceResult(
                                        List.of(new McpSchema.TextResourceContents(
                                                "resource://documents/index", "text/plain", content
                                        ))
                                ))
        );
        return List.of(specification);
    }

    @Bean
    public List<McpServerFeatures.AsyncPromptSpecification> mcpPrompts() {
        var prompt = new McpSchema.Prompt(
                "rag_assistant", "...",
                List.of(new McpSchema.PromptArgument("strict", "...", false))
        );

        var specification = new McpServerFeatures.AsyncPromptSpecification(
                prompt,
                (McpAsyncServerExchange exchange, McpSchema.GetPromptRequest request) -> {
                    boolean strict = Optional.ofNullable(request.arguments())
                            .map(args -> "true".equals(args.get("strict")))
                            .orElse(false);
                    return Mono.just(new McpSchema.GetPromptResult(
                            "...",
                            List.of(new McpSchema.PromptMessage(
                                    McpSchema.Role.ASSISTANT,
                                    new McpSchema.TextContent(strict ? STRICT_PROMPT : DEFAULT_PROMPT)
                            ))
                    ));
                }
        );
        return List.of(specification);
    }
}
```

#### After (어노테이션 방식 — `@Component`)

```java
@Component
@RequiredArgsConstructor
public class McpResourcePromptConfig {

    @McpResource(
            uri = "resource://documents/index",
            name = "인덱싱된 문서 목록",
            description = "...",
            mimeType = "text/plain"
    )
    public Mono<String> getDocumentIndex() {
        // JPA 블로킹 → boundedElastic 격리
        return Mono.fromCallable(this::buildDocumentIndex)
                .subscribeOn(Schedulers.boundedElastic());
    }

    @McpPrompt(
            name = "rag_assistant",
            description = "RAG 기반 문서 검색을 위한 시스템 프롬프트."
    )
    public String buildRagPrompt(
            @McpArg(name = "strict", description = "엄격 모드 여부", required = false)
            String strict
    ) {
        return "true".equalsIgnoreCase(strict) ? STRICT_SYSTEM_PROMPT : DEFAULT_SYSTEM_PROMPT;
    }
}
```

**Before → After 핵심 변화**:

| 항목 | Before | After |
|------|--------|-------|
| 클래스 어노테이션 | `@Configuration` | `@Component` |
| Resource 등록 | `@Bean` + `AsyncResourceSpecification` 생성자 | `@McpResource` 메서드 어노테이션 |
| Prompt 등록 | `@Bean` + `AsyncPromptSpecification` 생성자 | `@McpPrompt` 메서드 어노테이션 |
| Prompt 파라미터 | `McpSchema.PromptArgument` 직접 생성 | `@McpArg` 메서드 파라미터 어노테이션 |
| Resource 반환 타입 | `BiFunction<Exchange, Request, Mono<Result>>` | `Mono<String>` (자동 변환) |
| Prompt 반환 타입 | `Mono<McpSchema.GetPromptResult>` | `String` (자동 변환) |
| import 수 | 8개 (McpSchema, McpAsyncServerExchange, ...) | 3개 (McpResource, McpPrompt, McpArg) |

---

## 5. Spring AI 1.1.7 업그레이드 추가 변경 사항

1.1.2 → 1.1.7 업그레이드 시 `@McpTool` 어노테이션 자체는 변경 없으나, 아래 두 가지 파괴적 변경(Breaking Change)이 발생한다.

### 5-1. 서버 — TokenTextSplitter 생성자 제거

**변경 전 (1.1.2)**:
```java
// DocumentChunkTransformer.java
new TokenTextSplitter(chunkSize, minChunkSizeChars, minChunkLengthToEmbed, maxNumChunks, true)
```

**변경 후 (1.1.7)**:
```java
// 5인자 생성자 제거 → 빌더 패턴 사용
TokenTextSplitter.builder()
        .withChunkSize(chunkSize)
        .withMinChunkSizeChars(minChunkSizeChars)
        .withMinChunkLengthToEmbed(minChunkLengthToEmbed)
        .withMaxNumChunks(maxNumChunks)
        .withKeepSeparator(true)
        .build()
```

1.1.7에서 새로 추가된 6인자 생성자(`int, int, int, int, boolean, List<Character>`)도 있으나,
`punctuationMarks`를 커스터마이징할 필요가 없으면 빌더가 더 명확하다.

### 5-2. 클라이언트 — ChatMemory 상수명 변경

서버 코드와 직접 관련은 없지만, 동일 버전을 사용하는 클라이언트 프로젝트(`my-webflux-mcp-client`)에도 파괴적 변경이 있다.

**변경 전 (1.1.2)**:
```java
ChatMemory.DEFAULT_CONVERSATION_ID   // 기본 대화 ID 상수
```

**변경 후 (1.1.7)**:
```java
ChatMemory.CONVERSATION_ID           // 상수명 변경 (값은 동일)
```

`DEFAULT_CONVERSATION_ID`를 사용하는 모든 위치(`ChatController`, `SessionContext` 등)를
`CONVERSATION_ID`로 일괄 변경해야 한다.

---

## 6. 클라이언트 측 MCP 어노테이션 영향

`my-webflux-mcp-client`의 `ChatServiceImpl`이 서버 도구를 사용하는 방식:

```java
// 클라이언트: MCP 프로토콜로 서버 도구 목록 수신 (McpClientHolder 경유)
ToolCallback[] callbacks = mcpClientHolder.getToolCallbacks();
```

`McpClientHolder.getToolCallbacks()` 내부에서 `AsyncMcpToolCallbackProvider`를 사용하며,
이는 MCP 프로토콜 레이어에서 동작한다. 서버가 `@Tool`로 등록했든 `@McpTool`로 등록했든
클라이언트에는 동일한 MCP Tool로 노출되므로 **도구 호출 방식은 변경 없음**.

단, 1.1.7부터 `AsyncMcpToolCallbackProvider(List<McpAsyncClient>)` 생성자가 deprecated되어
빌더 패턴 사용을 권장한다:

```java
AsyncMcpToolCallbackProvider.builder()
        .mcpClients(clients)
        .build()
        .getToolCallbacks()
```

---

## 7. @McpResource 반환 타입 규칙

| 반환 타입 | 동기/비동기 | JPA 사용 시 |
|----------|-----------|------------|
| `String` | 동기 | ⚠️ 블로킹 — 사용 금지 (WebFlux) |
| `Mono<String>` | 비동기 | ✅ `subscribeOn(boundedElastic())` 필수 |
| `McpSchema.ReadResourceResult` | 동기 | ⚠️ 블로킹 — 사용 금지 (WebFlux) |
| `Mono<McpSchema.ReadResourceResult>` | 비동기 | ✅ `subscribeOn(boundedElastic())` 필수 |

이 프로젝트는 JPA(`DocumentMetadataRepository`)를 사용하므로 `Mono<String>` + `subscribeOn(Schedulers.boundedElastic())` 조합을 사용한다.

---

## 8. @McpPrompt 반환 타입 규칙

| 반환 타입 | 동작 |
|----------|------|
| `String` | `GetPromptResult`로 자동 변환 (ASSISTANT 역할 메시지) |
| `McpSchema.GetPromptResult` | 직접 반환 (역할 및 다중 메시지 제어 가능) |
| `Mono<String>` | 비동기 String |
| `Mono<McpSchema.GetPromptResult>` | 비동기 직접 반환 |

단순 텍스트 프롬프트는 `String` 반환이 가장 간결하다.

---

## 9. Resource와 Prompt의 활용 패턴

### Resource 활용

```
Claude/MCP 클라이언트 → "resource://documents/index" 읽기 요청
  → getDocumentIndex() 실행
  → "파일A (5 청크), 파일B (3 청크)..." 반환
  → 클라이언트: 어떤 파일이 검색 가능한지 파악 후 searchDocuments 호출
```

### Prompt 활용 (기본 모드)

```
Claude/MCP 클라이언트 → "rag_assistant" 프롬프트 요청 (strict 미지정)
  → buildRagPrompt(null) 실행
  → DEFAULT_SYSTEM_PROMPT 반환 (기술 질문에 searchDocuments 강제)
  → 클라이언트: 이 텍스트를 system 메시지로 사용
```

### Prompt 활용 (엄격 모드)

```
Claude/MCP 클라이언트 → "rag_assistant" 프롬프트 요청 (strict="true")
  → buildRagPrompt("true") 실행
  → STRICT_SYSTEM_PROMPT 반환 (모든 질문에 검색 강제)
```

---

## 10. MCP Tool / Resource / Prompt 역할 비교

| MCP 요소 | 역할 | 호출 주체 | 이 프로젝트 예시 |
|----------|------|----------|----------------|
| **Tool** | 실행 가능한 기능 | LLM이 필요 시 호출 | `searchDocuments`, `getCurrentDateTimeWithZone` |
| **Resource** | 읽기 전용 데이터 소스 | 클라이언트/LLM이 읽기 | `resource://documents/index` (파일 목록) |
| **Prompt** | 재사용 가능한 프롬프트 템플릿 | 클라이언트가 system 메시지로 사용 | `rag_assistant` (RAG 지침) |

---

## 11. 마이그레이션 후 파일 구조

```
src/main/java/com/example/webflux/
├── config/
│   ├── McpResourcePromptConfig.java   ← @Component (기존 @Configuration 교체)
│   └── [McpConfig.java 삭제됨]
└── service/impl/
    ├── DateTimeServiceImpl.java       ← @McpTool 사용
    └── DocumentSearchServiceImpl.java ← @McpTool 사용
```

---

## 12. 참고 자료

- [Spring AI MCP Server Annotations 공식 문서](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-annotations-server.html)
- [Spring AI MCP Client Annotations 공식 문서](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-annotations-client.html)
- [Spring AI Tool Calling 공식 문서](https://docs.spring.io/spring-ai/reference/api/tools.html)
- springaicommunity/mcp-annotations GitHub: `org.springaicommunity:mcp-annotations:0.9.0`
- MCP Java SDK: `io.modelcontextprotocol.sdk:mcp-core:0.18.2`
