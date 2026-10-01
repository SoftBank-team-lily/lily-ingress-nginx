package com.lily.ingress.route.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * #8 canary 열기 요청. docs/api/01-Ingress Nginx API.md 8절
 *
 * @param servicePort 비우면 80
 * @param weight      새 버전이 받는 비율 (%)
 */
public record CanaryRequest(
        @NotBlank String serviceName,
        @Min(1) @Max(65535) Integer servicePort,
        @NotNull @Min(1) @Max(100) Integer weight
) {
}
