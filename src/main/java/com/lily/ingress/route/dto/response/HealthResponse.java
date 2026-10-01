package com.lily.ingress.route.dto.response;

/** #11 헬스체크 응답 */
public record HealthResponse(String status) {

    public static final HealthResponse OK = new HealthResponse("ok");
}
