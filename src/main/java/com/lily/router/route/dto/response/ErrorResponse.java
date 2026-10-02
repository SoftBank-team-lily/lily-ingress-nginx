package com.lily.router.route.dto.response;

import java.time.Instant;

/**
 * 모든 오류 응답. 팀 공통 형식 {@code {timestamp, code, message}}. code 목록은 docs/api/01-Ingress Nginx API.md "오류"
 *
 * @param timestamp 오류를 만든 시각 (UTC, ISO-8601)
 */
public record ErrorResponse(Instant timestamp, String code, String message) {

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(Instant.now(), code, message);
    }
}
