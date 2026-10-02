package com.lily.router.route.dto.request;

import com.lily.router.route.dto.ValidationPatterns;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** #6 추가 주소 등록 요청 (nip.io, 커스텀 도메인). docs/api/01-Ingress Nginx API.md 6절 */
public record HostRequest(@NotBlank @Pattern(regexp = ValidationPatterns.HOST) String host) {
}
