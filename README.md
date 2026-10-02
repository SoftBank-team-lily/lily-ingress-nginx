# lily-router

Lily 플랫폼 **라우터 모듈**. 배포된 앱의 접근 경로(주소 × 경로 → Service)를 관리한다.

| 항목 | 값 |
|---|---|
| 역할 | 앱 라우트 저장·반영(k8s Ingress), 라우트·파드 위치 조회 API |
| 스택 | Java 21, Spring Boot 3.4, fabric8 kubernetes-client 6.13 |
| 포트 | `8075` (Service `lily-router.lily-system.svc:80`) |
| 위치 | lily-server (control-plane), namespace `lily-system`, 레플리카 1 |
| 저장소 | 없음. 원본은 Ingress 어노테이션 `lily.io/route` |
| 프록시 | 하지 않음. 실제 트래픽은 ingress-nginx 가 처리 |

---

## 1. 역할

| 하는 일 | 하지 않는 일 → 담당 |
|---|---|
| 배포 모듈이 보낸 라우트를 `{app}-ingress` 로 반영 | 빌드·배포·슬롯 전환 → lily-builder, lily-cicd |
| canary 입구(가중치 0~100%) 열기·올리기·닫기 | 노드 결정 → k8s 스케줄러 |
| 추가 주소(nip.io, 커스텀 도메인) 관리, 재배포해도 유지 | `{app}.lilycloud.kr` DNS·CNAME → lily-builder `AppAddress` |
| 다른 주체가 만든 Ingress 이어받기 (호스트·TLS·어노테이션) | HTTPS 인증서 → ALB(ACM), Cloudflare |
| 호스트·경로 충돌 검사 | 트래픽 프록시 → ingress-nginx |
| 앱 주소·배포 정보·실제 파드 위치(노드) 조회 | 지표·장애 판정 → lily-observer |

---

## 2. 구성

```
사용자 → Cloudflare → ALB(HTTPS) → worker:80 → ingress-nginx ──▶ 앱 파드
                                                    ▲ watch
lily-server (control-plane)                         │
  lily-cicd ─── PUT /routes ──▶ lily-router ─── Ingress 쓰기 ──▶ k3s API
  lily-web  ─┐                     ▲
  builder   ─┼── GET /routes ──────┘
  observer  ─┘
```

| 연동 모듈 | 방향 | 호출 | 시점 |
|---|---|---|---|
| lily-cicd | cicd → router | `PUT /routes/{ns}/{app}` | 슬롯 전환 직후 |
| lily-cicd | cicd → router | `PUT`·`DELETE .../canary` | canary 전략 가중치 단계, 판정 정리 |
| lily-cicd | cicd → router | `GET /routes/{ns}/{app}` | canary 판정 전 host 확인 |
| lily-cicd | cicd → router | `GET /routes?canary=true` | cicd 재시작 뒤 남은 canary 정리 |
| lily-cicd | cicd → router | `DELETE /routes/{ns}/{app}` | 앱 삭제 |
| lily-web (frontend) | web → router | `GET /routes/{ns}/{app}` | 대시보드 라우팅·canary 표시 (`INGRESS_API_URL`) |
| lily-builder, lily-observer | → router | `GET /routes` | 앱 접속 주소 |
| 운영자 | → router | `POST`·`DELETE .../hosts` | 추가 주소 관리 |

- 호출 방향은 항상 다른 모듈 → lily-router. lily-router 는 다른 모듈을 부르지 않음
- cicd 연동 코드: lily-cicd 브랜치 `기능/router-연동` (`HttpTrafficRouter`, `lily.router.url` 이 있을 때만 켜짐)

---

## 3. API

### 공통

| 항목 | 값 |
|---|---|
| Base URL | `http://lily-router.lily-system.svc` (로컬 `http://localhost:8075`) |
| 인증 | `/api/` 전체 `Authorization: Bearer {LILY_ROUTER_API_TOKEN}`. 토큰 미설정 시 검사 안 함 |
| 형식 | JSON |
| 경로 변수 | `{namespace}` DNS 라벨 63자 이하 · `{app}` 소문자·숫자·하이픈 48자 이하 · `{host}` DNS 이름 |
| 멱등성 | `PUT`·`DELETE` 는 같은 요청 재전송 시 결과 같음 |
| 반영 시점 | 응답 = Ingress 를 k8s 에 쓴 시점. ingress-nginx 반영은 보통 수 초 |
| 재시도 | `5xx` 재시도 가능, `4xx` 재시도 금지 |

### 엔드포인트

| # | 메서드 | 경로 | 요청 본문 | 성공 | 실패 |
|---|---|---|---|---|---|
| 1 | `PUT` | `/api/v1/routes/{namespace}/{app}` | `RegisterRouteRequest` | 200 `Route` | 400, 409, 502 |
| 2 | `GET` | `/api/v1/routes` `?namespace=` `?canary=true` | - | 200 `Route[]` | 502 |
| 3 | `GET` | `/api/v1/routes/{namespace}/{app}` | - | 200 `Route` | 400, 404 |
| 4 | `DELETE` | `/api/v1/routes/{namespace}/{app}` | - | 204 | 400, 404 |
| 5 | `PUT` | `/api/v1/routes/{namespace}/{app}/deployment` | `DeploymentInfo` | 200 `Route` | 400, 404 |
| 6 | `POST` | `/api/v1/routes/{namespace}/{app}/hosts` | `HostRequest` | 200 `Route` | 400, 404, 409 |
| 7 | `DELETE` | `/api/v1/routes/{namespace}/{app}/hosts/{host}` | - | 200 `Route` | 400(기본 주소), 404 |
| 8 | `PUT` | `/api/v1/routes/{namespace}/{app}/canary` | `CanaryRequest` | 200 `Route` | 400, 404(라우트 없음) |
| 9 | `DELETE` | `/api/v1/routes/{namespace}/{app}/canary` | - | 200 `Route` (안 열려 있어도 200) | 404(라우트 없음) |
| 10 | `GET` | `/api/v1/hosts/{host}` | - | 200 `Route` | 404 |
| 11 | `GET` | `/healthz` (토큰 불필요) | - | 200 `{"status":"ok"}` | - |

### 요청 본문

**`RegisterRouteRequest`** (#1)

| 필드 | 타입 | 필수 | 규칙 · 기본값 |
|---|---|---|---|
| `serviceName` | string | O | 같은 namespace 의 Service |
| `servicePort` | int | | 1~65535, 기본 `80` |
| `host` | string | | 기본 `{app}.lilycloud.kr` |
| `paths` | `PathRule[]` | | 기본 `[{"path":"/","pathType":"Prefix"}]` |
| `timeoutSeconds` | int | | 1~86400, 프록시 읽기·쓰기 타임아웃 |
| `deployment` | `DeploymentInfo` | | 조회용으로만 보관 |

**`PathRule`**: `path` (필수, `/` 로 시작) · `pathType` (`Prefix` \| `Exact`, 기본 `Prefix`)

**`DeploymentInfo`** (#1, #5): `strategy` · `slot` · `version` · `image` · `nodes[]` · `status` — 전부 선택, 값은 배포 모듈이 정함

**`HostRequest`** (#6): `host` (필수, DNS 이름)

**`CanaryRequest`** (#8): `serviceName` (필수) · `servicePort` (기본 80) · `weight` (필수, 0~100)

```bash
curl -X PUT http://lily-router.lily-system.svc/api/v1/routes/default/blog \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"serviceName":"blog-svc","deployment":{"slot":"green","version":"v2","status":"ACTIVE"}}'
```

### 응답 본문 `Route`

```json
{
  "namespace": "default",
  "app": "blog",
  "url": "https://blog.lilycloud.kr",
  "primaryHost": "blog.lilycloud.kr",
  "hosts": ["blog.lilycloud.kr", "blog.43.200.152.53.nip.io"],
  "serviceName": "blog-svc",
  "servicePort": 80,
  "paths": [{ "path": "/", "pathType": "Prefix" }],
  "timeoutSeconds": null,
  "canary": { "serviceName": "blog-canary-svc", "servicePort": 80, "weight": 20 },
  "deployment": { "strategy": "blue-green", "slot": "green", "version": "v2",
                  "image": "…/blog:v2", "nodes": null, "status": "ACTIVE" },
  "backends": [{ "pod": "blog-green-7d9f", "address": "10.42.1.7", "node": "lily-worker-1", "ready": true }],
  "updatedAt": "2026-10-02T05:00:00Z"
}
```

| 필드 | 내용 |
|---|---|
| `url` | `{scheme}://{primaryHost}`. 다른 모듈은 이 값을 앱 주소로 사용 |
| `hosts` | 받는 주소 전체. 첫 번째가 `primaryHost` |
| `canary` | 열려 있을 때만, 아니면 `null` |
| `deployment` | 배포 모듈이 마지막으로 보낸 값 |
| `backends` | 조회 시점의 실제 파드 (EndpointSlice). `node` = 파드가 뜬 노드 |

### 오류

```json
{ "timestamp": "2026-10-02T05:00:00.123Z", "code": "ROUTE_NOT_FOUND", "message": "default/blog 라우트가 없다" }
```

| HTTP | code | 경우 |
|---|---|---|
| 400 | `INVALID_REQUEST` | 필드 검증, JSON 형식, 경로 변수 형식, 기본 주소 삭제 |
| 401 | `UNAUTHORIZED` | 토큰 없음·불일치 |
| 404 | `ROUTE_NOT_FOUND` | 라우트·추가 주소 없음 |
| 409 | `HOST_CONFLICT` | 같은 호스트·경로를 다른 Ingress 가 사용 중 |
| 502 | `KUBERNETES_ERROR` | k8s API 실패 |

상세: [docs/api/01-Ingress Nginx API.md](<docs/api/01-Ingress Nginx API.md>)

---

## 4. 동작 규칙

| 규칙 | 내용 |
|---|---|
| 기본 주소 | `host` 미지정 시 `{app}.{LILY_ROUTER_DOMAIN}`. 재등록 때 바뀌면 이전 기본 주소는 제거 |
| 추가 주소 | `POST /hosts` 로 넣은 주소는 재등록해도 유지 |
| 이어받기 | lily-router 가 만들지 않은 `{app}-ingress` 가 있으면 첫 등록 때 호스트(→ 추가 주소)·TLS·어노테이션 유지. 제외: `kubernetes.io/ingress.class`, `kubectl.kubernetes.io/last-applied-configuration`, `lily.io/*` |
| 타임아웃 | `timeoutSeconds` 가 이어받은 타임아웃 어노테이션보다 우선 |
| canary | 라우트의 모든 주소·경로에 같은 비율. 라우트 변경 시 열린 canary 도 따라감 |
| 충돌 | 다른 Ingress(canary 제외)와 같은 호스트 + 같은 경로면 409 |
| 관리 대상 | `managed-by: lily-router` 레이블이 있는 Ingress 만 조회·수정 |
| 동시성 | 같은 앱 요청은 프로세스 안에서 순서대로 처리 (레플리카 1 전제) |
| 장애 | lily-router 중지 시 기존 Ingress 유지 → 사용자 트래픽 영향 없음, 라우트 변경만 실패 |

---

## 5. 클러스터 리소스

| 리소스 | 이름 | 내용 |
|---|---|---|
| Ingress | `{app}-ingress` | 모든 주소 × 경로 → Service. 어노테이션 `lily.io/route` = 라우트 원본 JSON |
| Ingress | `{app}-canary-ingress` | canary 열려 있을 때만. `nginx.ingress.kubernetes.io/canary-weight` |
| 레이블 | `app.kubernetes.io/managed-by: lily-router`, `lily.io/app`, `lily.io/role: main\|canary` | |

- Ingress 이름은 lily-cicd 기존 이름과 같음 → 기존 리소스 이어받기
- lily-observer 지표가 `ingress="{app}-ingress"` 라벨 기준 → 이름 변경 금지

---

## 6. 설정

| 환경변수 | 설정 키 | 기본값 | 내용 |
|---|---|---|---|
| `LILY_ROUTER_DOMAIN` | `lily.router.domain` | `lilycloud.kr` | 기본 주소 도메인. lily-cicd `LILY_DEPLOY_DOMAIN` 과 같게 |
| `LILY_ROUTER_URL_SCHEME` | `lily.router.url-scheme` | `https` | 응답 `url` scheme |
| `LILY_ROUTER_INGRESS_CLASS` | `lily.router.ingress-class` | `nginx` | 만드는 Ingress 의 `ingressClassName` |
| `LILY_ROUTER_API_TOKEN` | `lily.router.api-token` | (없음) | `/api` Bearer 토큰 |

---

## 7. 배포

매니페스트: [deploy/k3s/lily-router.yaml](deploy/k3s/lily-router.yaml)

| 리소스 | 내용 |
|---|---|
| ServiceAccount + ClusterRole | `ingresses` get·list·watch·create·update·patch·delete, `endpointslices` get·list |
| Deployment | 레플리카 1, control-plane 고정 (`nodeSelector` + toleration), probe `/healthz`, 요청 50m/256Mi · 상한 384Mi |
| Service | ClusterIP `lily-router:80` → `8075` |
| NetworkPolicy | `lily-system` 의 `lily-cicd`·`lily-web`·`lily-builder`·`lily-observer` → `:8075` 만 허용 |

```bash
sudo kubectl -n lily-system create secret generic lily-router-token \
  --from-literal=LILY_ROUTER_API_TOKEN=$(openssl rand -hex 24)
sudo kubectl apply -f deploy/k3s/lily-router.yaml
```

| 부르는 쪽 설정 | 값 |
|---|---|
| lily-cicd | `LILY_ROUTER_URL=http://lily-router.lily-system.svc`, `LILY_ROUTER_API_TOKEN` (Secret `lily-router-token`) |
| lily-web | `INGRESS_API_URL=http://lily-router.lily-system.svc`, `INGRESS_API_TOKEN` |

---

## 8. 코드 구조

```
src/main/java/com/lily/router/
├─ RouterApplication.java
├─ config/
│  ├─ RouterProperties.java      설정 (lily.router.*)
│  ├─ KubernetesConfig.java      KubernetesClient (클러스터 안: ServiceAccount, 로컬: KUBECONFIG)
│  └─ ApiTokenFilter.java        /api Bearer 토큰 검사
└─ route/
   ├─ RouteController.java       API #1~#10
   ├─ HealthController.java      #11 /healthz
   ├─ RouteService.java          등록·조회·삭제, 이어받기, 충돌 검사, backends 조회
   ├─ RouteState.java            라우트 원본 (Ingress 어노테이션에 JSON 저장)
   ├─ IngressSpecs.java          RouteState → Ingress 변환 (클러스터 호출 없음)
   ├─ RouteExceptions.java       404·409 예외
   ├─ ApiExceptionHandler.java   예외 → {timestamp, code, message}
   └─ dto/
      ├─ PathRule, DeploymentInfo, ValidationPatterns   요청·응답 공용
      ├─ request/   RegisterRouteRequest, HostRequest, CanaryRequest
      └─ response/  Route, CanaryView, Backend, ErrorResponse, HealthResponse
```

---

## 9. 개발·테스트

```bash
./gradlew test       # 클러스터 불필요 (fabric8 mock 서버)
./gradlew bootRun    # :8075, KUBECONFIG 의 클러스터에 연결
./gradlew bootJar    # build/libs/app.jar
```

| 테스트 | 범위 |
|---|---|
| `RouteServiceTest` | 등록·이어받기(TLS·어노테이션)·추가 주소·canary·충돌·backends·삭제 |
| `RouteControllerTest` | 엔드포인트별 상태 코드, 검증, 토큰, 오류 형식 |
| `CicdContractTest` | lily-cicd `HttpTrafficRouter` 요청 그대로: 블루그린 배포·전환, 기존 Ingress 이어받기, canary 0→100·정리, 앱 삭제 |

---

## 10. 문서

| 문서 | 내용 |
|---|---|
| [docs/01-Ingress Nginx 모듈화.md](<docs/01-Ingress Nginx 모듈화.md>) | 배경, 연동 단계, 기존 Ingress 옮기기 |
| [docs/02-라우터 설계.md](<docs/02-라우터 설계.md>) | 배치, 데이터, 모듈 간 흐름, 장애 처리, 보안, 검증 계획 |
| [docs/api/01-Ingress Nginx API.md](<docs/api/01-Ingress Nginx API.md>) | API 명세 |
| [docs/api/02-Ingress Nginx API Details.md](<docs/api/02-Ingress Nginx API Details.md>) | 필드 규칙, 배포 흐름 예시 |

---

## 11. 현재 상태

| 항목 | 상태 |
|---|---|
| API 11개, Ingress 반영, 이어받기, 충돌 검사, canary 0~100, backends | 완료 |
| 배치 고정, NetworkPolicy | 완료 (매니페스트) |
| lily-cicd 연동 (`HttpTrafficRouter`) | lily-cicd 로컬 브랜치 `기능/router-연동`, 담당자 합의 후 PR |
| lily-web 연동 | frontend 코드 완료, 배포 설정 `INGRESS_API_URL` 만 필요 |
| builder `ClusterApps`, observer `KubernetesClusterSource` 의 `url` 사용 | 예정 |
| 로컬 k3d(1 server + 2 agent) 전체 파이프라인 검증 | 예정 |
| 주소마다 다른 경로 | 미지원 (라우트 하나의 모든 주소가 같은 경로) |
