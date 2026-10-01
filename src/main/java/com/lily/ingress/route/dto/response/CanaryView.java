package com.lily.ingress.route.dto.response;

/** {@code Route.canary}. docs/api/01-Ingress Nginx API.md "CanaryView" */
public record CanaryView(String serviceName, int servicePort, int weight) {
}
