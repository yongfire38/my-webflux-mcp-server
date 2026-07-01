package com.example.webflux.util;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 업로드 문서 내용에서 LLM 지시 탈취(prompt injection)를 시도하는
 * 자연어 패턴을 휴리스틱하게 탐지한다.
 *
 * HTML/스크립트 페이로드(XSS)는 화면 렌더링 시 DOMPurify가 처리하므로 대상이 아니다.
 * 여기서 잡아야 하는 것은 LLM의 추론을 탈취하려는 자연어 지시문이다.
 */
public final class PromptInjectionDetector {

    private static final List<Pattern> SUSPICIOUS_PATTERNS = List.of(
            // 한국어 — 이전 지시 무시 유도
            Pattern.compile("이전\\s*(의\\s*)?(모든\\s*)?(지시|명령)\\s*(사항)?\\s*(을|를)?\\s*(무시|잊)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("지금까지의?\\s*(지시|명령|규칙)\\s*(을|를)?\\s*(무시|잊)", Pattern.CASE_INSENSITIVE),
            // 한국어 — 시스템 프롬프트/내부 설정 유출 유도
            Pattern.compile("시스템\\s*프롬프트\\s*(를|을)?\\s*(출력|공개|보여|알려)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(내부\\s*)?(지침|설정)\\s*(을|를)?\\s*(전부\\s*)?(출력|공개|보여)", Pattern.CASE_INSENSITIVE),
            // 한국어 — 필터링 없이 그대로 출력 유도
            Pattern.compile("(필터(링)?\\s*(하지\\s*말고|없이)|검열\\s*없이).{0,10}(그대로)?\\s*(출력|전달|복사)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("그대로\\s*(출력|복사)\\s*(해|하라|해라|해줘)", Pattern.CASE_INSENSITIVE),
            // 영어 — ignore/disregard previous instructions
            Pattern.compile("ignore\\s+(all\\s+|any\\s+)?(previous|prior|above|earlier)\\s+instructions?", Pattern.CASE_INSENSITIVE),
            Pattern.compile("disregard\\s+(all\\s+|any\\s+)?(previous|prior|above|earlier)\\s+instructions?", Pattern.CASE_INSENSITIVE),
            Pattern.compile("forget\\s+(all\\s+|everything\\s+)?(previous|prior|above)\\s+(instructions?|context)", Pattern.CASE_INSENSITIVE),
            // 영어 — system prompt 유출/탈옥 유도
            Pattern.compile("(reveal|print|show|output)\\s+(the\\s+)?(system\\s+prompt|your\\s+instructions)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("you\\s+are\\s+now\\s+(in\\s+)?(developer|dan|jailbreak|unrestricted)\\s*mode", Pattern.CASE_INSENSITIVE),
            Pattern.compile("act\\s+as\\s+(if\\s+you\\s+(have\\s+)?no\\s+restrictions|an?\\s+unrestricted\\s+ai)", Pattern.CASE_INSENSITIVE)
    );

    private PromptInjectionDetector() {
    }

    /**
     * 의심 패턴이 발견되면 매칭된 패턴 문자열을 담아 반환하고, 없으면 empty를 반환한다.
     */
    public static Optional<String> detect(String content) {
        if (content == null || content.isBlank()) {
            return Optional.empty();
        }
        for (Pattern pattern : SUSPICIOUS_PATTERNS) {
            if (pattern.matcher(content).find()) {
                return Optional.of(pattern.pattern());
            }
        }
        return Optional.empty();
    }
}
