package com.lily.router.route.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 노출할 경로 하나. 요청({@code RegisterRouteRequest.paths})과 응답({@code Route.paths})에 같이 쓴다.
 * docs/api/01-Ingress Nginx API.md "PathRule"
 *
 * @param path     "/" 로 시작
 * @param pathType Prefix 또는 Exact. 비우면 Prefix
 */
public record PathRule(
        @NotBlank @Pattern(regexp = ValidationPatterns.PATH) String path,
        @Pattern(regexp = "Prefix|Exact") String pathType
) {
    public static final PathRule ROOT = new PathRule("/", "Prefix");

    public PathRule {
        pathType = pathType == null || pathType.isBlank() ? "Prefix" : pathType;
    }
}
