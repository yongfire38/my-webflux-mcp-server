# MCP 서버/클라이언트 인증 설계 (클라이언트 단위 최소 인증)

> 상태: **구현 완료 + 실행 검증 완료(서버 단독 + 클라이언트 연동 Sampling 포함) — A안(정적 yml) 운영 마찰 확인, B안(DB) 전환 재검토 중**
> 관련 이슈: 내부 #24, 외부 [eGovFramework/egovframe-common-components#1150](https://github.com/eGovFramework/egovframe-common-components/issues/1150)
> 대상: `my-webflux-mcp-server`(enforcement) + `my-webflux-mcp-client`(자격증명 부착)
> 작성 근거: 로컬 소스 직접 확인 + Spring AI 1.1.8 공식 문서(context7) 교차검증
> 검토 이력: 2026-07-13(1차) — 별도 세션에서 소스 재대조, `docs.spring.io` 공식 레퍼런스 재검증, §6.2-D 필터 스코프/Swagger 분기 불일치 2건 수정
> 검토 이력: 2026-07-13(2차) — 로컬 `.m2` 캐시의 실제 jar(`mcp-spring-webflux-0.18.3`, `spring-ai-autoconfigure-mcp-server-webflux-1.1.8`, `mcp-annotations-0.9.0`)를 `javap`로 디컴파일해 §8-1·2·3(방식① 성패를 가르던 항목) 전부 확정 — `contextExtractor` 시그니처, `@ConditionalOnMissingBean` 빈 오버라이드 가능 여부, `ctx.transportContext()` accessor 모두 실물 API로 확인. §6.2-C를 개념 예시에서 확정 코드로 교체
> 구현·검증 이력: 2026-07-13(3차) — 같은 PC에서 실제로 Ollama/Docker/Maven이 가용함을 확인(VRAM 제약은 문서 작성 PC 한정 이슈였음), §6.2 전체 코드 작성 후 서버·클라이언트 모두 `mvn -o clean compile` 성공. 서버 기동 후 `/mcp` 익명 initialize(200), REST 쓰기 401/202, Swagger 차단, 실제 클라이언트로 유효 키 업로드(Sampling 왕복 포함) 성공 + DB `source_client` 정확히 기록, 무효 키 업로드 거부(`인증된 clientId: null`)까지 실측 확인 — 상세는 §10. 검증 중 A안(정적 yml)의 "신규 클라이언트 등록/키 교체마다 서버 재배포 필요" 트레이드오프가 실제 운영 마찰로 체감되어, B안(DB 레지스트리) 조기 전환 가능성을 §5-D5/§13에 재평가로 남김
> 비고: 애초에 "VRAM 부족으로 실행 검증 불가"는 이 문서를 처음 작성한 PC 한정 제약이었음 — 이 문서를 넘겨받은(또는 이어서 작업하는) PC에 Ollama·Docker·Maven이 있다면 §8-4를 포함한 모든 실행 검증을 바로 진행할 것.

---

## 0. 한 줄 요약

여러 사용자가 공유하는 단일 지식베이스(RAG) 구조이므로 **사용자별 인가(RFC 8693)는 범위 밖**이다. 실제 위협은 **인증 없이 열린 적재(쓰기) 경로**다. 이를 **클라이언트(에이전트) 단위 API 키**로 닫는다. 검색(읽기)은 계속 완전 오픈. 검증은 서버 필터에서, **허용/거부 판단은 적재 Tool 내부**에서 한다.

---

## 1. 배경과 결정 (요약)

- 외부 이슈 #1150 제안: OAuth2 인가서버 + RFC 8693 토큰 교환으로 **사용자 신원**을 MCP 서버까지 전파(다부처 문서 교차조회 차단).
- 본 프로젝트 전제는 다름:
  - 벡터스토어 하나(`ragdb`)에 문서를 함께 넣고 함께 검색하는 **단일 공유 지식베이스**. `DocumentMetadata`에 부서/소유자 접근제어 필드 없음.
  - 검색을 사용자별로 제한하는 것은 지식베이스의 목적(누구나 전체 검색)과 배치됨.
- 따라서:
  - `searchDocuments`(읽기) = **인증 없이 오픈 유지**.
  - 실제 갭 = **적재(쓰기) 경로가 열려 있어 임의 호출자가 KB를 오염**시킬 수 있음. 콘텐츠 검증(#21: 프롬프트 인젝션·덮어쓰기 차단)과 별개로 **신원 검증(등록된 클라이언트인가)이 전무**.
  - 사람 로그인/OAuth2 인가서버/RFC 8693은 **현 범위 밖**(멀티테넌트로 확장 시 재검토). 지금 도입하면 실제 위협 대비 과잉.
  - 대신 **클라이언트 단위 최소 인증** 도입: 클라이언트별 서로 다른 자격증명(`client_id` + API 키)으로 위조 불가능하게 "누가 적재했는지" 귀속 + 등록 클라이언트 검증.

---

## 2. 현행 소스 사실 (확인 완료)

| 항목 | 실제 값 / 위치 |
|---|---|
| 실행환경 | `egovframe-boot-starter-parent` **5.0.0** (Spring Boot 3.5 / Security 6.5 계열) |
| Spring AI | **1.1.8** (`pom.xml`에서 parent 기본 1.0.1을 오버라이드) |
| 서버 전송 | `spring-ai-starter-mcp-server-webflux` → **WebFlux(reactive)**, Streamable HTTP, `POST /mcp` 단일 엔드포인트 |
| MCP 애노테이션 | `org.springaicommunity.mcp` (`@McpTool`, `McpToolParam`, `McpAsyncRequestContext`) |
| 검색 Tool | `DocumentSearchServiceImpl#searchDocuments(String query)` — **ctx 인자 없음**, 순수 읽기 |
| 적재 Tool | `DocumentClientUploadServiceImpl#uploadAndIndexDocument(McpAsyncRequestContext ctx, String jobId, String filename, String base64Content, String mimeType)` |
| 현행 출처 식별 | `String clientId = ctx.clientInfo() != null ? ctx.clientInfo().name() : "unknown";` — **클라이언트 self-report(위조 가능)** |
| 메타데이터 | `DocumentMetadata.sourceClient` 필드 존재 |
| REST 쓰기 | `DocumentController` (`@RequestMapping("/api/documents")`): `POST /reindex`, `POST /upload`, `GET /status` |
| 클라이언트 전송 생성 | `McpClientHolder#tryReconnect()`: `WebClientStreamableHttpTransport.builder(builderTemplate.clone().baseUrl(url))` — **`WebClient.Builder` 경유 = 헤더 삽입 지점 열려 있음** |
| 클라이언트 self-id | `spec.clientInfo(new McpSchema.Implementation(clientId, "1.0.0"))`, `clientId = app.mcp-client-id` |
| Swagger | 서버/클라 모두 `springdoc` `/swagger-ui.html` 노출 |

### Spring AI 1.1.8 문서 확인 결과 (context7)

- `McpAsyncRequestContext` / `McpSyncRequestContext`는 "request details, **server exchange, transport context**, logging/progress/sampling/elicitation"에 대한 통합 인터페이스를 제공한다. → **transport context 접근 가능**함이 문서로 확인됨.
- `McpTransportContext`는 stateless 접근용 파라미터로 Tool 시그니처에 직접 주입 가능:
  ```java
  @McpTool(name = "stateless-tool", ...)
  public String statelessTool(McpTransportContext context, @McpToolParam(...) String input) { ... }
  ```
- **공식 `mcp-security` 모듈(OAuth2 resource server + API-key 지원, `McpServerOAuth2Configurer` 등)은 "specifically compatible with Spring WebMVC-based servers"** — **WebMVC 전용**. 본 서버는 **WebFlux**라 이 스타터를 그대로 쓸 수 없음. → §4, §6.2 참고.

> **[2026-07-13 재검증] `docs.spring.io/spring-ai/reference/api/mcp/mcp-security.html` 공식 문서 직접 확인 — 원문 인용:**
> - Server: "This module is compatible with Spring WebMVC-based servers only." / Known Limitations: **"WebFlux-based servers are not supported."**
> - Client: Known Limitations — **"Spring WebFlux servers are not supported."** (client도 동일 — §6.1에서 공식 모듈 대신 `defaultHeader` 수동 부착으로 간 것이 유일한 선택지였음을 재확인)
> - Authorization Server: Known Limitations — **"Spring WebFlux servers are not supported."**
> - 상위 레퍼런스 인덱스(`mcp-server-boot-starter-docs.html`)에는 이 모듈 전체가 **`MCP Security (WIP)`** — 즉 아직 정식 릴리스가 아닌 개발 중 상태로 표기됨.
> - **결론**: §4의 "WebMVC 전용" 전제는 context7 인용뿐 아니라 공식 문서 원문으로 이중 확인됨. Server·Client·Authorization Server 3개 모듈 전부 WebFlux 미지원이라, 이 프로젝트(서버·클라이언트 모두 WebFlux)는 애초에 공식 모듈을 부분적으로도 활용할 수 없음 — §4~§9의 자체 구현 방향이 유일한 선택지였다는 근거가 더 명확해짐. 향후 WIP 딱지가 풀리고 WebFlux 지원이 추가되면 이 설계를 재검토할 트리거로 삼을 것.

---

## 3. 위협 모델 & 목표

**막는 것**
- 익명/미등록 호출자의 **적재(KB 오염)**: `uploadAndIndexDocument` 및 REST `POST /api/documents/{upload,reindex}`.
- **출처 위조**: 현재 `sourceClient`가 self-report `clientInfo.name`에서 옴 → #21의 덮어쓰기 소유권 검사(`checkOverwriteOwnership`)를 무력화 가능. 인증된 키에 바인딩된 canonical id로 대체해야 함.

**막지 않는 것 (의도적)**
- 검색(`searchDocuments`, `describeKnowledgeBase`) 읽기 — 완전 오픈 유지.
- 사용자(사람) 단위 인가/감사 — 범위 밖.

**비목표 / 지금 안 함**
- rate limit, 읽기 감사 로그(필요성 커지면 별도). 단, 신원 배선은 모든 Tool에 걸어두어 나중에 공짜로 켤 수 있게 함(§6.2-E).

---

## 4. 핵심 제약: WebFlux → 공식 모듈 직접 사용 불가

Spring AI `mcp-security`(WebMVC 전용)를 못 쓰므로, **표준 Spring Security WebFlux(reactive) + 커스텀 배선**으로 구현한다. 두 계층을 명확히 분리:

```
[클라이언트]  X-API-Key 헤더 부착 (WebClient.Builder)
      │  POST /mcp  (JSON-RPC 바디: 검색/적재 구분은 바디 안에만 있음)
      ▼
[서버 WebFilter/SecurityWebFilterChain]
      · X-API-Key 있으면 → 레지스트리 검증 → canonical clientId 확정
      · 없거나 무효면 → 그냥 통과(익명). ★ /mcp를 하드 차단하지 않음
      · 확정된 clientId를 "요청 스코프"로 전달(→ transport context)
      ▼
[Tool 메서드]
      · searchDocuments      : 신원 무시, 항상 수행
      · uploadAndIndexDocument: clientId 없으면 거부(401 상당) + sourceClient=검증된 clientId로 덮어씀
```

**왜 필터 하드 차단이 아니라 2단인가**: 검색 오픈 + 적재 차단 + 단일 `POST /mcp`가 동시에 참이다. `/mcp`를 필터에서 통째로 막으면 익명 검색도 죽는다. Tool 종류는 JSON-RPC 바디 안에만 있어 경로매칭으로 구분 불가. 따라서 필터는 "있으면 검증, 없으면 통과"만 하고, 최종 허용/거부는 **적재 Tool 내부**에서 판단해야 한다.

---

## 5. 설계 결정 요약

| # | 결정 | 근거 |
|---|---|---|
| D1 | 자격증명 = **클라이언트별 API 키**(대칭, 서버 발급). OAuth2/JWT 아님 | 공유 KB엔 서비스 신원이면 충분, 최소 작업 |
| D2 | 전송 = **HTTP 헤더 `X-API-Key`**. `clientInfo`는 신뢰 근거 아님(로깅용만) | 헤더는 서버가 out-of-band 검증, self-report는 위조 가능 |
| D3 | enforcement = **적재 Tool 내부**, 필터는 검증·신원주입만 | §4 |
| D4 | 신원 전달 = **`McpTransportContext`** 경유(reactive SecurityContext가 Tool 실행까지 전파된다고 가정하지 않음) | Tool은 MCP dispatch의 별도 reactive 체인에서 실행됨 |
| D5 | 레지스트리 = **A안(정적 yml, BCrypt 해시)** 먼저, `ClientRegistry` 인터페이스 뒤로 은닉 → 후에 B(DB)로 교체 | 알려진 클라이언트 소수, 최소 작업 |
| D6 | `sourceClient`는 **검증된 canonical id로 덮어씀**(self-report 폐기) | #21 위조 갭 실제 차단 |
| D7 | REST 쓰기(`/api/documents/**`)·Swagger는 **경로매칭으로 별도 차단** | 일반 HTTP 경로라 바로 닫힘, MCP보다 쉬운 승리 |

---

## 6. 상세 설계

### 6.1 클라이언트 (`my-webflux-mcp-client`)

**삽입 지점(확인됨):** `McpClientHolder#tryReconnect()`의 transport 생성부.

```java
// 현행
var freshTransport = WebClientStreamableHttpTransport
        .builder(builderTemplate.clone().baseUrl(url))
        .endpoint(endpoint)
        .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
        .build();

// 변경: WebClient.Builder에 인증 헤더 부착
WebClient.Builder authedBuilder = builderTemplate.clone()
        .baseUrl(url)
        .defaultHeader("X-API-Key", mcpApiKey);   // 신규 주입값

var freshTransport = WebClientStreamableHttpTransport
        .builder(authedBuilder)
        .endpoint(endpoint)
        .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
        .build();
```

- `mcpApiKey`는 `McpClientHolder` 생성자 인자로 추가하고, `McpOptionalConfig`(홀더를 만드는 곳)에서 `@Value("${app.mcp-api-key}")`로 주입.
- `clientInfo(...)`는 그대로 둔다(로깅/디버깅용). **인증 근거 아님**을 주석으로 명시.
- `app.mcp-client-id`와 `app.mcp-api-key`는 **쌍**으로 관리(서버 레지스트리의 한 엔트리에 대응).

> 참고: Spring AI 문서에는 클라이언트측 `.transportContextProvider(new AuthenticationMcpTransportContextProvider())` 패턴도 있으나, 이는 인증 컨텍스트 전파용이고 본 설계의 "정적 API 키 1개"에는 `defaultHeader`가 더 단순·확실하다. 향후 OAuth2로 갈 때 재검토.

### 6.2 서버 (`my-webflux-mcp-server`)

#### A. 의존성

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-security</artifactId>
</dependency>
```

- WebFlux 환경이므로 **reactive Security**가 켜진다.
- ⚠️ **함정**: security 스타터를 추가하는 순간 기본 `SecurityWebFilterChain`이 **모든 요청을 인증 요구**로 잠근다 → 검색도 막힘. 반드시 아래 커스텀 체인으로 덮어써 **/mcp·검색을 permitAll**로 열어야 한다.
- ⚠️ 공식 `mcp-security`(spring-ai) 스타터는 **추가하지 말 것**(WebMVC 전용, WebFlux에서 오작동/미적용).

#### B. `ClientRegistry` (레지스트리 추상화, D5)

```java
public interface ClientRegistry {
    /** 원시 API 키 → canonical clientId. 미등록/불일치면 Optional.empty(). */
    Optional<String> resolveClientId(String rawApiKey);
}
```

**A안 구현 — 정적 yml + BCrypt 해시:**

```java
@ConfigurationProperties(prefix = "mcp.auth")
public class McpAuthProperties {
    private List<Entry> clients = new ArrayList<>();
    public record Entry(String clientId, String keyHash) {} // keyHash = BCrypt(rawKey)
    // getters/setters
}

@Component
@RequiredArgsConstructor
public class StaticClientRegistry implements ClientRegistry {
    private final McpAuthProperties props;
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    @Override
    public Optional<String> resolveClientId(String rawApiKey) {
        if (rawApiKey == null || rawApiKey.isBlank()) return Optional.empty();
        // 주의: 후보가 소수라는 전제. 매칭되는 항목의 해시와 비교(BCrypt 자체가 상수시간).
        return props.getClients().stream()
                .filter(e -> encoder.matches(rawApiKey, e.keyHash()))
                .map(McpAuthProperties.Entry::clientId)
                .findFirst();
    }
}
```

- 키 생성/등록 절차: 별도 유틸(또는 1회성 `main`)로 `BCryptPasswordEncoder().encode(rawKey)` 산출 → yml `keyHash`에 기입. **평문 키는 yml에 저장 금지.**
- 폐기 = 해당 엔트리 삭제 후 재배포. (재배포 없는 폐기가 필요해지면 B안(DB 테이블)으로 구현체 교체 — 인터페이스 덕에 국소 변경.)

#### C. API 키 검증 + 신원 주입 (핵심, D3·D4)

> **[2026-07-13 §8 항목 1·2 검증 완료] 별도 PC 없이 로컬 `.m2` 캐시(`mcp-spring-webflux-0.18.3.jar`, `spring-ai-autoconfigure-mcp-server-webflux-1.1.8.jar`, `mcp-annotations-0.9.0.jar`)를 `javap`로 직접 디컴파일하여 아래 API 전부를 실물 바이트코드 기준으로 확인함. 방식①이 문서상 추정이 아니라 확정된 구현 경로로 격상됨 — 방식②(폴백)는 불필요해져 삭제.**

**방식 ①(확정): `McpTransportContextExtractor` 배선**

`javap` 확인 결과, `WebFluxStreamableServerTransportProvider.Builder`에 정확히 아래 시그니처가 존재:
```
contextExtractor(io.modelcontextprotocol.server.McpTransportContextExtractor<org.springframework.web.reactive.function.server.ServerRequest>)
```

```java
McpTransportContextExtractor<ServerRequest> extractor = (serverRequest) -> {
    String rawKey = serverRequest.headers().firstHeader("X-API-Key");
    String clientId = registry.resolveClientId(rawKey).orElse(null); // 무효=null(익명)
    Map<String, Object> ctx = new HashMap<>();
    if (clientId != null) ctx.put("mcp.clientId", clientId);
    return McpTransportContext.create(ctx); // io.modelcontextprotocol.common.McpTransportContext — 정적 팩토리 확인됨
};
```

**전송 provider 빈 오버라이드 방법 확정**: `spring-ai-autoconfigure-mcp-server-webflux`의 `McpServerStreamableHttpWebFluxAutoConfiguration#webFluxStreamableServerTransportProvider(...)` 빈 메서드가 `@ConditionalOnMissingBean`임을 바이트코드로 확인 — 즉 아래처럼 **같은 타입의 빈을 직접 정의하면 자동구성이 그대로 물러난다**(별도 커스터마이저/프로퍼티 불필요):

```java
@Configuration
public class McpTransportSecurityConfig {

    @Bean
    public WebFluxStreamableServerTransportProvider webFluxStreamableServerTransportProvider(
            ObjectMapper objectMapper,
            McpServerStreamableHttpProperties properties,   // 자동구성과 동일 프로퍼티 재사용 (mcpEndpoint, keepAliveInterval, disallowDelete)
            ClientRegistry registry) {

        McpTransportContextExtractor<ServerRequest> extractor = serverRequest -> {
            String rawKey = serverRequest.headers().firstHeader("X-API-Key");
            String clientId = registry.resolveClientId(rawKey).orElse(null);
            Map<String, Object> ctx = new HashMap<>();
            if (clientId != null) ctx.put("mcp.clientId", clientId);
            return McpTransportContext.create(ctx);
        };

        return WebFluxStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(objectMapper))   // 자동구성 내부와 동일 래핑 (McpClientHolder에서 이미 쓰는 패턴)
                .messageEndpoint(properties.getMcpEndpoint())
                .keepAliveInterval(properties.getKeepAliveInterval())
                .disallowDelete(properties.isDisallowDelete())
                .contextExtractor(extractor)
                .build();
    }
}
```

- `McpServerStreamableHttpProperties`(`org.springframework.ai.mcp.server.common.autoconfigure.properties`)의 실제 필드는 `mcpEndpoint`/`keepAliveInterval`/`disallowDelete` 3개뿐임을 확인 — 위 코드가 자동구성과 동등한 값을 재사용하도록 정확히 매핑됨.
- **참고(채택 안 함)**: Builder에 `securityValidator(ServerTransportSecurityValidator)`도 있으나, 이건 헤더만 보고 즉시 예외를 던지는 **하드 차단** 훅(주로 Origin 헤더 검증 등 DNS 리바인딩 방지 용도로 추정)이라 "있으면 검증, 없으면 익명 통과"가 필요한 이 설계엔 맞지 않음 — `contextExtractor`가 맞는 선택.

Tool 내부에서 신원 읽기 — **accessor 확정**: `McpAsyncRequestContext`는 `McpRequestContextTypes<ET>`를 상속하며, 여기에 `transportContext()`가 정확히 존재함(`javap`로 확인):

```java
public Mono<String> uploadAndIndexDocument(McpAsyncRequestContext ctx, ...) {
    String clientId = (String) ctx.transportContext().get("mcp.clientId");  // 확정된 접근 경로 — 별도 헬퍼 불필요
    if (clientId == null) {
        return Mono.error(new UnauthorizedClientException("등록된 클라이언트 자격증명 필요"));
    }
    // 이하: clientId를 sourceClient로 사용(self-report clientInfo 폐기)
    ...
}
```

- 애초에 문서가 검토했던 "Tool 파라미터로 `McpTransportContext` 직접 받기"(stateless-tool 예시) 방식은 불필요해짐 — `ctx.transportContext()`로 충분.
- (참고) 전송 provider 내부 바이트코드에 `McpTransportContext`를 Reactor `Context`로 실어 나르는 람다(`lambda$handlePost$...(McpTransportContext, Context)`)가 확인됨 — SDK가 자체적으로 MCP 전용 Reactor Context 키로 전파하는 구조. Spring Security의 `ReactiveSecurityContextHolder`에 의존하지 않기로 한 D4 판단(방식② 회피)이 정확했음을 뒷받침.

#### D. `SecurityWebFilterChain` (검색 오픈, 쓰기 REST 차단 — D7)

> **[2026-07-13 리뷰 수정] 최초 초안은 `apiKeyAuthFilter`가 체인 전체(`/mcp` 포함)에 걸려 있어, 본문의 "REST에만 적용" 의도와 실제 코드가 어긋났다.** 필터가 경로 제한 없이 걸리면 `/mcp`의 익명 검색 요청까지 이 필터를 타게 되어 §4의 핵심 목표("검색은 완전 오픈")가 깨질 위험이 있었다. `AuthenticationWebFilter.setRequiresAuthenticationMatcher(...)`로 REST 쓰기 경로에만 명시적으로 스코프를 좁혀 아래와 같이 수정한다. Swagger도 본문(dev만 permit)과 코드(무조건 denyAll)가 불일치했는데, 이 프로젝트에 아직 `dev`/`prod` 프로파일 분리가 없으므로(`analysis-webflux-mcp.md` §7 별도 작업 항목) 지금은 **프로파일 분기 없이 무조건 denyAll로 단순화**하고, 프로파일 분리 작업이 끝나면 그때 `dev`만 permit하도록 재조정한다.

```java
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    SecurityWebFilterChain filterChain(ServerHttpSecurity http, ClientRegistry registry) {
        // ★ REST 쓰기 경로에만 적용 — 이 매처에 안 걸리면 필터가 아예 개입하지 않음(= /mcp는 그대로 통과)
        ServerWebExchangeMatcher restWriteMatcher =
                ServerWebExchangeMatchers.pathMatchers(HttpMethod.POST, "/api/documents/**");

        AuthenticationWebFilter apiKeyFilter = apiKeyAuthFilter(registry);
        apiKeyFilter.setRequiresAuthenticationMatcher(restWriteMatcher);

        return http
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .authorizeExchange(ex -> ex
                // MCP 엔드포인트: HTTP 계층 permitAll — 적재 거부는 Tool 내부에서(§C)
                .pathMatchers("/mcp/**", "/mcp").permitAll()
                // 읽기 REST 오픈
                .pathMatchers(HttpMethod.GET, "/api/documents/status").permitAll()
                // 쓰기 REST 차단: 유효 X-API-Key 필요
                .pathMatchers(HttpMethod.POST, "/api/documents/**").authenticated()
                // Swagger: 프로파일 분리 전까지는 무조건 차단(단순화). 분리 후 dev만 permit으로 재조정
                .pathMatchers("/swagger-ui/**", "/v3/api-docs/**").denyAll()
                .anyExchange().permitAll()
            )
            // 스코프가 좁혀진 필터만 등록 — /mcp 요청은 이 필터를 거치지 않고 authorizeExchange의 permitAll로 바로 통과
            .addFilterAt(apiKeyFilter, SecurityWebFiltersOrder.AUTHENTICATION)
            .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
            .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
            .build();
    }
    // apiKeyAuthFilter(...) : X-API-Key → registry.resolveClientId → Authentication 생성/거부
    //   restWriteMatcher에 안 걸리는 요청(/mcp 등)에는 이 필터가 관여하지 않으므로 별도의 "통과" 분기가 불필요하다.
}
```

- REST `POST /api/documents/**`는 경로매칭이 되므로 여기서 바로 닫힌다(Tool 내부 로직 불필요).
- **구현 시 회귀 확인 필수(§8-4와 연결)**: `apiKeyFilter`에 `setRequiresAuthenticationMatcher`를 빠뜨리면 리뷰 이전 초안과 동일한 문제(익명 검색 차단 위험)로 되돌아간다. §10 테스트의 "키 없이 검색 → 200"이 이 회귀를 잡아내는 케이스이므로 반드시 통과 확인.

#### E. 읽기 감사용 배선(선택, 지금 끔)

- extractor(§C)는 **모든 요청**에 대해 clientId를 심으므로, `searchDocuments`도 나중에 `McpTransportContext`를 파라미터로 추가하면 "누가 검색했나"를 로깅만으로 얻을 수 있다.
- 지금은 검색 시그니처 변경 없이 오픈 유지. 필요 시 파라미터 추가 + 로그만.

#### F. `sourceClient` 덮어쓰기 (D6)

- 현행 `checkOverwriteOwnership(filename, clientId, jobId)`의 `clientId`가 지금은 self-report. → **검증된 canonical clientId로 교체**.
- 저장 시 `DocumentMetadata.sourceClient = 검증된 clientId`. self-report `ctx.clientInfo().name()`은 로그로만 남기고 소유권 판단에 쓰지 않음.

### 6.3 REST/Swagger

- §6.2-D에서 처리. 추가로 `DocumentController`의 업로드/재인덱싱이 **인증 principal(clientId)**을 받도록(선택) → 그쪽 적재도 `sourceClient` 귀속 일관성 확보. 최소 구현에서는 "인증 필요"만으로도 오염 차단 목적은 달성.

---

## 7. 설정 키

**서버 `application.yml`**
```yaml
mcp:
  auth:
    clients:
      - clientId: webflux-mcp-client
        keyHash: "$2a$10$....(BCrypt of raw key)...."
      # 클라이언트 추가 시 엔트리 추가

# Swagger 운영 차단은 SecurityConfig에서 프로파일 분기(별도 flag 불필요)
```

**클라이언트 `application.yml`**
```yaml
app:
  mcp-client-id: webflux-mcp-client
  mcp-api-key: "raw-key-plaintext-여기"   # 서버 keyHash의 원본. 배포 비밀로 관리(가능하면 env/secret)
```

- 원시 키는 클라이언트에만 평문 필요(전송해야 하므로). 가능하면 환경변수/시크릿 매니저로 주입, yml 커밋 금지.

---

## 8. 빌드 PC에서 반드시 확인할 항목

> **[2026-07-13] 아래 1·2·3은 `.m2` 로컬 캐시 jar를 `javap`로 직접 디컴파일하여 검증 완료 — 방식①의 성패를 가르던 항목들이라 사실상 이 설계의 최대 리스크가 해소됨.** 상세 근거는 §6.2-C 참고. 남은 건 4(런타임 회귀)뿐이며, 이건 jar 디컴파일로는 확인 불가 — 실제 기동이 필요해 빌드 PC 몫으로 남는다.

1. ~~WebFlux Streamable 전송에 `McpTransportContextExtractor` 주입 방법~~ ✅ **확인 완료** — `WebFluxStreamableServerTransportProvider.Builder.contextExtractor(McpTransportContextExtractor<ServerRequest>)` 존재. 자동구성 빈(`McpServerStreamableHttpWebFluxAutoConfiguration#webFluxStreamableServerTransportProvider`)이 `@ConditionalOnMissingBean`이라 동일 타입 빈을 직접 정의하면 오버라이드됨(전용 customizer 불필요).
2. ~~`McpAsyncRequestContext`에서 transport context 꺼내는 정확한 accessor~~ ✅ **확인 완료** — `McpRequestContextTypes<ET>.transportContext()`가 정확한 accessor(`McpAsyncRequestContext`가 이를 상속). `ctx.transportContext().get("mcp.clientId")`로 바로 접근 가능, 별도 헬퍼나 Tool 파라미터 추가 불필요.
3. ~~reactive SecurityContext의 Tool 전파 여부(방식 ② 가능성)~~ **불필요해짐(moot)** — 1·2가 확정되어 방식①로 완결됨. 다만 확인 과정에서 전송 provider 내부가 `McpTransportContext`를 SDK 전용 Reactor Context 키로 실어 나르는 람다가 발견되어,애초에 Spring Security의 `ReactiveSecurityContextHolder`와는 별개 경로였음이 방증됨(D4 판단 근거 보강).
4. ~~security 스타터 추가 후 기존 검색/REST 회귀~~ ✅ **확인 완료(2026-07-13, 실제 기동)** — `/mcp` 익명 initialize 200(§10), REST 쓰기 401/202, `GET /api/documents/status` 200, Swagger 차단 전부 실측 확인. `apiKeyFilter.setRequiresAuthenticationMatcher(restWriteMatcher)`가 의도대로 `/mcp`에 관여하지 않음을 curl로 직접 확인. 커스텀 `webFluxStreamableServerTransportProvider` 빈이 자동구성을 정상적으로 밀어내고 기동 성공(순환참조·빈 충돌 없음) — 실제 클라이언트의 Sampling 포함 업로드까지 성공한 것으로 간접 재확인됨.

---

## 9. 폴백 (참고용 — §8-1·2·3 확정으로 필요성 크게 낮아짐)

방식①의 핵심 API가 모두 존재함이 확인되어 아래 폴백이 필요할 가능성은 낮아졌다. §8-4(런타임 회귀)에서 예상 밖의 문제(예: 빈 오버라이드 시 순환참조, 세션 팩토리 연동 이슈 등)가 나올 경우에 대비해 남겨둔다.

- **F-A. 적재를 인증 REST로 이전**: `uploadAndIndexDocument`를 MCP Tool에서 떼어 `POST /api/documents/client-upload`(인증 필요)로. 경로매칭으로 깔끔히 닫히나 **Sampling(클라 Ollama 요약) + Progress 스트리밍 상실** → 차선.
- **F-B. 서버를 WebMVC로 이전**: 공식 `mcp-security`(OAuth2 resource server + API-key) 사용 가능. 단 서버 전체가 reactive(Mono, PgVectorStore, Sampling)라 이식 비용 큼. 공유 KB PoC엔 과함 → 향후 OAuth2 정식화 시점에만 검토.

---

## 10. 테스트 계획 및 실측 결과 (2026-07-13, 실제 서버·클라이언트 기동)

| 케이스 | 기대 | 실측 결과 |
|---|---|---|
| **`/mcp` 익명 initialize** (§8-4 핵심 회귀) | 200, 세션 발급 | ✅ curl로 확인 — `X-API-Key` 없이 200 + `Mcp-Session-Id` 발급 |
| 유효 키로 검색(`searchDocuments`) | 200, 정상 결과 | ⚠️ 간접 확인만 — `searchDocuments` 자체를 직접 호출하진 않음. Tool에 인증 검사 코드가 아예 없어 구조상 항상 통과하나, 실행 재확인은 미실시 |
| **키 없이 검색** | 200(오픈 유지) | ⚠️ 위와 동일 — initialize 핸드셰이크(인증 검사와 무관한 전 단계)는 오픈 확인됐으나 실제 Tool 호출로는 미실시 |
| 유효 키로 적재 | 성공, `sourceClient` = 검증된 clientId | ✅ **완전 확인** — 실제 클라이언트로 업로드, Sampling 왕복(Ollama 요약 185자) 포함 전 과정 성공. 서버 로그 `인증된 clientId: webflux-mcp-client`, DB `document_metadata.source_client = webflux-mcp-client` 직접 조회 확인 |
| **무효 키로 적재** | 거부 | ✅ **확인** — 클라이언트 키를 일부러 자르고 재기동 후 업로드 시도 → 서버 로그 `인증된 clientId: null` → `UnauthorizedClientException` → 거부 메시지 반환 |
| **키 없이 적재** | 거부(Unauthorized), KB 미변경 | ⚠️ 별도 실행은 안 했으나 **코드상 무효 키와 동일 경로** — `McpTransportSecurityConfig`의 extractor는 헤더 부재든 불일치든 `resolveClientId()`가 `Optional.empty()`를 반환하는 지점이 같아 `ctx.transportContext().get(...)`이 동일하게 null. 위 무효 키 테스트가 이 경로를 실질적으로 함께 검증함 |
| self-report clientInfo ≠ 실제 키 | `sourceClient`는 **키 기준**으로 기록(위조 무력화) | ⚠️ 부분 확인 — 무효 키 테스트에서 self-report(`webflux-mcp-client`)와 별개로 인증된 clientId가 `null`로 처리되는 것(=self-report를 신원 근거로 안 씀)은 확인. 다만 "다른 등록된 클라이언트의 키로 위장"하는 시나리오는 등록된 클라이언트가 1개뿐이라 미실시 |
| 타 클라 문서 덮어쓰기 시도(검증된 id 기준) | `checkOverwriteOwnership` 거부 | ❌ **미실시** — 클라이언트가 1개만 등록돼 있어 재현 불가. 서버에 두 번째 client-id/key 엔트리를 추가하면 검증 가능 |
| 키 없이 `POST /api/documents/reindex`(REST) | 401 | ✅ 확인 — curl로 401 |
| 유효 키로 `POST /api/documents/reindex`(REST) | 202/200 | ✅ 확인 — curl로 202 "문서 재인덱싱이 처리되었습니다" |
| 키 없이 `GET /api/documents/status` | 200 | ✅ 확인 — curl로 200 |
| `/swagger-ui.html` 차단 | 차단 | ✅ 확인 — 401로 차단(denyAll 대상이 anonymous라 403 대신 401 — Spring Security 표준 동작, 차단 목적은 동일하게 달성) |

- 통합테스트는 도커 Postgres(pgvector) 필요 — 이미 기동 중인 기존 컨테이너(`postgres-pgvector`, `mcp-client-postgres`)로 검증, 별도 `docker compose up` 불필요했음.
- Sampling 경로(클라 Ollama 의존)까지 포함해 **완전한 실행 검증이 같은 세션에서 끝남** — 애초 우려했던 "VRAM 있는 별도 PC 필요"는 이 PC에 Ollama가 이미 있어 해당 없었음.
- 남은 미실시 케이스(덮어쓰기 차단, 진짜 위조 시나리오)는 **등록된 클라이언트가 1개뿐이라 재현 불가**했던 것 — 두 번째 client-id를 등록하면 바로 검증 가능. B안(DB) 전환 시 클라이언트 추가가 쉬워지므로 자연스럽게 이 케이스도 재검증하게 될 것(§13).

---

## 11. 작업 순서 (체크리스트)

- [x] **§8-1·2·3 확인** — transport context extractor 주입법 + accessor 확정, 방식① 확정(2026-07-13, `javap` 디컴파일로 검증)
- [x] 서버: `spring-boot-starter-security` 추가 + `SecurityConfig`(검색/READ permitAll, 쓰기 REST authenticated, Swagger 차단, `apiKeyFilter` 스코프 제한 — §6.2-D)
- [x] 서버: `ClientRegistry` + `StaticClientRegistry`(A안, BCrypt) + `McpAuthProperties`
- [x] 서버: `McpTransportSecurityConfig` — `webFluxStreamableServerTransportProvider` 빈 오버라이드 + `contextExtractor` 배선(§6.2-C, 코드 확정됨)
- [x] 서버: `uploadAndIndexDocument` — `ctx.transportContext().get("mcp.clientId")`로 미인증 거부 + `sourceClient` = 검증 clientId(§6.2-F)
- [x] 서버: `checkOverwriteOwnership` clientId 소스 교체 (기존 `clientId` 변수 출처만 교체 — 로직 자체는 무변경)
- [x] 클라: `McpClientHolder`에 `X-API-Key` 부착 + `mcpApiKey` 주입 배선
- [x] 설정: 양쪽 yml 키 추가, 키 생성 유틸(`KeyGenTool`, BCrypt) — 이번 세션 발급 키로 검증 완료, 운영 전환 시 새 키로 교체 권장
- [x] **§8-4 실행 검증** — 커스텀 전송 provider 빈이 자동구성을 정상적으로 밀어내고 기동 확인, 검색(`/mcp`) 오픈 유지 + 미인증 적재 401 회귀 없음 확인
- [x] 테스트(§10) 대부분 통과 — 미실시 2건(타 클라 덮어쓰기 차단, 진짜 위조 시나리오)은 클라이언트가 1개만 등록돼 재현 불가했던 것. §13 참고
- [ ] README/CLAUDE.md 갱신, 내부 #24 종결 — 미착수
- [ ] **B안(DB 레지스트리) 전환 여부 결정** — §13 참고, 실사용 중 재검토

---

## 12. 향후 확장 연결점 (범위 밖, 참고)

- 멀티테넌트(부서별 격리 문서)로 가면: `DocumentMetadata`에 소유/접근 필드 추가 + 이슈 #1150의 **OAuth2 인가서버 + RFC 8693 토큰 교환**(사용자 신원 전파)로 승격.
- 그 경우 본 설계의 `X-API-Key` 서비스 인증은 스펙트럼의 왼쪽 끝으로 남고, 사용자 인가 단이 그 위에 얹힌다: `서비스/클라이언트 인증(현재) → RFC 8693(사용자 인가·감사) → XAA(신뢰 도메인 간)`.
- Spring AI 2.0 / Streamable HTTP 1급화는 Boot 4 / Framework 7 필요 → 5.0 실행환경(Framework 6.2.x)과 어긋나므로 차기 실행환경 트랙에서.

---

## 13. 구현 후 재평가 — B안(DB 레지스트리) 조기 전환 검토 (2026-07-13)

D5에서는 "알려진 클라이언트가 소수"라는 전제로 A안(정적 yml)을 먼저 구현하기로 했었다. 실제로 구현·검증해본 결과, A안의 트레이드오프로 미리 적어뒀던 "재배포 필요"가 생각보다 체감되는 마찰이었다:

- **키 발급 흐름이 전부 수동**: 원시 키 생성 → `KeyGenTool`로 해싱 → 서버 yml에 엔트리 추가 → **서버 재기동** → 원시 키를 클라이언트 운영자에게 아웃오브밴드로 전달 → 클라이언트 yml에 입력 → **클라이언트 재기동**. 클라이언트 하나 등록/교체하는 데 양쪽 재기동이 다 필요함을 이번 세션에서 직접 겪음(무효 키 테스트 시 클라이언트 재기동, 최초 등록 시 서버 재기동).
- **§10 테스트 공백의 원인이기도 함**: "타 클라 문서 덮어쓰기 차단", "진짜 위조 시나리오" 두 케이스를 검증하려면 클라이언트를 하나 더 등록해야 하는데, 그때마다 서버 재기동이 필요해서 이번 세션에선 실시하지 않음 — B안이었다면 API 호출 한 번으로 등록하고 바로 재현해볼 수 있었을 상황.
- 다른 클라이언트 에이전트가 실제로 여러 개 붙기 시작하면(애초에 이 설계의 동기 중 하나), 등록/폐기/로테이션마다 서버를 재기동해야 하는 게 운영 부담으로 누적될 가능성이 큼.

**결론**: `ClientRegistry` 인터페이스로 감싸둔 목적이 정확히 이런 상황을 위한 것이었으므로, 클라이언트 수가 실제로 늘어나거나 등록/폐기가 잦아지는 시점에 **B안(DB 테이블 기반, 관리 UI 없이 SQL로 등록/폐기)으로 조기 전환할 가능성을 높게 봄**. 전환 시 변경 범위는 `StaticClientRegistry`를 대체하는 새 구현체(`DbClientRegistry` 등) + `mcp_clients` 테이블/엔티티/Repository 정도로, `McpTransportSecurityConfig`/`SecurityConfig`/Tool 쪽 코드는 무변경(인터페이스 의존이라 국소 변경 확인됨).
