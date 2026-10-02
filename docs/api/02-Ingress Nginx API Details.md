# lily-router 프로토콜 v1

다른 모듈이 lily-router 와 통신하는 규칙입니다. 이 문서와 [dto/](../../src/main/java/com/lily/router/route/dto/) 를 같이 고칩니다.

## 공통

| 항목 | 값 |
|---|---|
| 주소 | 클러스터 안: `http://lily-router.lily-system.svc` (포트 80 → 컨테이너 8075) |
| 형식 | JSON (`Content-Type: application/json`) |
| 인증 | `Authorization: Bearer {LILY_ROUTER_API_TOKEN}`. `/api/` 아래 전부. `/healthz` 는 제외 |
| 버전 | 경로의 `/api/v1`. 필드를 지우거나 뜻을 바꾸면 `/api/v2` 를 연다. 필드 추가는 v1 안에서 한다 (받는 쪽은 모르는 필드를 무시) |
| 식별자 | 라우트 하나 = `{namespace}/{app}`. `app` 은 소문자·숫자·하이픈, 48자 이하 |

### 오류 응답

```json
{ "code": "HOST_CONFLICT", "message": "host blog.apps.lilycloud.kr path / 는 이미 default/other-ingress 가 쓰고 있다" }
```

| HTTP | code | 언제 |
|---|---|---|
| 400 | `INVALID_REQUEST` | 필드 검증 실패, 이름 형식, 기본 주소 삭제 시도 |
| 401 | `UNAUTHORIZED` | 토큰 없음 또는 다름 |
| 404 | `ROUTE_NOT_FOUND` | 라우트 없음, 없는 추가 주소 삭제 |
| 409 | `HOST_CONFLICT` | 같은 호스트·경로를 다른 Ingress 가 이미 씀 |
| 502 | `KUBERNETES_ERROR` | 클러스터 API 실패 |

---

## 1. 배포 모듈 → lily-router

### 라우트 등록 (배포 완료 후)

`PUT /api/v1/routes/{namespace}/{app}`

배포 모듈이 트래픽 전환을 끝낸 뒤 부릅니다. 같은 요청을 여러 번 보내도 결과가 같습니다 (멱등).

```json
{
  "serviceName": "blog-svc",          // 필수. 같은 namespace 의 Service
  "servicePort": 80,                  // 생략 시 80
  "host": null,                       // 생략 시 {app}.apps.lilycloud.kr
  "paths": [                          // 생략 시 "/" 전체
    { "path": "/api/burst", "pathType": "Prefix" },
    { "path": "/api/agents/connect", "pathType": "Exact" }
  ],
  "timeoutSeconds": 3600,             // 생략 시 컨트롤러 기본값 (60초). WebSocket 등
  "deployment": {                     // 선택. 라우팅에는 안 쓰고 조회용으로 보관
    "strategy": "blue-green",
    "slot": "green",
    "version": "v2",
    "image": "....ecr.../blog:abc",
    "nodes": ["lily-worker-1"],       // 배포 모듈이 정한 배치
    "status": "ACTIVE"
  }
}
```

응답: 200, [라우트](#라우트-응답).

규칙:
- `host` 는 **기본 주소**입니다. 배포할 때마다 바뀔 수 있고, 바뀌면 이전 기본 주소는 빠집니다.
- 추가 주소(`/hosts` 로 넣은 것)는 재배포해도 남습니다.
- 처음 등록할 때 lily-router 가 만들지 않은 `{app}-ingress` 가 이미 있으면, 그 Ingress 의 호스트를 추가 주소로 이어받습니다 (lily-cicd 가 만든 Ingress 를 옮겨 올 때).

### 배포 상태 갱신

`PUT /api/v1/routes/{namespace}/{app}/deployment`

라우팅은 그대로 두고 `deployment` 만 바꿉니다. 본문은 위 `deployment` 객체와 같습니다. 라우트가 없으면 404.
배포를 시작할 때 `DEPLOYING`, 실패하면 `FAILED`, 롤백하면 `ROLLED_BACK` 처럼 씁니다. 값은 배포 모듈이 정합니다.

### canary 열기·닫기

`PUT /api/v1/routes/{namespace}/{app}/canary`

```json
{ "serviceName": "blog-canary-svc", "servicePort": 80, "weight": 10 }
```

기본 라우트와 같은 호스트·경로로 들어온 요청 중 `weight`% 를 `serviceName` 으로 보냅니다.
라우트가 먼저 있어야 합니다 (없으면 404). 열려 있는 동안 라우트가 바뀌면 canary 도 따라갑니다.

`DELETE /api/v1/routes/{namespace}/{app}/canary` 로 닫습니다. 열려 있지 않아도 200.

### 라우트 삭제

`DELETE /api/v1/routes/{namespace}/{app}` → 204. canary 도 같이 지웁니다.

---

## 2. 운영자·다른 모듈 → lily-router

### 추가 주소

```
POST   /api/v1/routes/{namespace}/{app}/hosts          { "host": "blog.43.200.152.53.nip.io" }
DELETE /api/v1/routes/{namespace}/{app}/hosts/{host}
```

nip.io, 커스텀 도메인처럼 기본 주소 외에 받을 주소. 기본 주소는 지울 수 없습니다 (400).

### 조회

| 요청 | 응답 |
|---|---|
| `GET /api/v1/routes` | 전체 라우트 목록. `?namespace=default` 로 좁힘 |
| `GET /api/v1/routes/{namespace}/{app}` | 라우트 하나 |
| `GET /api/v1/hosts/{host}` | 이 주소를 받는 라우트. 없으면 404 |

### 라우트 응답

```json
{
  "namespace": "default",
  "app": "blog",
  "url": "https://blog.apps.lilycloud.kr",
  "primaryHost": "blog.apps.lilycloud.kr",
  "hosts": ["blog.apps.lilycloud.kr", "blog.43.200.152.53.nip.io"],
  "serviceName": "blog-svc",
  "servicePort": 80,
  "paths": [{ "path": "/", "pathType": "Prefix" }],
  "timeoutSeconds": null,
  "canary": { "serviceName": "blog-canary-svc", "servicePort": 80, "weight": 10 },
  "deployment": { "strategy": "blue-green", "slot": "green", "version": "v2",
                  "image": "...", "nodes": ["lily-worker-1"], "status": "ACTIVE" },
  "backends": [
    { "pod": "blog-green-7d9f", "address": "10.42.1.7", "node": "lily-worker-1", "ready": true }
  ],
  "updatedAt": "2026-10-01T07:00:00Z"
}
```

| 필드 | 출처 |
|---|---|
| `url` | 앱 접속 주소. 다른 모듈은 이 값을 그대로 쓴다 (scheme 포함) |
| `canary` | 열려 있을 때만. 아니면 null |
| `deployment` | 배포 모듈이 보낸 값 그대로 |
| `backends` | **조회 시점에 클러스터에서 읽은 실제 값** (EndpointSlice). 파드가 어느 노드에 떠 있는지, Ready 인지 |

`deployment.nodes`(배포 모듈이 의도한 배치)와 `backends[].node`(실제 배치)를 비교하면 배치가 어긋났는지 알 수 있습니다.

---

## 3. 흐름 예시 (블루그린 배포)

```
lily-cicd                                   lily-router
  │ PUT .../blog/deployment {status:DEPLOYING}  →  (라우트가 있으면 기록)
  │ 슬롯 green 배포, Ready 대기
  │ PUT .../blog/canary {blog-canary-svc, 10}   →  blog-canary-ingress 생성
  │ 30초 판정
  │ DELETE .../blog/canary                      →  삭제
  │ Service selector → green
  │ PUT .../blog {blog-svc, deployment:{slot:green, status:ACTIVE}}
  │                                         ←  { url: "https://blog.apps.lilycloud.kr", ... }
  │ 응답의 url 을 배포 결과로 돌려준다
```

첫 배포에서는 라우트가 아직 없으므로 `deployment` 갱신과 canary 가 404 입니다. 배포 모듈은 이 404 를 무시하고 진행합니다.
