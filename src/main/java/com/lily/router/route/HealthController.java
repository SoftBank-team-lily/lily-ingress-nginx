package com.lily.router.route;

import com.lily.router.route.dto.response.HealthResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** #11 헬스체크. readiness·liveness 용으로 토큰 없이 열려 있다. docs/api/01-Ingress Nginx API.md 11절 */
@RestController
public class HealthController {

    @GetMapping("/healthz")
    public HealthResponse health() {
        return HealthResponse.OK;
    }
}
