package com.example.webflux;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import com.example.webflux.service.DocumentManagementService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@SpringBootApplication
@EnableJpaRepositories(basePackages = "com.example.webflux.repository")
@RequiredArgsConstructor
public class McpServerApplication {

	private final DocumentManagementService documentManagementService;

	public static void main(String[] args) {
		SpringApplication.run(McpServerApplication.class, args);
	}

	@EventListener(ApplicationReadyEvent.class)
	public void initializeDocuments() {
		log.info("문서 인덱싱을 비동기적으로 시작합니다...");

		documentManagementService.loadDocumentsAsync()
				.thenAccept(count -> {
					if (count == 0) {
						log.info("처리할 문서가 없거나 변경된 문서가 없습니다.");
					} else {
						log.info("문서 인덱싱 완료: {}개 청크 처리됨", count);
					}
				})
				.exceptionally(throwable -> {
					log.error("문서 인덱싱 중 오류 발생", throwable);
					return null;
				});
	}
}
