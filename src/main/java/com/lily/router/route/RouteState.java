package com.lily.router.route;

import com.lily.router.route.dto.DeploymentInfo;
import com.lily.router.route.dto.PathRule;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 앱 하나의 라우팅 원본. 기본 Ingress 의 어노테이션 {@code lily.io/route} 에 JSON 으로 둔다.
 * 따로 DB 를 두지 않으므로 Ingress 를 지우면 라우트도 사라진다.
 *
 * @param primaryHost 배포 모듈이 정한 주소. 배포할 때마다 바뀔 수 있다
 * @param extraHosts  사람이나 다른 모듈이 추가한 주소. 재배포해도 남는다
 */
record RouteState(
        String serviceName,
        int servicePort,
        String primaryHost,
        List<String> extraHosts,
        List<PathRule> paths,
        Integer timeoutSeconds,
        DeploymentInfo deployment,
        Instant updatedAt
) {
    RouteState {
        extraHosts = extraHosts == null ? List.of() : List.copyOf(extraHosts);
        paths = paths == null || paths.isEmpty() ? List.of(PathRule.ROOT) : List.copyOf(paths);
    }

    /** 기본 주소가 먼저 온다. 같은 주소는 한 번만 */
    List<String> hosts() {
        LinkedHashSet<String> all = new LinkedHashSet<>();
        all.add(primaryHost);
        all.addAll(extraHosts);
        return List.copyOf(all);
    }

    RouteState withExtraHosts(List<String> hosts) {
        List<String> next = new ArrayList<>(new LinkedHashSet<>(hosts));
        next.remove(primaryHost);
        return new RouteState(serviceName, servicePort, primaryHost, next, paths, timeoutSeconds, deployment,
                Instant.now());
    }

    RouteState withDeployment(DeploymentInfo info) {
        return new RouteState(serviceName, servicePort, primaryHost, extraHosts, paths, timeoutSeconds, info,
                Instant.now());
    }
}
