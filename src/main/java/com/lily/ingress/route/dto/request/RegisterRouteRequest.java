package com.lily.ingress.route.dto.request;

import com.lily.ingress.route.dto.DeploymentInfo;
import com.lily.ingress.route.dto.PathRule;
import com.lily.ingress.route.dto.ValidationPatterns;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.List;

/**
 * #1 라우트 등록·갱신 요청. 배포가 끝난 뒤 배포 모듈이 보낸다. docs/api/01-Ingress Nginx API.md 1절
 *
 * @param serviceName    트래픽을 받을 Service (같은 namespace)
 * @param servicePort    Service 포트. 비우면 80
 * @param host           주소 전체. 비우면 {@code {app}.{domain}}
 * @param paths          노출할 경로. 비우면 "/" 전체
 * @param timeoutSeconds 프록시 읽기·쓰기 타임아웃. WebSocket 처럼 오래 열린 연결에 쓴다. 비우면 컨트롤러 기본값
 */
public record RegisterRouteRequest(
        @NotBlank String serviceName,
        @Min(1) @Max(65535) Integer servicePort,
        @Pattern(regexp = ValidationPatterns.HOST) String host,
        List<@Valid PathRule> paths,
        @Min(1) @Max(86400) Integer timeoutSeconds,
        @Valid DeploymentInfo deployment
) {
}
