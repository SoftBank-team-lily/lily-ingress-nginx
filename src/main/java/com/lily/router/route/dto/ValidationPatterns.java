package com.lily.router.route.dto;

/** 요청 검증에 쓰는 정규식. docs/api/01-Ingress Nginx API.md 공통 규칙과 같이 고친다 */
public final class ValidationPatterns {

    /** DNS 이름. lily-cicd DeployRequest.host 와 같은 규칙 */
    public static final String HOST = "^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$";

    /** Ingress path. "/" 로 시작하는 출력 가능한 ASCII */
    public static final String PATH = "^/[!-~]*$";

    private ValidationPatterns() {
    }
}
