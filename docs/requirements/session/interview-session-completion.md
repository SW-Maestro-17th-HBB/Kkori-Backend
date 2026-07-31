# 면접 세션 종료

> **User Story**: HBB1-294 — 나는 사용자로서 면접을 정상적으로 끝낼 수 있다.
>
> **하위 이슈**: HBB1-295 [개발] LiveKit webhook 수신 기반 개발 · HBB1-296 [개발] 종료 API 개발 · HBB1-297 [개발] AGENT_LOST 판별 개발

## Overview

본 기능은 HBB1-18(생성)·HBB1-289(디스패치)가 확립한 세션 생성 파이프라인에 **종료 반쪽을 붙여 상태 머신을 완성**한다. 현재 세션은 디스패치 후에도 영구히 `PENDING`에 머무는 반쪽 상태다 — 면접이 끝나도 `ENDED` 전이가 없어 이력서가 `RESUME_IN_USE`로 계속 차단되고, 사용자 명시 종료 API도 없다. 본 스토리로:

- LiveKit **webhook을 수신**해 세션을 전이시킨다 — `participant_joined(agent)` → `ACTIVE`, `room_finished` → terminal(`ENDED`/`ABORTED`).
- **사용자 명시 종료 API**(`POST /api/v1/sessions/{id}/end`)를 도입한다 — 상태와 무관하게 세션의 terminal 수렴을 보장한다.
- **AGENT_LOST 판별**(`participant_left(agent)` 3-경로)과 **유예 만료 정리**를 도입한다 — 에이전트 소실 세션이 영구 잔존해 재생성을 409로 막는 일이 없게 한다.

상대 절반(Kkori-AI)은 **이미 전부 구현·머지되어 있다** — 정상 종료 시퀀스·종료 표식·판별 계약은 Kkori-AI `docs/prd/interview-end.md` §3에 **확정**으로 문서화되어 있고, 본 문서의 크로스 레포 계약은 그 확정 내용의 Spring 측 인용이다. **본 스토리는 Spring 절반만 구현하며 AI 레포 변경은 없다.**

### 기능 요구사항

| No. | Function | Description |
| --- | --- | --- |
| 1 | LiveKit webhook 수신·상태 전이 | 서명 검증된 webhook을 수신해 룸 이름으로 세션을 역추적하고, 이벤트→전이 매핑대로 조건부 UPDATE로 원자적·멱등하게 전이시켜야 한다. |
| 2 | 세션 종료 API + fallback | `POST /api/v1/sessions/{id}/end`가 상태와 무관하게 세션의 terminal 수렴을 보장해야 한다 — `ACTIVE`는 SendData로 에이전트 정상 종료를 유도(+타임아웃 fallback), 에이전트 부재 상태는 즉시 정리한다. |
| 3 | AGENT_LOST 판별·유예 정리 | `participant_left(agent)` 관측 시 transcript 행·종료 표식 기준 3-경로로 판별하고, `AGENT_LOST` 유예 만료 세션을 `ABORTED`로 정리해야 한다. |

### 크로스 레포 계약 (Kkori-AI 공유 — 임의 변경 금지)

Kkori-AI 레포와 공유하는 계약으로, 원천은 Kkori-AI `docs/prd/interview-end.md` §3(확정)이다. 변경은 양 레포 합의·동시 반영으로만 한다.

**에이전트 동작 사실** — 이 전제 위에서 매핑이 설계되었다:

- **정상 종료**(마지막 답변 완료·시간 소진 판단·hard 강제·사용자 종료): 에이전트가 클로징 발화 → transcript DB flush(`interview_transcript` 1행) → Redis 사본 정리 → 리포트 요청 발행 → **에이전트가 직접 룸을 삭제**(bounded retry) → `room_finished` 발생. **즉 `room_finished`가 정상 종료의 신호다.** flush가 룸 삭제보다 먼저이므로, `room_finished` 생성 시점에 transcript 행은 항상 커밋되어 있다.
- **오류 종료**(TTS 재시도 소진 등 진행 불가): 클로징·flush·표식·룸 삭제 없이 잡만 종료한다 → Spring에는 `participant_left(agent)`로 관측된다(룸 잔존). 에이전트 측이 의도적으로 `AGENT_LOST` 경로로 남겨 재dispatch 여지를 보존한 설계다(재dispatch 자체는 후속 스토리).
- **candidate 이탈**(현행): 에이전트도 flush·표식 없이 즉시 종료하고, 빈 룸은 empty timeout으로 소멸해 `room_finished`가 발생한다. 재연결 처리는 후속 `INTERRUPTED` 스토리.
- **candidate 미입장**: 에이전트는 입장 대기 타임아웃 후 flush·표식 없이 종료한다 — candidate 이탈과 동일하게 관측된다.

**사용자 종료 신호 (SendData)**:

- Spring이 LiveKit Server API `SendData`로 발신한다 — topic **`interview:end`**, payload **`{"sessionId":"123"}`**(세션 id의 문자열화), **RELIABLE**, 룸 브로드캐스트(대상 지정 없음).
- 에이전트는 (1) 발신 participant 없음(서버 API 발신) (2) topic 일치 (3) payload `sessionId` 일치 — 셋 다 만족할 때만 처리한다. 참가자 발신 동일 topic은 무시된다(Spring 관문 우회 차단). SendData는 응답이 없는(fire-and-forget) 계약이며 **`room_finished`가 사실상의 ack**다.

**종료 표식 (termination marker)**:

- 에이전트가 CLOSING 진입 부수효과로 Redis에 기록한다 — 키 `interview:{sessionId}:termination`, 값 JSON `{"cause":"<원인>","markedAt":"<UTC ISO-8601(초 단위, Z)>"}`, TTL 24h(86400s), **best-effort**(기록 실패 가능).
- cause는 4종 전부 "의도된 클로징" 계열이다: `FINAL_QUESTION`·`LLM_END`·`HARD_TIMEOUT`·`USER_REQUEST`. 오류 종료·candidate 이탈은 CLOSING을 거치지 않아 **표식을 남기지 않는다**.
- **Spring은 cause 값으로 분기하지 않는다** — 표식의 존재가 판별 신호이고 cause는 진단 로그용으로만 기록한다. 에이전트가 cause를 추가해도 Spring 계약은 불변이다.

**AGENT_LOST 판별표** (`participant_left(agent)` 관측 시 — 우선순위 순):

| 우선순위 | 관측 | 판별 | Spring 처리 |
| --- | --- | --- | --- |
| 1 | transcript 행 있음 | 정상 종료 완료 (룸 정리 또는 표식 기록만 실패) | `ENDED` 정리, 재dispatch 금지 |
| 2 | 행 없음 + 종료 표식 있음 | 종료 국면 진입 후 flush 실패 | `ABORTED` 정리, 재dispatch 금지 |
| 3 | 행 없음 + 표식 없음 | 예기치 못한 소실 (오류 종료·crash 등) | `AGENT_LOST` 전이, 유예 후 정리 |

- **행이 1순위인 근거**: flush는 정상 종료 시퀀스에서만 일어나므로 행 존재 단독으로 정상 종료의 증거다. 표식은 best-effort라 부재가 "종료 아님"을 뜻하지 않는다 — 표식 기록만 실패하고 flush는 성공한 세션도 이 우선순위가 `ENDED`로 수렴시킨다.

**fallback (ack 부재 보완)**:

- `/end` 후 타임아웃 내 `room_finished`가 오지 않으면 Spring이 **종료 결과를 먼저 상태로 기록한 뒤 룸을 직접 삭제**한다(선기록 후 삭제 — 순서 고정). fallback 삭제도 같은 `room_finished`를 만들므로, 선기록이 없으면 정상 종료와 구분할 수 없다. 이후 도착하는 webhook은 terminal no-op으로 수렴한다.
- **선기록도 판별표를 따른다**: 기록 전에 transcript 행을 조회해 **행이 있으면 `ENDED`로 기록**하고(에이전트가 flush까지 완료하고 룸 삭제만 지연·실패한 경우) 룸 정리만 수행한다 — 정상 종료 세션이 fallback 경합으로 `ABORTED`가 되는 것을 막는다. 행이 없으면 `ABORTED`로 기록한다(표식이 있으면 cause를 로그). 조회와 기록 사이의 잔여 경합 창은 감수한다 — fallback 타임아웃을 종료 시퀀스 소요보다 충분히 크게 잡는 전제다.

**공유 인프라·소유 경계**:

- Spring은 에이전트와 **같은 Redis**(표식)·**같은 PostgreSQL**(`interview_transcript`)을 본다. Spring은 이 둘을 **읽기만** 한다 — 쓰기·DDL·마이그레이션은 에이전트 소유다(쓰기 권한 경계 = 소유권 경계).
- `interview_transcript` 스키마(에이전트 확정): `id`, `session_id`(숫자, **UNIQUE**), `content`(jsonb), `deleted_at`. FK 없음. Spring의 조회는 `session_id` 기준 존재 확인(EXISTS)뿐이다.
- 세션 상태의 단일 원천은 Spring(PostgreSQL `interview_session`)이다 — 에이전트는 세션 상태를 전이시키지 않으며, 에이전트가 **의도적으로 발신하는** 전이 신호는 룸 삭제(→`room_finished`)뿐이다. `participant_left`·`participant_connection_aborted`는 에이전트의 신호가 아니라 LiveKit이 관측한 소실 사건이고, 행·표식은 Spring이 판별 시 조회하는 재료다.

### 이벤트 → 상태 전이 매핑

세션은 `livekit_room`(UNIQUE)으로 역추적한다. **모든 전이는 조건부 UPDATE로 원자적·멱등**이며(중복·역순 webhook 안전), **terminal 세션은 어떤 이벤트에도 no-op**이다(공통 가드). terminal 확정 시각은 `ended_at`, `ACTIVE` 전환 시각은 `started_at`에 기록한다(서버 수신 시각 — 공통 Clock·마이크로초 절삭).

**terminal 확정 원칙 — 어떤 경로든 `ENDED` 기록 조건은 transcript 행 존재와 일치시킨다.** `room_finished`·fallback·판별 어디서든 행이 있으면 `ENDED`, 없으면 `ABORTED`다. 정상 경로에서는 행이 `room_finished` 생성 전에 항상 커밋되어 있어(에이전트 동작 사실) 결과가 같고, webhook 순서 역전 같은 병리 케이스에서 "transcript 없는 `ENDED`"가 구조적으로 불가능해진다. 역방향("행 있는 `ABORTED`")은 fallback 경합에서 발생할 수 있으며 감수한다 — 기능 2의 잔여 경합 참조.

| 이벤트 | 세션 상태 | 전이 | 비고 |
| --- | --- | --- | --- |
| `participant_joined` (kind=AGENT) | `PENDING` | → `ACTIVE` (`started_at`) | 그 외 상태 no-op (중복 전달 포함) |
| `participant_joined` (그 외 participant) | * | no-op | candidate 선입장 허용(PENDING 정의), 구 토큰 재입장도 흡수 |
| `participant_left` (kind=AGENT) | `PENDING`·`ACTIVE` | **판별 3-경로** (기능 3) | `PENDING` 포함: 입장 직후 소실은 joined/left가 역순 도착할 수 있다 — 역순이어도 ③→유예→`ABORTED`로 수렴 |
| `participant_left` (그 외 participant) | * | no-op | `INTERRUPTED`는 후속 스토리. 에이전트도 즉시 종료하므로 `participant_left(agent)` 경로가 이어서 처리 |
| `participant_connection_aborted` (kind=AGENT) | `PENDING`·`ACTIVE` | **판별 3-경로** (기능 3 — `participant_left`와 동일 취급) | signaling 성립 후 media 연결 실패(공식 문서) — `participant_joined` **없이** 발생할 수 있고 후속 `participant_left`가 보장되지 않으므로, 무시하면 에이전트 접속 실패 세션이 stale 회수(45m)까지 `PENDING`에 방치된다 |
| `participant_connection_aborted` (그 외 participant) | * | no-op | candidate 연결 실패의 재시도·재입장은 프론트·재연결 스토리 소관 |
| `room_finished` | 비terminal 전체 | 행 있음 → `ENDED`, 없음 → `ABORTED` | terminal 확정 원칙의 단일 규칙. 상태별 기대 결과는 아래 시나리오 표 |
| `room_finished` | terminal | no-op | `PENDING` 자동 교체의 룸 삭제, fallback 삭제, 구 토큰 재생성 룸 소멸이 전부 여기로 흡수 |
| 그 외 이벤트 (`room_started`, `track_*` 등) | * | 무시 | |

`room_finished`의 상태별 기대 결과: `ACTIVE`+행 = 정상 종료 → `ENDED` / `PENDING` — 통상 행이 없어 `ABORTED`: 미입장·디스패치 실패 잔존 `PENDING`이 룸 소멸(empty timeout 기본 5분)과 함께 자동 정리되므로 HBB1-18이 미룬 "준비 타임아웃 스케줄러"가 불필요해진다. 단 **`PENDING`은 Spring의 관측 상태일 뿐이다** — `participant_joined` 유실 병리에서는 면접이 실제로 진행·완료되어 행이 있을 수 있고, 그 경우 규칙이 `ENDED`로 정확히 수렴시킨다(상태 단정이 아니라 행 판별이어야 하는 이유) / `AGENT_LOST` — 판별 ③을 거쳐 진입해 통상 행이 없다 → `ABORTED`: candidate 이탈 연쇄의 수렴점이며 유예 만료보다 먼저 오면 먼저 정리한다.

**시나리오별 수렴 경로** (설계 검증용 — 모든 시나리오가 terminal로 수렴해야 한다):

| 시나리오 | 이벤트 흐름 | 최종 상태 |
| --- | --- | --- |
| 정상 종료 (시간 만료·마지막 답변) | joined(agent)→`ACTIVE` → 에이전트 flush·룸 삭제 → left(agent)·room_finished (순서 무관 — 양쪽 다 행 확인 → `ENDED`) | `ENDED` |
| 사용자 종료 (정상 처리) | `/end` → SendData → 정상 종료와 동일 | `ENDED` |
| 사용자 종료 (에이전트 무응답) | `/end` → fallback 타임아웃 → 행 판별 선기록 → 룸 삭제 → room_finished no-op | `ABORTED` (행 있으면 `ENDED`) |
| 에이전트 오류 소실 (룸 잔존) | left(agent) → 판별 ③ `AGENT_LOST` → 유예 만료(스위퍼) 또는 candidate 퇴장 후 room_finished | `ABORTED` |
| 에이전트 접속 실패 (media 연결 실패) | connection_aborted(agent) → 판별 ③ `AGENT_LOST` → 유예 만료 | `ABORTED` |
| 에이전트 flush 실패 소실 | left(agent) → 판별 ② (표식 있음) | `ABORTED` |
| candidate 이탈 | 에이전트 즉시 종료 → left(agent) → ③ `AGENT_LOST` → 빈 룸 empty timeout → room_finished | `ABORTED` |
| candidate 미입장 (에이전트 대기 타임아웃) | joined(agent)→`ACTIVE` → 에이전트 종료 → left(agent) → ③ → room_finished 또는 유예 만료 | `ABORTED` |
| 워커 다운 (에이전트 미입장) | 이벤트 없음 → 유저 퇴장·미입장 시 empty timeout → room_finished(`PENDING`) | `ABORTED` |
| `PENDING` 자동 교체 | 교체 커밋(`ABORTED`) → 룸 삭제 → room_finished | no-op (이미 terminal) |
| 구 토큰의 빈 룸 재생성 | joined(candidate) no-op → empty timeout → room_finished | no-op (이미 terminal) |
| webhook 최종 유실 (수신 서버 장기 다운·URL 미설정) | 이벤트 없음 → stale 회수(스위퍼)가 판별 재실행 | `ENDED`(행 있음) 또는 `ABORTED` |
| joined만 유실 + 면접 진행 중 (에이전트 hang 포함) | stale `PENDING` 회수 → AGENT 관측 → `ACTIVE` 복원 → room_finished(`ENDED`) 또는 ACTIVE stale 판별 | `ENDED` / `ABORTED` |

**수렴 완결성 (webhook 무관 안전망)**: LiveKit webhook 재전송은 유한하므로 최종 유실될 수 있다(수신 서버가 재시도 창 동안 다운·URL 미설정). 따라서 **본 스토리에서 도달 가능한 모든 non-terminal 상태(`PENDING`·`ACTIVE`·`AGENT_LOST`)는 webhook 없이도 유한 시간 내 terminal에 수렴하는 경로를 별도로 가진다** — `PENDING`: 자동 교체 + stale 회수, `ACTIVE`: fallback + stale 회수, `AGENT_LOST`: 유예 만료(전부 DB 기반 스위퍼 — 공통: 스위퍼·설정). `INTERRUPTED`는 본 스토리에서 도달 불가하며, 재연결 스토리가 자체 수렴 경로와 함께 도입해야 한다. 이 안전망이 없으면 webhook 유실 세션이 영구 잔존해 신규 생성 409 차단·`RESUME_IN_USE` 영구 차단이 재발한다 — 본 스토리가 해결하려는 핵심 동기의 재발 방지 조건이다.

**동시성 — user 행 잠금 선행**: webhook 핸들러·스위퍼의 모든 전이는 세션에서 `user_id`를 역추적해 **user 행 잠금을 선행**한다(HBB1-18이 권장 계약으로 예고한 것을 본 스토리가 이행 — 생성 경로의 "교체 건수 불일치 방어선"이 실제로 발동하지 않는 계약의 완성). 잠금 순서는 user 선행으로 기존 경로들과 동일하다. 단 **활성 재확인은 하지 않는다** — 전이는 유저 상태와 무관한 세션 수렴이 목적이므로, 탈퇴 유저의 잔존 세션도 전이시킨다(생성 경로의 잠금과 달리 `deleted_at` 무관 잠금 — 리포지토리 별도 쿼리).

---

## LiveKit webhook 수신·상태 전이

### 설명

LiveKit Cloud가 발송하는 webhook을 수신·검증해 이벤트→전이 매핑(Overview)을 실행해야 한다.

- **엔드포인트**: `POST /api/v1/webhook/livekit` — SecurityConfig `permitAll`(카카오 unlink webhook과 동일 방침). 인증은 JWT 필터가 아니라 **LiveKit 서명 검증**이 담당한다.
- **서명 검증**: livekit-server 0.14.0 내장 `WebhookReceiver(apiKey, apiSecret).receive(body, authHeader)`를 사용한다(서명·바디 해시 검증 — 직접 구현하지 않는다). 자격증명은 기존 `livekit.*` 설정을 재사용하며 **신규 설정·환경변수는 없다**. 검증 실패는 `401`로 응답하고 전이를 실행하지 않는다. 요청 바디는 raw로 읽어야 한다(재직렬화하면 해시 불일치).
- **어댑터 격리**: SDK(`WebhookReceiver`·proto 이벤트)는 `global.livekit` 어댑터에 격리하고, 세션 도메인은 벤더 무관 이벤트 표현(이벤트 종류·룸 이름·participant kind)만 받는다 — 기존 `SessionRoomManager` 등과 동일 구조.
- **처리 모델**: 요청 스레드에서 동기 처리한다(볼륨 미미 — 세션당 이벤트 수 건). 전이 성공·no-op(미등록 룸·무시 이벤트 포함)은 `200`, 처리 실패(DB 오류 등)는 `500`으로 응답해 LiveKit 재전송을 유도한다 — 전이가 멱등이라 재전송은 안전하다. 단 재전송은 유한하다(수 회 시도 후 포기 — 공식 문서, 전달 보장 없음): 최종 유실은 Overview 수렴 완결성의 stale 회수가 흡수한다.
- **룸 역추적**: 이벤트의 룸 이름으로 `interview_session.livekit_room`(UNIQUE)을 조회한다. 미등록 룸은 로그만 남기고 no-op(`200`) — 검증용 룸·타 소스 룸이 해당한다.
- **participant 판별**: webhook participant의 `kind` 필드로 에이전트(`AGENT`)를 식별한다. candidate identity(`candidate-{sessionId}`)는 판별에 사용하지 않는다.
- **순서 전제**: LiveKit webhook은 이벤트 간 순서를 보장하지 않는다. 매핑은 임의 순서에서 동일 terminal로 수렴하도록 설계되었고(멱등 조건부 UPDATE + terminal no-op + terminal 확정 원칙), 유일하게 남는 병리 역전(joined/left 역순)은 매핑 표의 비고대로 수렴한다.

### 실행 조건

- **LiveKit Cloud 프로젝트에 webhook 수신 URL이 등록**되어 있어야 한다(콘솔 설정 — 이벤트가 이 서버로 발송되는 전제). dev/prod 배포 시 공인 URL 등록이 필요하며 **배포 전 필수 체크리스트에 추가**한다. 로컬은 터널(예: ngrok·cloudflared)의 공인 URL을 등록하면 실이벤트 수신이 가능하다 — 수동 E2E가 이 방식을 쓴다(공통: 수동 검증).
- 기존 `livekit.*` 설정(HBB1-256~289 계승)이 충족되어야 한다.

### 검증 기준

- 유효 서명 요청이 수신·처리되고, 서명 무효·바디 변조 요청이 `401`로 거부되며 전이가 일어나지 않는지 확인 (테스트가 동일 더미 자격증명으로 서명 헤더를 직접 구성)
- `participant_joined(kind=AGENT)`로 `PENDING`→`ACTIVE` 전이·`started_at` 기록 확인, `PENDING`이 아닌 상태(중복 전달 포함)는 no-op 확인
- `participant_joined`(kind 비AGENT)가 어떤 상태에서도 no-op인지 확인
- `room_finished`: `ACTIVE`+행 있음 → `ENDED`(`ended_at`), `ACTIVE`+행 없음 → `ABORTED`, `PENDING`+행 있음 → `ENDED`(joined 유실 병리), `PENDING`+행 없음 → `ABORTED`, `AGENT_LOST` → `ABORTED`, terminal → no-op 확인
- 미등록 룸 이벤트가 로그 후 `200` no-op인지 확인
- 동일 이벤트 중복 전달이 최종 상태를 바꾸지 않는지(멱등), 처리 실패 시 `500`으로 응답하는지 확인
- webhook 전이와 동일 유저의 세션 생성이 동시 실행돼도 user 잠금 직렬화로 정합이 유지되는지 확인 (동시성 통합 테스트 — 생성의 교체 건수 방어선이 발동하지 않음)
- 탈퇴 유저의 세션도 webhook 전이가 수행되는지 확인 (활성 재확인 없는 잠금)
- 수동 (실제 Cloud + 로컬 터널): 실이벤트 수신으로 생성→`ACTIVE`→종료→`ENDED` 관통 확인 — 공통: 수동 검증(E2E) 참조

### 성능 요구사항

- 없음 (이벤트 볼륨 미미 — 세션당 수 건, 동기 처리로 충분)

### 인터페이스 요구사항

- 엔드포인트: `POST /api/v1/webhook/livekit` — LiveKit Cloud 발신 전용(Content-Type `application/webhook+json`, Authorization 헤더의 서명 JWT). 응답 바디는 소비되지 않으므로 공통 envelope를 따르되 내용은 무의미하다.
- 외부 시스템: LiveKit Cloud webhook 발송(수신 방향 — 기존 Server API 왕복과 반대 방향의 첫 연동).

### 제약사항

- `INTERRUPTED` 전이(candidate 이탈 재연결 대기)는 도입하지 않는다 — `participant_left`(candidate)는 no-op이며 재연결 스토리 소관.
- 이벤트 순서 보장·정확히 1회 전달을 전제하지 않는다 — 멱등 설계로 흡수한다.

### 기타 요구사항

- webhook 로그는 이벤트 종류·룸 이름·세션 id 등 식별자만 남긴다(기존 로깅 방침 계승). 서명 JWT·API Secret은 로그 금지.

---

## 세션 종료 API + fallback

### 설명

`POST /api/v1/sessions/{id}/end`는 "이 세션을 끝내달라"는 의도 표명이며, **세션 상태와 무관하게 terminal 수렴을 보장**해야 한다. 상태별로 종료 방법이 다르다:

| 시점 상태 | 동작 | 응답 |
| --- | --- | --- |
| `ACTIVE` | `end_requested_at` 기록(최초 1회 — 이미 있으면 유지) 후 커밋, 커밋 후 SendData 발신 → 에이전트가 클로징·flush·룸 삭제 → `room_finished`로 `ENDED` | `202` |
| `PENDING`·`AGENT_LOST` | 에이전트가 없어 보낼 상대가 없다 — **즉시 `ABORTED`(`ended_at`) + 룸 삭제**(best-effort) | `202` |
| `INTERRUPTED` | `PENDING`과 동일 취급 (본 스토리에서 도달 불가 — 재연결 스토리가 재검토) | `202` |
| terminal | 멱등 no-op + **룸 삭제 best-effort 재시도** — 이미 종료 상태이므로 상태는 불변이고(더블클릭·낡은 화면에 안전), 선기록 후 삭제 실패로 잔존한 룸이 있으면 이 재시도가 능동 복구 경로다(룸이 없으면 무해한 no-op) | `202` |

- **비동기 계약**: `202 Accepted`는 수리(종료 수렴 보장)의 의미다 — `ACTIVE` 경로의 실제 종료 확정은 `room_finished` webhook이며, 클라이언트는 응답이 아니라 룸 종료(DisconnectReason=`ROOM_DELETED`)로 종료를 감지한다. API 문서(Swagger)에 이 비동기 계약을 명시한다.
- **처리 순서**: 트랜잭션{user 행 잠금 → 세션 조회·소유 검증 → 상태별 기록} → 커밋 → [커밋 후] SendData 또는 룸 삭제 — LiveKit 왕복을 트랜잭션·잠금 밖에 두는 기존 방침 계승.
- **SendData 실패**(`S008`, 커밋 후): `500 SESSION_END_SIGNAL_FAILED`로 응답한다. `end_requested_at`은 이미 커밋되어 있으므로 **재시도 없이도 fallback이 종료를 보장**한다 — 재시도(/end 재호출)는 정상 종료(클로징) 기회를 되살리는 선택지다. LiveKit 호출은 `livekit.api-timeout` 상한·재시도 없음(기존 방침).
- **중복 `/end`**: `ACTIVE`인 한 SendData를 재발신하되(에이전트의 종료 신호 처리는 멱등 — 전진 전용 상태 머신) `end_requested_at`은 최초값을 유지한다 — fallback 창이 재호출로 연장되지 않는다.
- **fallback**(스위퍼 — 공통: 스위퍼·설정): `status=ACTIVE AND end_requested_at ≤ now − fallback타임아웃` 세션을 크로스 레포 계약대로 처리한다 — 행 판별 선기록(행 있음 `ENDED` / 없음 `ABORTED`, 표식 있으면 cause 로그) → 커밋 → 룸 삭제. 선기록 후 삭제 순서는 계약이다. **terminal 선기록 후 룸 삭제 실패(감수)**: 세션이 이미 terminal이라 스위퍼 대상에서 빠지므로, candidate가 남아 있으면 empty timeout이 시작되지 않아 룸이 잔존하고 클라이언트가 `ROOM_DELETED`를 받지 못할 수 있다. 수렴 경로는 세 겹이다 — ① 참가자 퇴장 즉시 empty timeout이 정리 ② 유저가 종료 버튼을 다시 누르면 terminal `/end`의 룸 삭제 재시도가 정리(기회적 보조 경로 — **프론트에 재호출을 요구하는 계약이 아니다**) ③ 운영 탐지·수동 삭제(`lk room list`). **보장 범위**: 본 스토리가 하드 보장하는 것은 **DB 세션의 terminal 수렴**(이력서 차단 해제·재생성 허용)이며 이는 선기록 시점에 이미 성립해 있다. **실제 룸 폐쇄와 `ROOM_DELETED` 전달은 best-effort다** — 통상은 유저 이탈(자발 퇴장·창 닫기·연결 끊김)이 participant 퇴장으로 관측되어 empty timeout이 정리하지만, 클라이언트가 연결을 유지하는 한 이탈 시점에 상한이 없다. 삭제 실패 시 사용자가 직접 이탈해야 할 수 있음을 잔여 UX로 수용한다. 서버 상태(이력서 차단 해제·재생성 허용)는 선기록 시점에 이미 회복되어 있어 영향은 UX에 한정된다 — HBB1-18의 동일 잔여 위험(candidate 잔류 룸) 수용과 같은 판단이며, 영속 재시도 상태(`room_cleanup_pending` 류)는 발생이 실측되면 그때 도입을 검토한다.
- **잔여 경합의 실제 형태 (감수)**: 에이전트가 정상 진행 중인데 fallback이 선착하면 — 선기록의 행 판별이 flush 커밋보다 먼저 실행된 경우 — `ABORTED` 선기록·룸 삭제 뒤 에이전트가 flush·리포트 발행을 이어가 **"`ABORTED` + transcript 행 + 리포트"**가 남을 수 있다. terminal 확정 원칙("행 없는 `ENDED`" 차단)의 역방향은 막지 않으며, fallback 타임아웃을 종료 시퀀스 최악 소요보다 충분히 크게 잡아(공통: 스위퍼·설정의 값 근거) 창을 실질 0으로 좁힌다. **리포트 소비 스토리는 terminal 상태와 산출물(행·리포트) 존재의 불일치를 허용해야 한다** — 연계 제약으로 명시한다. 이 경합의 구조적 제거(ENDING 중간 상태·terminal 보정 ABORTED→ENDED)는 도입하지 않는다: terminal no-op 가드는 에이전트 PRD가 전이 경쟁 수렴의 전제로 의존하는 크로스 레포 계약이고 상태 집합 6종도 HBB1-18 확정 계약이라, terminal을 가변화하는 쪽이 더 큰 위험이다 — 창의 발생 조건은 "종료 시퀀스 최악 소요(≈119s)를 넘는 병리적 지연"뿐으로 타임아웃 설계(180s)가 실질 0으로 좁힌다.
- `/end` 도중 상태가 바뀌는 경합(예: 판별이 먼저 `AGENT_LOST` 전이)은 user 잠금이 직렬화한다 — 잠금 획득 시점의 상태 기준으로 위 표를 적용한다.

### 실행 조건

- 유저가 인증되어 있어야 한다(`Authorization: Bearer {accessToken}`).
- SendData·룸 삭제는 LiveKit Server API 왕복이다 — 기존 `livekit.*` 설정 전제.

### 검증 기준

- `ACTIVE` 세션 `/end` 시 `202`와 함께 `end_requested_at`이 기록되고, 커밋 후 SendData 어댑터가 topic `interview:end`·payload `{"sessionId":"<id>"}`·RELIABLE로 1회 호출되는지 확인
- `PENDING`·`AGENT_LOST` 세션 `/end` 시 즉시 `ABORTED`(`ended_at`) 전이·룸 삭제 시도·`202` 응답 확인, SendData는 호출되지 않는지 확인
- terminal 세션 `/end`가 상태 무변화·`202`이되 룸 삭제를 best-effort로 재시도하는지 확인 (멱등 + 잔존 룸 능동 복구)
- 중복 `/end`(ACTIVE 유지 중)가 SendData를 재발신하되 `end_requested_at` 최초값을 유지하는지 확인
- 미존재 세션 `404 SESSION_NOT_FOUND`, 타 유저 세션 `403 SESSION_FORBIDDEN`, 미인증 `401` 확인
- SendData 실패 시 `500 SESSION_END_SIGNAL_FAILED`로 응답하되 `end_requested_at`은 유지되어 fallback으로 수렴하는지 확인
- fallback 스위퍼: 타임아웃 경과 `ACTIVE` 세션이 행 없음 → `ABORTED` 선기록 후 룸 삭제 호출, 행 있음 → `ENDED` 기록 후 룸 삭제 호출되는지, 타임아웃 전·이미 terminal 세션은 건드리지 않는지 확인
- fallback 처리 후 도착하는 `room_finished`가 no-op인지 확인 (선기록 계약)
- 룸 삭제 실패가 fallback 처리 결과(terminal 기록)를 되돌리지 않는지 확인

### 성능 요구사항

- 없음 (LiveKit 왕복 1회 추가 — `livekit.api-timeout` 상한, 기존 SLA 방침 유지)

### 인터페이스 요구사항

- 엔드포인트: `POST /api/v1/sessions/{id}/end` (인증 필요), 성공 시 `202 Accepted` — `{ "success": true, "data": null }`
- SendData: `RoomServiceClient.sendData(room, payload, Kind.RELIABLE, ..., topic)` — topic 인자를 갖는 오버로드 사용(livekit-server 0.14.0 확인). 어댑터는 `global.livekit`에 격리.
- 에러 코드는 공통: 에러 코드 참조.

### 제약사항

- `/end`는 종료 의도의 관문일 뿐 종료 실행 주체가 아니다 — `ACTIVE`의 실제 종료(클로징·flush·룸 삭제)는 에이전트 소관이며, **`ACTIVE` 경로에서** Spring이 직접 종료를 실행하는 것은 fallback뿐이다. 에이전트 부재 상태(`PENDING`·`AGENT_LOST`)의 즉시 정리와 terminal의 룸 삭제 재시도는 에이전트가 없는 세션에 대한 별개 경로다.
- 프론트 종료 UI·`ROOM_DELETED` 처리 화면은 프론트 스토리 소관(프론트는 룸 종료 감지 로직 기준 추가 작업 없음).

### 기타 요구사항

- SendData payload는 세션 id뿐이라 개인정보가 없다 — 로그 제약은 기존 방침(식별자만)으로 충분하다.

---

## AGENT_LOST 판별·유예 정리

### 설명

`participant_left(kind=AGENT)` **또는 `participant_connection_aborted(kind=AGENT)`** 관측 시(대상 상태 `PENDING`·`ACTIVE`) 크로스 레포 판별표의 3-경로를 실행해야 한다. connection_aborted는 접속 자체가 실패한 경우라 대개 ③(행·표식 없음)으로 `AGENT_LOST`에 진입하며, 실패한 job은 `JRP_NEVER`(HBB1-289)라 재실행되지 않는다 — 뒤늦은 `participant_joined`가 오는 병리는 조건부 전이(no-op)와 유예 수렴이 흡수한다.

- **① transcript 행 있음** → `ENDED`(`ended_at`) + 룸 삭제(best-effort) — 에이전트가 flush까지 완료하고 룸 삭제만 실패한 경우가 이 경로로 관측되므로, Spring이 잔존 룸을 정리한다(정상 경로에서 룸이 이미 없으면 삭제는 무해한 no-op).
- **② 행 없음 + 종료 표식 있음** → `ABORTED`(`ended_at`) + 룸 삭제(best-effort). 표식의 cause·markedAt은 로그로 남긴다(분기 없음 — 크로스 레포 계약).
- **③ 행 없음 + 표식 없음** → `AGENT_LOST` 전이 + `agent_lost_at` 기록. **룸은 삭제하지 않는다** — 재dispatch 여지 보존(후속 스토리)이 에이전트 측 설계 의도이고, 룸 소멸 시의 수렴은 `room_finished` 매핑(→`ABORTED`)이 담당한다.
- **판별 조회 규칙**: transcript 행은 `interview_transcript`에 `session_id` EXISTS 네이티브 쿼리(엔티티 미생성 — 읽기 전용 경계), 표식은 Redis GET. **Redis 조회 실패는 표식 부재로 취급**하고 경고 로그를 남긴다 — ③으로 후퇴해도 유예 후 `ABORTED`로 같은 결과에 수렴하며, 잘못된 `ENDED`를 만들지 않는 안전한 방향의 후퇴다.
- **표식 TTL 충분성**: 판별 시점은 `participant_left` webhook 도착 시점으로 소실 후 초~분 단위이고, LiveKit 재전송 지연을 감안해도 시간 단위다 — TTL 24h는 판별 창을 넉넉히 덮는다.
- **유예 만료 정리**(스위퍼 — 공통: 스위퍼·설정): `status=AGENT_LOST AND agent_lost_at ≤ now − 유예` 세션을 `ABORTED`(`ended_at`)로 전이하고 룸을 삭제한다(best-effort — 룸의 candidate는 `ROOM_DELETED`로 퇴장된다). 이 정리가 없으면 `AGENT_LOST` 세션이 영구 잔존해 신규 생성이 `SESSION_ALREADY_IN_PROGRESS`(409)로 계속 막힌다. 유예 값은 현재 재dispatch가 없어 순수 대기 비용이므로 짧게 두고, 재dispatch 스토리에서 재검토한다.
- **stale 회수**(스위퍼 — webhook 최종 유실 안전망, Overview 수렴 완결성):
  - `status=ACTIVE AND end_requested_at IS NULL AND started_at ≤ now − stale타임아웃`: `room_finished`·`participant_left`가 최종 유실된 잔존 세션이다. **판별 3-경로를 재실행하되 ③은 `AGENT_LOST`가 아니라 즉시 `ABORTED`**로 기록한다 — 면접 상한을 한참 지난 시점이라 재dispatch가 무의미하고 유예는 지연만 더한다. 세 경로 모두 룸을 best-effort 삭제한다(webhook 유실 상황에서는 룸 실존 여부를 알 수 없다). **`end_requested_at`이 있는 세션은 fallback(기능 2)이 전담한다** — stale 임계 직전의 `/end`가 보장한 정상 종료 창(180s)을 stale 회수가 선점·박탈하지 않게 하는 우선순위 분리다.
  - `status=PENDING AND created_at ≤ now − stale타임아웃`: **stale `ACTIVE`와 같은 판별을 실행한다** — `PENDING`은 Spring의 관측 상태일 뿐이라, `participant_joined` 유실 병리에서는 실제 면접이 진행·완료되어 행·표식이 존재할 수 있다(행 있음 → `ENDED`). 판별 ③(행·표식 없음)은 정리 전에 **룸 참가자 대조**를 거친다: `created_at` 기준 경과에는 상한 없는 디스패치 대기가 포함되므로(워커 장기 다운 후 복귀), joined 유실과 겹치면 뒤늦게 시작된 면접이 진행 중일 수 있다. 룸에 AGENT 참가자가 관측되면 정리하는 대신 **`PENDING`→`ACTIVE`로 복원**한다 — 유실된 `participant_joined`의 관측 기반 보정이며, `started_at`은 LiveKit 참가자의 입장 시각(joined_at, 부재 시 보수적으로 현재 시각)으로 기록한다. 복원 후에는 `started_at` 앵커의 ACTIVE stale 회수가 hard ceiling을 담당하므로, **hang 상태로 연결만 유지하는 에이전트도 무한 skip 없이 유한 시간 내 수렴한다**(정상 진행 중이던 면접은 이후 `room_finished`가 `ENDED`로 정확히 끝낸다). 룸 미존재는 "진행 중 아님"의 확정 증거라 정리를 진행하고, 참가자 조회 실패는 이번 회차만 건너뛴다(다음 스위프 재시도 — 이 경로의 수렴이 LiveKit API 가용성에 조건부가 되는 것은 수용한다: API 장애 중엔 신규 면접도 불가하며 해소 시 재개된다). `ACTIVE`에는 이 대조를 두지 않는다 — `started_at` 앵커는 대기 시간이 소거된 시점이라 45m가 정상 체류 상한(≈40분)을 이미 보장한다. `PENDING`을 회수하는 이유: 생성은 막지 않지만(자동 교체) non-terminal이라 이력서를 `RESUME_IN_USE`로 차단하므로, 유저가 재생성하지 않는 한 webhook 유실 시 영구 잔존하는 동일 갭이다.
  - **계류 dispatch 잔여 (감수 — HBB1-289 계승)**: stale `PENDING`의 발생 조건(워커 다운)은 계류 dispatch(`JS_PENDING`)의 존재 조건과 겹친다 — 룸 삭제는 계류 dispatch의 취소를 보장하지 않으므로(agent-dispatch.md 실패 모델), 워커 복귀 시 뒤늦은 할당이 terminal 세션의 룸을 재생성할 수 있다. 재생성 룸은 자기 제한적으로 소멸하고(joined는 terminal no-op → 에이전트는 candidate 대기 300s 후 퇴장 → empty timeout → `room_finished` no-op) 유저 영향이 없으므로, HBB1-289와 같은 판단으로 `listDispatch` 보상을 두지 않는다 — **뒤늦은 할당이 실측되면 도입**한다는 판정 기준과 운영 탐지 절차(`lk dispatch list <룸 이름>` — 룸 이름 출처는 DB)를 그대로 계승한다.

### 실행 조건

- **공유 인프라 접근**: 에이전트와 같은 Redis(기존 `spring-boot-starter-data-redis` 연결 재사용)·같은 PostgreSQL(동일 데이터소스)이어야 한다. dev/prod 환경 구성에서 이 전제의 성립 확인은 배포 스토리 체크리스트에 연계한다.
- `interview_transcript` 테이블이 존재해야 한다 — DDL은 에이전트 소유이므로 dev/prod는 에이전트 배포가 선행 조건이다. 로컬·테스트는 계약 픽스처 DDL(`id`, `session_id` UNIQUE, `content` jsonb, `deleted_at`)로 생성한다 — 자구는 에이전트 PRD §4 스키마 기준이며 계약 인용으로 관리한다.

### 검증 기준

- `ACTIVE` 세션 + 행 있음 → `ENDED` 전이·룸 삭제 시도 확인 (판별 ①)
- `ACTIVE` 세션 + 행 없음·표식 있음 → `ABORTED` 전이·룸 삭제 시도·cause 로그 확인 (판별 ②)
- `ACTIVE` 세션 + 행·표식 없음 → `AGENT_LOST` 전이·`agent_lost_at` 기록·룸 미삭제 확인 (판별 ③)
- `PENDING` 세션의 `participant_left(agent)`도 판별이 실행되는지 확인 (joined/left 역순 병리)
- `participant_connection_aborted(kind=AGENT)`가 `PENDING`에서 판별을 실행해 ③ → `AGENT_LOST` → 유예 후 `ABORTED`로 수렴하는지, 비AGENT의 connection_aborted는 no-op인지 확인
- Redis 조회 실패 시 ③으로 후퇴하고 경고 로그를 남기는지 확인
- 표식 cause 값이 무엇이든 처리가 동일한지 확인 (cause 불분기 — 미정의 값 포함)
- 유예 스위퍼: 만료된 `AGENT_LOST` 세션이 `ABORTED`로 전이·룸 삭제 시도되는지, 미만료·타 상태 세션은 건드리지 않는지 확인
- 유예 중 `room_finished` 선착 시 `ABORTED`로 먼저 수렴하고 이후 스위퍼가 no-op인지 확인
- `AGENT_LOST` 세션 보유 유저의 생성 요청이 409로 거부되고, 유예 정리 후 생성 가능해지는지 확인 (재생성 차단 해소 — 본 스토리의 핵심 동기)
- terminal 세션의 `participant_left(agent)`가 no-op인지 확인
- stale 회수: 임계 경과 `ACTIVE` 세션이 행 있음 → `ENDED`, 표식만 있음 → `ABORTED`, 둘 다 없음 → **즉시 `ABORTED`**(`AGENT_LOST` 경유 없음)로 정리되고 세 경로 모두 룸 삭제가 시도되는지 확인
- stale 회수: 임계 경과 `PENDING` 세션에도 같은 판별이 적용되는지 확인 — 행 있음(joined 유실 후 정상 종료) → `ENDED`, 행·표식 없음 + 룸에 AGENT 참가자 없음(또는 룸 미존재) → `ABORTED`·룸 삭제
- stale `PENDING` 판별 ③에서 룸에 AGENT 참가자가 관측되면 `ACTIVE`로 복원되는지(`started_at` = 참가자 입장 시각, 부재 시 현재 시각), 복원된 세션이 이후 ACTIVE stale 회수의 대상이 되는지 확인 (hard ceiling — 무한 skip 없음)
- stale `PENDING` 참가자 조회 실패 시 이번 회차만 건너뛰고 다음 스위프에 재시도되는지 확인
- `end_requested_at`이 기록된 `ACTIVE` 세션은 stale 임계를 지나도 stale 회수가 건드리지 않고 fallback이 처리하는지 확인 (fallback 전담 우선순위)
- 임계 미만의 `ACTIVE`·`PENDING` 세션은 stale 회수가 건드리지 않는지 확인

### 성능 요구사항

- 없음 — 판별 추가 조회는 EXISTS 1회 + Redis GET 1회이고, stale `PENDING` 경로(스위퍼)에만 LiveKit 참가자 조회 1회가 더해진다. LiveKit 호출은 트랜잭션·user 잠금 밖에서 수행한다(기존 원칙 계승 — 스위퍼의 세션별 트랜잭션은 DB 작업만 묶고, 대조·조회 결과는 조건부 UPDATE의 상태 술어가 낡음을 걸러낸다).

### 인터페이스 요구사항

- 외부 시스템: 에이전트 소유 Redis 키(`interview:{sessionId}:termination`)·PostgreSQL 테이블(`interview_transcript`) — **읽기 전용**. stale `PENDING` 가드의 룸 참가자 조회는 LiveKit Server API(기존 자격증명·`livekit.api-timeout`, `global.livekit` 어댑터 경유)를 사용한다.

### 제약사항

- `AGENT_LOST` 재dispatch·복원은 범위 밖(후속 스토리) — 본 스토리의 `AGENT_LOST`는 판별 결과의 보존과 유예 정리까지다.
- Spring은 표식·transcript에 어떤 쓰기도 하지 않는다(TTL 연장·삭제 포함).

### 기타 요구사항

- 판별 로그는 세션 id·경로 번호·cause만 남긴다 — transcript 내용은 조회 자체를 하지 않고(EXISTS), 표식 원문 로그는 cause·markedAt으로 한정한다.

---

## 공통: 스위퍼·설정

- **스위퍼**: 단일 `@Scheduled`(fixedDelay) 컴포넌트가 fallback 만료·유예 만료·stale 회수(`ACTIVE`·`PENDING`)를 함께 스캔한다. 대상 시각이 전부 DB 컬럼(`end_requested_at`·`agent_lost_at`·`started_at`·`created_at`)에 있으므로 **서버 재시작에도 감시가 유실되지 않는다**(인메모리 타이머 없음). 세션별 처리는 독립 트랜잭션(user 잠금 선행 + 조건부 UPDATE)으로 격리한다 — 한 세션의 실패가 다른 세션 처리를 막지 않으며, 다중 인스턴스 동시 실행도 조건부 UPDATE가 무해화한다(현재 단일 인스턴스 — 잠금 인프라 불도입).
- **신규 컬럼** (`interview_session`): `end_requested_at`(timestamptz, nullable — 최초 종료 요청 시각), `agent_lost_at`(timestamptz, nullable — `AGENT_LOST` 전이 시각). ERD 문서(`docs/erd.md`) 갱신을 완료 조건에 포함하며, `interview_transcript`도 에이전트 소유 외부 테이블로 소유권 표에 명시한다. **배포 의존성(하드 제약 — HBB1-18 계승)**: dev/prod는 `ddl-auto: validate`라 신규 컬럼 미반영 시 기동 자체가 실패한다 — 본 기능이 포함된 빌드의 dev/prod 배포는 마이그레이션 도입(Flyway + baseline DDL — 배포 스토리)이 선행 조건이다. **본 스토리의 완료 기준은 local·CI(Testcontainers) 동작 + 로컬 실환경 수동 E2E(공통: 수동 검증 — dev 배포 불요)다.** 로컬·테스트 스키마는 `ddl-auto: update`가 처리한다.
- **설정** (`session.*` 신설 — 실측 전 초기값, 환경별 조정 가능하도록 프로퍼티화):

  ```yaml
  session:
    end-fallback-timeout: ${SESSION_END_FALLBACK_TIMEOUT:180s}  # /end 후 room_finished 대기 상한
    agent-lost-grace: ${SESSION_AGENT_LOST_GRACE:60s}           # AGENT_LOST 유예
    stale-recovery-timeout: ${SESSION_STALE_RECOVERY_TIMEOUT:45m}  # webhook 유실 세션 회수 임계
    sweep-interval: ${SESSION_SWEEP_INTERVAL:10s}               # 스위퍼 주기
  ```

  local은 기본값 포함, dev/prod는 기본값 없는 placeholder(기존 방침 — 배포 환경변수 매니페스트에 4종 추가). 값 근거(에이전트 실측 상수 기준):
  - **fallback 180s**: 에이전트 종료 시퀀스 최악 합산 ≈ 119s — 진행 중 발화 완료 대기(끼어들기 불가, ~30s) + 클로징 재생(~15s) + 단계별 타임아웃 10s × 4단계(drain·flush·purge·발행) + 룸 삭제 bounded retry(3회 × 10s + 백오프 2s×2 = 34s) — 에 안전 계수를 둔 값. 이보다 짧으면 정상 진행 중인 에이전트에 fallback이 선착해 잔여 경합(기능 2)의 창이 실질화된다.
  - **유예 60s**: 재dispatch 부재 동안의 잠금 해제 지연 상한 — 짧게 두고 재dispatch 스토리에서 재검토.
  - **stale 45m**: 최장 정상 `ACTIVE` 체류 ≈ 40분 — candidate 입장 대기 상한(에이전트 300s) + `THIRTY_MIN` 30분 + hard 유예 3분 + 종료 시퀀스 ~2분 — 에 여유를 둔 값. 면접 유형별 임계 분리는 두지 않는다(stale 회수는 지연 무민감한 안전망 — 최악 유형 기준 단일 값). `PENDING`의 `created_at` 기준 경과에는 상한 없는 디스패치 대기가 **포함되므로** 이 값만으로는 진행 중 면접의 배제를 보장할 수 없다 — stale `PENDING` ③의 룸 참가자 대조가 `ACTIVE` 복원으로 보호한다(기능 3).

## 공통: 수동 검증 (E2E)

서명 검증·전이는 직접 구성한 서명 요청으로 자동 테스트(JUnit)하고, 실환경 E2E는 **로컬 기동 + 실제 LiveKit Cloud**로 수행한다 — webhook 수신은 로컬 터널(예: ngrok·cloudflared)의 공인 URL을 LiveKit 프로젝트에 등록해 받는다. 기존 스토리들의 "실제 Cloud + 로컬 AI 워커" 수동 검증 체계의 연장이며, **dev 배포는 E2E의 선행 조건이 아니다**(신규 컬럼의 dev/prod 반영이 마이그레이션 스토리에 묶여 있어도 본 스토리 검증이 막히지 않는다). 로컬 Spring + 로컬 AI 워커 + Cloud 조합으로 다음을 확인한다:

- 생성 → 에이전트 입장 → `ACTIVE`(`started_at`) → 면접 진행 → 시간 만료 정상 종료 → `ENDED`(`ended_at`) → 같은 이력서의 수정·삭제가 다시 허용되는지 (RESUME_IN_USE 해제 관통)
- `/end` → 에이전트 클로징 발화 → 룸 종료(`ROOM_DELETED`) → `ENDED`
- 판별 3-경로: ① 에이전트 룸 삭제 실패 유도(또는 관측 시 확인) ② 재현 곤란 시 표식 수동 주입으로 대체 ③ 에이전트 프로세스 강제 종료 → `AGENT_LOST` → 유예 후 `ABORTED` → 재생성 가능
- candidate 이탈 → 에이전트 종료 → empty timeout → `ABORTED`
- `/end` 후 에이전트 무응답(정지 상태) → fallback 선기록·룸 삭제 → `ABORTED`

## 공통: 에러 코드

| 코드 | 이름 | HTTP | 상황 |
| --- | --- | --- | --- |
| S006 | SESSION_NOT_FOUND | 404 | 미존재 세션에 대한 종료 요청 (신설) |
| S007 | SESSION_FORBIDDEN | 403 | 타 유저 세션에 대한 종료 요청 (신설) |
| S008 | SESSION_END_SIGNAL_FAILED | 500 | SendData 발신 실패·타임아웃 — 종료 의도는 기록되어 fallback이 수렴 보장 (신설) |

- S001~S005는 기존 정의 그대로 유지된다.
- webhook 서명 검증 실패는 공통 `UNAUTHORIZED`(401, C005)로 응답한다.

## 범위 제외

다음은 모두 **범위 밖**이며 후속 스토리에서 도입한다:

- candidate 재연결(`INTERRUPTED` 전이·`disconnected_at`·재입장 토큰) — candidate 이탈은 현행 에이전트 동작(즉시 종료)대로 `ABORTED` 수렴까지만 다룬다
- `AGENT_LOST` 재dispatch·에이전트 복원 — 본 스토리는 판별·유예 정리까지
- 리포트 생성·소비(리포트 요청 발행은 에이전트 소관으로 이미 구현됨), egress·전달력 연계
- E1(탈퇴) 연계 — 탈퇴 시 세션 즉시 abort·파기
- 프론트 종료 UI — 프론트는 `ROOM_DELETED` 수신으로 종료를 감지하므로 본 스토리에 따른 추가 작업 없음
- **AI 레포 변경 없음** — 크로스 레포 계약은 전부 기구현 확정분의 인용이다
