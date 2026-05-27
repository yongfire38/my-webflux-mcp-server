package com.example.webflux.service.impl;

import java.time.LocalDateTime;
import java.time.ZoneId;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

import com.example.webflux.service.DateTimeService;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Service
@Slf4j
public class DateTimeServiceImpl extends EgovAbstractServiceImpl implements DateTimeService {

    @Override
    @McpTool(
        name = "getCurrentDateTimeWithZone",
        description = "Get the current date and time from the specified timezone"
    )
    public Mono<String> getCurrentDateTimeWithZone(
        @McpToolParam(description = "Zone Id (예: Asia/Seoul, America/New_York, Europe/London)")
        String zoneId
    ) {
        return Mono.fromCallable(() -> {
            ZoneId zone = ZoneId.of(zoneId);
            String result = LocalDateTime.now()
                .atZone(zone)
                .toString();
            log.info("현재 시간 조회 - Zone: {}, Result: {}", zoneId, result);
            return result;
        }).onErrorReturn("시간 조회 중 오류가 발생했습니다.");
    }
}
