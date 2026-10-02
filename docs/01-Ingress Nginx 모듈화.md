# 다른 모듈 연동 가이드

Nginx Ingress를 선언하는 부분이 CICD/LoadBalancer 2부분이라서 Ingress 모듈을 따로 빼서 개발했습니다.

CICD는 배포만을 진행하고 Ingress는 배포되고 있는 서비스명과 접근 경로를 관리합니다.

코드를 살펴본 결과 CICD에서 k8s를 이용해서 배포되어야 할 EC2 인스턴스를 정하고 배포를 진행합니다.

클러스터의 Ingress 를 쓰는 곳은 lily-router 하나가 되고, 다른 모듈은 [프로토콜](<api/02-Ingress Nginx API Details.md>)로 요청합니다.

## 지금 Ingress 를 다루는 곳과 바뀌는 점

| 위치 | 지금 | 연동 후 |
|---|---|---|
| lily-cicd `NginxIngressRouter` | `{app}-ingress` 를 배포마다 통째로 교체 | `HttpTrafficRouter` 가 `PUT /routes` 호출 |
| lily-cicd `CanaryAnalysis` | `{app}-canary-ingress` 직접 생성·삭제 | `PUT/DELETE /routes/.../canary` 호출 (2단계) |
| lily-cicd `CanaryAnalysis.skipReason` | `{app}-ingress` 를 직접 읽음 | `GET /routes/{ns}/{app}` (2단계) |
| lily-builder `ClusterApps` | `{app}-ingress` 첫 호스트에 `http://` 를 붙여 URL 로 사용 | `GET /routes/{ns}/{app}` 의 `url` |
| lily-loadbalancer `manifests/` | 사람이 `kubectl apply` | 앱 Ingress 는 `POST /hosts` 로. 플랫폼 Ingress 는 `PUT /routes` 로 등록 |
| lily-builder `deploy/k3s/lily-builder-burst.yaml` | loadbalancer 와 같은 이름의 옛 버전 | 삭제 |

## lily-router API

코드 기준: [RouteController](../src/main/java/com/lily/router/route/RouteController.java), 요청·응답 타입 [RouteModels](../src/main/java/com/lily/router/route/dto/), 오류 [ApiExceptionHandler](../src/main/java/com/lily/router/route/ApiExceptionHandler.java).
필드별 자세한 규칙과 예시는 [protocol.md](<api/02-Ingress Nginx API Details.md>) 에 있습니다.

- 주소: `http://lily-router.lily-system.svc` (클러스터 안)
- `/api/` 아래는 모두 `Authorization: Bearer {LILY_ROUTER_API_TOKEN}` 필요. 없거나 다르면 `401 UNAUTHORIZED`
- 요청·응답 본문은 JSON

### 경로와 반환값

| 메서드 | 경로 | 요청 본문 | 성공 응답 | 실패 응답 | 쓰는 곳 |
|---|---|---|---|---|---|
| `PUT` | `/api/v1/routes/{namespace}/{app}` | `RegisterRouteRequest` | `200` `Route` | `400`, `409`, `502` | cicd 배포 완료 후 (1단계) |
| `GET` | `/api/v1/routes` (`?namespace=` 선택) | - | `200` `Route[]` (namespace, app 순 정렬) | `502` | 전체 조회 |
| `GET` | `/api/v1/routes/{namespace}/{app}` | - | `200` `Route` | `400`, `404` | builder URL 조회, cicd canary 판정 전 확인 (2단계) |
| `DELETE` | `/api/v1/routes/{namespace}/{app}` | - | `204` (본문 없음). canary 도 같이 삭제 | `400`, `404` | 앱 삭제 |
| `PUT` | `/api/v1/routes/{namespace}/{app}/deployment` | `DeploymentInfo` | `200` `Route` (라우팅은 그대로) | `400`, `404` | cicd 배포 상태 갱신 |
| `POST` | `/api/v1/routes/{namespace}/{app}/hosts` | `HostRequest` | `200` `Route` (이미 있는 주소면 그대로) | `400`, `404`, `409` | nip.io·커스텀 도메인 추가 |
| `DELETE` | `/api/v1/routes/{namespace}/{app}/hosts/{host}` | - | `200` `Route` | `400` (기본 주소), `404` (없는 주소) | 추가 주소 삭제 |
| `PUT` | `/api/v1/routes/{namespace}/{app}/canary` | `CanaryRequest` | `200` `Route` (`canary` 채워짐) | `400`, `404` (라우트 없음) | cicd canary 판정 시작 (2단계) |
| `DELETE` | `/api/v1/routes/{namespace}/{app}/canary` | - | `200` `Route` (`canary` 는 null). 안 열려 있어도 200 | `404` (라우트 없음) | cicd canary 판정 끝 (2단계) |
| `GET` | `/api/v1/hosts/{host}` | - | `200` `Route` | `404` | 주소로 앱 찾기 |
| `GET` | `/healthz` | - | `200` `{"status":"ok"}` (토큰 불필요) | - | k8s probe |

- `{app}` 은 소문자·숫자·하이픈, 48자 이하. `{namespace}` 는 DNS 라벨 형식. 어기면 `400`
- lily-router 가 만들지 않은 Ingress(`managed-by` 레이블 없음)는 조회되지 않습니다 (`404`). 처음 `PUT /routes` 를 할 때 이어받습니다

### 요청 본문

| 타입 | 필드 (굵게 = 필수) |
|---|---|
| `RegisterRouteRequest` | **`serviceName`**, `servicePort` (1~65535, 기본 80), `host` (기본 `{app}.apps.lilycloud.kr`), `paths` (`PathRule[]`, 기본 `/` Prefix), `timeoutSeconds` (1~86400), `deployment` (`DeploymentInfo`) |
| `PathRule` | **`path`** (`/` 로 시작), `pathType` (`Prefix` \| `Exact`, 기본 `Prefix`) |
| `DeploymentInfo` | `strategy`, `slot`, `version`, `image`, `nodes` (문자열 배열), `status`. 전부 선택, 값은 배포 모듈이 정함 |
| `HostRequest` | **`host`** (DNS 이름) |
| `CanaryRequest` | **`serviceName`**, `servicePort` (기본 80), **`weight`** (1~100, %) |

### 응답 본문 `Route`

| 필드 | 타입 | 내용 |
|---|---|---|
| `namespace`, `app` | string | 라우트 식별자 |
| `url` | string | 앱 접속 주소 `{scheme}://{primaryHost}`. 다른 모듈은 이 값을 그대로 씀 |
| `primaryHost` | string | 배포 모듈이 정한 기본 주소 |
| `hosts` | string[] | 받는 주소 전체. 기본 주소가 첫 번째, 그 뒤 추가 주소 |
| `serviceName`, `servicePort` | string, int | 트래픽을 받는 Service |
| `paths` | `PathRule[]` | 노출 경로 |
| `timeoutSeconds` | int \| null | 프록시 타임아웃. null 이면 컨트롤러 기본값 |
| `canary` | `{serviceName, servicePort, weight}` \| null | 열려 있을 때만 |
| `deployment` | `DeploymentInfo` \| null | 배포 모듈이 마지막으로 보낸 값 |
| `backends` | `{pod, address, node, ready}[]` | **조회 시점의 실제 파드** (EndpointSlice). `node` = 파드가 뜬 EC2 노드 |
| `updatedAt` | string (ISO-8601) | 라우트를 마지막으로 바꾼 시각 |

### 오류 응답 `{code, message}`

| HTTP | `code` | 언제 |
|---|---|---|
| 400 | `INVALID_REQUEST` | 필드 검증 실패, JSON 형식 오류, 이름 형식, 기본 주소 삭제 시도 |
| 401 | `UNAUTHORIZED` | 토큰 없음·불일치 |
| 404 | `ROUTE_NOT_FOUND` | 라우트 없음, 없는 추가 주소 삭제, 주소로 찾기 실패 |
| 409 | `HOST_CONFLICT` | 같은 호스트·경로를 다른 Ingress 가 이미 사용 (admission webhook 거부 포함) |
| 502 | `KUBERNETES_ERROR` | k8s API 호출 실패 |

## 1단계: lily-cicd Router 교체 (cicd 담당자와 합의 필요)

lily-cicd 는 DB 모듈을 `DatabaseProvisioner` + `HttpDatabaseProvisioner` 로 붙여 두었습니다. Router 도 같은 방식으로 붙입니다.
`lily.router.url` 이 없으면 지금의 `NginxIngressRouter` 가 그대로 동작하므로 기존 배포는 영향이 없습니다.

`src/main/java/com/lily/cicd/module/HttpTrafficRouter.java` (제안):

```java
package com.lily.cicd.module;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

/**
 * lily-router 에 라우트를 등록한다. Ingress 를 직접 만들지 않는다.
 * 프로토콜: lily-router docs/protocol.md
 */
public final class HttpTrafficRouter implements TrafficRouter {

    private final RestClient http;

    public HttpTrafficRouter(RestClient.Builder builder, String baseUrl, String apiToken) {
        this.http = builder
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiToken)
                .build();
    }

    @Override
    public void route(DeployContext context) {
        Map<String, Object> deployment = new LinkedHashMap<>();
        deployment.put("slot", context.targetColor());
        deployment.put("version", context.appVersion());
        deployment.put("image", context.imageUrl());
        deployment.put("status", "ACTIVE");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("serviceName", context.serviceName());
        body.put("servicePort", context.servicePort());
        body.put("host", context.host());
        body.put("deployment", deployment);

        http.put()
                .uri("/api/v1/routes/{ns}/{app}", context.namespace(), context.appName())
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }
}
```

`DatabaseModuleConfiguration` 과 같은 모양의 설정:

```java
@Configuration
@ConditionalOnProperty(prefix = "lily.router", name = "url")
public class RouterModuleConfiguration {

    @Bean
    @Primary
    public TrafficRouter httpTrafficRouter(
            @Value("${lily.router.url}") String url,
            @Value("${lily.router.api-token:}") String apiToken) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(10));
        return new HttpTrafficRouter(RestClient.builder().requestFactory(factory), url, apiToken);
    }
}
```

`deploy/k3s/lily-cicd.yaml` 에 추가:

```yaml
- name: LILY_ROUTER_URL
  value: http://lily-router.lily-system.svc
- name: LILY_ROUTER_API_TOKEN
  valueFrom:
    secretKeyRef:
      name: lily-router-token
      key: LILY_ROUTER_API_TOKEN
```

주의:
- cicd 의 `appName` 은 55자까지 허용하지만 lily-router 는 48자까지입니다 (`-canary-ingress` 접미사). cicd 쪽 제한도 48로 맞춥니다.
- 지금 cicd 응답의 `targetHostUrl` 은 엔진이 `url-scheme + host` 로 만듭니다. 같은 값이 나오지만, 장기적으로는 lily-router 응답의 `url` 을 쓰는 편이 맞습니다 (`TrafficRouter` 반환 타입 변경 필요).

## 2단계: canary 와 조회도 lily-router 로

- `CanaryAnalysis.createIngress` → `PUT /api/v1/routes/{ns}/{app}/canary` `{serviceName: "{app}-canary-svc", weight}`
- `CanaryAnalysis.cleanup` → `DELETE /api/v1/routes/{ns}/{app}/canary`
- `CanaryAnalysis.skipReason` 의 Ingress 조회 → `GET /api/v1/routes/{ns}/{app}` 가 404 면 "no route yet"
- `TrafficRouter` 인터페이스에 `openCanary`, `closeCanary`, `find` 를 추가하는 방식이 깔끔합니다 (cicd 담당자와 협의)

## 3단계: Ingress 쓰기 권한 정리

- lily-cicd ClusterRole 의 `ingresses` 에서 `create, update, patch, delete` 를 뺍니다
- lily-builder ClusterRole 의 `ingresses get` 도 `GET /routes` 로 바꾼 뒤 뺍니다
- 그 뒤로는 lily-router 외에는 Ingress 를 쓸 수 없습니다

## 기존 Ingress 옮기기

lily-router 를 배포한 뒤, 기존 앱은 다음 배포 때 `PUT /routes` 가 불리면서 자동으로 옮겨집니다.
배포 전에 바로 옮기려면 직접 등록합니다. 기존 `{app}-ingress` 의 nip.io 호스트는 추가 주소로 이어받습니다.

```bash
# 클러스터 안에서 (예: kubectl run 으로 임시 파드) 또는 port-forward 후
TOKEN=...
for app in blog blog2 lily-test; do
  curl -s -X PUT http://lily-router.lily-system.svc/api/v1/routes/default/$app \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d "{\"serviceName\":\"$app-svc\"}"
done
```

플랫폼 Ingress (`lily-builder-burst`) 는 이름 규칙이 다르므로 새 라우트로 등록한 뒤 옛 Ingress 를 지웁니다.
같은 호스트·경로를 두 Ingress 가 가질 수 없으므로 **옛 것을 먼저 지우고** 등록합니다 (몇 초 끊김).

```bash
kubectl -n lily-system delete ingress lily-builder-burst
curl -s -X PUT http://lily-router.lily-system.svc/api/v1/routes/lily-system/lily-builder \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{
    "serviceName": "lily-builder",
    "host": "builder.apps.lilycloud.kr",
    "paths": [{"path": "/api/burst"}, {"path": "/api/agents/connect", "pathType": "Exact"}],
    "timeoutSeconds": 3600
  }'
# nip.io 주소는 /api/burst 만 열려 있었다. 경로가 주소마다 다르면 지금 프로토콜로는 표현할 수 없다 (아래 한계)
```

## 지금 버전의 한계

- 라우트 하나의 모든 주소는 **같은 경로 목록**을 씁니다. `builder` 처럼 주소마다 열린 경로가 다르면 하나로 맞추거나 앱을 나눠 등록합니다.
- 레플리카는 1개 기준입니다 (같은 앱 갱신을 프로세스 안에서 직렬화).
- 실제 프록시는 여전히 ingress-nginx 입니다. 자체 컨트롤러로 바꿀 때는 `LILY_ROUTER_INGRESS_CLASS` 만 바꾸면 됩니다.
