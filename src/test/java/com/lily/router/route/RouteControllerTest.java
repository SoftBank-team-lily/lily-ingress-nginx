package com.lily.router.route;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lily.router.config.ApiTokenFilter;
import com.lily.router.config.RouterProperties;
import com.lily.router.route.RouteExceptions.RouteConflictException;
import com.lily.router.route.RouteExceptions.RouteNotFoundException;
import com.lily.router.route.dto.PathRule;
import com.lily.router.route.dto.response.Backend;
import com.lily.router.route.dto.response.Route;
import io.fabric8.kubernetes.client.KubernetesClientException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {RouteController.class, HealthController.class})
@Import({ApiTokenFilter.class, ApiExceptionHandler.class})
@EnableConfigurationProperties(RouterProperties.class)
@TestPropertySource(properties = "lily.router.api-token=secret")
class RouteControllerTest {

    private static final String AUTH = "Bearer secret";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    RouteService routes;

    @Test
    void registerReturnsRoute() throws Exception {
        when(routes.register(eq("default"), eq("blog"), any())).thenReturn(route());

        mvc.perform(put("/api/v1/routes/default/blog").header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceName\":\"blog-svc\",\"servicePort\":80}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://blog.apps.lilycloud.kr"))
                .andExpect(jsonPath("$.backends[0].node").value("lily-worker-1"));
    }

    @Test
    void missingServiceNameIs400() throws Exception {
        mvc.perform(put("/api/v1/routes/default/blog").header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"servicePort\":80}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void badPathTypeIs400() throws Exception {
        mvc.perform(put("/api/v1/routes/default/blog").header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceName\":\"blog-svc\",\"paths\":[{\"path\":\"/\",\"pathType\":\"Regex\"}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void notFoundAndConflictMapToCodes() throws Exception {
        when(routes.get("default", "nope")).thenThrow(new RouteNotFoundException("no"));
        when(routes.register(eq("default"), eq("dup"), any())).thenThrow(new RouteConflictException("dup"));

        mvc.perform(get("/api/v1/routes/default/nope").header("Authorization", AUTH))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROUTE_NOT_FOUND"));
        mvc.perform(put("/api/v1/routes/default/dup").header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceName\":\"dup-svc\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOST_CONFLICT"));
    }

    @Test
    void resolveHostWithDots() throws Exception {
        when(routes.findByHost("blog.apps.lilycloud.kr")).thenReturn(route());

        mvc.perform(get("/api/v1/hosts/blog.apps.lilycloud.kr").header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.app").value("blog"));
    }

    @Test
    void apiNeedsToken() throws Exception {
        mvc.perform(get("/api/v1/routes")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/routes").header("Authorization", "Bearer wrong"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void healthzIsOpen() throws Exception {
        mvc.perform(get("/healthz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void deleteIs204WithoutBody() throws Exception {
        mvc.perform(delete("/api/v1/routes/default/blog").header("Authorization", AUTH))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        verify(routes).delete("default", "blog");
    }

    @Test
    void listPassesNamespaceFilter() throws Exception {
        when(routes.list("default")).thenReturn(List.of(route()));

        mvc.perform(get("/api/v1/routes").param("namespace", "default").header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].app").value("blog"));
    }

    @Test
    void hostEndpoints() throws Exception {
        when(routes.addHost("default", "blog", "blog.43.200.152.53.nip.io")).thenReturn(route());
        when(routes.removeHost("default", "blog", "blog.apps.lilycloud.kr"))
                .thenThrow(new IllegalArgumentException("기본 주소는 지울 수 없다"));

        mvc.perform(post("/api/v1/routes/default/blog/hosts").header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"host\":\"blog.43.200.152.53.nip.io\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/routes/default/blog/hosts").header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"host\":\"Not A Host\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/api/v1/routes/default/blog/hosts/blog.apps.lilycloud.kr").header("Authorization", AUTH))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void canaryEndpoints() throws Exception {
        when(routes.openCanary(eq("default"), eq("blog"), any())).thenReturn(route());
        when(routes.closeCanary("default", "blog")).thenReturn(route());

        mvc.perform(put("/api/v1/routes/default/blog/canary").header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceName\":\"blog-canary-svc\",\"weight\":10}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/v1/routes/default/blog/canary").header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceName\":\"blog-canary-svc\",\"weight\":101}"))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/api/v1/routes/default/blog/canary").header("Authorization", AUTH))
                .andExpect(status().isOk());
    }

    @Test
    void deploymentEndpoint() throws Exception {
        when(routes.updateDeployment(eq("default"), eq("blog"), any())).thenReturn(route());

        mvc.perform(put("/api/v1/routes/default/blog/deployment").header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slot\":\"blue\",\"status\":\"DEPLOYING\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void malformedJsonIs400() throws Exception {
        mvc.perform(put("/api/v1/routes/default/blog").header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void kubernetesFailureIs502() throws Exception {
        when(routes.list(null)).thenThrow(new KubernetesClientException("connection refused"));

        mvc.perform(get("/api/v1/routes").header("Authorization", AUTH))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("KUBERNETES_ERROR"));
    }

    private static Route route() {
        return new Route("default", "blog", "https://blog.apps.lilycloud.kr", "blog.apps.lilycloud.kr",
                List.of("blog.apps.lilycloud.kr"), "blog-svc", 80, List.of(PathRule.ROOT), null, null,
                null, List.of(new Backend("blog-blue-7d9", "10.42.1.7", "lily-worker-1", true)),
                Instant.parse("2026-10-01T07:00:00Z"));
    }
}
