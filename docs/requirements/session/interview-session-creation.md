# 면접 세션 생성

> **User Story**: HBB1-18 — 나는 사용자로서 면접을 시작하기 위해 면접 유형과 대상 이력서를 선택할 수 있다.
>
> **하위 이슈**: HBB1-142 [개발] 세션 레코드 생성 · HBB1-265 [개발] LiveKit 룸 생성 및 토큰 발급

## Overview

본 기능은 사용자가 면접 유형과 대상 이력서를 선택해 면접 세션을 시작하는 흐름을 정의한다. HBB1-256(`session.md`)이 확립한 상태 없는 토큰 발급 엔드포인트 `POST /api/v1/sessions`를 **면접 세션 생성**으로 확장하는 첫 단계다:

- 요청에 `resumeId`·`interviewType`·`position`(직무)이 추가되고, 서버는 검증을 거쳐 **세션 레코드(`interview_session`, 상태 `PENDING`)를 생성·저장**한다.
- LiveKit 룸을 **세션 생성 시점에 명시적으로 생성**하고(첫 접속 시 자동 생성에서 변경), 참가자 신원을 세션 파생 신원(`candidate-{sessionId}`)으로 발급한다.
- 응답에 세션 식별자(`id`)가 추가된다.

본 문서는 `POST /api/v1/sessions`의 계약을 개정한다 — `session.md`(HBB1-256)와 어긋나는 부분은 **본 문서가 우선**한다(토큰 grant·TTL·시크릿 보호 등 개정하지 않은 규칙은 그대로 계승).

경계: 이력서 선택 화면의 데이터는 기존 이력서 목록 API(`resume.md` §2 — status 필터)가 담당하고, 본 스토리는 선택 결과의 제출·검증·세션 생성과, 세션 생성이 성립시키는 이력서 사용 중 차단(`RESUME_IN_USE` — 이력서 도메인 연계)까지 다룬다. webhook 기반 상태 전이·Agent 디스패치·세션 조회/종료/재연결은 후속 면접 스토리의 PRD에서 다룬다(기능 1 제약사항의 범위 밖 목록 참조).

### 기능 요구사항

| No. | Function | Description |
| --- | --- | --- |
| 1 | 면접 세션 레코드 생성 | `POST /api/v1/sessions`에서 면접 유형·직무·대상 이력서를 검증하고 `PENDING` 세션 레코드를 생성해야 한다. 유저당 진행 중 세션 1개 불변식을 위해 기존 `PENDING` 세션은 `ABORTED`로 자동 교체하고, 진행 중 세션(`ACTIVE` 등)이 있으면 409로 거부해야 한다. |
| 2 | LiveKit 룸 생성 및 토큰 발급 | 세션 전용 LiveKit 룸을 명시적으로 생성하고, 세션 파생 신원(`candidate-{sessionId}`)의 입장 토큰과 접속 정보를 발급해야 한다. |

### 면접 유형 (interview_type)

세션 인프라(생성·음성 대화·재연결·종료·저장)는 유형 무관하게 공유하고, **유형에 따라 갈리는 것은 질문 로직(AI 파이프라인)뿐**이다. 유형은 세션 레코드에 저장되어, 후속 스토리에서 Agent 디스패치 시 전달되어 파이프라인을 분기시킨다.

| 유형 | 성격 | 이력서(resumeId) |
| --- | --- | --- |
| `THIRTY_MIN` | 30분, 이력서 기반 개인 맞춤 면접 (RAG·경험 심화) | **필수** |
| `FIVE_MIN` | 5분, CS 지식 위주 면접 (정답 있는 질문, 별도 평가) | **선택** |

- **세션 생성은 유형 무관하게 동일하다** — 두 유형 모두 본 스토리에서 생성을 지원한다. 유형에 따라 갈리는 것(질문 파이프라인·평가 로직)은 Agent 소관이며 후속 스토리 범위다. 서버는 유형을 검증·저장해 두었다가 디스패치 시 전달할 뿐, 유형의 의미를 해석하지 않는다.
- **유형별로 갈리는 검증은 `resumeId` 필수 여부뿐이다**: `THIRTY_MIN`은 필수, `FIVE_MIN`은 선택. 필수 여부는 애플리케이션 검증이 담당하고 스키마의 `resume_id`는 nullable로 둔다(기능 1 데이터 모델). `FIVE_MIN`에서 `resumeId`가 제출되면 이력서 검증(존재·소유·분석 상태)을 `THIRTY_MIN`과 동일하게 적용한다 — 무효한 참조는 유형과 무관하게 저장하지 않는다.

### 직무 (position)

면접 준비 화면에서 유저는 면접 유형·이력서와 함께 **직무**를 선택한다. 직무는 면접 유형과 독립인 축으로, 세션 레코드에 저장되어 후속 스토리에서 Agent 디스패치 시 전달된다 — 질문 개인화(초기 질문의 직무 반영 등) 입력으로 쓰인다.

| 값 | 의미 |
| --- | --- |
| `BACKEND` | 백엔드 |
| `FRONTEND` | 프론트엔드 |

- 본 스토리의 enum은 위 2종으로 시작하며, 직무 추가는 enum 값 추가로 확장한다(별도 테이블·조회 불필요).
- 서버는 값의 유효성만 검증하고 의미를 해석하지 않는다 — 직무별 분기는 AI 파이프라인(후속 스토리) 소관.

### 세션 상태

세션 수명주기는 면접 도메인 설계의 6개 상태를 따르며, 상태값 집합을 본 스토리에서 정의한다. **본 스토리에서 실제로 발생하는 상태는 `PENDING`(생성)과 `ABORTED`(기존 세션 자동 정리)뿐이고**, 나머지 전이(webhook 기반 `ACTIVE` 전환, 유예·종료 처리)는 후속 스토리에서 도입한다.

| 상태 | 의미 | 성격 | 본 스토리 |
| --- | --- | --- | --- |
| `PENDING` | 룸·토큰 발급 완료, Agent 접속 전 — candidate는 먼저 입장해 있을 수 있다 | 준비 | 생성 시 초기값 |
| `ACTIVE` | Agent 접속 완료, 면접 진행 중 | 활성 | 값만 정의 |
| `INTERRUPTED` | candidate 연결 끊김, 재연결 대기 | 유예 | 값만 정의 |
| `AGENT_LOST` | Agent 이탈, 재dispatch 대기 | 유예 | 값만 정의 |
| `ENDED` | 정상 종료 | terminal | 값만 정의 |
| `ABORTED` | 비정상 종료 | terminal | 자동 정리 시 사용 |

---

## 면접 세션 레코드 생성

### 설명

인증된 유저가 `POST /api/v1/sessions`에 `{ resumeId, interviewType, position }`를 제출하면, 서버는 **요청 형식 검증 → user 행 잠금 → 활성 유저 재확인 → 이력서 검증 → 기존 세션 판정·정리 → 신규 세션 생성 → 룸 생성·토큰 발급(기능 2)** 순으로 처리하고 `201 Created`로 세션 식별자와 접속 정보를 반환해야 한다. 이력서 검증은 반드시 **user 행 잠금 획득 후** 수행한다 — 잠금 전에 검증하면 이력서 사용 중 차단(아래)과의 직렬화가 깨져 검증~생성 사이 TOCTOU가 남는다.

**검증 순서**:

1. 요청 형식 — `interviewType` 누락·미정의 값(`THIRTY_MIN`/`FIVE_MIN` 외 전부), `position` 누락·미정의 값(`BACKEND`/`FRONTEND` 외 전부), `THIRTY_MIN` 유형에서 `resumeId` 누락 → `400 INVALID_INPUT_VALUE`(fieldErrors 포함). `FIVE_MIN` 유형은 `resumeId`를 생략할 수 있다.
2. 이력서 존재 — 미존재 또는 삭제된(soft delete 포함) 이력서 → `404 RESUME_NOT_FOUND`.
3. 이력서 소유 — 타 유저 소유 → `403 RESUME_FORBIDDEN`. (이력서 도메인의 기존 접근 규칙 계승)
4. 이력서 분석 상태 — 면접은 분석이 완전히 끝난 이력서(`EMBEDDED`)로만 시작할 수 있다. 진행 중(`UPLOADED`~`EMBEDDING`) → `409 RESUME_ANALYSIS_IN_PROGRESS`, `FAILED` → `409 RESUME_ANALYSIS_FAILED`. (이력서 도메인의 기존 코드 재사용 — 프론트는 `RESUME_ANALYSIS_FAILED`에서 재분석 유도 동선을 그대로 쓸 수 있다)

2~4의 이력서 검증은 `resumeId`가 있는 요청에 적용한다 — `THIRTY_MIN`은 항상, `FIVE_MIN`은 제출한 경우. `resumeId` 없는 `FIVE_MIN` 요청은 이력서 검증 없이 진행한다.

**기존 세션 정리 (유저당 진행 중 세션 최대 1개)**:

새 세션 생성 시 해당 유저의 기존 세션을 상태별로 처리한다.

- **`PENDING` — 자동 교체**: 조건부 UPDATE로 `ABORTED` 전이(`ended_at` 기록) 후 새 세션을 생성한다. `PENDING`은 Agent 접속 전이라 **실제 면접(대화·transcript)이 시작되지 않은 상태**다 — candidate가 이미 룸에 입장해 있었다면 교체(룸 삭제)로 연결이 끊길 수 있으나 잃는 것은 입장 상태뿐이며, **재시도·중복 요청에서는 마지막으로 생성된 세션을 유효 세션으로 삼는 정책**을 적용한다. 이 유효성은 **서버 기준**이다 — 교체된 세션은 ABORTED가 되고 룸도 삭제되지만, 이미 발급된 구 토큰 자체는 서명 특성상 만료 전까지 폐기할 수 없다(기능 2의 잔여 위험 참조). 본 스토리에는 종료 API·webhook·타임아웃이 없어 `PENDING`이 스스로 끝나지 못하므로, 자동 교체가 좀비 누적 없이 "한 번에 한 면접" 불변식을 유지한다.
- **`ACTIVE`/`INTERRUPTED`/`AGENT_LOST` — `409 SESSION_ALREADY_IN_PROGRESS` 거부**: 진행 중·복귀 대기 면접을 생성 API 재호출이 소리 없이 강제 종료해서는 안 된다. 새 면접은 기존 면접을 명시적으로 종료(후속 스토리의 `/end`)한 뒤에만 시작할 수 있다. 본 스토리에는 이 상태로의 전이가 없어 실제로는 도달하지 않는 경로지만, 상태 머신 도입 시 생성 계약이 바뀌지 않도록 가드를 지금 구현한다.
- **terminal(`ENDED`/`ABORTED`) — no-op**: 건드리지 않는다(이미 terminal이면 no-op — 면접 도메인 공통 가드).

교체로 `ABORTED`된 세션의 LiveKit 룸은 **새 세션 커밋 성공 후 best-effort로 삭제**한다(순서·보상 규칙은 기능 2) — 삭제 실패는 로그만 남기며 응답에 영향을 주지 않는다.

**교체 전이 검증 (잠금 미공유 전이 경로 방어선)**: 교체의 조건부 UPDATE 영향 행 수가 조회된 `PENDING` 수와 다르면 생성을 중단하고 `409 SESSION_ALREADY_IN_PROGRESS`로 거부한다(전체 롤백). user 잠금 하에서는 도달 불가한 상태이므로, 불일치는 잠금을 공유하지 않는 전이 경로가 조회~전이 사이에 상태를 바꿨다는 신호다 — 계속 진행하면 `ACTIVE`와 신규 `PENDING`이 공존할 수 있다. **향후 세션 상태를 전이시키는 모든 경로(webhook 컨슈머·스케줄러 등)는 user 행 잠금을 선행할 것을 권장**하며, 이 검증은 그 계약이 지켜지지 않았을 때의 최후 방어선이다.

**동시성 — user 행 잠금 직렬화**:

- 동일 유저의 세션 생성 트랜잭션은 **user 행 잠금(비관적 잠금)을 직렬화 지점**으로 사용한다. 잠금 없이 "정리 → 생성"을 수행하면 동시 요청 두 건이 서로의 신규 세션을 보지 못해 non-terminal 세션이 2개 남는다. "유저당 진행 중 1개"를 강제할 부분 유니크 인덱스는 JPA 애너테이션으로 표현할 수 없어(`deletion_log`와 동일한 제약) 마이그레이션 도구 도입 시 DB 불변식으로 함께 도입하며(데이터 모델의 스키마 반영 경로 참조), 그때까지 불변식은 잠금이 단독으로 담당한다.
- 잠금 획득 후 유저 활성 여부(`deleted_at IS NULL`)를 재확인한다 — **탈퇴가 선점한 경우** 생성이 `401`로 거부된다(account.md의 유저 상태 경로 관례와 일치). 잠금은 직렬화만 보장하고 순서를 정하지 않으므로, **생성이 선점한 경우**에는 세션이 생성된 뒤 탈퇴가 이어질 수 있다 — 잔존하는 탈퇴 유저 명의 세션의 정리는 E1 연계(범위 밖 — withdraw의 세션 abort)가 담당하며, 그때까지는 JWT 필터가 해당 유저의 접근 자체를 차단한다. 잠금 순서는 user 선행(E1의 user → deletion_log → RT 계약과 충돌 없음 — 본 경로는 user 외 E1 리소스를 잠그지 않는다).

**이력서 사용 중 차단 (이력서 도메인 연계)**:

- 세션이 생성되면 그 이력서는 "진행 중 면접에서 사용 중"이 된다 — 판정 기준은 **해당 이력서를 참조하는 non-terminal 세션의 존재**다. `resume.md`(§4·§5)가 이미 요구하는 사용 중 이력서의 수정·재분석·삭제 차단(`409 RESUME_IN_USE`)은 이 판정 기준이 있어야 성립하므로, **이력서 측 검사 활성화를 본 스토리 범위에 포함**한다. 적용 대상은 이력서 상태를 바꾸는 현존 경로 전부 — **수정·재분석·삭제**(삭제 API는 `resume.md` §5로 구현 완료)다.
- 동시성: 검사만으로는 "세션 생성이 `EMBEDDED` 확인 → 그 사이 삭제·재분석 커밋 → 무효 이력서를 참조한 세션 생성"의 TOCTOU가 남는다. 이력서 상태를 바꾸는 경로(수정·재분석·삭제)도 **user 행 잠금을 직렬화 지점으로 공유**해(모두 본인 이력서 경로이므로 같은 user 행) 세션 생성의 이력서 검증~레코드 생성과 직렬화한다. 잠금 순서는 세션 생성과 동일하게 user 선행.

**데이터 모델 — `interview_session`**:

| 컬럼 | 타입 | 제약 | 설명 |
| --- | --- | --- | --- |
| `id` | bigint | PK | 세션 식별자 |
| `user_id` | bigint | NOT NULL | 소유 유저. FK 제약 없이 id만 보관(도메인 간 결합 최소화 — `resume.user_id`와 동일 방침) |
| `resume_id` | bigint | nullable | 대상 이력서. `THIRTY_MIN` 필수·`FIVE_MIN` 선택 — 필수 여부는 애플리케이션 검증이 담당하고 스키마는 nullable(미제출 `FIVE_MIN`은 NULL). FK 제약 없이 id만 보관 |
| `interview_type` | string | NOT NULL | 면접 유형 enum (`THIRTY_MIN`/`FIVE_MIN`) |
| `position` | string | NOT NULL | 직무 enum (`BACKEND`/`FRONTEND`) |
| `status` | string | NOT NULL | 세션 상태 enum (6종 — Overview) |
| `livekit_room` | string | NOT NULL, UNIQUE | 세션↔룸 매핑. 후속 webhook 스토리가 룸 식별자로 세션을 역추적하는 조회 키 |
| `started_at` | timestamptz | nullable | `ACTIVE` 전환 시각 — 후속 스토리 사용 |
| `ended_at` | timestamptz | nullable | terminal 전환 시각 — 본 스토리는 자동 정리(`ABORTED`) 시 기록 |
| `disconnected_at` | timestamptz | nullable | `INTERRUPTED` 전환 시각 — 후속 스토리 사용 |
| `deleted_at` | timestamptz | nullable | 탈퇴 파기 처리 시각 — E1 파기 연계(후속 스토리) 사용 |

- `global.entity.BaseEntity` 상속(`created_at`/`updated_at`). 후속 스토리에서 쓰는 컬럼(`started_at` 등)도 설계 ERD대로 지금 함께 생성해 스키마 변경 반복을 피한다.
- 인덱스: `(user_id, status)` — 자동 정리와 향후 "진행 중 세션" 판정(이력서 사용 중 검사 등)의 조회 경로.
- 자동 교체의 조건부 UPDATE는 auditing이 적용되지 않는 벌크 쿼리이므로 `updated_at`을 쿼리에서 명시적으로 갱신한다(`deletion_log` 상태 전이와 동일 방침).
- **스키마 반영 경로**: local·테스트는 `ddl-auto: update`·Testcontainers가 엔티티에서 테이블을 생성한다. **dev/prod는 `ddl-auto: validate`라 엔티티 추가만으로 테이블이 생성되지 않는다** — dev/prod 스키마 반영(테이블·인덱스 DDL)은 배포 선행 체크리스트의 마이그레이션 도구 도입(Flyway + baseline DDL — `interview_session` 포함)과 함께 배포 스토리에서 수행하며, 그 시점에 위 non-terminal 부분 유니크 인덱스도 DB 불변식으로 함께 도입한다. 본 스토리의 완료 기준은 local·CI(Testcontainers) 동작이다.
- **배포 의존성 (하드 제약)**: 마이그레이션이 적용되기 전에는 본 기능이 포함된 빌드를 dev/prod에 배포할 수 없다 — `validate`가 테이블 부재로 **기동 자체를 실패**시킨다. 배포 순서는 **DB migration 적용 → 애플리케이션 배포**이고, DDL 작성·적용 주체는 백엔드(배포 스토리 담당)다. 현재 CI는 빌드만 수행하고 develop 자동 배포(CD)는 없어 즉시 위험은 아니지만, 서술만으로는 누락될 수 있으므로 **본 스토리 구현 PR을 병합하기 전에 마이그레이션 도입(Flyway + baseline DDL) 지라 이슈를 생성하고 그 번호를 이 문단에 연결하는 것을 완료 조건으로 한다**.

### 실행 조건

- 유저가 인증되어 있어야 한다(`Authorization: Bearer {accessToken}`). 유저 상태 기반 인가(ACTIVE 유저만 등)는 유저 상태 개념이 없어 두지 않는다 — 인증 필터가 탈퇴 유저를 차단한다(HBB1-256 결정 계승).
- `THIRTY_MIN` 유형은 분석 완료(`EMBEDDED`) 상태의 본인 이력서가 있어야 한다. `FIVE_MIN` 유형은 이력서 없이도 시작할 수 있다.
- LiveKit Cloud 설정(기능 2 실행 조건)이 충족되어야 한다.

### 검증 기준

- `EMBEDDED` 본인 이력서 + `interviewType=THIRTY_MIN` + `position=BACKEND`(또는 `FRONTEND`) 요청 시 `201 Created`와 함께 `data.id`·`data.livekitRoom`·`data.livekitToken`·`data.livekitUrl`이 반환되는지 확인
- 생성된 세션 레코드에 `user_id`·`resume_id`·`interview_type`·`position`·`livekit_room`이 기록되고 `status=PENDING`인지 확인
- `interviewType=FIVE_MIN`은 `resumeId` 없이도 `201`로 생성되고 레코드의 `resume_id`가 NULL인지 확인
- `FIVE_MIN` + 유효한(`EMBEDDED` 본인) `resumeId` 요청 시 `201`로 생성되고 `resume_id`가 기록되는지 확인
- `THIRTY_MIN` 유형에서 `resumeId` 누락, `interviewType`·`position` 누락·미정의 값 요청 시 `400 INVALID_INPUT_VALUE`(fieldErrors 포함)가 반환되는지 확인
- 존재하지 않거나 삭제된 이력서로 요청 시 `404 RESUME_NOT_FOUND`가 반환되는지 확인 (`FIVE_MIN`이 무효한 `resumeId`를 제출한 경우 포함)
- 다른 유저의 이력서로 요청 시 `403 RESUME_FORBIDDEN`이 반환되는지 확인
- 분석 진행 중(`EMBEDDED` 이전) 이력서로 요청 시 `409 RESUME_ANALYSIS_IN_PROGRESS`, `FAILED` 이력서로 요청 시 `409 RESUME_ANALYSIS_FAILED`가 반환되는지 확인
- 거부된 요청(400/403/404/409)에서는 세션 레코드가 생성되지 않고 기존 세션도 정리되지 않는지 확인
- 기존 `PENDING` 세션이 있는 유저가 새 세션을 생성하면 기존 세션이 `ABORTED`로 전이되고(`ended_at` 기록) 새 세션이 생성되는지 확인
- `ACTIVE`·`INTERRUPTED`·`AGENT_LOST` 세션이 있는 유저의 생성 요청이 `409 SESSION_ALREADY_IN_PROGRESS`로 거부되고 기존 세션이 변경되지 않는지 확인 (본 스토리엔 해당 상태로의 전이가 없으므로 상태를 직접 설정해 검증)
- terminal 세션(`ENDED`/`ABORTED`)은 정리가 상태를 변경하지 않는지 확인 (no-op 가드)
- non-terminal 세션이 참조하는 이력서의 수정·재분석·삭제 요청이 `409 RESUME_IN_USE`로 거부되는지, 그 세션이 terminal이 되면 다시 허용되는지 확인
- 세션 생성과 같은 이력서의 삭제(또는 재분석)가 동시에 실행돼도 "생성 성공 + 이력서 변경 거부"나 "이력서 변경 성공 + 생성 거부" 중 하나로만 수렴하고, 무효 이력서를 참조하는 세션이 생기지 않는지 확인 (동시성 통합 테스트 — user 행 잠금 공유)
- 동일 유저의 세션 생성 두 건이 동시에 실행돼도 non-terminal 세션이 최대 1개만 남는지 확인 (동시성 통합 테스트 — user 행 잠금)
- `PENDING` 교체의 전이 건수가 조회 수와 불일치하면 세션을 만들지 않고 `409 SESSION_ALREADY_IN_PROGRESS`로 중단되는지 확인 (단위 테스트 — 잠금 미공유 전이 경로 대비 방어선)
- 탈퇴가 완료된 유저의 생성 요청이 `401`로 거부되는지 확인 (잠금 후 활성 재확인)
- 생성과 탈퇴가 동시에 실행되면 {탈퇴 선점 → 생성 `401`·세션 없음} 또는 {생성 선점 → 세션 생성 후 탈퇴 — 잔존 세션은 E1 연계가 정리} 중 하나로만 수렴하는지 확인 (동시성 통합 테스트 — 잠금은 직렬화만 보장)
- 인증 없이(또는 무효한 AT로) 요청 시 `401 UNAUTHORIZED`가 반환되는지 확인

### 성능 요구사항

- 없음 (응답 시간 SLA는 기능 2 성능 요구사항 참조)

### 인터페이스 요구사항

- 엔드포인트: `POST /api/v1/sessions` (인증 필요), 성공 시 `201 Created` — HBB1-256과 동일 경로의 계약 개정(요청 body 추가, 응답에 `id` 추가)
- 모든 응답은 공통 envelope `ApiResponse<T>`로 감싼다(HTTP 상태코드는 바디에 넣지 않음).
- 에러 코드는 공통: 에러 코드 참조.

`POST /api/v1/sessions` 요청:

```json
{
  "resumeId": 12,
  "interviewType": "THIRTY_MIN",
  "position": "BACKEND"
}
```

`POST /api/v1/sessions` 응답 예시 (`201 Created`):

```json
{
  "success": true,
  "data": {
    "id": 34,
    "livekitToken": "eyJhbG...",
    "livekitUrl": "wss://<project>.livekit.cloud",
    "livekitRoom": "room-3f2a9c1e"
  }
}
```

### 제약사항

다음은 모두 **범위 밖**이며 후속 스토리에서 도입한다:

- webhook 수신·상태 전이(`PENDING → ACTIVE` 등) — 본 스토리의 세션은 자동 정리 전까지 `PENDING`에 머문다
- Agent 디스패치, Resume RAG 조회, transcript
- 세션 조회(`GET /sessions/{id}`)·종료(`/end`)·재연결(`/rejoin`) API
- 준비 타임아웃 스케줄러(stale `PENDING` 정리) — 자동 정리 정책으로 유저당 좀비는 최대 1개로 억제된다
- E1 연계 — 탈퇴 시 non-terminal 세션 즉시 `ABORTED`(withdraw 트랜잭션), 유예 후 파기(`deleted_at` 처리)
- `FIVE_MIN` 유형의 질문 파이프라인·평가 로직·`question_pool` — 세션 생성은 본 스토리가 두 유형 모두 지원하며, 유형별 분기는 Agent 디스패치 이후(후속 스토리) 소관

### 기타 요구사항

- 세션 도메인 패키지(`session`)가 면접 세션을 흡수한다(HBB1-256 결정). 엔티티는 `session.domain`에 두고 테이블명은 설계 ERD와 같은 `interview_session`을 사용한다.
- 면접 도메인 설계 초안의 `400 INVALID_RESUME`는 이력서 도메인 기존 코드 재사용으로 대체한다 — 상태별 구분이 프론트 동선(재분석 유도 등)에 더 유용하다.
- ERD 문서(`docs/erd.md`)는 아직 저장소에 없다 — 스키마를 도입하는 구현 PR에서 신설하고 `interview_session`을 포함해 작성한다.

---

## LiveKit 룸 생성 및 토큰 발급

### 설명

세션 생성 트랜잭션에서 세션 전용 LiveKit 룸을 **명시적으로 생성**하고(RoomService), 그 룸의 입장 토큰을 발급해 응답해야 한다. HBB1-256의 "토큰만 발급, 룸은 첫 접속 시 자동 생성"에서 변경한다 — 후속 스토리의 Agent 디스패치·룸 설정(재연결 timeout 등)이 룸의 사전 존재를 전제하므로, 그 통합 지점을 본 스토리에서 확립한다.

- **룸 이름**: 서버가 요청마다 새로 생성하며(`room-{UUID}` — 기존 규칙 유지) 클라이언트 입력을 받지 않는다. 생성된 이름은 세션 레코드의 `livekit_room`에 저장된다.
- **처리 순서**: [트랜잭션 전] **신규 룸 선생성**(LiveKit API) → [트랜잭션] 기존 `PENDING` 세션 `ABORTED` 처리(기능 1) → 세션 레코드 저장(룸 이름 포함, id 확보) → **커밋(user 잠금·DB 커넥션 해제)** → [커밋 후] 토큰 발급(로컬 서명) → 교체된 기존 세션의 룸 삭제(best-effort) → 응답. **LiveKit 왕복은 트랜잭션·잠금 밖에서 수행한다** — 직렬화가 필요한 구간은 순수 DB 작업뿐이므로, 외부 응답을 기다리는 동안 DB 커넥션과 user 행 잠금을 들지 않는다(LiveKit 지연이 커넥션 풀·동일 유저의 다른 경로에 전파되지 않음). **선생성으로 "세션 행이 존재하면 룸은 이미 존재한다"는 불변식이 성립한다** — 동시 생성이 이 세션을 교체하며 수행하는 룸 삭제가 항상 실효적이라, 커밋과 룸 생성 사이에 교체가 끼어들어 삭제가 헛도는 경합(룸 잔존·교체된 세션에 성공 응답)이 구조적으로 차단된다.
- **실패 시 처리**: ① **룸 선생성 실패(`S002`)** — DB 접촉 전이라 아무 레코드도 남지 않으며, 시도한 룸을 best-effort로 보상 삭제한다(타임아웃은 "룸이 안 만들어졌다"가 아니라 "응답을 못 받았다"일 수 있음; 미생성 룸 삭제 시도는 무해). ② **트랜잭션 실패**(검증 거부·경합 중단) — 전체 롤백 + 선생성 룸 보상 삭제(시끄러운 실패 — 유저 재시도로 복구). ③ **토큰 발급 실패(`S001`, 커밋 후)** — 커밋된 신규 `PENDING`과 확정된 교체는 유지된 채 500으로 응답하고 룸은 보상 삭제한다; 잔존 `PENDING`은 다음 생성의 자동 교체가 수렴시킨다(로컬 서명 실패라 사실상 설정 오류 수준으로 드묾). ④ 검증 거부(403/404/409)될 요청도 룸을 선생성·즉시 삭제하는 왕복 낭비가 있으나, 형식 오류(400)는 컨트롤러 검증이 룸 선생성 전에 차단하고 나머지는 프론트 사전 차단으로 빈도가 낮아 수용한다.
- **기존 세션의 룸은 트랜잭션 안에서 삭제하지 않는다** — 교체(`ABORTED`)가 커밋으로 확정된 후에만 삭제하며, 신규 룸 생성·토큰 발급의 성공 여부와 무관하게 시도한다(교체는 이미 확정된 사실이므로).
- 보상·삭제 실패의 잔여물(고아 신규 룸, 삭제하지 못한 기존 룸)은 로그만 남기고 별도 재시도 큐·outbox를 두지 않는다 — 참가자 없는 룸은 LiveKit empty timeout으로 자연 소멸하는 안전망이 있어 재시도 인프라의 비용이 이득을 넘는다(`resume.md`의 Outbox 반려와 동일 판단).
- **[MVP 잔여 위험]** 교체(`ABORTED`)된 세션의 룸은 삭제되지만 **그 룸을 가리키는 구 토큰은 서명 만료(TTL, 기본 1h) 전까지 폐기할 수 없다**. LiveKit은 미존재 룸 접속 시 룸을 자동 생성하므로, 보관된 구 토큰으로 삭제된 룸이 재생성될 수 있다 — 즉 "마지막 토큰만 유효"는 서버 기준이고 LiveKit 접속 기준으로는 미만료 토큰 전부가 살아 있다. 영향은 본인 명의의 빈 룸 재생성에 한정되고(해당 세션은 terminal이라 후속 webhook 이벤트도 no-op 가드로 무시됨), 창은 TTL로 상한된다. 완화 경로: 재연결 스토리의 TTL 단축(3분 입장 윈도우)이 창을 줄이고, self-host SFU 전환 시 `room.auto_create` 비활성화로 원천 차단을 검토한다. 남용 방어(생성 rate limit 등)는 별도 백로그로 관리한다.
- **[MVP 잔여 위험]** 삭제에 실패한 기존 룸에 candidate가 남아 있으면(`PENDING`은 Agent 접속 전 상태일 뿐 candidate는 입장해 있을 수 있다 — Overview 세션 상태) 참가자가 퇴장할 때까지 empty timeout이 작동하지 않아 룸이 잔존할 수 있다. 발생 조건이 이중 실패(룸 삭제 실패 ∧ 참가자 잔류)로 드물고 참가자 퇴장 즉시 empty timeout이 정리하므로 의도적으로 수용하며, 잔존 룸은 운영에서 LiveKit 콘솔·`lk room list`로 탐지해 수동 삭제한다.
- **참가자 신원(identity)**: `candidate-{sessionId}` 파생 규칙(별도 저장 없음)으로 설정한다. HBB1-256의 `userId` 신원을 대체한다 — 세션 단위 신원이라 유저의 이전 세션과 충돌하지 않고, 후속 재연결 스토리의 "같은 identity 재입장(Agent 재링크)" 조건의 기반이 된다. identity는 여전히 서버가 확정하며 클라이언트 입력을 받지 않는다.
- **권한(grant)·TTL·시크릿 보호**: HBB1-256 규칙을 그대로 계승한다 — 발행은 마이크로 제한(`canPublish=true`, `canPublishSources=["microphone"]`, `canPublishData=false`), 구독 전체 허용(`canSubscribe=true`), TTL은 `livekit.token-ttl`(기본 1h), API Secret은 어디에도 노출 금지.
- **LiveKit API 타임아웃**: 룸 생성·삭제 호출에는 짧은 연결·응답 타임아웃을 명시적으로 설정하고 재시도하지 않는다 — **신규 룸 생성** 실패·타임아웃은 `SESSION_ROOM_CREATE_FAILED`(S002, 500), **룸 삭제**(기존 정리·보상)는 어떤 실패든 로그만 남긴다. 모든 LiveKit 호출이 트랜잭션·잠금 밖이므로 타임아웃은 잠금이 아니라 **요청 응답 지연의 상한**이다. 값은 설정 프로퍼티 `livekit.api-timeout`으로 관리한다(local 기본 3s, dev/prod는 기본값 없는 환경변수 — 기존 `livekit.*` 방침과 동일).
- **룸 세부 설정**(empty timeout, departure timeout, max participants 등)은 본 스토리에서 지정하지 않고 LiveKit 기본값을 쓴다 — 재연결·타임아웃 정책과 함께 후속 스토리에서 확정한다.

### 실행 조건

- LiveKit Cloud 프로젝트의 URL·API Key·API Secret이 서버에 설정되어 있어야 한다(`livekit.*` — HBB1-256 설정 계약 계승, 신규 `livekit.api-timeout`은 인터페이스 요구사항 참조). 미주입 시 부팅 실패(fail-fast).
- 룸 생성은 LiveKit API 왕복이므로 서버가 LiveKit Cloud에 접속 가능해야 한다 — 토큰 서명만 로컬 연산이던 HBB1-256과 달리, LiveKit 장애 시 세션 생성이 실패한다(500).

### 검증 기준

- 세션 생성 시 룸 생성 어댑터가 세션의 `livekit_room` 이름으로 호출되는지 확인 (단위·통합 — 더미 설정, 실제 Cloud 미접속)
- 실제 LiveKit Cloud에서 응답의 `livekitRoom` 룸이 생성되어 있는지 확인 (수동 — `lk room list`, HBB1-256의 수동 검증 체계 계승)
- 발급된 토큰의 `identity`가 `candidate-{생성된 세션 id}`인지 확인 (JWT 디코딩)
- 연속된 두 요청이 서로 다른 세션·서로 다른 룸 이름을 반환하는지 확인
- 토큰 grant(마이크 발행 제한·데이터 채널 차단·구독 허용)와 TTL(`exp`)이 HBB1-256 검증 기준대로 유지되는지 확인 (기존 테스트 회귀 유지)
- 룸 선생성 실패 시(어댑터 예외·타임아웃) `500 SESSION_ROOM_CREATE_FAILED`가 반환되고 **DB에 아무 변화도 없으며**(세션 미생성·기존 `PENDING` 유지), 시도한 룸에 보상 삭제가 시도되는지 확인 (타임아웃은 룸이 만들어졌을 수 있음)
- 토큰 발급 실패 시 `500 SESSION_TOKEN_ISSUE_FAILED`가 반환되고 **커밋된 신규 `PENDING`이 남으며**, 이미 생성된 신규 룸에 보상 삭제가 시도되는지 확인
- 실패로 잔존한 `PENDING`(토큰 실패 등)이 같은 유저의 재시도에서 자동 교체되어 정상 생성으로 수렴하는지 확인 (실패 잔여물의 수렴 경로)
- 검증 거부(403/404/409)로 롤백되는 경우 DB는 무변화이고 선생성된 룸이 보상 삭제되는지, 형식 오류(400)는 룸 선생성 자체가 일어나지 않는지 확인
- 정상 생성 시 교체된 기존 세션의 룸에 삭제 어댑터가 커밋 후 호출되는지, 삭제가 실패해도 응답이 정상인지(로그만 남김) 확인
- 신규 룸 보상 삭제가 실패해도 원래의 에러 응답(S001/S002)이 유지되는지 확인
- 응답 body·서버 로그 어디에도 LiveKit API Secret이 노출되지 않는지 확인

### 성능 요구사항

- HBB1-256의 "100ms 이내(로컬 연산)" 요구는 폐기한다 — 룸 명시 생성으로 LiveKit Cloud API 왕복 1회가 추가되어 성립하지 않는다. 대체 응답 시간 SLA는 별도 정의하지 않는다(추후 실측 후 정의).

### 인터페이스 요구사항

- 외부 시스템: LiveKit Cloud — 룸 생성·삭제(Server API 왕복), 룸 접속·오디오 중계(WebRTC). 토큰 발급은 로컬 서명.
- 설정 계약: `livekit.url`·`livekit.api-key`·`livekit.api-secret`·`livekit.token-ttl` — HBB1-256 계약 그대로(환경변수 주입, local은 `.env`, dev/prod는 기본값 없는 placeholder). 룸 생성·삭제용 RoomService도 동일 자격증명을 사용한다. 신규 설정은 `livekit.api-timeout`뿐이다 — local은 `${LIVEKIT_API_TIMEOUT:3s}`(기본 3s), dev/prod는 기본값 없는 placeholder로 두어 배포 환경변수 매니페스트에 추가된다(기존 `livekit.*` 값들과 동일 방침).
- 테스트 설정 계약: HBB1-256 계승 — 일반 테스트는 더미 URL/Key/Secret으로 Context를 부팅하고 룸 생성·삭제 어댑터는 스텁·모킹한다(더미 값으로 Cloud 왕복 불가). 실제 Cloud 연동(룸 생성 확인·접속)은 JUnit으로 자동화하지 않고 실제 `LIVEKIT_*` 주입 상태에서 수동 검증한다.

### 제약사항

- LiveKit 서버는 개발 단계 LiveKit Cloud, prod 자체 호스팅 전환 예정(HBB1-256 결정 유지 — 룸 생성 코드 경로도 설정값 교체로 이행 가능).
- 오디오만 대상으로 한다(비디오·화면공유 범위 밖).
- 룸 이름·identity는 서버가 결정하며 클라이언트 입력을 받지 않는다.

### 기타 요구사항

- 룸 생성·삭제 등 RoomService 호출도 토큰 서명과 같은 벤더 어댑터(`global.livekit`)에 격리한다 — 도메인 서비스는 "룸 준비·정리" 추상에만 의존하고 LiveKit SDK를 직접 알지 않는다(HBB1-256의 어댑터 구조 확장).
- **TTL 후속 조정 예고**: 면접 도메인 설계는 토큰 TTL을 짧은 입장 윈도우(예: 3분)로 줄이는 방향이나, 재발급 경로(`/rejoin`)가 없는 본 스토리에서 단축하면 새로고침한 유저가 복귀할 수 없다. 본 스토리는 기본 1h를 유지하고 재연결 스토리에서 함께 단축한다.

---

## 공통: 에러 코드

세션 도메인 에러는 `ErrorCode` enum에 접두사 **`S`** + 3자리로 정의한다(HBB1-256에서 확립).

| 코드 | 이름 | HTTP | 상황 |
| --- | --- | --- | --- |
| S001 | SESSION_TOKEN_ISSUE_FAILED | 500 | 토큰 서명 등 발급 처리 실패 (기존 — HBB1-256) |
| S002 | SESSION_ROOM_CREATE_FAILED | 500 | LiveKit 룸 생성 실패·타임아웃 (신설) |
| S003 | SESSION_ALREADY_IN_PROGRESS | 409 | 진행 중(`ACTIVE`/`INTERRUPTED`/`AGENT_LOST`) 세션이 있는 상태의 생성 요청 (신설) |

- 이력서 검증 실패는 이력서 도메인의 기존 코드를 재사용한다: `RESUME_NOT_FOUND`(404, R008) · `RESUME_FORBIDDEN`(403, R009) · `RESUME_ANALYSIS_IN_PROGRESS`(409, R010) · `RESUME_ANALYSIS_FAILED`(409, R011).
- 이력서 사용 중 차단은 이력서 도메인에 `RESUME_IN_USE`(409, R013)를 **신설**해 사용한다 — `resume.md`가 정의만 해둔 코드를 본 스토리가 도입·이행한다.
- 요청 형식 오류는 공통 `INVALID_INPUT_VALUE`(400, C002, fieldErrors 포함), 인증 실패는 공통 `UNAUTHORIZED`(401, C005)를 따른다.
