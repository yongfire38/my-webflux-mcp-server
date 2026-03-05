# WebFlux MCP Server Sample

Spring AI MCP Server with WebFlux (Reactive) transport (SSE) 샘플 프로젝트입니다.

## 특징

- **Transport**: HTTP/SSE (Server-Sent Events)
- **Processing Model**: Reactive, Non-blocking I/O (Spring WebFlux)
- **Performance**: 적은 스레드로 많은 동시 연결 처리 (고성능)
- **Deployment**: 독립적인 웹 서비스로 실행
- **Multi-client**: 수천 명의 클라이언트가 동시 접속 가능
- **Remote Access**: 네트워크를 통한 원격 접속 지원
- **Production Ready**: Spring AI 공식 권장 방식

## 환경 설정

### 표준프레임워크 실행환경 5.0 (Boot 적용)

| 항목 | 버전 |
| :--- | :--- |
| JDK | 17 |
| Jakarta EE | 10 |
| Servlet | 6.0 |
| Spring Framework | 6.2.11 |
| Spring Boot | 3.5.6 |
| Spring AI | 1.1.2 |

### 개발 및 빌드 도구

| 항목 | 버전 |
| :--- | :--- |
| Maven | 3.9.9 |
| Docker | 28.0.4 |

### 외부 서비스

| 항목 | 버전 | 비고 |
| :--- | :--- | :--- |
| Ollama | 0.16.0 | LLM 모델 서빙 |
| PostgreSQL (PGVector) | 16 | Docker 이미지: `pgvector/pgvector:pg16` |

## 제공하는 Tools

### 1. getTouristWeatherIndex
- **설명**: 도시 코드로 현재 날짜의 관광지 TCI(기후 쾌적도) 지수를 조회합니다
- **파라미터**:
  - `cityAreaId`: 도시 코드 (예: 1168000000은 서울 강남구)
- **구현**: `WeatherService.java`

### 2. getTouristWeatherByDate
- **설명**: 도시 코드와 특정 날짜로 관광지 TCI 지수를 조회합니다
- **파라미터**:
  - `cityAreaId`: 도시 코드
  - `date`: 조회할 날짜 (형식: yyyyMMdd, 예: 20251103)
- **구현**: `WeatherService.java`

### 3. getCityInfo
- **설명**: 주요 도시 코드 정보를 반환합니다
- **파라미터**: 없음
- **구현**: `WeatherService.java`

### 4. getCurrentDateTimeWithZone
- **설명**: 특정 타임존의 현재 날짜와 시간을 조회합니다
- **파라미터**:
  - `zoneId`: Zone ID (예: Asia/Seoul, America/New_York, Europe/London)
- **구현**: `DateTimeService.java`

## 프로젝트 구조

```
my-webflux-mcp-server/
├── pom.xml                                    # Maven 설정 (webflux, spring-ai-mcp-server-webflux)
├── README.md
└── src/main/
    ├── java/com/example/webflux/
    │   ├── WebfluxMcpApplication.java         # Main Application
    │   ├── config/
    │   │   └── McpConfig.java                 # MCP Tool 등록
    │   └── service/
    │       ├── WeatherService.java            # 관광지 날씨 TCI 조회 Tool
    │       └── DateTimeService.java           # 현재 시간 조회 Tool
    └── resources/
        └── application.properties             # 애플리케이션 설정
```

## 빌드 및 실행

### 1. 빌드
```bash
cd C:\workspace-test\webflux-mcp-sample\my-webflux-mcp-server
mvn clean package
```

### 2. 실행
```bash
java -jar target/webflux-mcp-0.0.1-SNAPSHOT.jar
```

서버가 시작되면 다음 포트에서 실행됩니다:
- **Application**: http://localhost:9090
- **MCP SSE Endpoint**: http://localhost:9090/mcp/sse
- **API Docs**: http://localhost:9090/v3/api-docs

## MCP 클라이언트 연결 설정

### Claude Desktop 설정 예시

`claude_desktop_config.json`:
```json
{
  "mcpServers": {
    "webflux-weather-api": {
      "url": "http://localhost:9090/mcp/sse",
      "transport": "sse"
    }
  }
}
```

### 원격 서버 연결 예시
서버를 다른 PC에서 실행한 경우:
```json
{
  "mcpServers": {
    "webflux-weather-api": {
      "url": "http://remote-server-ip:9090/mcp/sse",
      "transport": "sse"
    }
  }
}
```

## STDIO 방식과의 차이점

| 항목 | STDIO 방식 | WebFlux 방식 (이 프로젝트) |
|------|-----------|--------------------------|
| **Dependency** | `spring-ai-starter-mcp-server` | `spring-ai-starter-mcp-server-webflux` |
| **Transport** | stdin/stdout | HTTP/SSE (Reactive) |
| **Processing** | 블로킹 I/O | 논블로킹 I/O |
| **프로세스** | 클라이언트가 직접 시작 | 독립 실행 웹 서버 |
| **접속 범위** | 로컬 PC만 | 네트워크로 원격 접속 가능 |
| **동시 접속** | 단일 클라이언트 | 수천+ 클라이언트 (고성능) |
| **배포 방식** | 각 PC에 jar 파일 배포 | 서버 1대만 실행 |
| **프로덕션** | 테스트용 | Spring AI 공식 권장 |

## API 문서 확인

서버 실행 후 브라우저에서 다음 URL로 접속:
- API Docs: http://localhost:9090/v3/api-docs

여기서 MCP 서버가 제공하는 엔드포인트와 스키마를 확인할 수 있습니다.

## 테스트

### MCP Tools 목록 확인
서버 시작 후 로그에서 등록된 Tool 목록을 확인할 수 있습니다:
```
DEBUG com.example.webflux - Registered MCP Tools:
  - getTouristWeatherIndex
  - getTouristWeatherByDate
  - getCityInfo
  - getCurrentDateTimeWithZone
```

### 클라이언트 테스트
Claude Desktop이나 다른 MCP 클라이언트에서:
1. "서울 강남구 관광지 날씨 알려줘" → `getCityInfo` + `getTouristWeatherIndex` Tool 호출
2. "제주도 관광지 TCI 지수는?" → `getCityInfo` + `getTouristWeatherIndex` Tool 호출
3. "서울 시간대의 현재 시간은?" → `getCurrentDateTimeWithZone` Tool 호출

## 주의사항

- `weather.api.key`는 공공데이터포털(기상청 관광지 기후 지수)의 실제 API 키로 교체하세요
- 프로덕션 환경에서는 보안 설정 (HTTPS, 인증 등)을 추가하세요
- 방화벽 설정에서 9090 포트를 열어야 원격 접속이 가능합니다

## WebFlux (Reactive) 특징

### 성능 장점
- **논블로킹 I/O**: 스레드가 I/O 대기 중에도 다른 요청 처리 가능
- **적은 메모리**: 스레드 스택 메모리를 절약 (요청당 ~1MB → ~10KB)
- **높은 동시성**: 적은 스레드(4~8개)로 수천 개의 연결 처리
- **확장성**: 서버 리소스를 효율적으로 활용

### WebMVC vs WebFlux 성능 비교

| 동시 연결 수 | WebMVC | WebFlux (현재) |
|--------------|--------|----------------|
| 100명 | 스레드 100개, 메모리 ~100MB | 스레드 8개, 메모리 ~10MB |
| 500명 | 스레드 500개, 메모리 ~500MB | 스레드 8개, 메모리 ~30MB |
| 1000명+ | Thread Pool 고갈 가능 | 안정적 처리 |

### 프로덕션 배포 권장
Spring AI 공식 문서에서 프로덕션 환경에 WebFlux 방식을 권장합니다.
