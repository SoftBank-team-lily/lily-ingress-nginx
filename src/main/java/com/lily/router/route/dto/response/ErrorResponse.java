package com.lily.router.route.dto.response;

/** 모든 오류 응답. code 목록은 docs/api/01-Ingress Nginx API.md "오류" */
public record ErrorResponse(String code, String message) {
}
