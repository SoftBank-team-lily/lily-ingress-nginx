package com.lily.ingress.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param domain       호스트를 지정하지 않은 앱의 기본 주소 {@code {app}.{domain}}
 * @param urlScheme    응답 url 의 scheme. ALB 가 HTTPS 를 끝내면 https
 * @param ingressClass 만드는 Ingress 의 ingressClassName
 * @param apiToken     /api 요청의 Bearer 토큰. 비우면 인증하지 않는다
 */
@ConfigurationProperties(prefix = "lily.ingress")
public record IngressProperties(
        @DefaultValue("apps.lilycloud.kr") String domain,
        @DefaultValue("https") String urlScheme,
        @DefaultValue("nginx") String ingressClass,
        @DefaultValue("") String apiToken
) {
}
