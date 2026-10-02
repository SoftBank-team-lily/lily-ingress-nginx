package com.lily.router.route;

import com.lily.router.route.dto.request.CanaryRequest;
import com.lily.router.route.dto.PathRule;
import io.fabric8.kubernetes.api.model.networking.v1.HTTPIngressPath;
import io.fabric8.kubernetes.api.model.networking.v1.HTTPIngressPathBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.api.model.networking.v1.IngressBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.IngressRule;
import io.fabric8.kubernetes.api.model.networking.v1.IngressRuleBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.IngressTLSBuilder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 라우트 원본을 Ingress 리소스로 바꾼다. 클러스터를 부르지 않는다.
 *
 * <p>이 서비스가 만든 Ingress 에는 {@code app.kubernetes.io/managed-by: lily-router} 가 붙는다.
 * 이 레이블이 있는 Ingress 는 이 서비스만 고친다.
 */
final class IngressSpecs {

    static final String MANAGED_BY = "app.kubernetes.io/managed-by";
    static final String MANAGER = "lily-router";
    static final String APP_LABEL = "lily.io/app";
    static final String ROLE_LABEL = "lily.io/role";
    static final String ROLE_MAIN = "main";
    static final String ROLE_CANARY = "canary";
    static final String STATE_ANNOTATION = "lily.io/route";

    static final String NGINX = "nginx.ingress.kubernetes.io/";
    static final String CANARY = NGINX + "canary";
    static final String CANARY_WEIGHT = NGINX + "canary-weight";
    static final String READ_TIMEOUT = NGINX + "proxy-read-timeout";
    static final String SEND_TIMEOUT = NGINX + "proxy-send-timeout";
    private static final Set<String> DROPPED_ON_ADOPT = Set.of(
            "kubernetes.io/ingress.class",
            "kubectl.kubernetes.io/last-applied-configuration");

    private IngressSpecs() {
    }

    /** lily-cicd 가 쓰던 이름 그대로. 옮겨 올 때 같은 리소스를 이어받는다 */
    static String mainName(String app) {
        return app + "-ingress";
    }

    /** lily-cicd CanaryAnalysis 가 쓰던 이름 그대로 */
    static String canaryName(String app) {
        return app + "-canary-ingress";
    }

    static Ingress main(String namespace, String app, RouteState state, String ingressClass, String stateJson) {
        // 이어받은 어노테이션이 먼저, lily-router 가 정하는 값이 나중 (같은 키면 lily-router 값)
        Map<String, String> annotations = new LinkedHashMap<>(state.keptAnnotations());
        annotations.put(STATE_ANNOTATION, stateJson);
        if (state.timeoutSeconds() != null) {
            annotations.put(READ_TIMEOUT, String.valueOf(state.timeoutSeconds()));
            annotations.put(SEND_TIMEOUT, String.valueOf(state.timeoutSeconds()));
        }
        return new IngressBuilder()
                .withNewMetadata()
                    .withName(mainName(app))
                    .withNamespace(namespace)
                    .withLabels(labels(app, ROLE_MAIN))
                    .withAnnotations(annotations)
                .endMetadata()
                .withNewSpec()
                    .withIngressClassName(ingressClass)
                    .withRules(rules(state.hosts(), state.paths(), state.serviceName(), state.servicePort()))
                    .withTls(state.tls().stream()
                            .map(rule -> new IngressTLSBuilder()
                                    .withHosts(rule.hosts())
                                    .withSecretName(rule.secretName())
                                    .build())
                            .toList())
                .endSpec()
                .build();
    }

    /**
     * 다른 주체가 만든 Ingress 를 이어받을 때 남길 어노테이션.
     * 컨트롤러 지정(ingressClassName 으로 대신함)과 kubectl 이 붙이는 직전 적용본, lily-router 자기 값은 뺀다.
     */
    static Map<String, String> adoptableAnnotations(Ingress ingress) {
        Map<String, String> kept = new LinkedHashMap<>();
        Map<String, String> source = ingress.getMetadata().getAnnotations();
        if (source == null) {
            return kept;
        }
        source.forEach((key, value) -> {
            if (!DROPPED_ON_ADOPT.contains(key) && !key.startsWith("lily.io/")) {
                kept.put(key, value);
            }
        });
        return kept;
    }

    static List<RouteState.TlsRule> adoptableTls(Ingress ingress) {
        if (ingress.getSpec() == null || ingress.getSpec().getTls() == null) {
            return List.of();
        }
        return ingress.getSpec().getTls().stream()
                .map(tls -> new RouteState.TlsRule(tls.getHosts(), tls.getSecretName()))
                .toList();
    }

    /**
     * ingress-nginx canary 는 같은 호스트의 기본 Ingress 가 있어야 동작한다. 기본 라우트의 호스트·경로를 그대로 쓴다.
     */
    static Ingress canary(String namespace, String app, RouteState main, CanaryRequest request, String ingressClass) {
        Map<String, String> annotations = new LinkedHashMap<>();
        annotations.put(CANARY, "true");
        annotations.put(CANARY_WEIGHT, String.valueOf(request.weight()));
        return new IngressBuilder()
                .withNewMetadata()
                    .withName(canaryName(app))
                    .withNamespace(namespace)
                    .withLabels(labels(app, ROLE_CANARY))
                    .withAnnotations(annotations)
                .endMetadata()
                .withNewSpec()
                    .withIngressClassName(ingressClass)
                    .withRules(rules(main.hosts(), main.paths(), request.serviceName(), port(request.servicePort())))
                .endSpec()
                .build();
    }

    static boolean managed(Ingress ingress) {
        Map<String, String> labels = ingress.getMetadata().getLabels();
        return labels != null && MANAGER.equals(labels.get(MANAGED_BY));
    }

    static boolean isCanary(Ingress ingress) {
        Map<String, String> annotations = ingress.getMetadata().getAnnotations();
        return annotations != null && "true".equals(annotations.get(CANARY));
    }

    static int port(Integer port) {
        return port == null ? 80 : port;
    }

    private static Map<String, String> labels(String app, String role) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(MANAGED_BY, MANAGER);
        labels.put(APP_LABEL, app);
        labels.put(ROLE_LABEL, role);
        return labels;
    }

    private static List<IngressRule> rules(List<String> hosts, List<PathRule> paths, String service, int port) {
        List<HTTPIngressPath> httpPaths = paths.stream()
                .map(path -> new HTTPIngressPathBuilder()
                        .withPath(path.path())
                        .withPathType(path.pathType())
                        .withNewBackend()
                            .withNewService()
                                .withName(service)
                                .withNewPort().withNumber(port).endPort()
                            .endService()
                        .endBackend()
                        .build())
                .toList();
        return hosts.stream()
                .map(host -> new IngressRuleBuilder()
                        .withHost(host)
                        .withNewHttp().withPaths(httpPaths).endHttp()
                        .build())
                .toList();
    }
}
