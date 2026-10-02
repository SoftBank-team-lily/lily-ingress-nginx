package com.lily.router.route.dto.response;

/**
 * Service 뒤에 실제로 붙어 있는 파드 (EndpointSlice 기준). docs/api/01-Ingress Nginx API.md "Backend"
 *
 * @param node 파드가 떠 있는 노드 (EC2)
 */
public record Backend(String pod, String address, String node, boolean ready) {
}
