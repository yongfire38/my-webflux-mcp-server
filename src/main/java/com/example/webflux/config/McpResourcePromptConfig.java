package com.example.webflux.config;

import org.springaicommunity.mcp.annotation.McpArg;
import org.springaicommunity.mcp.annotation.McpPrompt;
import org.springaicommunity.mcp.annotation.McpResource;
import org.springframework.stereotype.Component;

import com.example.webflux.service.DocumentSearchService;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

/**
 * MCP Resource / Prompt 등록 컴포넌트
 *
 * @McpResource: 읽기 전용 데이터 소스 노출 (resource://documents/index)
 *   → LLM이 어떤 파일이 검색 가능한지 사전 파악
 *   → searchDocuments 호출 전 사전 확인 용도
 *
 * @McpPrompt: 재사용 가능한 프롬프트 템플릿 (rag_assistant)
 *   → 클라이언트가 system 메시지로 사용하면 LLM이 searchDocuments를 우선 호출
 *   → strict 파라미터로 엄격도 조정 가능
 */
@Component
@RequiredArgsConstructor
public class McpResourcePromptConfig {

    private final DocumentSearchService documentSearchService;

    /**
     * [Resource 예시] 인덱싱된 문서 목록
     *
     * MCP Resource는 LLM이 읽기 전용으로 접근하는 데이터 소스입니다.
     * 도구(Tool)와의 차이: 도구는 실행/검색, 리소스는 정적 데이터 노출
     *
     * - URI: resource://documents/index
     * - 반환: 인덱싱된 파일 목록 텍스트
     * - describeKnowledgeBase MCP Tool과 동일 로직이므로 DocumentSearchService에 위임
     */
    @McpResource(
            uri = "resource://documents/index",
            name = "인덱싱된 문서 목록",
            description = "RAG 지식 베이스에 등록된 검색 가능한 문서 목록. "
                    + "searchDocuments 호출 전 어떤 파일이 있는지 확인할 수 있습니다.",
            mimeType = "text/plain"
    )
    public Mono<String> getDocumentIndex() {
        return documentSearchService.describeKnowledgeBase();
    }

    /**
     * [Prompt 예시] RAG 어시스턴트 시스템 프롬프트
     *
     * MCP Prompt는 재사용 가능한 프롬프트 템플릿입니다.
     * 도구(Tool)와의 차이: 도구는 기능 실행, 프롬프트는 LLM 행동 지침 제공
     *
     * - strict=false (기본): 기술 질문에만 searchDocuments 강제
     * - strict=true        : 모든 질문에 검색 강제, 검색 결과 없으면 답변 거부
     */
    @McpPrompt(
            name = "rag_assistant",
            description = "RAG 기반 문서 검색을 위한 시스템 프롬프트. "
                    + "클라이언트가 system 메시지로 사용하여 LLM의 RAG 검색을 유도합니다."
    )
    public String buildRagPrompt(
            @McpArg(
                    name = "strict",
                    description = "엄격 모드 여부 (true/false). "
                            + "true이면 모든 질문에 검색 강제, 검색 결과 없으면 답변 거부.",
                    required = false
            ) String strict
    ) {
        return "true".equalsIgnoreCase(strict) ? STRICT_SYSTEM_PROMPT : DEFAULT_SYSTEM_PROMPT;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 시스템 프롬프트 템플릿
    // ─────────────────────────────────────────────────────────────────────────

    private static final String DEFAULT_SYSTEM_PROMPT = """
            당신은 문서 기반 AI 어시스턴트입니다.

            [필수 규칙]
            1. 기술 질문(설정 방법, 사용법, 오류 해결, 코드 예제, API 설명)에는
               반드시 searchDocuments 도구를 먼저 호출하고 그 결과를 근거로 답변하세요.
            2. 학습된 지식만으로 답변하지 마세요. 문서 검색 후 답변하세요.
            3. 검색 결과가 없으면 "문서에서 찾을 수 없습니다"라고 안내하세요.
            4. describeKnowledgeBase 도구로 어떤 문서가 있는지 먼저 확인할 수 있습니다.
            """;

    private static final String STRICT_SYSTEM_PROMPT = """
            당신은 문서 기반 AI 어시스턴트입니다.

            [엄격 모드 - 필수 규칙]
            1. 모든 질문에 대해 searchDocuments 도구를 반드시 호출하세요. 예외 없음.
            2. 검색 결과가 있는 경우에만 답변하세요.
            3. 검색 결과가 없으면 "해당 내용은 문서에 없습니다"라고만 답변하세요.
            4. 학습된 지식으로 답변을 보완하거나 추측하지 마세요.
            """;
}
