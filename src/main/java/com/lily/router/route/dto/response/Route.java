package com.lily.router.route.dto.response;

import com.lily.router.route.dto.DeploymentInfo;
import com.lily.router.route.dto.PathRule;
import java.time.Instant;
import java.util.List;

/**
 * 라우트 조회 응답. 오류가 아닌 응답은 대부분 이 형태다. docs/api/01-Ingress Nginx API.md "Route"
 *
 * @param url         {@code {scheme}://{primaryHost}}. 다른 모듈은 이 값을 앱 주소로 쓴다
 * @param hosts       받는 주소 전체. 첫 번째가 primaryHost
 * @param canary      열려 있을 때만. 아니면 null
 * @param deployment  배포 모듈이 마지막으로 보낸 값
 * @param backends    조회 시점에 Service 뒤에 붙어 있는 실제 파드
 */
public record Route(
        String namespace,
        String app,
        String url,
        String primaryHost,
        List<String> hosts,
        String serviceName,
        int servicePort,
        List<PathRule> paths,
        Integer timeoutSeconds,
        CanaryView canary,
        DeploymentInfo deployment,
        List<Backend> backends,
        Instant updatedAt
) {
}
