package com.lily.router.route;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lily.router.config.RouterProperties;
import com.lily.router.route.RouteExceptions.RouteConflictException;
import com.lily.router.route.RouteExceptions.RouteNotFoundException;
import com.lily.router.route.dto.request.CanaryRequest;
import com.lily.router.route.dto.DeploymentInfo;
import com.lily.router.route.dto.PathRule;
import com.lily.router.route.dto.request.RegisterRouteRequest;
import com.lily.router.route.dto.response.Route;
import io.fabric8.kubernetes.api.model.discovery.v1.EndpointSliceBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.api.model.networking.v1.IngressBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.IngressRule;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@EnableKubernetesMockClient(crud = true)
class RouteServiceTest {

    KubernetesClient client;
    RouteService routes;

    @BeforeEach
    void setUp() {
        routes = new RouteService(client, new RouterProperties("apps.lilycloud.kr", "https", "nginx", ""));
    }

    @Test
    void registerCreatesManagedIngressWithDefaultHost() {
        Route route = routes.register("default", "blog", request("blog-svc", null));

        assertThat(route.url()).isEqualTo("https://blog.apps.lilycloud.kr");
        assertThat(route.hosts()).containsExactly("blog.apps.lilycloud.kr");
        Ingress ingress = ingress("default", "blog-ingress");
        assertThat(ingress.getMetadata().getLabels())
                .containsEntry("app.kubernetes.io/managed-by", "lily-router")
                .containsEntry("lily.io/role", "main");
        assertThat(ingress.getSpec().getIngressClassName()).isEqualTo("nginx");
        var backend = ingress.getSpec().getRules().get(0).getHttp().getPaths().get(0).getBackend().getService();
        assertThat(backend.getName()).isEqualTo("blog-svc");
        assertThat(backend.getPort().getNumber()).isEqualTo(80);
    }

    @Test
    void registerAdoptsHostsOfIngressMadeByOthers() {
        // lily-cicd NginxIngressRouter 가 만들고 사람이 nip.io 를 손으로 추가한 상태
        client.network().v1().ingresses().inNamespace("default").resource(unmanaged("default", "blog-ingress",
                "blog.apps.lilycloud.kr", "blog.43.200.152.53.nip.io")).create();

        Route route = routes.register("default", "blog", request("blog-svc", null));

        assertThat(route.hosts()).containsExactly("blog.apps.lilycloud.kr", "blog.43.200.152.53.nip.io");
        assertThat(hosts(ingress("default", "blog-ingress")))
                .containsExactly("blog.apps.lilycloud.kr", "blog.43.200.152.53.nip.io");
    }

    @Test
    void redeployKeepsExtraHosts() {
        routes.register("default", "blog", request("blog-svc", null));
        routes.addHost("default", "blog", "blog.43.200.152.53.nip.io");

        Route route = routes.register("default", "blog",
                new RegisterRouteRequest("blog-svc", 80, null, null, null,
                        new DeploymentInfo("blue-green", "green", "v2", "img:v2", List.of("lily-worker-1"), "ACTIVE")));

        assertThat(route.hosts()).containsExactly("blog.apps.lilycloud.kr", "blog.43.200.152.53.nip.io");
        assertThat(route.deployment().slot()).isEqualTo("green");
    }

    @Test
    void registerWithNewPrimaryHostDropsOldPrimary() {
        routes.register("default", "blog", request("blog-svc", null));

        Route route = routes.register("default", "blog", request("blog-svc", "blog.onprem.lilycloud.kr"));

        assertThat(route.url()).isEqualTo("https://blog.onprem.lilycloud.kr");
        assertThat(route.hosts()).containsExactly("blog.onprem.lilycloud.kr");
    }

    @Test
    void pathsAndTimeoutBecomeRulesAndAnnotations() {
        routes.register("lily-system", "builder", new RegisterRouteRequest("lily-builder", 80,
                "builder.apps.lilycloud.kr",
                List.of(new PathRule("/api/burst", null), new PathRule("/api/agents/connect", "Exact")),
                3600, null));

        Ingress ingress = ingress("lily-system", "builder-ingress");
        assertThat(ingress.getMetadata().getAnnotations())
                .containsEntry("nginx.ingress.kubernetes.io/proxy-read-timeout", "3600")
                .containsEntry("nginx.ingress.kubernetes.io/proxy-send-timeout", "3600");
        var paths = ingress.getSpec().getRules().get(0).getHttp().getPaths();
        assertThat(paths).extracting(p -> p.getPath() + " " + p.getPathType())
                .containsExactly("/api/burst Prefix", "/api/agents/connect Exact");
    }

    @Test
    void hostUsedByAnotherIngressIsConflict() {
        client.network().v1().ingresses().inNamespace("default")
                .resource(unmanaged("default", "other-ingress", "blog.apps.lilycloud.kr")).create();

        assertThatThrownBy(() -> routes.register("default", "blog", request("blog-svc", null)))
                .isInstanceOf(RouteConflictException.class)
                .hasMessageContaining("default/other-ingress");
    }

    @Test
    void canaryFollowsMainHostsAndCanBeClosed() {
        routes.register("default", "blog", request("blog-svc", null));
        routes.addHost("default", "blog", "blog.43.200.152.53.nip.io");

        Route opened = routes.openCanary("default", "blog", new CanaryRequest("blog-canary-svc", null, 10));

        assertThat(opened.canary().weight()).isEqualTo(10);
        assertThat(opened.canary().serviceName()).isEqualTo("blog-canary-svc");
        Ingress canary = ingress("default", "blog-canary-ingress");
        assertThat(canary.getMetadata().getAnnotations())
                .containsEntry("nginx.ingress.kubernetes.io/canary", "true")
                .containsEntry("nginx.ingress.kubernetes.io/canary-weight", "10");
        assertThat(hosts(canary)).containsExactly("blog.apps.lilycloud.kr", "blog.43.200.152.53.nip.io");

        Route closed = routes.closeCanary("default", "blog");

        assertThat(closed.canary()).isNull();
        assertThat(client.network().v1().ingresses().inNamespace("default").withName("blog-canary-ingress").get())
                .isNull();
    }

    @Test
    void backendsShowPodNodes() {
        client.discovery().v1().endpointSlices().inNamespace("default").resource(new EndpointSliceBuilder()
                .withNewMetadata()
                    .withName("blog-svc-abc")
                    .withNamespace("default")
                    .addToLabels("kubernetes.io/service-name", "blog-svc")
                .endMetadata()
                .withAddressType("IPv4")
                .addNewEndpoint()
                    .withAddresses("10.42.1.7")
                    .withNodeName("lily-worker-1")
                    .withNewConditions().withReady(true).endConditions()
                    .withNewTargetRef().withKind("Pod").withName("blog-blue-7d9").endTargetRef()
                .endEndpoint()
                .build()).create();

        Route route = routes.register("default", "blog", request("blog-svc", null));

        assertThat(route.backends()).singleElement().satisfies(backend -> {
            assertThat(backend.pod()).isEqualTo("blog-blue-7d9");
            assertThat(backend.node()).isEqualTo("lily-worker-1");
            assertThat(backend.ready()).isTrue();
        });
    }

    @Test
    void listAndFindByHost() {
        routes.register("default", "blog", request("blog-svc", null));
        routes.register("default", "blog2", request("blog2-svc", null));
        client.network().v1().ingresses().inNamespace("default")
                .resource(unmanaged("default", "manual-ingress", "manual.lilycloud.kr")).create();

        assertThat(routes.list(null)).extracting(Route::app).containsExactly("blog", "blog2");
        assertThat(routes.findByHost("blog2.apps.lilycloud.kr").serviceName()).isEqualTo("blog2-svc");
        assertThatThrownBy(() -> routes.findByHost("manual.lilycloud.kr"))
                .isInstanceOf(RouteNotFoundException.class);
    }

    @Test
    void updateDeploymentKeepsRouting() {
        routes.register("default", "blog", request("blog-svc", null));

        Route route = routes.updateDeployment("default", "blog",
                new DeploymentInfo("blue-green", "blue", "v3", "img:v3", null, "DEPLOYING"));

        assertThat(route.deployment().status()).isEqualTo("DEPLOYING");
        assertThat(route.hosts()).containsExactly("blog.apps.lilycloud.kr");
    }

    @Test
    void removeHostRejectsPrimaryAndUnknown() {
        routes.register("default", "blog", request("blog-svc", null));
        routes.addHost("default", "blog", "blog.43.200.152.53.nip.io");

        assertThatThrownBy(() -> routes.removeHost("default", "blog", "blog.apps.lilycloud.kr"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> routes.removeHost("default", "blog", "nope.lilycloud.kr"))
                .isInstanceOf(RouteNotFoundException.class);
        assertThat(routes.removeHost("default", "blog", "blog.43.200.152.53.nip.io").hosts())
                .containsExactly("blog.apps.lilycloud.kr");
    }

    @Test
    void deleteRemovesRoute() {
        routes.register("default", "blog", request("blog-svc", null));
        routes.openCanary("default", "blog", new CanaryRequest("blog-canary-svc", 80, 10));

        routes.delete("default", "blog");

        assertThatThrownBy(() -> routes.get("default", "blog")).isInstanceOf(RouteNotFoundException.class);
        assertThat(client.network().v1().ingresses().inNamespace("default").list().getItems()).isEmpty();
    }

    @Test
    void unmanagedIngressIsNotARoute() {
        client.network().v1().ingresses().inNamespace("default")
                .resource(unmanaged("default", "blog-ingress", "blog.apps.lilycloud.kr")).create();

        assertThatThrownBy(() -> routes.get("default", "blog")).isInstanceOf(RouteNotFoundException.class);
    }

    @Test
    void rejectsInvalidNames() {
        assertThatThrownBy(() -> routes.get("default", "Blog_1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> routes.get("Default", "blog")).isInstanceOf(IllegalArgumentException.class);
    }

    private static RegisterRouteRequest request(String service, String host) {
        return new RegisterRouteRequest(service, null, host, null, null, null);
    }

    private Ingress ingress(String namespace, String name) {
        return client.network().v1().ingresses().inNamespace(namespace).withName(name).get();
    }

    private static List<String> hosts(Ingress ingress) {
        return ingress.getSpec().getRules().stream().map(IngressRule::getHost).toList();
    }

    private static Ingress unmanaged(String namespace, String name, String... hosts) {
        IngressBuilder builder = new IngressBuilder()
                .withNewMetadata().withName(name).withNamespace(namespace).endMetadata()
                .withNewSpec().withIngressClassName("nginx").endSpec();
        for (String host : hosts) {
            builder.editSpec().addNewRule()
                    .withHost(host)
                    .withNewHttp().addNewPath()
                        .withPath("/").withPathType("Prefix")
                        .withNewBackend().withNewService().withName("x-svc")
                            .withNewPort().withNumber(80).endPort().endService().endBackend()
                    .endPath().endHttp()
                    .endRule().endSpec();
        }
        return builder.build();
    }
}
