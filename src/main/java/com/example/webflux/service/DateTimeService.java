package com.example.webflux.service;

import java.time.LocalDateTime;
import java.time.ZoneId;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class DateTimeService {

    @Tool(
        name = "getCurrentDateTimeWithZone",
        description = "Get the current date and time from the specified timezone"
    )
    public String getCurrentDateTimeWithZone(
        @ToolParam(description = "Zone Id (예: Asia/Seoul, America/New_York, Europe/London)")
        String zoneId
    ) {
        try {
            ZoneId zone = ZoneId.of(zoneId);
            String result = LocalDateTime.now()
                .atZone(zone)
                .toString();

            log.info("현재 시간 조회 - Zone: {}, Result: {}", zoneId, result);
            return result;
        } catch (Exception e) {
            log.error("시간 조회 중 오류 발생", e);
            return "시간 조회 중 오류가 발생했습니다: " + e.getMessage();
        }
    }
}
