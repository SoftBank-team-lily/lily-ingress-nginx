package com.lily.ingress.route;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.lily.ingress.config.IngressProperties;
import com.lily.ingress.route.RouteExceptions.RouteConflictException;
import com.lily.ingress.route.RouteExceptions.RouteNotFoundException;
import com.lily.ingress.route.dto.response.Backend;
import com.lily.ingress.route.dto.request.CanaryRequest;
import com.lily.ingress.route.dto.response.CanaryView;
import com.lily.ingress.route.dto.DeploymentInfo;
import com.lily.ingress.route.dto.PathRule;
import com.lily.ingress.route.dto.request.RegisterRouteRequest;
import com.lily.ingress.route.dto.response.Route;
import io.fabric8.kubernetes.api.model.discovery.v1.Endpoint;
import io.fabric8.kubernetes.api.model.discovery.v1.EndpointSlice;
import io.fabric8.kubernetes.api.model.networking.v1.HTTPIngressPath;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.api.model.networking.v1.IngressRule;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.NonDeletingOperation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 앱별 라우트를 Ingress 로 반영하고 조회한다. 클러스터에서 Ingress 를 쓰는 곳은 여기 하나다.
 *
 * <p>원본은 기본 Ingress 의 {@code lily.io/route} 어노테이션이다. 매번 원본 전체로 Ingress 를 다시 만들므로
 * 배포 모듈이 기본 주소를 바꿔도 추가 주소({@code extraHosts})는 남는다.
 */
@Service
public class RouteService {

    private static final Logger log = LoggerFactory.getLogger(RouteService.class);
    private static final String APP_PATTERN = "[a-z0-9]([-a-z0-9]*[a-z0-9])?";

    private final KubernetesClient k8s;
    private final IngressProperties properties;
    private final ObjectMapper json = JsonMapper.builder()
            .findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    /** 같은 앱의 읽고-고치고-쓰기를 한 번에 하나만. 레플리카 1 기준 */
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public RouteService(KubernetesClient k8s, IngressProperties properties) {
        this.k8s = k8s;
        this.properties = properties;
    }

    /**
     * 배포가 끝난 앱의 라우트를 등록하거나 갱신한다.
     * 처음 등록할 때 이 서비스가 만들지 않은 {@code {app}-ingress} 가 있으면 그 호스트를 추가 주소로 이어받는다.
     */
    public Route register(String namespace, String app, RegisterRouteRequest request) {
        validate(namespace, app);
        return locked(namespace, app, () -> {
            Ingress existing = mainIngress(namespace, app);
            String primary = request.host() == null || request.host().isBlank()
                    ? app + "." + properties.domain()
                    : request.host();
            List<String> extra = new ArrayList<>();
            if (existing != null && IngressSpecs.managed(existing)) {
                extra.addAll(readState(existing).extraHosts());
            } else if (existing != null) {
                extra.addAll(hostsOf(existing));
                log.info("adopting unmanaged ingress. namespace={} name={} hosts={}",
                        namespace, existing.getMetadata().getName(), extra);
            }
            extra.remove(primary);
            List<PathRule> paths = request.paths() == null || request.paths().isEmpty()
                    ? List.of(PathRule.ROOT) : request.paths();
            RouteState state = new RouteState(
                    request.serviceName(),
                    IngressSpecs.port(request.servicePort()),
                    primary,
                    extra,
                    paths,
                    request.timeoutSeconds(),
                    request.deployment(),
                    Instant.now());
            apply(namespace, app, state);
            return view(namespace, app, state);
        });
    }

    public Route get(String namespace, String app) {
        validate(namespace, app);
        return view(namespace, app, requireState(namespace, app));
    }

    /** @param namespace null 이면 전체 */
    public List<Route> list(String namespace) {
        var ingresses = namespace == null || namespace.isBlank()
                ? k8s.network().v1().ingresses().inAnyNamespace()
                        .withLabel(IngressSpecs.ROLE_LABEL, IngressSpecs.ROLE_MAIN).list()
                : k8s.network().v1().ingresses().inNamespace(namespace)
                        .withLabel(IngressSpecs.ROLE_LABEL, IngressSpecs.ROLE_MAIN).list();
        return ingresses.getItems().stream()
                .filter(IngressSpecs::managed)
                .map(ingress -> view(ingress.getMetadata().getNamespace(), appOf(ingress), readState(ingress)))
                .sorted(Comparator.comparing(Route::namespace).thenComparing(Route::app))
                .toList();
    }

    /** 주소로 라우트를 찾는다. 다른 모듈이 "이 주소는 어느 앱인가" 를 물을 때 쓴다 */
    public Route findByHost(String host) {
        return list(null).stream()
                .filter(route -> route.hosts().contains(host))
                .findFirst()
                .orElseThrow(() -> new RouteNotFoundException("no route for host " + host));
    }

    public void delete(String namespace, String app) {
        validate(namespace, app);
        locked(namespace, app, () -> {
            requireState(namespace, app);
            k8s.network().v1().ingresses().inNamespace(namespace).withName(IngressSpecs.canaryName(app)).delete();
            k8s.network().v1().ingresses().inNamespace(namespace).withName(IngressSpecs.mainName(app)).delete();
            return null;
        });
    }

    /** 배포 정보만 바꾼다. 라우팅 규칙은 그대로다 */
    public Route updateDeployment(String namespace, String app, DeploymentInfo info) {
        validate(namespace, app);
        return locked(namespace, app, () -> {
            RouteState state = requireState(namespace, app).withDeployment(info);
            apply(namespace, app, state);
            return view(namespace, app, state);
        });
    }

    public Route addHost(String namespace, String app, String host) {
        validate(namespace, app);
        return locked(namespace, app, () -> {
            RouteState state = requireState(namespace, app);
            List<String> hosts = new ArrayList<>(state.extraHosts());
            hosts.add(host);
            RouteState next = state.withExtraHosts(hosts);
            apply(namespace, app, next);
            return view(namespace, app, next);
        });
    }

    public Route removeHost(String namespace, String app, String host) {
        validate(namespace, app);
        return locked(namespace, app, () -> {
            RouteState state = requireState(namespace, app);
            if (host.equals(state.primaryHost())) {
                throw new IllegalArgumentException("기본 주소는 지울 수 없다. 배포 모듈이 다시 등록할 때 바뀐다: " + host);
            }
            if (!state.extraHosts().contains(host)) {
                throw new RouteNotFoundException(app + " 에 추가 주소 " + host + " 가 없다");
            }
            List<String> hosts = new ArrayList<>(state.extraHosts());
            hosts.remove(host);
            RouteState next = state.withExtraHosts(hosts);
            apply(namespace, app, next);
            return view(namespace, app, next);
        });
    }

    /** 기본 라우트와 같은 주소로 일부 트래픽을 다른 Service 에 보낸다 */
    public Route openCanary(String namespace, String app, CanaryRequest request) {
        validate(namespace, app);
        return locked(namespace, app, () -> {
            RouteState state = requireState(namespace, app);
            Ingress canary = IngressSpecs.canary(namespace, app, state, request, properties.ingressClass());
            write(canary);
            return view(namespace, app, state);
        });
    }

    public Route closeCanary(String namespace, String app) {
        validate(namespace, app);
        return locked(namespace, app, () -> {
            RouteState state = requireState(namespace, app);
            k8s.network().v1().ingresses().inNamespace(namespace).withName(IngressSpecs.canaryName(app)).delete();
            return view(namespace, app, state);
        });
    }

    private void apply(String namespace, String app, RouteState state) {
        checkConflicts(namespace, app, state);
        write(IngressSpecs.main(namespace, app, state, properties.ingressClass(), toJson(state)));
        Ingress canary = k8s.network().v1().ingresses().inNamespace(namespace)
                .withName(IngressSpecs.canaryName(app)).get();
        if (canary != null && IngressSpecs.managed(canary)) {
            // 열려 있는 canary 도 새 호스트·경로를 따라가야 같은 주소로 들어온 요청을 나눌 수 있다
            CanaryView current = canaryOf(canary);
            write(IngressSpecs.canary(namespace, app, state,
                    new CanaryRequest(current.serviceName(), current.servicePort(), current.weight()),
                    properties.ingressClass()));
        }
    }

    private void write(Ingress ingress) {
        k8s.network().v1().ingresses()
                .inNamespace(ingress.getMetadata().getNamespace())
                .resource(ingress)
                .createOr(NonDeletingOperation::update);
    }

    /**
     * ingress-nginx admission webhook 과 같은 규칙: 같은 호스트·같은 경로를 두 Ingress 가 가질 수 없다.
     * webhook 이 없는 컨트롤러로 바꿔도 같은 오류를 먼저 돌려준다. canary Ingress 는 예외다.
     */
    private void checkConflicts(String namespace, String app, RouteState state) {
        for (Ingress other : k8s.network().v1().ingresses().inAnyNamespace().list().getItems()) {
            boolean self = namespace.equals(other.getMetadata().getNamespace())
                    && (IngressSpecs.mainName(app).equals(other.getMetadata().getName())
                        || IngressSpecs.canaryName(app).equals(other.getMetadata().getName()));
            if (self || IngressSpecs.isCanary(other) || other.getSpec() == null || other.getSpec().getRules() == null) {
                continue;
            }
            for (IngressRule rule : other.getSpec().getRules()) {
                if (rule.getHost() == null || !state.hosts().contains(rule.getHost()) || rule.getHttp() == null) {
                    continue;
                }
                for (HTTPIngressPath path : rule.getHttp().getPaths()) {
                    boolean samePath = state.paths().stream().anyMatch(p -> p.path().equals(path.getPath()));
                    if (samePath) {
                        throw new RouteConflictException("host " + rule.getHost() + " path " + path.getPath()
                                + " 는 이미 " + other.getMetadata().getNamespace() + "/"
                                + other.getMetadata().getName() + " 가 쓰고 있다");
                    }
                }
            }
        }
    }

    private Route view(String namespace, String app, RouteState state) {
        Ingress canary = k8s.network().v1().ingresses().inNamespace(namespace)
                .withName(IngressSpecs.canaryName(app)).get();
        return new Route(
                namespace,
                app,
                properties.urlScheme() + "://" + state.primaryHost(),
                state.primaryHost(),
                state.hosts(),
                state.serviceName(),
                state.servicePort(),
                state.paths(),
                state.timeoutSeconds(),
                canary == null ? null : canaryOf(canary),
                state.deployment(),
                backends(namespace, state.serviceName()),
                state.updatedAt());
    }

    private List<Backend> backends(String namespace, String serviceName) {
        List<Backend> backends = new ArrayList<>();
        for (EndpointSlice slice : k8s.discovery().v1().endpointSlices().inNamespace(namespace)
                .withLabel("kubernetes.io/service-name", serviceName).list().getItems()) {
            if (slice.getEndpoints() == null) {
                continue;
            }
            for (Endpoint endpoint : slice.getEndpoints()) {
                String address = endpoint.getAddresses() == null || endpoint.getAddresses().isEmpty()
                        ? null : endpoint.getAddresses().get(0);
                String pod = endpoint.getTargetRef() == null ? null : endpoint.getTargetRef().getName();
                boolean ready = endpoint.getConditions() == null
                        || !Boolean.FALSE.equals(endpoint.getConditions().getReady());
                backends.add(new Backend(pod, address, endpoint.getNodeName(), ready));
            }
        }
        backends.sort(Comparator.comparing(Backend::pod, Comparator.nullsLast(Comparator.naturalOrder())));
        return backends;
    }

    private CanaryView canaryOf(Ingress canary) {
        var backend = canary.getSpec().getRules().get(0).getHttp().getPaths().get(0).getBackend().getService();
        String weight = canary.getMetadata().getAnnotations().getOrDefault(IngressSpecs.CANARY_WEIGHT, "0");
        return new CanaryView(backend.getName(), backend.getPort().getNumber(), Integer.parseInt(weight));
    }

    private Ingress mainIngress(String namespace, String app) {
        return k8s.network().v1().ingresses().inNamespace(namespace).withName(IngressSpecs.mainName(app)).get();
    }

    private RouteState requireState(String namespace, String app) {
        Ingress ingress = mainIngress(namespace, app);
        if (ingress == null || !IngressSpecs.managed(ingress)) {
            throw new RouteNotFoundException(namespace + "/" + app + " 라우트가 없다");
        }
        return readState(ingress);
    }

    private RouteState readState(Ingress ingress) {
        String raw = Optional.ofNullable(ingress.getMetadata().getAnnotations())
                .map(a -> a.get(IngressSpecs.STATE_ANNOTATION))
                .orElseThrow(() -> new IllegalStateException(
                        ingress.getMetadata().getName() + " 에 " + IngressSpecs.STATE_ANNOTATION + " 가 없다"));
        try {
            return json.readValue(raw, RouteState.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(ingress.getMetadata().getName() + " 의 라우트 원본을 읽지 못했다", e);
        }
    }

    private String toJson(RouteState state) {
        try {
            return json.writeValueAsString(state);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("라우트 원본을 쓰지 못했다", e);
        }
    }

    private static List<String> hostsOf(Ingress ingress) {
        if (ingress.getSpec() == null || ingress.getSpec().getRules() == null) {
            return List.of();
        }
        return ingress.getSpec().getRules().stream()
                .map(IngressRule::getHost)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private static String appOf(Ingress ingress) {
        return ingress.getMetadata().getLabels().get(IngressSpecs.APP_LABEL);
    }

    private <T> T locked(String namespace, String app, Supplier<T> action) {
        synchronized (locks.computeIfAbsent(namespace + "/" + app, key -> new Object())) {
            return action.get();
        }
    }

    private static void validate(String namespace, String app) {
        if (namespace == null || !namespace.matches(APP_PATTERN) || namespace.length() > 63) {
            throw new IllegalArgumentException("namespace 형식이 아니다: " + namespace);
        }
        if (app == null || !app.matches(APP_PATTERN) || app.length() > 48) {
            throw new IllegalArgumentException(
                    "app 은 소문자, 숫자, 하이픈만, 48자 이하여야 한다 ('-canary-ingress' 를 붙여도 63자 이내): " + app);
        }
    }
}
