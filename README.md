# lily-ingress

Lily 플랫폼의 **라우터 모듈**입니다. 클러스터의 Ingress 를 쓰는 곳을 이 서비스 하나로 모읍니다.

- **lily-cicd 는 배포만**: 어느 노드에 어떻게 띄울지 정하고 배포한 뒤, 서비스 이름과 접근 경로를 lily-ingress 에 알려 줍니다
- **lily-ingress 는 라우팅만**: 받은 정보로 Ingress 를 만들고, 다른 모듈이 앱 주소·배포 상태·파드 위치를 물으면 알려 줍니다
- 실제 프록시는 지금처럼 ingress-nginx 컨트롤러가 합니다

```
             ① 배포 (노드 결정, Deployment, Service)
lily-cicd ───────────────────────────────────────▶ k3s
    │
    │ ② PUT /api/v1/routes/{ns}/{app}  {serviceName, host, paths, deployment}
    ▼
lily-ingress ──③ Ingress {app}-ingress 생성·갱신──▶ k3s ──▶ ingress-nginx 가 읽어서 프록시
    ▲
    │ ④ GET /api/v1/routes/...   → url, hosts, deployment, backends(파드·노드)
builder · observer · frontend
```

## 왜 만들었나

지금은 같은 Ingress 를 lily-cicd(배포 때마다 통째로 교체)와 사람(`kubectl apply`)이 같이 고쳐서,
재배포하면 손으로 넣은 주소(nip.io)가 사라졌습니다 (lily-cicd `docs/troubleshooting-ingress-host-overwrite.md`).
lily-ingress 는 앱별 라우트 원본(기본 주소 + 추가 주소)을 들고 매번 전체를 다시 쓰므로, 배포 모듈이 기본 주소를 갱신해도 추가 주소가 남습니다.

| 지금 | lily-ingress 이후 |
|---|---|
| cicd `NginxIngressRouter`, `CanaryAnalysis`, loadbalancer 매니페스트, builder 매니페스트가 각자 Ingress 를 씀 | lily-ingress 만 씀. 다른 모듈은 HTTP 요청 |
| 앱 주소를 알려면 Ingress 첫 rule 의 host 를 읽고 `http://` 를 붙임 | `GET /routes` 의 `url` |
| 파드가 어느 노드에 떴는지 알 방법이 없음 | `backends[].node` |

## 문서

- [docs/protocol.md](docs/protocol.md): 통신 규칙 (API, 요청·응답, 오류 코드, 배포 흐름 예시)
- [docs/integration.md](docs/integration.md): lily-cicd·builder 연동 방법, 기존 Ingress 옮기기, 한계

## API 요약

| 메서드·경로 | 누가 | 하는 일 |
|---|---|---|
| `PUT /api/v1/routes/{ns}/{app}` | 배포 모듈 | 배포 완료 후 라우트 등록·갱신 |
| `PUT /api/v1/routes/{ns}/{app}/deployment` | 배포 모듈 | 배포 상태만 갱신 |
| `PUT` / `DELETE /api/v1/routes/{ns}/{app}/canary` | 배포 모듈 | 일부 트래픽을 새 버전으로 |
| `POST` / `DELETE /api/v1/routes/{ns}/{app}/hosts` | 운영자 | nip.io, 커스텀 도메인 추가 |
| `DELETE /api/v1/routes/{ns}/{app}` | 배포 모듈 | 앱 삭제 |
| `GET /api/v1/routes`, `GET /api/v1/routes/{ns}/{app}` | 누구나 | 조회 |
| `GET /api/v1/hosts/{host}` | 누구나 | 주소로 앱 찾기 |
| `GET /healthz` | k8s | 헬스체크 (토큰 없음) |

## 클러스터에 남기는 것

DB 를 두지 않고 Ingress 자체에 기록합니다.

| 리소스 | 이름 | 내용 |
|---|---|---|
| Ingress | `{app}-ingress` | 모든 주소 × 경로 → Service. 어노테이션 `lily.io/route` 에 라우트 원본(JSON) |
| Ingress | `{app}-canary-ingress` | canary 가 열려 있을 때만 |
| 공통 레이블 | `app.kubernetes.io/managed-by: lily-ingress`, `lily.io/app`, `lily.io/role: main\|canary` | 이 레이블이 있는 Ingress 는 lily-ingress 만 고친다 |

이름은 lily-cicd 가 쓰던 것과 같아서, 옮겨 올 때 기존 리소스를 그대로 이어받습니다.

## 설정

| 환경변수 | 기본값 | 설명 |
|---|---|---|
| `LILY_INGRESS_DOMAIN` | `apps.lilycloud.kr` | 호스트를 안 주면 `{app}.{domain}` |
| `LILY_INGRESS_URL_SCHEME` | `https` | 응답 `url` 의 scheme (ALB 가 HTTPS 종료) |
| `LILY_INGRESS_INGRESS_CLASS` | `nginx` | 만드는 Ingress 의 ingressClassName |
| `LILY_INGRESS_API_TOKEN` | (없음) | `/api` Bearer 토큰. 비우면 인증 안 함 |

## 실행

```bash
./gradlew test                       # 단위 테스트 (fabric8 mock 서버, 클러스터 불필요)
./gradlew bootRun                    # 로컬 실행 :8075. KUBECONFIG 의 클러스터에 붙는다
```

클러스터 배포는 [deploy/k3s/lily-ingress.yaml](deploy/k3s/lily-ingress.yaml) 상단 주석을 따릅니다.

```bash
sudo kubectl -n lily-system create secret generic lily-ingress-token \
  --from-literal=LILY_INGRESS_API_TOKEN=$(openssl rand -hex 24)
sudo kubectl apply -f deploy/k3s/lily-ingress.yaml
```

## 다음 할 일

- [ ] lily-cicd 에 `HttpTrafficRouter` 붙이기 (cicd 담당자 합의, [integration.md](docs/integration.md) 1단계)
- [ ] canary·조회도 lily-ingress 로 (2단계), builder `ClusterApps` 가 `url` 사용
- [ ] cicd·builder 의 Ingress 쓰기 권한 제거 (3단계)
- [ ] 기존 Ingress 옮기기, lily-loadbalancer `manifests/` 와 builder 의 중복 매니페스트 정리
- [ ] 주소마다 다른 경로 지원 (지금은 라우트 하나의 모든 주소가 같은 경로)
- [ ] 자체 nginx 컨트롤러로 교체할 때 `INGRESS_CLASS` 전환
