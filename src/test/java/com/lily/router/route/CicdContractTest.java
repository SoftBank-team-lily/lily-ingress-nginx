package com.lily.router.route;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lily.router.config.RouterProperties;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.api.model.networking.v1.IngressBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.IngressRule;
import io.fabric8.kubernetes.api.model.networking.v1.IngressRuleBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * lily-cicd {@code HttpTrafficRouter} 가 보내는 요청을 그대로 재현한다 (lily-cicd 브랜치 기능/router-연동).
 * 요청 본문·경로·cicd 가 기대하는 응답 코드(특히 404 의 뜻)가 바뀌면 이 테스트가 깨진다.
 *
 * <table>
 *   <tr><th>cicd 메서드</th><th>요청</th><th>cicd 가 기대하는 것</th></tr>
 *   <tr><td>route</td><td>PUT /routes/{ns}/{app}</td><td>2xx</td></tr>
 *   <tr><td>openCanary</td><td>PUT .../canary</td><td>2xx, 라우트 없으면 404 → route 후 재시도</td></tr>
 *   <tr><td>closeCanary</td><td>DELETE .../canary</td><td>2xx, 404 는 성공으로 봄</td></tr>
 *   <tr><td>routes</td><td>GET /routes/{ns}/{app}</td><td>hosts 에 host, 404 면 false</td></tr>
 *   <tr><td>openCanaries</td><td>GET /routes?canary=true</td><td>[{namespace, app}]</td></tr>
 *   <tr><td>remove</td><td>DELETE /routes/{ns}/{app}</td><td>2xx, 404 면 지운 것 없음</td></tr>
 * </table>
 */
@EnableKubernetesMockClient(crud = true)
class CicdContractTest {

    KubernetesClient client;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        RouteService service = new RouteService(client, new RouterProperties("lilycloud.kr", "https", "nginx", ""));
        mvc = MockMvcBuilders.standaloneSetup(new RouteController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    /** HttpTrafficRouter.route 가 만드는 본문 */
    private static String routeBody(String slot) {
        return """
                {"serviceName":"blog-svc","servicePort":80,"host":"blog.lilycloud.kr",
                 "deployment":{"slot":%s,"version":"20261002-1","image":"ecr/blog:1","status":"ACTIVE"}}
                """.formatted(slot == null ? "null" : "\"" + slot + "\"");
    }

    /** HttpTrafficRouter.openCanary 가 만드는 본문 */
    private static String canaryBody(int weight) {
        return """
                {"serviceName":"blog-canary-svc","servicePort":80,"weight":%d}
                """.formatted(weight);
    }

    @Test
    void blueGreenFirstDeployThenSwitch() throws Exception {
        // CanaryAnalysis.skipReason → routes(): 첫 배포는 라우트가 없다 → 판정 건너뜀
        mvc.perform(get("/api/v1/routes/default/blog")).andExpect(status().isNotFound());

        // DeploymentEngine.route → route()
        call(put("/api/v1/routes/default/blog"), routeBody("blue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://blog.lilycloud.kr"))
                .andExpect(jsonPath("$.deployment.slot").value("blue"));

        // 다음 배포 판정 전: hosts 에 이번 host 가 있어야 판정을 한다
        mvc.perform(get("/api/v1/routes/default/blog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hosts[0]").value("blog.lilycloud.kr"));

        // 전환 뒤 같은 요청을 slot 만 바꿔 다시 (멱등)
        call(put("/api/v1/routes/default/blog"), routeBody("green"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deployment.slot").value("green"));
        assertThat(client.network().v1().ingresses().inNamespace("default").list().getItems()).hasSize(1);
    }

    @Test
    void takesOverIngressMadeByNginxIngressRouter() throws Exception {
        // 연동 전 cicd(NginxIngressRouter) 가 만들고 loadbalancer 쪽에서 nip.io 를 더한 상태
        client.network().v1().ingresses().inNamespace("default").resource(new IngressBuilder()
                .withNewMetadata().withName("blog-ingress").withNamespace("default")
                    .withAnnotations(Map.of("kubernetes.io/ingress.class", "nginx")).endMetadata()
                .withNewSpec().withIngressClassName("nginx")
                    .withRules(rule("blog.43.200.152.53.nip.io"), rule("blog.apps.lilycloud.kr"),
                            rule("blog.lilycloud.kr"))
                .endSpec().build()).create();

        call(put("/api/v1/routes/default/blog"), routeBody("blue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hosts.length()").value(3));

        Ingress ingress = client.network().v1().ingresses().inNamespace("default").withName("blog-ingress").get();
        assertThat(ingress.getSpec().getRules()).extracting(IngressRule::getHost)
                .containsExactly("blog.lilycloud.kr", "blog.43.200.152.53.nip.io", "blog.apps.lilycloud.kr");
        assertThat(ingress.getMetadata().getLabels()).containsEntry("app.kubernetes.io/managed-by", "lily-router");
    }

    @Test
    void canaryStrategyRampsWeightAndCloses() throws Exception {
        // 라우트가 없으면 404 ROUTE_NOT_FOUND → cicd 가 route() 로 먼저 등록한다
        call(put("/api/v1/routes/default/blog/canary"), canaryBody(0))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROUTE_NOT_FOUND"));
        call(put("/api/v1/routes/default/blog"), routeBody(null)).andExpect(status().isOk());

        // CanaryDeploymentStrategy.raise: 0 → step(20) → … → 100
        for (int weight : List.of(0, 20, 40, 60, 80, 100)) {
            call(put("/api/v1/routes/default/blog/canary"), canaryBody(weight))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.canary.weight").value(weight));
        }

        // cicd 재시작 정리: DeployRecovery → openCanaries()
        mvc.perform(get("/api/v1/routes").param("canary", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].namespace").value("default"))
                .andExpect(jsonPath("$[0].app").value("blog"));

        // 승격 뒤 closeCanary
        mvc.perform(delete("/api/v1/routes/default/blog/canary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canary").doesNotExist());
        mvc.perform(get("/api/v1/routes").param("canary", "true"))
                .andExpect(jsonPath("$.length()").value(0));
        // 이미 닫힌 뒤 다시 닫아도 성공 (CanaryAnalysis.cleanup 이 판정마다 부른다)
        mvc.perform(delete("/api/v1/routes/default/blog/canary")).andExpect(status().isOk());
    }

    @Test
    void closeCanaryWithoutRouteIs404() throws Exception {
        // cicd 는 이 404 를 "닫을 것 없음" 으로 본다
        mvc.perform(delete("/api/v1/routes/default/blog/canary"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROUTE_NOT_FOUND"));
    }

    @Test
    void appRemoverDeletesRouteOnce() throws Exception {
        call(put("/api/v1/routes/default/blog"), routeBody("blue")).andExpect(status().isOk());
        call(put("/api/v1/routes/default/blog/canary"), canaryBody(10)).andExpect(status().isOk());

        mvc.perform(delete("/api/v1/routes/default/blog")).andExpect(status().isNoContent());
        assertThat(client.network().v1().ingresses().inNamespace("default").list().getItems()).isEmpty();

        // 두 번째 삭제는 404 → cicd 는 "지운 것 없음" 으로 기록
        mvc.perform(delete("/api/v1/routes/default/blog")).andExpect(status().isNotFound());
    }

    private ResultActions call(MockHttpServletRequestBuilder request,
                               String body) throws Exception {
        return mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static IngressRule rule(String host) {
        return new IngressRuleBuilder()
                .withHost(host)
                .withNewHttp().addNewPath()
                    .withPath("/").withPathType("Prefix")
                    .withNewBackend().withNewService().withName("blog-svc")
                        .withNewPort().withNumber(80).endPort().endService().endBackend()
                .endPath().endHttp()
                .build();
    }
}
