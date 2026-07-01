package com.example.webflux.util;

/**
 * 다른 클라이언트(또는 REST 경로)가 적재한 문서를 덮어쓰려는 시도를 차단할 때 사용하는 예외.
 */
public class DocumentOverwriteForbiddenException extends RuntimeException {

    public DocumentOverwriteForbiddenException(String message) {
        super(message);
    }
}
