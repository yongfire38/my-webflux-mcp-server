package com.example.webflux.service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.net.URLEncoder;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class WeatherService {

    // 이미 인코딩된 API 키 사용
    @Value("${weather.api.key}")
    private String API_KEY;

    @Tool(name = "getTouristWeatherIndex", description = "도시 코드로 관광지 TCI 지수를 반환합니다.")
    public String getTouristWeatherIndex(String cityAreaId) {
        String currentDate = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        return getTouristWeatherByDate(cityAreaId, currentDate);
    }

    @Tool(name = "getTouristWeatherByDate", description = "도시 코드와 날짜로 관광지 TCI 지수를 반환합니다. 날짜 형식: yyyyMMdd")
    public String getTouristWeatherByDate(String cityAreaId, String date) {
        try {
            // API URL 구성
            String apiUrl = "https://apis.data.go.kr/1360000/TourStnInfoService1/getCityTourClmIdx1"
                + "?ServiceKey=" + URLEncoder.encode(API_KEY, StandardCharsets.UTF_8)
                + "&pageNo=1"
                + "&numOfRows=10"
                + "&dataType=JSON"
                + "&CURRENT_DATE=" + date
                + "&DAY=5"
                + "&CITY_AREA_ID=" + cityAreaId;

            log.info("API 요청 URL: {}", apiUrl);

            // HttpURLConnection 사용하여 API 호출
            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");

            int responseCode = conn.getResponseCode();
            log.info("응답 코드: {}", responseCode);

            // 응답 읽기
            BufferedReader in;
            if (responseCode >= 200 && responseCode < 300) {
                in = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            } else {
                in = new BufferedReader(new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8));
            }

            String inputLine;
            StringBuilder response = new StringBuilder();
            while ((inputLine = in.readLine()) != null) {
                response.append(inputLine);
            }
            in.close();

            String json = response.toString();
            log.info("API 응답: {}", json);

            // 응답 코드가 오류인 경우 처리
            if (responseCode >= 400) {
                return "API 오류 응답 (코드: " + responseCode + "): " + json;
            }

            // JSON 파싱
            JsonNode root = new ObjectMapper().readTree(json);
            JsonNode responseNode = root.path("response");

            // 헤더 정보 확인
            JsonNode headerNode = responseNode.path("header");
            String resultCode = headerNode.path("resultCode").asText();
            String resultMsg = headerNode.path("resultMsg").asText();

            log.info("응답 코드: {}, 메시지: {}", resultCode, resultMsg);

            // 정상 서비스 확인 (NORMAL SERVICE. 또는 NORMAL_SERVICE 둘 다 허용)
            if (!"00".equals(resultCode) &&
                    !resultMsg.contains("NORMAL") &&
                    !resultMsg.contains("정상")) {
                return "API 오류: " + resultMsg;
            }

            // 바디 정보 확인
            JsonNode bodyNode = responseNode.path("body");
            if (bodyNode.isMissingNode() || bodyNode.isNull()) {
                return "API 응답에 body 정보가 없습니다.";
            }

            // 아이템 정보 확인
            JsonNode itemsNode = bodyNode.path("items");
            if (itemsNode.isMissingNode() || itemsNode.isNull()) {
                return "API 응답에 items 정보가 없습니다.";
            }

            JsonNode items = itemsNode.path("item");
            if (items.isMissingNode() || items.isNull()) {
                return "API 응답에 item 정보가 없습니다.";
            }

            // 결과 포맷팅
            StringBuilder sb = new StringBuilder();
            sb.append("📍 도시 코드 ").append(cityAreaId).append(" 관광지 TCI 지수 (기준일: ").append(date).append(")\n\n");

            if (items.isArray() && items.size() > 0) {
                for (JsonNode item : items) {
                    sb.append("📅 ").append(item.path("tm").asText("날짜 정보 없음"))
                            .append("\n🌍 ").append(item.path("totalCityName").asText("지역 정보 없음"))
                            .append("\n🌡️ TCI 지수: ").append(item.path("kmaTci").asText("지수 정보 없음"))
                            .append(" (").append(item.path("TCI_GRADE").asText("등급 정보 없음")).append(")\n\n");
                }
            } else {
                sb.append("해당 날짜와 도시에 대한 데이터가 없습니다.");
            }

            return sb.toString();

        } catch (JsonProcessingException e) {
            log.error("JSON 처리 중 오류", e);
            return "JSON 처리 중 오류가 발생했습니다: " + e.getMessage();
        } catch (Exception e) {
            log.error("API 호출 중 오류", e);
            return "API 호출 중 오류가 발생했습니다: " + e.getMessage();
        }
    }

    @Tool(name = "getCityInfo", description = "주요 도시 코드 정보를 반환합니다.")
    public String getCityInfo() {
        return "주요 도시 및 시/군/구 코드 정보 (일부 예시):\n\n" +
                "- 서울 강남구: 1168000000\n" +
                "- 서울 송파구: 1171000000\n" +
                "- 서울 강서구: 1150000000\n" +
                "- 서울 중구: 1114000000\n" +
                "- 부산 해운대구: 2635000000\n" +
                "- 부산 중구: 2611000000\n" +
                "- 대구 수성구: 2726000000\n" +
                "- 대구 중구: 2711000000\n" +
                "- 인천 연수구: 2818500000\n" +
                "- 인천 부평구: 2823700000\n" +
                "- 광주 북구: 2917000000\n" +
                "- 대전 서구: 3017000000\n" +
                "- 울산 남구: 3114000000\n" +
                "- 세종 세종시: 3611031000\n" +
                "- 경기 수원시: 4111100000\n" +
                "- 경기 부천시: 4119500000\n" +
                "- 경기 고양시: 4128100000\n" +
                "- 강원 춘천시: 4211000000\n" +
                "- 충북 청주시: 4311100000\n" +
                "- 충남 천안시: 4413100000\n" +
                "- 전북 전주시: 4511100000\n" +
                "- 전남 여수시: 4613000000\n" +
                "- 경북 포항시: 4711100000\n" +
                "- 경남 창원시: 4812100000\n" +
                "- 제주 제주시: 5011000000\n" +
                "\n※ 전체 시/군/구 코드는 내부 데이터(지역코드.txt) 또는 기상청 공식 문서를 참고하세요.";
    }
}
