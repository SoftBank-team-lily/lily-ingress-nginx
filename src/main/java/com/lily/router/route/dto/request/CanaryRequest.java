package com.lily.router.route.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * #8 canary 열기 요청. docs/api/01-Ingress Nginx API.md 8절
 *
 * @param servicePort 비우면 80
 * @param weight      새 버전이 받는 비율 (%). 0 이면 입구만 만들고 트래픽은 보내지 않는다
 *                    (lily-cicd canary 전략이 0 에서 시작해 단계적으로 올린다)
 */
public record CanaryRequest(
        @NotBlank String serviceName,
        @Min(1) @Max(65535) Integer servicePort,
        @NotNull @Min(0) @Max(100) Integer weight
) {
}
