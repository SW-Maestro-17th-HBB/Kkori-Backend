# 리포트 (Report)

> **User Story**: HBB1-21 — 나는 사용자로서 리포트에서 과거 면접을 복기하고 개선점을 파악할 수 있다 / HBB1-22 — 나는 사용자로서 면접 복기를 위해 리포트를 불러올 수 있다

## Overview

리포트 도메인은 면접 세션이 끝나면 질문-답변 기록을 AI로 평가해 종합 리포트(총평·영역별 점수·답변별 피드백·약점 태그·개선 과제)를 비동기로 생성하고, 사용자가 목록·상세·질문-답변 타임라인·통계로 과거 면접을 복기하는 기능 영역이다. **생성 수명주기 전체 — 리포트(PENDING) 로우·Job·스냅샷 생성부터 평가·저장·COMPLETED 전이까지 — 는 Python AI Worker가 세션 도메인이 발행하는 이벤트(Redis Stream)를 직접 소비해 수행한다.** Spring(리포트 도메인)은 조회 API·접근 권한·재생성 API를 담당하고, 상태 이벤트를 소비해 SSE로 사용자에게 실시간 전달한다.

핵심 데이터 흐름: `세션 정상 종료 → 세션 종료 이벤트 발행(세션 도메인) → Worker가 직접 소비: 리포트(PENDING)·Job·스냅샷 생성 → 1단계: transcripts·이력서 근거로 텍스트 평가·저장 → (음성 업로드 이벤트 소비) 2단계: 녹음 파일(S3 호환)로 전달력 산출 → 리포트 COMPLETED → 상태 이벤트 → Spring이 SSE push`

Worker의 평가 입력은 해당 세션의 `INTERVIEW_TRANSCRIPTS`(질문-답변 기록)와 면접에 사용한 이력서의 구조화 데이터·청크이며, Worker가 DB에서 직접 읽는다(생성 요청 메시지는 포인터만 전달). 평가에 인용한 이력서 근거는 답변별 피드백의 `resume_context`로 함께 저장되어, 이후 이력서가 삭제되어도 리포트는 자기완결적으로 성립한다.

### 평가 축 (4축, 전달력은 조건부)

| 축 | 컬럼 | 평가 근거 | NULL 허용 |
| --- | --- | --- | --- |
| 논리성 | logic_score | STT 텍스트 (LLM) | 아니오 |
| 구체성 | specificity_score | STT 텍스트 (LLM) | 아니오 |
| 기술 정확성 | technical_accuracy_score | STT 텍스트 (LLM) + 이력서 근거 | 아니오 |
| 전달력 (속도·간결성) | delivery_score | **세션 오디오 녹음 (도입 시)** | **예 — 오디오가 없으면 미평가(null)** |

- 점수는 jsonb가 아닌 **개별 컬럼**으로 저장한다 — 통계 집계(§6 축별 평균)가 SQL 평균으로 가능해야 하고, 0~100 CHECK·NULL 제약을 컬럼 단위로 선언하기 위함. **저장 위치는 다음이 전부다: 텍스트 3축은 `REPORT_SCORES`(세션 단위 영역 점수)와 `REPORT_FEEDBACKS`(답변 단위 점수), 전달력(delivery_score)과 종합(overall_score)은 `REPORTS`.** 조회 API는 REPORT_SCORES와 REPORTS를 합쳐 `scores` 객체로 조립한다(§3·§6).
- **전달력은 세션 단위로만 측정한다 (2026-07-23 확정)**: 세션 오디오 전체의 말 속도·멈춤에서 delivery_score 하나만 산출한다. 답변별 전달력 점수는 소비하는 화면이 없고, 산출하려면 오디오에서 질문별 답변 구간을 잘라내야 하는 비용만 생기므로 두지 않는다 — **`REPORT_FEEDBACKS`에는 delivery_score 컬럼이 없다(텍스트 3축만)**.
- **delivery_score의 저장 위치는 `REPORTS` 컬럼이다**: REPORT_SCORES 행은 텍스트 분석(1단계)이 만드는데, 음성 분석(2단계)이 먼저 끝날 수도 있다. REPORTS 행은 PENDING 시점부터 항상 존재하므로 여기에 두면 어느 단계가 먼저 끝나든 단순 UPDATE로 저장할 수 있다. 세션 단위 단일 값이라 overall_score와 같은 테이블에 있는 것이 의미상으로도 맞다. (REPORT_SCORES는 텍스트 3축 전용)
- **평가는 2단계 파이프라인이다 (2026-07-23 확정)**: 텍스트 분석(LLM — 3축 평가·총평·태그·과제)과 음성 분석(LLM 무관 — VAD+결정적 수식으로 delivery_score)을 분리 수행하고, **둘 다 끝나야 COMPLETED**다. 텍스트 분석은 세션 종료 직후 시작하고, 음성 분석은 녹음 파일이 준비된 뒤 수행한다. LLM 호출은 텍스트 단계 1회뿐이라 단계 분리로 인한 LLM 비용 증가는 없다.
- **음성 분석이 늦으면 기다리지 않고 완성한다**: 오디오가 **유예 시간 내 준비되지 않으면 텍스트 3축만으로 COMPLETED 처리한다(delivery_score null)** — 녹음 사고가 리포트를 영구 미완성에 머물게 하거나 복기 기록을 잃게 하지 않는다. 총평·약점 태그·개선 과제는 텍스트 단계 소관으로 한정하므로, 음성 단계가 늦거나 생략되어도 달라지는 것은 delivery_score(와 그에 따른 overall) 하나뿐이다.
- **녹음 파일은 전 환경 S3 호환 저장소를 경유한다 (2026-07-23 확정)**: 자체 호스팅 LiveKit의 Egress(사용자 트랙 분리 녹음)가 로컬은 MinIO, prod는 실제 S3의 전용 prefix(예: `recordings/`)에 업로드하고, Worker는 전 환경 동일하게 S3 클라이언트로 다운로드한다(이력서 PDF와 같은 패턴 — 코드 경로 단일). 로컬 파일·볼륨 공유 방식은 호스트 결합·유실·파기 통제 문제로 채택하지 않는다. **녹음 파일의 수명 (2026-07-23 확정)**: 객체 키는 `recordings/{sessionId}`로 세션과 1:1로 연결되며, **음성 분석이 끝나면 Worker가 즉시 삭제한다.** 삭제를 놓친 객체는 S3 수명주기 규칙(7일 자동 삭제)이 안전망으로 정리한다.

### 생성 상태

| 상태 | 의미 |
| --- | --- |
| PENDING | 세션 종료로 리포트가 생성되어 평가 대기 중 |
| PROCESSING | Worker가 생성 요청을 수신하여 평가 진행 중 (텍스트 분석 → 음성 분석 2단계 포함) |
| COMPLETED | 평가 완료, 조회 가능한 최종 상태 |
| FAILED | 생성 실패 (Worker 재시도 소진 또는 재전달 임계 초과) |

- 상태의 진실 원천은 `REPORTS.status`다(사용자 노출 상태). `REPORT_GENERATION_JOBS`는 시도 추적·운영 관찰용(retry_count, error_message, 시각 필드)이며 사용자 노출 판단에 쓰지 않는다.
- 생성 수명주기(로우 생성 → 상태 전이 → 산출물 저장)는 전부 Worker가 수행하고, 단계 진입 시 상태를 갱신하며 상태 이벤트를 발행한다. Spring은 상태 이벤트를 소비해 SSE로 중계하고, 재생성 요청 발행만 담당한다.
- `retry_count`·Job의 진행 시각은 Worker가 기록하고 서버는 읽기 전용(이력서 `retry_count` 선례와 동일).

### 기능 요구사항

| No. | Function | Description |
| --- | --- | --- |
| 1 | 리포트 생성 트리거 및 비동기 생성 | 세션 정상 종료 시 리포트를 생성하고 비동기 평가 파이프라인을 시작한다. 실패 시 재생성을 지원한다. |
| 2 | 리포트 목록 조회 | 본인 리포트 목록을 스냅샷 기반으로 조회한다(정렬·필터 지원). |
| 3 | 리포트 상세 조회 | 총평·영역별 점수·약점 태그 요약·개선 과제·AI 분석 한계 안내를 조회한다. |
| 4 | 질문-답변 타임라인 조회 | 세션의 질문-답변 기록을 질문 단위로, 답변별 평가와 함께 조회한다. |
| 5 | 생성 상태 추적 | 생성 진행 상태를 REST 조회 및 SSE 실시간 이벤트로 제공한다. |
| 6 | 리포트 통계 조회 | 완료된 리포트 전체를 집계한 KPI·추이·축별 평균·약점 분포를 제공한다. |

---

## 1. 리포트 생성 트리거 및 비동기 생성

### 설명

세션 도메인은 면접 세션이 **정상 종료**(`ended_at` 확정)될 때 Redis Stream에 세션 종료 이벤트를 발행하고, **Python Worker가 이 스트림을 직접 소비해** 리포트(PENDING)·Job 레코드를 생성하고 스냅샷 컬럼을 채운 뒤 곧바로 텍스트 분석을 시작한다 — 중간에 Spring을 거치는 전달 단계가 없다. 사용자가 호출하는 생성 API는 없다 — 생성은 세션 종료 이벤트가 트리거하고, 사용자 개입은 FAILED 재생성뿐이다.

- **트리거 범위**: 세션 종료 이벤트는 정상 종료 세션에 대해서만 발행된다(발행 조건은 발행자인 세션 도메인 소관 — 중도 이탈 세션의 종료 확정 정책은 면접 도메인 확정 후 재검토). Worker는 소비 시점에 세션 대본(`INTERVIEW_TRANSCRIPTS` — 세션당 1행, 발화 배열 JSON)을 확인해 **사용자 발화가 0건이면 리포트를 생성하지 않고 ACK만 한다**(평가할 대상이 없음). **대본 행 자체가 없으면 스킵이 아니라 처리 실패로 취급**해 ACK하지 않는다 — "아직 저장 전"과 "답변 없음"을 구별하기 위함이며, 대본 저장이 세션 종료 이벤트 발행보다 선행된다는 순서 보장은 면접 도메인에 요구사항으로 전달한다(**미정**).
- **시작 신호는 이벤트 2개다 (2026-07-23 확정)**: 세션 도메인은 ①세션이 정상 종료될 때 **세션 종료 이벤트**를, ②녹음 파일이 S3에 올라간 뒤 **음성 업로드 이벤트**를 각각 발행하며, **둘 다 Worker가 직접 소비한다.** 텍스트 분석은 ①로 바로 시작하므로 리포트 생성이 녹음 업로드를 기다리지 않고, 음성 분석은 ②가 와야 시작한다.
- **세션:리포트 = 1:1, 소비 멱등**: 같은 세션에 리포트는 1개만 존재한다. 세션 종료 이벤트 스트림은 Consumer Group 기반 **at-least-once**라 동일 이벤트가 중복 전달될 수 있으므로 Worker의 소비 처리는 **sessionId 기준 멱등**이어야 한다 — `interview_session_id` 유니크 제약으로 중복 생성을 방어하고, 이미 완결(COMPLETED/FAILED)된 리포트가 있으면 아무것도 하지 않고 ACK한다.
- **스냅샷 컬럼**: Worker가 리포트 로우 생성(PENDING) 시점에 목록 조회용 값을 복사한다 — `resume_file_name_snapshot`(이력서 원본 파일명), `interview_type_snapshot`(면접 유형), `interview_duration_minutes`(`ended_at - started_at`, 분). 이후 원본이 변경·삭제되어도 목록 표시는 스냅샷으로 성립한다.
- **Worker 파이프라인 (2단계)**:
  - **1단계 텍스트 분석** (세션 종료 이벤트로 시작): 대본 확인 → **리포트(PENDING)·Job·스냅샷 생성** → transcripts·이력서 데이터 로드 → 답변별 평가(텍스트 3축 점수 + 피드백 + 약점 태그 + **개선 과제** + 이력서 근거) → 영역 점수 산정 → 총평 생성 → 약점 태그 요약 집계 → 산출물을 한 트랜잭션으로 저장하고 `text_analyzed_at`을 기록. 재시도하면 1단계를 처음부터 다시 수행한다(저장 전 기존 산출물이 있으면 지우고 다시 저장).
  - **2단계 음성 분석** (음성 업로드 이벤트로 시작): 녹음 파일 다운로드 → 사용자 음성의 말 속도·멈춤 계산 → `REPORTS.delivery_score` 갱신 + `audio_analyzed_at` 기록.
  - **두 단계는 서로 다른 워커가 동시에 처리해도 된다** — 쓰는 컬럼이 겹치지 않아 부딪힐 일이 없고, 결과는 DB에 각자 저장되면서 자연히 합쳐진다. 같은 세션의 두 이벤트를 한 워커에 몰아줄 필요도, 결과를 따로 합치는 절차도 없다.
  - **완성 판정**: "두 완료 시각이 모두 채워진 경우에만 COMPLETED로 바꾼다"는 조건을 UPDATE 문 자체에 넣는다. 먼저 끝난 쪽은 조건이 안 맞아 그냥 지나가고, **나중에 끝난 쪽이 overall_score 계산과 COMPLETED 전환까지 수행한다.** 거의 동시에 끝나도 같은 행을 고치는 UPDATE는 DB가 한 번에 하나씩만 처리하므로 추가 장치가 필요 없다.
  - 음성 업로드 이벤트 처리 시 리포트 로우가 없으면 대본을 확인한다: **사용자 발화가 0건이면 "리포트 없음"이 정상이므로 그대로 ACK**하고(무응답 세션의 음성 이벤트가 영구 재전달되는 것 방지), 발화가 있는데 로우가 없으면(소비 지연) ACK하지 않고 재전달을 기다린다.
  - **이미 COMPLETED된 리포트에 뒤늦게 도착한 음성 업로드 이벤트는 무시하고 ACK만 한다** (음성 없이 완성 처리된 경우 포함 — 전달력은 빈 값 유지). 사용자에게 이미 보여준 점수는 사후에 바뀌지 않는다. 이를 위해 2단계의 delivery 갱신·완성 전환 UPDATE는 **COMPLETED가 아닌 리포트에만** 적용되도록 조건을 건다.
- **점수 체계 (백엔드 확정, Worker 이행)**: 모든 점수는 0~100 정수. 텍스트 3축의 영역 점수(REPORT_SCORES) = 답변별 해당 축 점수의 평균(반올림). **전달력은 예외로 답변별 평균이 아니라 세션 오디오에서 직접 산출**한다(결정적 수식 — 지표·매핑 기준은 평가 기준 설계에서 확정). `overall_score` = **평가된 축**(전달력 미평가 시 3축, 평가 시 4축) 점수의 평균(반올림). **산식은 결정적이며 본 문서가 정의 원천**이다 — LLM은 답변별 텍스트 3축 점수만 산정하고 집계는 산식을 따른다.
- **약점 태그**: `weakness_tags`는 자유 텍스트가 아닌 **고정 어휘집의 코드 문자열**이다(집계·후속 면접 질문 반영이 가능하도록). 어휘집의 내용·선정은 Worker PRD 소관이며 백엔드는 코드를 불투명 문자열로 취급한다. `REPORTS.weakness_tag_summary`는 전체 답변의 태그 빈도 상위 3개(`[{tag, count}]`)로 Worker가 저장 시 계산한다.
- **개선 과제는 답변 단위로 저장한다** (ERD 원안 유지 — `REPORT_FEEDBACKS.improvement_tasks`, `[{title, description}]`): Worker가 답변별 평가 시 해당 답변의 개선 과제를 함께 생성한다. 상세 화면의 "개선 과제 추천" 영역은 상세 API가 답변별 과제를 조회 시점에 모아 반환한다(§3) — 리포트 단위 별도 저장은 하지 않는다.
- **발행은 Outbox로 한 트랜잭션이다 (2026-07-23 확정 — 이력서 도메인과 함께 도입)**: 발행할 메시지를 스트림에 직접 쏘는 대신 **같은 DB 트랜잭션 안의 Outbox 테이블에 기록**하고, 릴레이가 이를 읽어 실제 발행한다 — "DB 커밋은 됐는데 발행만 실패해서 이벤트가 증발하는" 틈을 원천적으로 없앤다. 전환 대상은 모든 발행 지점 — 이력서 업로드(이력서 도메인), 세션 종료·음성 업로드(세션 도메인), **재생성 요청(Spring 리포트 도메인의 유일한 발행 지점)**. 릴레이는 같은 메시지를 두 번 보낼 수 있으므로 각 소비자의 멱등 규칙(sessionId·reportId 기준)이 전제 조건이며, 릴레이 구현·기록 정리 정책은 공통 인프라 설계 소관.
- **발행 측(세션 도메인) 요구사항** — 면접 도메인 설계에 전달: 세션 종료 기록과 이벤트의 Outbox 기록을 한 트랜잭션으로 묶는다. 웹훅 처리 자체가 실패하면 5xx 응답으로 LiveKit 웹훅 재전송을 유도하고, `WEBHOOK_EVENTS` 멱등 기록은 처리 성공 시에만 `processed_at`을 남겨 재전송이 안전하게 재처리되게 한다.
- **조정 배치 (최후 안전망, Worker 소유)**: "정상 종료(`ended_at` 존재) + **리포트 로우 부재** + 유예 시간 경과" 세션을 주기적으로 찾아 세션 종료 이벤트 소비와 동일한 처리를 수행한다. **판정 기준은 로우 부재이며, 상태(COMPLETED 여부) 기준을 쓰지 않는다** — 상태 기준이면 오래 걸리는 생성·FAILED를 재생성하는 오탐이 생긴다. Worker는 소비 초반에 PENDING 로우를 만들므로 생성 소요 시간은 판정에 영향이 없다. 지연 소비와의 레이스는 `interview_session_id` 유니크가 무해화한다. **PENDING/PROCESSING 정체는 메시지 회수(XAUTOCLAIM) 소관, FAILED 복구는 사용자의 재생성 API 소관** — 배치는 이들을 건드리지 않는다. 단 하나의 예외로, **텍스트 분석은 끝났는데 음성 분석이 유예 시간을 넘긴 리포트를 delivery null로 완성(COMPLETED) 처리하는 규칙**도 이 배치가 함께 수행한다. Outbox 도입 후에도 버그·운영 사고까지 잡는 안전망으로 유지한다.
- **실패·재시도**: Worker가 소비하는 스트림(세션 종료·음성 업로드·재생성 요청)은 모두 Consumer Group 기반 at-least-once이며, 처리는 sessionId(또는 reportId) 기준 멱등이어야 한다. **모든 세션 종료 이벤트는 반드시 COMPLETED/FAILED로 끝나거나 스킵 ACK된다** — Worker는 ACK 없이 방치된 메시지를 회수(XAUTOCLAIM)해 재평가하고, 재전달 횟수가 임계를 넘으면 FAILED로 종결한다(`failed_reason` 기록). idle time·임계값·내부 재시도 수치는 Worker PRD 소관. 이력서와 달리 트리거가 시스템이므로 **자동 재시도를 허용**하되, FAILED 종결 후의 복구 주체는 사용자다(아래 재생성).
- **FAILED 재생성**(`POST /api/v1/reports/{reportId}/retry`): **본인 리포트에만** 가능하다(타인 리포트 403 REPORT_FORBIDDEN, 없는 리포트 404 REPORT_NOT_FOUND). FAILED 리포트에 한해 Spring이 기존 산출물 무효화·PENDING 전환·**재생성 요청**의 Outbox 기록을 **한 트랜잭션으로** 처리하고, Worker가 재생성 요청을 소비해 1단계부터 다시 수행한다. 트랜잭션 도중 실패하면 전부 되돌아가 FAILED가 유지되므로, 발행만 실패해서 다시 시도할 길이 없는 PENDING에 갇히는 경우가 없다. Job의 `requested_at`을 갱신한다(`retry_count`는 Worker 소관 — 서버는 초기화하지 않는다).

### 실행 조건

- Worker의 이벤트 컨슈머가 동작 중이어야 생성이 시작된다(컨슈머 정지 중의 이벤트는 스트림에 남아 재기동 후 처리된다).
- 재생성 API에서 처리 트랜잭션 실패 시 500(REPORT_GENERATION_REQUEST_FAILED)으로 응답하며, 리포트는 FAILED로 유지된다(사용자가 다시 시도 가능).
- Worker가 transcripts·이력서 데이터를 읽고 REPORTS·REPORT_SCORES·REPORT_FEEDBACKS·REPORT_GENERATION_JOBS에 쓸 수 있어야 한다.

### 검증 기준

- Worker가 세션 종료 이벤트를 소비하면 리포트(PENDING)·Job 레코드가 생성되고 텍스트 분석이 시작되는지 확인
- 대본에 사용자 발화가 0건인 세션의 이벤트는 리포트 생성 없이 ACK만 되는지 확인
- 대본 행이 아직 없는 세션의 이벤트는 ACK되지 않고 재전달로 이월되는지 확인
- 같은 세션의 종료 이벤트가 중복 전달되어도 리포트가 1개만 존재하는지 확인 (`interview_session_id` 유니크)
- 소비 처리 도중 실패 시 ACK되지 않고 재전달로 복구되는지 확인
- 정상 종료 후 유예 시간이 지나도 리포트 로우가 없는 세션을 조정 배치가 감지해 세션 종료 이벤트 소비와 동일한 처리(생성·분석)를 수행하는지 확인
- 조정 배치가 PENDING/PROCESSING/FAILED 리포트가 존재하는 세션을 건드리지 않는지 확인 (로우 부재 기준)
- 조정 배치와 지연된 이벤트 소비가 겹쳐도 리포트가 1개만 생성되는지 확인
- 리포트 생성 시점에 스냅샷 컬럼(파일명·면접 유형·소요 시간)이 채워지는지 확인
- 생성 완료 시 REPORT_SCORES 1건과 답변 수만큼의 REPORT_FEEDBACKS가 존재하고 상태가 COMPLETED가 되는지 확인
- 영역 점수·overall_score가 산식(텍스트 3축은 답변별 평균, 전달력은 세션 측정 → overall은 평가된 축 평균, 반올림)과 일치하는지 확인
- 전달력 미평가 리포트에서 delivery_score가 null이고 overall_score가 3축 평균으로 계산되는지 확인
- 텍스트 분석만 끝난 상태에서는 COMPLETED가 아닌지 확인
- 텍스트·음성 어느 쪽이 먼저 끝나든, 나중에 끝난 쪽이 처리된 뒤에만 COMPLETED가 되는지 확인
- 음성 업로드 이벤트가 리포트 로우 생성보다 먼저 도착하면(발화 있는 세션) ACK되지 않고 재전달되는지 확인
- 사용자 발화 0건 세션의 음성 업로드 이벤트는 리포트 없이 ACK되는지 확인
- 텍스트 분석 완료 후 음성 분석이 유예 시간을 넘기면 delivery null로 COMPLETED되는지 확인
- 음성 없이 완성 처리된 리포트에 뒤늦게 음성 업로드 이벤트가 도착하면 무시(ACK만)되고 점수가 바뀌지 않는지 확인
- 음성 분석 완료 후 녹음 파일이 삭제되는지 확인
- weakness_tag_summary가 답변별 태그 빈도 상위 3개와 일치하는지 확인
- 답변별 REPORT_FEEDBACKS에 개선 과제(improvement_tasks)가 저장되는지 확인
- FAILED 리포트에 재생성 요청 시 상태가 PENDING으로 되돌아가고 재생성 요청이 Outbox로 기록·발행되어 Worker가 1단계부터 다시 수행하는지 확인
- FAILED가 아닌 리포트에 재생성 요청 시 409(진행 중이면 REPORT_GENERATION_IN_PROGRESS, COMPLETED면 REPORT_RETRY_NOT_ALLOWED)로 거부되는지 확인
- 다른 사용자의 리포트에 재생성 요청 시 403(REPORT_FORBIDDEN)이 반환되는지 확인
- 재생성 처리 도중 실패하면 리포트가 FAILED로 유지되는지 확인 (다시 시도할 길 없는 PENDING에 갇히지 않음)
- 재생성 후 완료 시 이전 산출물(점수·피드백)이 새 결과로 대체되는지 확인 (중복 누적 없음)

### 성능 요구사항

- 없음 (생성 소요 시간 SLA는 추후 정의)

### 인터페이스 요구사항

- `POST /api/v1/reports/{reportId}/retry` (바디 없음) — FAILED 재생성. 그 외 생성은 API가 아닌 세션 종료 이벤트 소비로 트리거
- Redis Stream 4종. **스트림별 메시지 스키마의 정의 원천은 계약 record**:
  - **세션 종료 이벤트 스트림(Worker가 소비)** — 발행자는 세션 도메인. `SessionEndedMessage`(sessionId, userId — 필드 확정은 면접 도메인과 합의, **미정**)
  - **음성 업로드 이벤트 스트림(Worker가 소비)** — 발행자는 세션 도메인(녹음 파일 S3 업로드 완료 시). `SessionAudioUploadedMessage`(sessionId, bucket, objectKey — 필드 확정은 면접 도메인과 합의, **미정**)
  - **재생성 요청 스트림(Spring이 발행, Worker가 소비)** — `ReportRegenerationRequestedMessage`(reportId, sessionId, userId — Worker가 DB에서 입력을 직접 읽으므로 포인터만)
  - **상태 이벤트 스트림(Worker가 발행, Spring이 소비)** — `ReportStatusChangedMessage`(reportId, userId, status, message — userId는 SSE 라우팅 근거로 Worker가 에코)
  - Python Worker는 4개 계약 record 전부를 계약 문서로 참조
- **jsonb 산출물의 스키마 정의 원천도 계약 record** (이력서 `StructuredData` 선례) — `weakness_tags`(코드 문자열 배열), `weakness_tag_summary`(`[{tag, count}]`), `improvement_tasks`(답변 단위, `[{title, description}]`). `resume_context`는 Worker 소관의 자유 구조로 백엔드는 불투명하게 취급한다(계약 없음).
- 응답은 공통 엔벨로프 `ApiResponse<T>`를 따른다 (이하 모든 REST API 동일)

### 제약사항

- 재생성은 FAILED 상태에서만 가능
- 리포트 삭제 API는 MVP에서 제공하지 않는다 — 리포트는 회원 탈퇴 파기로만 제거된다
- 조정 배치의 실행 주기·유예 시간: **미정** (소비 지연과의 레이스를 줄일 만큼만 두면 되며, 리포트 생성 소요 시간과는 무관)

### 기타 요구사항

- **평가·조인 단위는 질문 단위(questionNumber)로 확정 (2026-07-23)** — 대본이 세션당 1행 JSON으로 변경되면서 발화 행 FK가 성립하지 않으므로, `REPORT_FEEDBACKS.transcript_id`는 **`question_number`(int) 컬럼으로 대체**한다(ERD 갱신 필요). Worker는 질문당 피드백 1건을 question_number와 함께 저장하고, 타임라인(§4)은 같은 키로 대본과 평가를 결합한다.
- **대본 JSON의 스키마는 면접 도메인·Worker 소유의 계약** — 발화 요소: {questionNumber, speaker, content, questionType, spokenAt}. 리포트 도메인은 읽기 전용 소비자로서 questionNumber는 정수, speaker·questionType은 고정 값 집합(예: INTERVIEWER/USER, MAIN/TAIL), spokenAt은 ISO-8601 타임스탬프를 전제한다 — 값 집합·직렬화 형식 확정은 면접 도메인·Worker와 합의(**미정**).
- **`interview_type_snapshot`의 원천인 세션의 면접 유형 컬럼이 현재 ERD의 `INTERVIEW_SESSIONS`에 없다** — 면접 유형 선택(HBB1-18)을 설계하는 면접 도메인에서 도입 예정. 도입 전까지 스냅샷 채움 규칙은 미정.
- **녹음·음성 분석의 남은 확정 사항** — 자체 호스팅 LiveKit의 Egress 설정(사용자 트랙 분리 녹음), 음성 업로드 이벤트의 발행 시점·필드(세션 도메인 소관), 음성 유예 시간 값, audio_usage 동의와 녹음의 연결. 확정 전까지 Worker는 텍스트 3축으로만 동작하고 delivery_score는 null이다.
- **계약 변경 권한은 백엔드** — 스트림·상태·점수 산식·jsonb 계약이 양 repo에서 어긋나면 본 문서와 계약 record가 우선한다(이력서와 동일). Worker PRD는 계약 전문의 자기완결 사본을 유지한다.
- **알려진 한계 (수용)**: 세션 종료~COMPLETED 사이에 사용자가 해당 이력서의 파싱 결과 수정·재분석을 실행하면 평가가 최신 파싱 결과 기준이 될 수 있다. 파싱 결과 수정은 면접 전 보정 단계의 행동이라 이 창구간에 겹칠 확률이 낮고 피해도 경미하므로 잠금 없이 수용한다.
- **회원 탈퇴 파기**: 탈퇴 후 3일 경과 시 해당 회원의 리포트 데이터 전체(REPORTS·REPORT_SCORES·REPORT_FEEDBACKS·REPORT_GENERATION_JOBS)와 **아직 남아 있는 녹음 파일(S3 `recordings/` 객체)**을 완전 삭제한다(이력서와 동일 정책, DELETION_LOG 파기 대상에 포함).
- 샘플 면접 리포트(HBB1-202)는 본 문서의 스키마·계약을 따르는 시드 데이터로 준비한다(준비 방식은 배포 작업 소관).

---

## 2. 리포트 목록 조회

### 설명

사용자는 본인의 리포트 목록을 조회할 수 있다(`GET /api/v1/reports`).

- 목록 항목은 **REPORTS 단독으로 구성한다**(조인 없음) — overall_score, 상태, 스냅샷 컬럼(이력서 파일명·면접 유형·소요 시간), weakness_tag_summary, 생성·완료 시각. 스냅샷 비정규화가 이 요구를 위한 설계다.
- 페이지네이션: 기본 page=0, size=20.
- 정렬(`sort`): `createdAt`(기본, 내림차순) 또는 `overallScore`. `order`: `desc`(기본)/`asc`. **overallScore 정렬 시 값이 null인 리포트(미완성)는 order와 무관하게 항상 뒤에 두고, 동점·동시각은 생성 시각 내림차순 → id 내림차순으로 순서를 고정한다** — DB나 페이지에 따라 목록 순서가 흔들리지 않게.
- 필터: `status`(생성 상태), `interviewType`(면접 유형 스냅샷). 생성 중(PENDING/PROCESSING)·FAILED 리포트도 목록에 노출된다(상태 표시는 프론트 소관).
- 목록 화면의 KPI·추이·약점 분포는 목록 응답이 아니라 통계 API(§6)가 담당한다.
- 프론트가 표시하는 "제목"(예: "백엔드 개발자 · 기술 면접")과 날짜·시간 포맷은 서버 필드(면접 유형·이력서 파일명·시각)로 프론트가 구성한다 — 서버는 title 필드를 제공하지 않는다. 모든 시각 필드는 ISO-8601이다.

### 실행 조건

- 사용자가 인증된 상태여야 하며, 본인 리포트만 조회할 수 있다.

### 검증 기준

- 목록이 page/size 파라미터대로 페이지네이션되는지 확인
- 기본 정렬이 생성 시각 내림차순인지, sort=overallScore 지정 시 점수순으로 정렬되는지 확인
- overallScore 정렬에서 null인 리포트가 항상 뒤에 오고, 동점 시 생성 시각·id 순서로 고정되는지 확인
- status·interviewType 필터 지정 시 해당 리포트만 반환되고, 잘못된 값은 400(INVALID_INPUT_VALUE)인지 확인
- PENDING/PROCESSING/FAILED 리포트도 목록에 노출되는지 확인 (미완성 리포트의 overallScore·weaknessTagSummary는 null)
- 다른 사용자의 리포트가 목록에 포함되지 않는지 확인

### 성능 요구사항

- 없음

### 인터페이스 요구사항

- `GET /api/v1/reports?status=&interviewType=&sort=&order=&page=&size=`

목록 항목 예시:

```json
{
  "reportId": 7,
  "status": "COMPLETED",
  "overallScore": 82,
  "resumeFileName": "백엔드_개발자_이력서.pdf",
  "interviewType": "REAL",
  "durationMinutes": 30,
  "weaknessTagSummary": [
    { "tag": "두괄식 부족", "count": 3 },
    { "tag": "말 속도 빠름", "count": 2 }
  ],
  "createdAt": "2026-06-03T14:21:07Z",
  "completedAt": "2026-06-03T14:24:31Z"
}
```

### 제약사항

- 정렬 키는 createdAt·overallScore 2종만 지원 (확장은 화면 요구 발생 시)

### 기타 요구사항

- `interviewType`의 값 집합(예: REAL/QUICK)은 면접 도메인의 면접 유형 확정에 따른다 (**면접 도메인 의존**).

---

## 3. 리포트 상세 조회

### 설명

사용자는 완성된 리포트의 상세를 조회할 수 있다(`GET /api/v1/reports/{reportId}`).

- 응답 구성: 리포트 메타(스냅샷·완료 시각) + 총평(`summary`) + 축별 점수·overall(`scores` 객체 — 텍스트 3축은 REPORT_SCORES에서, deliveryScore·overallScore는 REPORTS에서 조립) + 질문 수 + 약점 태그 요약 + **개선 과제**(`improvementTasks` — 답변별 REPORT_FEEDBACKS.improvement_tasks를 질문 순서대로 모아 반환, 별도 저장 없음) + **AI 분석 한계 안내 문구**.
- AI 분석 한계 안내 문구는 서버가 관리하는 고정 상수로 응답에 포함한다(`aiDisclaimer`) — 문구 수정이 배포로 통제되고 모든 클라이언트에 일관 적용된다. 문구 내용: **미정** (HBB1-193에서 확정). 표시 UI는 프론트 소관.
- 상세 조회는 **COMPLETED에서만 가능하다**. 답변별 피드백은 상세가 아닌 타임라인 API(§4)가 질문-답변 기록과 결합해 반환한다(상세 화면과 타임라인 화면이 분리된 화면 설계에 대응).
- 사용자 간 백분위(rank, "상위 N%")는 **MVP에서 제공하지 않는다** (2026-07-22 결정 — UI에서도 제거).
- 원본 이력서로의 링크·참조는 제공하지 않는다 — 리포트는 평가 당시 스냅샷(스냅샷 컬럼·resume_context)만 보여준다. 이력서는 이후 수정·삭제될 수 있어 살아있는 참조는 리포트 근거와 어긋날 수 있다.

### 실행 조건

- 사용자가 인증된 상태여야 하며, 본인 리포트만 조회할 수 있다.
- 리포트가 COMPLETED 상태여야 한다. PENDING/PROCESSING이면 409(REPORT_GENERATION_IN_PROGRESS), FAILED면 409(REPORT_GENERATION_FAILED)로 거부한다.

### 검증 기준

- 다른 사용자의 리포트 접근 시 403(REPORT_FORBIDDEN)이 반환되는지 확인
- 존재하지 않는 reportId 조회 시 404(REPORT_NOT_FOUND)가 반환되는지 확인
- PENDING/PROCESSING 리포트 조회 시 409(REPORT_GENERATION_IN_PROGRESS), FAILED 조회 시 409(REPORT_GENERATION_FAILED)인지 확인
- COMPLETED 리포트 응답에 총평·축별 점수·overall·질문 수·약점 태그 요약·개선 과제·aiDisclaimer가 포함되는지 확인
- 전달력 미평가 리포트의 deliveryScore가 null로 반환되는지 확인
- 응답에 원본 이력서 참조(resumeId 등 살아있는 링크)와 rank 필드가 포함되지 않는지 확인

### 성능 요구사항

- 없음

### 인터페이스 요구사항

- `GET /api/v1/reports/{reportId}`

응답 예시 (`data` 부분):

```json
{
  "reportId": 7,
  "resumeFileName": "백엔드_개발자_이력서.pdf",
  "interviewType": "REAL",
  "durationMinutes": 30,
  "completedAt": "2026-06-03T14:24:31Z",
  "overallScore": 82,
  "scores": {
    "logicScore": 85,
    "specificityScore": 72,
    "technicalAccuracyScore": 88,
    "deliveryScore": null
  },
  "questionCount": 3,
  "summary": "전반적으로 기술 근거가 탄탄하지만 결론을 먼저 말하는 구성이 부족합니다. ...",
  "weaknessTagSummary": [
    { "tag": "두괄식 부족", "count": 3 },
    { "tag": "말 속도 빠름", "count": 2 },
    { "tag": "근거 부족", "count": 2 }
  ],
  "improvementTasks": [
    { "title": "결론부터 말하기 (PREP)", "description": "답변 첫 문장에 핵심 결론 배치" },
    { "title": "수치·사례로 근거 보강", "description": "'왜'에 정량적 근거 1개 이상" }
  ],
  "aiDisclaimer": "AI 분석 결과는 참고용이며 실제 면접 평가와 다를 수 있습니다."
}
```

- `questionCount`는 약점 빈도 표시("질문 N개 중 M회 지적")의 분모로 프론트가 사용한다.

### 제약사항

- 없음

### 기타 요구사항

- 없음

---

## 4. 질문-답변 타임라인 조회

### 설명

사용자는 리포트에서 면접의 질문-답변 흐름을 복기할 수 있다(`GET /api/v1/reports/{reportId}/timeline`).

- 해당 세션의 대본(`INTERVIEW_TRANSCRIPTS` — 세션당 1행, 발화 배열 JSON)을 **질문 단위(questionNumber)로 그룹핑**해 반환한다 — 항목: 질문 번호, 꼬리 질문 여부(questionType에서 유도), 질문 텍스트(면접관 발화), 답변 텍스트(같은 questionNumber의 사용자 발화를 시간순으로 연결), 답변 평가(축별 점수·피드백·약점 태그 — `question_number`로 REPORT_FEEDBACKS와 결합). 항목 순서는 발화 spokenAt 오름차순 기준이다.
- 답변 평가는 REPORT_FEEDBACKS를 결합한다. 평가 인용 근거(`resume_context`)의 노출 여부·형태는 화면 설계 확정 후 결정한다(**미정** — 현재 응답에 포함하지 않는다).
- 타임라인은 리포트 복기 화면의 일부이므로 상세와 동일하게 **COMPLETED에서만** 조회 가능하다.
- transcripts는 면접 도메인 소유 테이블이다 — 리포트 도메인은 **읽기 전용으로만 접근**하며, 스키마 변경 권한은 면접 도메인에 있다.

### 실행 조건

- 사용자가 인증된 상태여야 하며, 본인 리포트만 조회할 수 있다.
- 리포트가 COMPLETED 상태여야 한다(§3과 동일한 409 규칙).

### 검증 기준

- 타임라인이 질문 단위로 그룹핑되어 spoken_at 오름차순으로 반환되는지 확인
- 꼬리 질문이 isTailQuestion=true로 표시되는지 확인
- 각 항목에 질문·답변 텍스트와 축별 점수·피드백·약점 태그가 결합되어 반환되는지 확인
- 응답에 resume_context가 포함되지 않는지 확인
- 접근 권한·상태 규칙이 §3과 동일하게 적용되는지 확인 (403/404/409)

### 성능 요구사항

- 없음

### 인터페이스 요구사항

- `GET /api/v1/reports/{reportId}/timeline`

응답 예시 (`data` 부분):

```json
{
  "items": [
    {
      "questionNumber": 1,
      "isTailQuestion": false,
      "question": "자기소개를 부탁드려요.",
      "answer": "안녕하세요, 3년차 백엔드 개발자 ...",
      "evaluation": {
        "logicScore": 80,
        "specificityScore": 75,
        "technicalAccuracyScore": 82,
        "feedback": "두괄식으로 시작하면 더 좋아요",
        "weaknessTags": ["두괄식 부족"]
      }
    },
    {
      "questionNumber": 2,
      "isTailQuestion": true,
      "question": "그 결정에서 가장 어려웠던 점은?",
      "answer": "가장 어려웠던 부분은 ...",
      "evaluation": {
        "logicScore": 70,
        "specificityScore": 68,
        "technicalAccuracyScore": 80,
        "feedback": "사례가 부족합니다",
        "weaknessTags": ["근거 부족"]
      }
    }
  ]
}
```

### 제약사항

- 페이지네이션 없음 — 한 세션의 transcripts는 유한하고(면접 시간 상한) 복기 화면은 전체 흐름을 한 번에 그린다

### 기타 요구사항

- 질문-답변 그룹핑의 유일 키는 questionNumber다(같은 번호의 면접관 발화=질문, 사용자 발화=답변). speaker·questionType 값 집합 등 대본 JSON 스키마는 §1 기타의 대본 계약 항목을 따른다.
- 세션 soft delete(`INTERVIEW_SESSIONS.deleted_at`)·transcripts 삭제와 리포트 조회의 관계는 면접 도메인의 삭제 정책 확정 시 정합을 재확인한다(**면접 도메인 의존**).

---

## 5. 생성 상태 추적

### 설명

생성 진행 상태를 두 채널로 제공한다(이력서 분석 상태 추적과 동일 패턴).

- REST 조회(`GET /api/v1/reports/{reportId}/status`): 현재 상태, 실패 사유(`failedReason`), 시각 정보를 반환한다. SSE 유실·재연결 시 동기화용. 모든 상태에서 조회 가능하다.
- SSE 구독(`GET /sse/v1/reports`): 사용자 단위 단일 연결로 본인 리포트의 상태 변경만 push한다(userId 키 라우팅). 연결·재연결·keepalive 규약은 이력서 SSE(`/sse/v1/resumes`)와 동일하다 — 화면 진입·재연결 시 REST로 동기화, 놓친 이벤트는 재전송하지 않음, 15~30초 keepalive 주석 라인.
- **PENDING은 push하지 않는다** — PENDING은 로우 생성 직후의 짧은 초기 상태다. 사용자는 REST 동기화(목록·상태 조회)로 PENDING을 인지하고, SSE 이벤트는 PROCESSING부터 흐른다. 재생성으로 PENDING에 복귀하는 경우도 재생성 API 응답이 그 사실을 전달하므로 push가 필요 없다.

SSE 이벤트는 3종이며 data 스키마는 단일 형식이다:

| event | 시점 |
| --- | --- |
| REPORT_GENERATION_STATUS_CHANGED | 중간 상태 변경 (PROCESSING) |
| REPORT_GENERATION_COMPLETED | 최종 성공 (COMPLETED) |
| REPORT_GENERATION_FAILED | 실패 (FAILED) |

```json
{ "reportId": 7, "status": "PROCESSING", "message": null }
```

- `message`는 status만으로 유도할 수 없는 정보 전달용(예: FAILED의 실패 사유). 표시 문구는 프론트가 status 기반으로 매핑한다.

### 실행 조건

- 사용자가 인증된 상태여야 하며, 상태 조회는 **본인 리포트만** 가능하다 — 다른 사용자의 리포트는 403(REPORT_FORBIDDEN), 없는 리포트는 404(REPORT_NOT_FOUND).
- Spring이 상태 이벤트 스트림을 소비 중이어야 SSE push가 동작한다.

### 검증 기준

- 상태 변경 시 해당 사용자의 SSE 연결로 이벤트가 push되는지 확인
- 3종 이벤트의 data가 동일 스키마({reportId, status, message})인지 확인
- FAILED 이벤트·/status 응답에 실패 사유가 포함되는지 확인
- 다른 사용자의 리포트 이벤트가 수신되지 않는지 확인
- 다른 사용자의 리포트 상태 조회 시 403(REPORT_FORBIDDEN)이 반환되는지 확인
- SSE 미연결 중 상태가 변해도 재연결 후 REST 조회로 최신 상태를 얻을 수 있는지 확인

### 성능 요구사항

- 없음

### 인터페이스 요구사항

- `GET /api/v1/reports/{reportId}/status` / `GET /sse/v1/reports` (Content-Type: text/event-stream)

`/status` 응답 예시 (`data` 부분):

```json
{
  "reportId": 7,
  "status": "FAILED",
  "failedReason": "재전달 임계 초과",
  "createdAt": "2026-06-03T14:21:07Z",
  "completedAt": null
}
```

### 제약사항

- SSE로 놓친 이벤트는 재전송하지 않는다 — 복구는 REST 동기화로만 한다.
- 사용자 단위 SSE 연결이 도메인별로 늘고 있다(resumes·reports) — 브라우저 동시 연결 한도를 고려해 **3개 도메인째 SSE 채널이 필요해지면 단일 이벤트 채널로의 통합을 재검토한다.**

### 기타 요구사항

- 에러 코드는 리포트 도메인 접두사 `RP` + 3자리로 정의한다: REPORT_NOT_FOUND(404), REPORT_FORBIDDEN(403), REPORT_GENERATION_IN_PROGRESS(409), REPORT_GENERATION_FAILED(409), REPORT_RETRY_NOT_ALLOWED(409), REPORT_GENERATION_REQUEST_FAILED(500) — 번호 배정은 구현 시 확정.

---

## 6. 리포트 통계 조회

### 설명

사용자의 리포트 전체를 집계한 통계를 제공한다(`GET /api/v1/reports/stats`). 리포트 목록 화면의 KPI 카드(평균·총 횟수·최고점)·점수 추이 그래프·축별 평균·약점 분포와 대시보드의 최근 흐름이 이 API를 사용한다.

- **집계 대상은 본인의 COMPLETED 리포트만**이다 (PENDING/PROCESSING/FAILED 제외).
- 제공 항목:
  - `totalCount` — 완료 리포트 수
  - `avgScore` — overall_score 전체 평균(반올림)
  - `bestScore` — overall_score 최고값
  - `monthlyDelta` — 이번 달 평균 − 지난달 평균 (어느 한쪽이 없으면 null)
  - `trend` — 최근 완료 리포트의 시계열(완료 시각 오름차순, 최대 12개): `[{completedAt, overallScore}]`
  - `axisAverages` — 축별 평균. `deliveryScore` 평균은 전달력이 평가된 리포트만 모수로 하며, 평가된 리포트가 없으면 null
  - `weaknessSegments` — 완료 리포트의 `weakness_tag_summary`를 합산한 태그별 빈도(내림차순 전체): `[{tag, count}]`
- 대시보드의 "최근 3회 흐름"·최근 평균·직전 대비 변화는 별도 필드로 제공하지 않는다 — 프론트가 `trend`의 마지막 3개로 계산한다. 표시 문자열(예: "지난달 대비 +5")·날짜 축 포맷도 프론트 소관이다.
- **시간대·반올림 기준**: monthlyDelta의 월 경계와 시각 기반 집계는 **Asia/Seoul 기준**이다. 평균·차이 값은 소수점 첫째 자리에서 반올림한 정수다(전 항목 동일).

### 실행 조건

- 사용자가 인증된 상태여야 한다.

### 검증 기준

- COMPLETED 리포트만 집계에 포함되는지 확인 (진행 중·실패 리포트가 평균·횟수에 영향 없음)
- 완료 리포트가 0건일 때 totalCount=0, 나머지 수치가 null·빈 배열로 반환되는지 확인
- avgScore·bestScore·trend가 본인 리포트만으로 계산되는지 확인
- 전달력 미평가 리포트가 deliveryScore 평균의 모수에서 제외되는지, 전부 미평가면 null인지 확인
- weaknessSegments가 완료 리포트들의 weakness_tag_summary 합산과 일치하는지 확인
- monthlyDelta가 이번 달·지난달 중 한쪽에 완료 리포트가 없으면 null인지 확인
- 월 경계가 Asia/Seoul 기준으로 집계되는지 확인 (월말·월초에 걸친 완료 시각으로 검증)

### 성능 요구사항

- 없음 (완료 리포트 수가 사용자당 수십 건 규모인 MVP에서는 실시간 집계로 충분 — 사전 집계 테이블은 규모 문제가 생길 때 도입)

### 인터페이스 요구사항

- `GET /api/v1/reports/stats`

응답 예시 (`data` 부분):

```json
{
  "totalCount": 4,
  "avgScore": 76,
  "bestScore": 82,
  "monthlyDelta": 5,
  "trend": [
    { "completedAt": "2026-05-12T10:02:11Z", "overallScore": 68 },
    { "completedAt": "2026-05-20T15:40:03Z", "overallScore": 79 },
    { "completedAt": "2026-05-28T11:18:44Z", "overallScore": 74 },
    { "completedAt": "2026-06-03T14:24:31Z", "overallScore": 82 }
  ],
  "axisAverages": {
    "logicScore": 82,
    "specificityScore": 70,
    "technicalAccuracyScore": 85,
    "deliveryScore": null
  },
  "weaknessSegments": [
    { "tag": "두괄식 부족", "count": 4 },
    { "tag": "말 속도 빠름", "count": 3 },
    { "tag": "근거 부족", "count": 2 }
  ]
}
```

### 제약사항

- trend 최대 개수: 12 (화면 그래프 폭 기준 — 변경은 프론트와 합의)

### 기타 요구사항

- 백로그에 본 기능의 설계·개발·테스트 서브태스크가 없다 — **티켓 추가 필요** (2026-07-22 프론트 UI 확인으로 신설된 기능).
