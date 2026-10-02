# lily-router API 명세 (v1)

코드 기준: [RouteController](../../src/main/java/com/lily/router/route/RouteController.java) · 요청·응답 타입 [dto/](../../src/main/java/com/lily/router/route/dto/) (`request/`, `response/`, 공용 `PathRule`·`DeploymentInfo`) · [ApiExceptionHandler](../../src/main/java/com/lily/router/route/ApiExceptionHandler.java) · [HealthController](../../src/main/java/com/lily/router/route/HealthController.java)

## 공통

| 항목 | 값 |
|---|---|
| Base URL | `http://lily-router.lily-system.svc` (클러스터 안, Service 80 → 컨테이너 8075) |
| 로컬 | `http://localhost:8075` |
| 형식 | `Content-Type: application/json` |
| 인증 | `/api/` 아래 전부 `Authorization: Bearer {LILY_ROUTER_API_TOKEN}`. 서버에 토큰이 설정되지 않았으면 검사하지 않음 |
| 경로 변수 | `{namespace}`: 소문자·숫자·하이픈, 63자 이하 / `{app}`: 소문자·숫자·하이픈, 48자 이하 / `{host}`: DNS 이름 |
| 멱등성 | `PUT`·`DELETE` 는 같은 요청을 다시 보내도 결과가 같습니다. 응답이 끊기면 다시 보내면 됩니다 |
| 반영 시점 | `200`/`204` 는 **Ingress 리소스를 k8s 에 쓴 시점**입니다. ingress-nginx 가 읽어 트래픽에 반영하기까지 보통 수 초 걸립니다. 반영을 확인해야 하면 실제 주소로 요청해 확인합니다 |
| 재시도 | `5xx` 는 다시 보내도 됩니다. `4xx` 는 요청을 고쳐야 합니다 |

## 엔드포인트 목록

| # | 메서드 | 경로 | 성공 | 응답 본문 |
|---|---|---|---|---|
| 1 | `PUT` | [`/api/v1/routes/{namespace}/{app}`](#1-라우트-등록갱신) | 200 | `Route` |
| 2 | `GET` | [`/api/v1/routes`](#2-라우트-목록) | 200 | `Route[]` |
| 3 | `GET` | [`/api/v1/routes/{namespace}/{app}`](#3-라우트-조회) | 200 | `Route` |
| 4 | `DELETE` | [`/api/v1/routes/{namespace}/{app}`](#4-라우트-삭제) | 204 | 없음 |
| 5 | `PUT` | [`/api/v1/routes/{namespace}/{app}/deployment`](#5-배포-정보-갱신) | 200 | `Route` |
| 6 | `POST` | [`/api/v1/routes/{namespace}/{app}/hosts`](#6-추가-주소-등록) | 200 | `Route` |
| 7 | `DELETE` | [`/api/v1/routes/{namespace}/{app}/hosts/{host}`](#7-추가-주소-삭제) | 200 | `Route` |
| 8 | `PUT` | [`/api/v1/routes/{namespace}/{app}/canary`](#8-canary-열기) | 200 | `Route` |
| 9 | `DELETE` | [`/api/v1/routes/{namespace}/{app}/canary`](#9-canary-닫기) | 200 | `Route` |
| 10 | `GET` | [`/api/v1/hosts/{host}`](#10-주소로-라우트-찾기) | 200 | `Route` |
| 11 | `GET` | [`/healthz`](#11-헬스체크) | 200 | `{"status":"ok"}` |

---

## 1. 라우트 등록·갱신

`PUT /api/v1/routes/{namespace}/{app}`

배포가 끝난 앱의 서비스와 접근 경로를 등록합니다. 같은 요청을 다시 보내면 같은 결과가 됩니다 (멱등).

**요청 본문** `RegisterRouteRequest`

| 필드 | 타입 | 필수 | 규칙 / 기본값 |
|---|---|---|---|
| `serviceName` | string | O | 같은 namespace 의 Service 이름 |
| `servicePort` | int | | 1~65535. 기본 `80` |
| `host` | string | | DNS 이름. 기본 `{app}.lilycloud.kr` |
| `paths` | `PathRule[]` | | 기본 `[{"path":"/","pathType":"Prefix"}]` |
| `timeoutSeconds` | int | | 1~86400. 프록시 읽기·쓰기 타임아웃. 기본은 컨트롤러 값 (60초) |
| `deployment` | `DeploymentInfo` | | 조회용으로만 보관 |

```bash
curl -X PUT http://lily-router.lily-system.svc/api/v1/routes/default/blog \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{
    "serviceName": "blog-svc",
    "servicePort": 80,
    "deployment": { "strategy": "blue-green", "slot": "green", "version": "v2",
                    "image": "123.dkr.ecr.ap-northeast-2.amazonaws.com/blog:abc", "status": "ACTIVE" }
  }'
```

**응답** `200` [`Route`](#route)

동작:
- `host` 가 바뀌면 이전 기본 주소는 빠지고, 추가 주소(#6)는 그대로 남습니다
- lily-router 가 만들지 않은 `{app}-ingress` 가 이미 있으면 그 호스트를 추가 주소로, TLS 와 어노테이션도 그대로 이어받습니다. 이어받은 값은 이후 재등록해도 남습니다
  (빼는 것: `kubernetes.io/ingress.class`, `kubectl.kubernetes.io/last-applied-configuration`, `lily.io/*`. `timeoutSeconds` 를 주면 이어받은 타임아웃보다 우선)
- canary 가 열려 있으면 canary 도 새 호스트·경로를 따라갑니다

| 실패 | code | 예 |
|---|---|---|
| 400 | `INVALID_REQUEST` | `serviceName` 없음, `pathType` 이 `Prefix`/`Exact` 가 아님, 이름 형식 |
| 409 | `HOST_CONFLICT` | 다른 Ingress 가 같은 호스트·경로 사용 중 |
| 502 | `KUBERNETES_ERROR` | k8s API 실패 |

---

## 2. 라우트 목록

`GET /api/v1/routes`
`GET /api/v1/routes?namespace=default`
`GET /api/v1/routes?canary=true`

| 쿼리 | 필수 | 설명 |
|---|---|---|
| `namespace` | | 비우면 전체 namespace |
| `canary` | | `true` 면 canary 가 열려 있는 라우트만. 기본 `false`. 배포 모듈이 재시작 뒤 남은 canary 를 정리할 때 씁니다 |

**응답** `200` [`Route`](#route)`[]`. `namespace`, `app` 순으로 정렬. lily-router 가 관리하는 라우트만 포함

| 실패 | code |
|---|---|
| 502 | `KUBERNETES_ERROR` |

---

## 3. 라우트 조회

`GET /api/v1/routes/{namespace}/{app}`

**응답** `200` [`Route`](#route)

| 실패 | code | 예 |
|---|---|---|
| 400 | `INVALID_REQUEST` | 이름 형식 |
| 404 | `ROUTE_NOT_FOUND` | 라우트 없음 (lily-router 가 만들지 않은 Ingress 포함) |

---

## 4. 라우트 삭제

`DELETE /api/v1/routes/{namespace}/{app}`

기본 Ingress 와 canary Ingress 를 함께 지웁니다.

**응답** `204` (본문 없음)

| 실패 | code |
|---|---|
| 400 | `INVALID_REQUEST` |
| 404 | `ROUTE_NOT_FOUND` |

---

## 5. 배포 정보 갱신

`PUT /api/v1/routes/{namespace}/{app}/deployment`

라우팅은 그대로 두고 `deployment` 만 바꿉니다.

**요청 본문** [`DeploymentInfo`](#deploymentinfo)

```json
{ "strategy": "blue-green", "slot": "blue", "version": "v3", "status": "DEPLOYING" }
```

**응답** `200` [`Route`](#route)

| 실패 | code |
|---|---|
| 400 | `INVALID_REQUEST` |
| 404 | `ROUTE_NOT_FOUND` (첫 배포 전) |

---

## 6. 추가 주소 등록

`POST /api/v1/routes/{namespace}/{app}/hosts`

nip.io, 커스텀 도메인처럼 기본 주소 외에 받을 주소. 재배포(#1)해도 남습니다.

**요청 본문** `HostRequest`

| 필드 | 타입 | 필수 | 규칙 |
|---|---|---|---|
| `host` | string | O | DNS 이름 |

```json
{ "host": "blog.43.200.152.53.nip.io" }
```

**응답** `200` [`Route`](#route). 이미 있는 주소면 바뀌지 않음

| 실패 | code |
|---|---|
| 400 | `INVALID_REQUEST` |
| 404 | `ROUTE_NOT_FOUND` |
| 409 | `HOST_CONFLICT` |

---

## 7. 추가 주소 삭제

`DELETE /api/v1/routes/{namespace}/{app}/hosts/{host}`

```
DELETE /api/v1/routes/default/blog/hosts/blog.43.200.152.53.nip.io
```

**응답** `200` [`Route`](#route)

| 실패 | code | 예 |
|---|---|---|
| 400 | `INVALID_REQUEST` | 기본 주소(`primaryHost`)를 지우려 함 |
| 404 | `ROUTE_NOT_FOUND` | 라우트 없음, 추가 주소에 없는 host |

---

## 8. canary 열기

`PUT /api/v1/routes/{namespace}/{app}/canary`

기본 라우트와 같은 호스트·경로로 들어온 요청 중 `weight`% 를 다른 Service 로 보냅니다.

**요청 본문** `CanaryRequest`

| 필드 | 타입 | 필수 | 규칙 / 기본값 |
|---|---|---|---|
| `serviceName` | string | O | 새 버전 Service |
| `servicePort` | int | | 1~65535. 기본 `80` |
| `weight` | int | O | 0~100 (%). `0` 이면 입구만 만들고 트래픽은 보내지 않음. 단계적으로 올릴 때는 같은 요청을 weight 만 바꿔 다시 보냄 |

```json
{ "serviceName": "blog-canary-svc", "weight": 10 }
```

**응답** `200` [`Route`](#route). `canary` 가 채워짐

| 실패 | code |
|---|---|
| 400 | `INVALID_REQUEST` |
| 404 | `ROUTE_NOT_FOUND` (기본 라우트가 먼저 있어야 함) |

---

## 9. canary 닫기

`DELETE /api/v1/routes/{namespace}/{app}/canary`

**응답** `200` [`Route`](#route). `canary` 는 `null`. 열려 있지 않아도 200

| 실패 | code |
|---|---|
| 404 | `ROUTE_NOT_FOUND` |

---

## 10. 주소로 라우트 찾기

`GET /api/v1/hosts/{host}`

```
GET /api/v1/hosts/blog.lilycloud.kr
```

기본 주소와 추가 주소 모두 찾습니다.

**응답** `200` [`Route`](#route)

| 실패 | code |
|---|---|
| 404 | `ROUTE_NOT_FOUND` |

---

## 11. 헬스체크

`GET /healthz` (토큰 불필요)

**응답** `200`

```json
{ "status": "ok" }
```

---

## 데이터 타입

### Route

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
  "canary": null,
  "deployment": {
    "strategy": "blue-green", "slot": "green", "version": "v2",
    "image": "123.dkr.ecr.ap-northeast-2.amazonaws.com/blog:abc",
    "nodes": null, "status": "ACTIVE"
  },
  "backends": [
    { "pod": "blog-green-7d9f", "address": "10.42.1.7", "node": "lily-worker-1", "ready": true }
  ],
  "updatedAt": "2026-10-01T07:00:00Z"
}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| `namespace` | string | |
| `app` | string | |
| `url` | string | `{scheme}://{primaryHost}`. 앱 접속 주소 |
| `primaryHost` | string | 배포 모듈이 정한 기본 주소 |
| `hosts` | string[] | 받는 주소 전체. 첫 번째가 `primaryHost` |
| `serviceName` | string | |
| `servicePort` | int | |
| `paths` | [`PathRule`](#pathrule)[] | |
| `timeoutSeconds` | int \| null | |
| `canary` | [`CanaryView`](#canaryview) \| null | 열려 있을 때만 |
| `deployment` | [`DeploymentInfo`](#deploymentinfo) \| null | 배포 모듈이 마지막으로 보낸 값 |
| `backends` | [`Backend`](#backend)[] | 조회 시점에 Service 뒤에 붙어 있는 실제 파드 |
| `updatedAt` | string (ISO-8601, UTC) | 라우트를 마지막으로 바꾼 시각 |

### PathRule

| 필드 | 타입 | 필수 | 규칙 / 기본값 |
|---|---|---|---|
| `path` | string | O | `/` 로 시작 |
| `pathType` | string | | `Prefix` \| `Exact`. 기본 `Prefix` |

### DeploymentInfo

모든 필드 선택. lily-router 는 값을 해석하지 않고 그대로 보관합니다.

| 필드 | 타입 | 예 |
|---|---|---|
| `strategy` | string | `blue-green`, `canary` |
| `slot` | string | `blue`, `green`, `stable`, `canary` |
| `version` | string | `v2` |
| `image` | string | ECR 이미지 |
| `nodes` | string[] | 배포 모듈이 의도한 노드 |
| `status` | string | `DEPLOYING`, `ACTIVE`, `FAILED`, `ROLLED_BACK` |

### CanaryView

| 필드 | 타입 |
|---|---|
| `serviceName` | string |
| `servicePort` | int |
| `weight` | int (%) |

### Backend

| 필드 | 타입 | 설명 |
|---|---|---|
| `pod` | string \| null | 파드 이름 |
| `address` | string \| null | 파드 IP |
| `node` | string \| null | 파드가 떠 있는 노드 (EC2) |
| `ready` | boolean | 트래픽을 받을 수 있는지 |

---

## 오류

모든 오류는 팀 공통 형식 `{timestamp, code, message}` 입니다.

```json
{ "timestamp": "2026-10-02T05:00:00.123Z", "code": "ROUTE_NOT_FOUND", "message": "default/blog 라우트가 없다" }
```

| 필드 | 타입 | 설명 |
|---|---|---|
| `timestamp` | string (ISO-8601, UTC) | 오류를 만든 시각 |
| `code` | string | 아래 표 |
| `message` | string | 사람이 읽는 설명 |

| HTTP | code | 언제 |
|---|---|---|
| 400 | `INVALID_REQUEST` | 필드 검증 실패, JSON 형식 오류, 경로 변수 형식, 기본 주소 삭제 시도 |
| 401 | `UNAUTHORIZED` | Bearer 토큰 없음·불일치 |
| 404 | `ROUTE_NOT_FOUND` | 라우트·추가 주소 없음 |
| 409 | `HOST_CONFLICT` | 같은 호스트·경로를 다른 Ingress 가 사용 중 (admission webhook 거부 포함) |
| 502 | `KUBERNETES_ERROR` | k8s API 호출 실패 |
