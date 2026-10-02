package com.lily.router.route;

import com.lily.router.route.dto.DeploymentInfo;
import com.lily.router.route.dto.request.CanaryRequest;
import com.lily.router.route.dto.request.HostRequest;
import com.lily.router.route.dto.request.RegisterRouteRequest;
import com.lily.router.route.dto.response.Route;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * lily-router API v1. 명세는 docs/api/01-Ingress Nginx API.md. 메서드 주석의 번호는 그 문서의 절 번호다.
 *
 * <ul>
 *   <li>배포 모듈(lily-cicd): 배포가 끝나면 라우트 등록(#1), 배포 정보 갱신(#5), canary 열고 닫기(#8, #9)</li>
 *   <li>운영자: 추가 주소(#6, #7)</li>
 *   <li>다른 모듈(builder, observer, frontend): 조회(#2, #3, #10)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
public class RouteController {

    private final RouteService routes;

    public RouteController(RouteService routes) {
        this.routes = routes;
    }

    /** #1 라우트 등록·갱신. 200 Route / 400, 409, 502 */
    @PutMapping("/routes/{namespace}/{app}")
    public Route register(@PathVariable String namespace, @PathVariable String app,
                          @Valid @RequestBody RegisterRouteRequest request) {
        return routes.register(namespace, app, request);
    }

    /** #2 라우트 목록. namespace 를 비우면 전체. 200 Route[] / 502 */
    @GetMapping("/routes")
    public List<Route> list(@RequestParam(required = false) String namespace) {
        return routes.list(namespace);
    }

    /** #3 라우트 조회. 200 Route / 400, 404 */
    @GetMapping("/routes/{namespace}/{app}")
    public Route get(@PathVariable String namespace, @PathVariable String app) {
        return routes.get(namespace, app);
    }

    /** #4 라우트 삭제. canary 도 같이 지운다. 204 / 400, 404 */
    @DeleteMapping("/routes/{namespace}/{app}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String namespace, @PathVariable String app) {
        routes.delete(namespace, app);
    }

    /** #5 배포 정보 갱신. 라우팅은 그대로. 200 Route / 400, 404 */
    @PutMapping("/routes/{namespace}/{app}/deployment")
    public Route updateDeployment(@PathVariable String namespace, @PathVariable String app,
                                  @Valid @RequestBody DeploymentInfo request) {
        return routes.updateDeployment(namespace, app, request);
    }

    /** #6 추가 주소 등록. 이미 있으면 그대로. 200 Route / 400, 404, 409 */
    @PostMapping("/routes/{namespace}/{app}/hosts")
    public Route addHost(@PathVariable String namespace, @PathVariable String app,
                         @Valid @RequestBody HostRequest request) {
        return routes.addHost(namespace, app, request.host());
    }

    /** #7 추가 주소 삭제. 기본 주소는 400. 200 Route / 400, 404 */
    @DeleteMapping("/routes/{namespace}/{app}/hosts/{host}")
    public Route removeHost(@PathVariable String namespace, @PathVariable String app, @PathVariable String host) {
        return routes.removeHost(namespace, app, host);
    }

    /** #8 canary 열기. 기본 라우트가 먼저 있어야 한다. 200 Route / 400, 404 */
    @PutMapping("/routes/{namespace}/{app}/canary")
    public Route openCanary(@PathVariable String namespace, @PathVariable String app,
                            @Valid @RequestBody CanaryRequest request) {
        return routes.openCanary(namespace, app, request);
    }

    /** #9 canary 닫기. 열려 있지 않아도 200. 200 Route / 404 */
    @DeleteMapping("/routes/{namespace}/{app}/canary")
    public Route closeCanary(@PathVariable String namespace, @PathVariable String app) {
        return routes.closeCanary(namespace, app);
    }

    /** #10 주소로 라우트 찾기. 기본 주소와 추가 주소 모두. 200 Route / 404 */
    @GetMapping("/hosts/{host}")
    public Route findByHost(@PathVariable String host) {
        return routes.findByHost(host);
    }
}
