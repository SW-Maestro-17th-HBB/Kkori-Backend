# 동기/비동기 디스패치 전환 실험 (HBB1-327)

## 목적

이력서 분석 요청 처리를 **동기 방식과 비동기 방식으로 갈아끼워 부하 테스트로 실측 비교**하기 위한 실험 장치다.

- **비동기 모드(기본, 현행)**: 업로드 API가 `resume.parse.requested` 스트림에 발행하고 즉시 응답. 워커가 나중에 소비·처리
- **동기 모드(실험)**: 업로드 API가 워커의 HTTP 엔드포인트를 호출하고, 분석이 끝날 때까지 기다렸다가 응답

측정 대상은 **업로드 API**다 — PDF 검증 → S3 업로드 → DB 저장 → 분석 요청 전달의 실제 사용자 경로 전체가 요청 하나에 들어 있다. 재분석 API는 측정 대상이 아니지만 같은 전달 코드를 쓰므로 함께 전환된다.

전환 방식은 부팅 시 환경변수로 결정한다. 요청 헤더 등 요청별 전환은 "외부 요청이 서버 동작 방식을 바꿀 수 있다"는 보안 문제로 기각했다(멘토 자문). 모드마다 서버를 새로 띄우므로 이전 모드의 JIT·커넥션 풀 상태가 측정에 섞이지 않는 장점도 있다.

## 전환 방법

| 환경변수 | 의미 | 기본값 |
|---|---|---|
| `AI_DISPATCH_MODE` | `async` 또는 `sync` | `async` (미주입 = 현행 동작) |
| `AI_WORKER_BASE_URL` | 동기 모드가 호출할 워커 주소 | 없음 — sync일 때 필수(미주입 시 기동 실패) |
| `AI_DISPATCH_SYNC_READ_TIMEOUT` | 동기 호출 read timeout | `120s` |

값이 `async`/`sync` 외의 것이면 어느 구현체도 등록되지 않아 **부팅이 실패한다** — 오타가 조용히 엉뚱한 모드로 뜨는 일은 구조적으로 없다.

ECS에서의 전환: 태스크 정의의 환경변수를 바꾼 새 리비전을 등록하고 `aws ecs update-service`로 재배포한다(모드당 1회). 인프라 terraform은 `ignore_changes = [task_definition]`이라 드리프트가 생기지 않는다 — 인프라 PR 불필요.

## 워커(Kkori-AI) 엔드포인트 계약 — 별도 구현 필요

동기 모드가 호출할 HTTP 엔드포인트는 현재 워커에 존재하지 않는다. Kkori-AI 쪽 작업으로 아래 계약을 구현해야 동기 모드의 성공 경로가 동작한다.

```
POST {AI_WORKER_BASE_URL}/internal/analyses/resume
Content-Type: application/json

{ "resumeId": 1, "userId": 2, "bucket": "kkori-resumes", "objectKey": "resumes/2/....pdf", "mode": "FULL" }
```

- 요청 바디는 `resume.parse.requested` 스트림 메시지(`ResumeParseRequestedMessage`)와 동일한 5개 필드. 숫자는 JSON 숫자로 실린다
- **2xx는 처리가 전부 끝난 뒤에만** 반환한다 — 파싱·결과 저장·상태 전이(EMBEDDED)까지. 상태 전이의 소유권은 비동기 모드와 동일하게 워커에 있다
- 비-2xx 또는 타임아웃이면 Spring이 해당 이력서를 FAILED로 전이시키고 500(R007)을 반환한다
- 부하 테스트용 가짜 추론은 **논블로킹 지연**(`asyncio.sleep`)으로 구현한다 — `time.sleep`은 이벤트 루프를 막아 동시 처리 능력이 실제보다 나쁘게 측정된다. 지연값은 동기·비동기 양쪽에 동일하게 적용해야 변인이 통제된다
- 인증 없음 — 대신 네트워크로 접근을 제한한다. 인수 조건:
  - Worker의 `/internal/analyses/resume` ingress는 Spring 백엔드의 보안 그룹에서만 허용한다
  - 인터넷, 사용자 클라이언트, 다른 ECS 서비스는 이 포트와 경로에 접근할 수 없다
  - `AI_WORKER_BASE_URL`은 내부 DNS 또는 private IP만 사용한다
  - 클라우드 측정 전에 보안 그룹 규칙을 검증하고, 허용되지 않은 출발지의 요청이 네트워크 계층에서 거부되는지 확인한다
  - 실험 종료 후 엔드포인트 존치 여부와 함께 인증 필요성을 재검토한다

## 알려진 한계·주의

- **동기 실패 시 SSE 상태 이벤트는 발행되지 않는다** — 평소 상태 이벤트는 워커가 발행하는데, 이 경로는 Spring이 직접 FAILED를 쓴다. 실험 한정으로 수용
- 동기 모드는 요청당 톰캣 스레드를 워커 처리 시간만큼 점유한다. 부하 수준에 따라 `server.tomcat.threads.max`·ALB 유휴 타임아웃 조정 필요. Spring 2대(ALB 분산) 구성도 결과 해석 시 고려
- dev/prod yaml의 `${AI_DISPATCH_MODE:async}`는 "환경 파일은 기본값 없는 `${ENV}`" 규칙의 의도적 이탈이다 — 실험 토글은 미주입=async가 안전한 기본이라서
- **리포트 생성의 전환은 2차 작업**이다. 같은 패턴(`ReportGenerationRequester`)으로 추가 예정이며, 리포트 최초 생성은 면접 에이전트가 트리거하므로 백엔드 전환 대상은 재생성 경로뿐이라는 한계도 그때 문서화한다
