package com.example.webflux.config;

import java.util.Map;
import java.util.stream.Collectors;

import org.springaicommunity.mcp.annotation.McpArg;
import org.springaicommunity.mcp.annotation.McpPrompt;
import org.springaicommunity.mcp.annotation.McpResource;
import org.springframework.stereotype.Component;

import com.example.webflux.model.DocumentMetadata;
import com.example.webflux.repository.DocumentMetadataRepository;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * MCP Resource / Prompt 등록 컴포넌트
 */
@Component
@RequiredArgsConstructor
public class McpResourcePromptConfig {

    private final DocumentMetadataRepository documentMetadataRepository;

    /**
     * MCP Resource: 인덱싱된 문서 목록
     * URI: resource://documents/index
     */
    @McpResource(
            uri = "resource://documents/index",
            name = "인덱싱된 문서 목록",
            description = "RAG 지식 베이스에 등록된 검색 가능한 문서 목록. "
                    + "searchDocuments 호출 전 어떤 파일이 있는지 확인할 수 있습니다.",
            mimeType = "text/plain"
    )
    public Mono<String> getDocumentIndex() {
        return Mono.fromCallable(() -> {
            long totalChunks = documentMetadataRepository.count();
            if (totalChunks == 0) {
                return "인덱싱된 문서가 없습니다. 먼저 문서를 업로드하고 인덱싱하세요.";
            }
            Map<String, Long> fileChunks = documentMetadataRepository.findAll().stream()
                    .collect(Collectors.groupingBy(DocumentMetadata::getFilename, Collectors.counting()));

            StringBuilder sb = new StringBuilder();
            sb.append("=== RAG 지식 베이스 현황 ===\n");
            sb.append(String.format("총 파일 수: %d개 | 총 청크 수: %d개\n\n", fileChunks.size(), totalChunks));
            sb.append("파일 목록 (파일명 - 청크 수):\n");
            fileChunks.forEach((filename, count) ->
                    sb.append(String.format("  - %s (%d 청크)\n", filename, count)));
            sb.append("\nsearchDocuments 도구로 이 파일들의 내용을 검색할 수 있습니다.");
            return sb.toString();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * MCP Prompt: RAG 어시스턴트 시스템 프롬프트 템플릿
     * - strict=false (기본): 기술 질문에만 searchDocuments 강제
     * - strict=true:         모든 질문에 검색 강제
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

    private static final String DEFAULT_SYSTEM_PROMPT = """
            당신은 문서 기반 AI 어시스턴트입니다.

            [필수 규칙]
            1. 기술 질문(설정 방법, 사용법, 오류 해결, 코드 예제, API 설명)에는
               반드시 searchDocuments 도구를 먼저 호출하고 그 결과를 근거로 답변하세요.
            2. 학습된 지식만으로 답변하지 마세요. 문서 검색 후 답변하세요.
            3. 검색 결과가 없으면 "문서에서 찾을 수 없습니다"라고 안내하세요.
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
