package com.lily.ingress.route.dto;

import java.util.List;

/**
 * 배포 모듈(lily-cicd)이 알려 주는 배포 정보. 라우팅에는 쓰지 않고 다른 모듈이 조회할 수 있게 보관만 한다.
 * 요청(#1, #5)과 응답({@code Route.deployment})에 같이 쓴다. docs/api/01-Ingress Nginx API.md "DeploymentInfo"
 *
 * @param strategy blue-green, canary 등
 * @param slot     지금 트래픽을 받는 슬롯 (blue, green, stable ...)
 * @param nodes    배포 모듈이 의도한 노드. 실제로 뜬 노드는 {@code Route.backends} 에 있다
 * @param status   DEPLOYING, ACTIVE, FAILED, ROLLED_BACK 등 배포 모듈이 정한 값
 */
public record DeploymentInfo(
        String strategy,
        String slot,
        String version,
        String image,
        List<String> nodes,
        String status
) {
}
