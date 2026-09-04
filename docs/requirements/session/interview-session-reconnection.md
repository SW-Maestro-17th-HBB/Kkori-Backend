# 면접 재연결·복원

> **User Story**: HBB1-308 — 나는 사용자로서 연결이 끊겨도 면접을 이어갈 수 있다.
>
> **하위 이슈**: HBB1-311 [개발] 재연결 상태 전이 개발 · HBB1-312 [개발] 에이전트 재디스패치 개발

## Overview

본 기능은 HBB1-294(종료 상태 머신)가 "후속 스토리 소관"으로 남긴 두 복구 경로를 도입해 상태 머신을 완성한다 — **candidate 이탈·복귀**(`INTERRUPTED`)와 **에이전트 재디스패치**(`AGENT_LOST` 복원).

**전제 변경이 출발점이다.** HBB1-294의 "에이전트 동작 사실" 중 candidate 이탈 절("에이전트도 즉시 종료 → `participant_left(agent)` 경로가 이어서 처리")이 본 스토리와 병행하는 Kkori-AI 변경으로 깨진다 — 에이전트가 이제 **재연결 창(3분) 동안 세션을 유지**한다. 따라서 candidate 이탈의 처리 책임이 Spring으로 넘어오며, `participant_left(candidate)` no-op 매핑은 `INTERRUPTED` 전이로 교체된다. 이 계약 변경은 `interview-session-completion.md`의 해당 절 갱신(본 문서 §HBB1-294 문서 갱신 범위)과 함께 반영한다.

- **재연결 상태 전이**: `participant_left(candidate)` → `INTERRUPTED`(`disconnected_at`), 재입장 `participant_joined(candidate)` → `ACTIVE` 복귀, 유예 만료 스위퍼(룸 대조·복원 가드 포함) → `ABORTED`.
- **재입장 토큰 발급 API**: 같은 identity(`candidate-{sessionId}`)·재연결 deadline 기준 TTL의 토큰을 발급한다. 최초 생성 토큰의 TTL 단축(1h→10m)을 함께 반영해 HBB1-18의 구토큰 잔여 위험을 실질 소거한다.
- **에이전트 재디스패치**: 판별 ③(`AGENT_LOST`) 이후 유예 창에서 기존 디스패치 어댑터·metadata 조립을 재사용해 최대 1회 재디스패치하고, `participant_joined(agent)`의 룸 대조로 `ACTIVE`/`INTERRUPTED` 복귀를 분기한다.

### 기능 요구사항

| No. | Function | Description |
| --- | --- | --- |
| 1 | 재연결 상태 전이 | candidate 이탈을 `INTERRUPTED`로 전이하고(`disconnected_at`), 재입장 시 `ACTIVE`로 복귀시키며, 유예 만료 세션을 판별·대조 가드를 거쳐 terminal로 정리해야 한다. `/end`·fallback·판별의 `INTERRUPTED` 취급도 함께 개정한다. |
| 2 | 재입장 토큰 발급 API | `POST /api/v1/sessions/{id}/rejoin`이 같은 identity·deadline 기준 TTL의 재입장 토큰을 발급해야 한다. 최초 토큰 TTL 단축(10m)을 포함한다. |
| 3 | 에이전트 재디스패치 | `AGENT_LOST` 전이 직후 AGENT 사전 확인(관측 시 CAS 없이 상태 복원)을 거쳐, 부재 시에만 CAS 최대 1회(at-most-once)로 재디스패치하고(기존 dispatch 정리·재확인 후 생성), 복귀 입장을 룸 대조로 `ACTIVE`/`INTERRUPTED`에 분기시켜야 한다. |

### 크로스 레포 계약 (Kkori-AI 공유 — 변경·신설분)

HBB1-294의 계약 원천 규칙(양 레포 합의·동시 반영)을 따른다. 본 절은 병행 Kkori-AI 스토리와 합의된 변경·신설 계약이다.

**에이전트 동작 사실 (개정)** — candidate 이탈 절이 다음으로 교체된다:

- **candidate 이탈(신규)**: 에이전트는 즉시 종료하지 않고 **재연결 창(3분) 동안 세션을 유지**한다. 창 내 재입장은 identity 일치 기반으로 면접 재개를 판정한다 — **재입장 토큰의 identity가 최초와 다르면 재입장이 무시된다**(Spring은 결정적 파생 `candidate-{sessionId}`로 동일성을 보장한다 — 회신 확정). 창 소진 시: 남은 면접 시간이 있으면 **flush 없이 룸 삭제**(표식 cause `RECONNECT_TIMEOUT`) → `room_finished` + 행 없음 → 기존 매핑 그대로 `ABORTED`; 면접 시간이 이미 소진된 경우는 flush 후 정상 종료 → 행 있음 → `ENDED`. **창 소진 수렴의 판정 주체는 에이전트다**(Spring의 `INTERRUPTED` 유예는 에이전트도 소실된 경우의 안전망).
- **내구 저장·복원 판별**: 에이전트는 시작 시각·대화 사본을 Redis에 내구 저장하며, **재디스패치된 잡의 신규/복원 판별은 Redis 상태 존재 기반**이다 — dispatch metadata에 구분 필드를 추가하지 않는다(**metadata 4필드 계약 불변**).
- **표식 cause 추가**: `RECONNECT_TIMEOUT`·`RECOVERED_CLOSING` 2종이 추가된다. Spring 판별은 표식 **존재**만 보므로 계약 불변이며(HBB1-294 확정), cause는 로그 해석용이다.
- **재디스패치 금지 조건**: 종료 표식 있는 세션은 재디스패치하지 않는다(기존 판별 계약 그대로 — 판별 ③은 표식 부재가 전제라 자연 충족). 에이전트는 위반 관측 시 방어적으로 잡만 종료한다.
- candidate 미입장 절(입장 대기 타임아웃 후 flush·표식 없이 종료)은 현행 유지된다.

**재디스패치 호출 계약 (불변)**: 최초와 동일한 `createDispatch(룸, "kkori-interviewer", metadata, JRP_NEVER)` — metadata 4필드 계약·자구 규칙(agent-dispatch.md) 그대로.

**활성 dispatch 단일성 (신설 — 2계층)**: 같은 세션에 두 에이전트 잡이 동시에 살아있지 않도록 한다. **1차는 Spring의 순서 보장**이다 — AGENT 사전 확인(관측 시 CAS 없이 상태 복원 — 재디스패치 기회 보존) + 부재 시에만 상태 CAS **최대 1회(at-most-once)** + 기존 dispatch 정리(목록 조회 후 삭제) + 부재 재확인(관측 시 복구 포기) + 생성 직전 상태 재확인 이후에만 생성. 이 계약의 본질은 **중복 금지**이며 실행 보장이 아니다 — CAS 마커 커밋과 LiveKit 호출 사이 프로세스 다운으로 미실행이 남는 창은 감수하고, 유예 만료 수렴이 덮는다(계약 문안의 "정확히 1회"는 중복 금지의 뜻으로 확정 — 교차 검토 시 자구 확인). 단 **하드 보장은 아니다**: `DeleteDispatch`는 응답 시점에 실행 중 잡의 종료 완료를 계약하지 않으므로(공식 API 명세에 보장 없음), 부재 확인 후 구 잡이 뒤늦게 재연결하는 잔여 창이 남는다. **이 잔여 창의 완화가 에이전트 측 owner 검사(2차)다** — 원자성 없는 관측 계층이지만 이 창에서는 유일한 방어이므로, 교차 검토 시 owner 검사가 이 잔여 창을 전제로 동작하는지 확인한다.

**값 정합 (계약값 1종 + 파생 2종)**:

| 값 | 정의 | 어긋나면 생기는 병리 |
| --- | --- | --- |
| 재연결 창 | **3분 — 단일 계약값.** 양 레포 config에 같은 값을 주입한다(Spring `session.reconnect-window` ↔ AI 측 대응 설정). 양측이 각자 관측한 이탈 시각에 같은 창을 적용하며 수 초 편차는 감수한다 | 값 자체가 갈리면 한쪽이 살아있는 재연결을 다른 쪽이 닫는다 — 단일 계약값인 이유 |
| 재입장 토큰 TTL | 고정값이 아니라 **`disconnected_at + 창 − 발급 시각`(동적)** — 만료가 재연결 deadline과 일치 | 고정 TTL이 잔여 deadline을 넘으면 창 소진 후에도 토큰이 살아 소진 직전 룸에 좀비 입장하는 창이 열린다 |
| `INTERRUPTED` 유예 (Spring 스위퍼) | **창 + 마진(코드 상수 45s)** — 주 수렴(에이전트의 창 소진 룸 삭제)이 오지 않을 때의 안전망. 마진 근거: 양측 이탈 관측 시각 편차(수 초) + 에이전트 창 소진 처리·룸 삭제 bounded retry 최악 34s(HBB1-294 실측 상수)에 여유를 둔 값 | 창보다 짧으면 에이전트가 정당하게 기다리는 재연결 창을 Spring이 `ABORTED`+룸 삭제로 박탈한다 — 정합 위반의 최악 형태. 마진이 룸 삭제 소요보다 짧아도 같은 병리가 창 경계에서 재현된다 |

**identity 계약의 부수효과 (설계 활용)**: LiveKit은 동일 identity 재입장 시 기존 연결을 `DUPLICATE_IDENTITY`로 강제 퇴장시킨다 — 이탈 감지 전 재입장의 유령 연결이 자가 정리된다. 단 이 퇴장이 만드는 구 연결의 `participant_left(candidate)`가 재입장 joined **이후에** 도착할 수 있어(순서 무보장), 가드 없이는 복귀 직후 가짜 `INTERRUPTED`로 오전이된다 — 매핑의 reason 가드(1차), 이탈 전이 직후 즉시 대조(2차), 유예 스위퍼의 대조·복원(3차)이 방어한다(기능 1).

### 이벤트 → 상태 전이 매핑 확장

HBB1-294 매핑 표에 대한 증분이다. 표기 없는 행은 현행 유지이며, 전이 원칙(조건부 UPDATE 원자·멱등, terminal no-op, 공통 Clock, user 행 잠금 선행)은 전부 계승한다.

| 이벤트 | 세션 상태 | 전이 | 비고 |
| --- | --- | --- | --- |
| `participant_left` (candidate, reason=`DUPLICATE_IDENTITY`) | * | **no-op** | 동일 identity 재입장이 걷어찬 유령 연결의 퇴장 — 전이 재료가 아니다. reason 필드는 livekit-server 0.14.0 `ParticipantInfo.getDisconnectReason()`으로 확인(2026-08-06). Cloud webhook의 실제 population은 E2E 확인 항목이되, **미실림이어도 2차 방어(유예 스위퍼 대조)가 수렴을 보장한다** — 이 가드는 정밀도 계층이다 |
| `participant_left` (candidate, 그 외 reason) | `ACTIVE` | → **`INTERRUPTED`** (`disconnected_at` 기록) | 신규 매핑 — 전제 변경의 본체. 전이 커밋 직후 **즉시 대조 1회**(기능 1)가 candidate + AGENT 동시 관측 시 바로 `ACTIVE` 복원한다(reason 미실림 대비 2차 방어 — candidate 단독은 복원 증거가 아니다). `ROOM_DELETED` 사유 퇴장은 룸 소멸 동반이라 `room_finished` no-op/terminal 수렴에 흡수된다 |
| `participant_left` (candidate) | `AGENT_LOST` | 상태 유지 + **`disconnected_at` 기록(null일 때만)** | 교차곱: 에이전트 소실 중 candidate 이탈. 복원 에이전트 입장 시 대조가 `INTERRUPTED`로 분기할 때 재연결 deadline의 앵커가 된다 |
| `participant_left` (candidate) | `INTERRUPTED` | no-op | 중복·유령 — `disconnected_at` 갱신 금지(창 연장 금지, first-wins) |
| `participant_left` (candidate) | `PENDING`·terminal | no-op | 현행 유지 — 선입장 후 이탈은 empty timeout → `room_finished` 수렴 |
| `participant_joined` (candidate) | `INTERRUPTED` | **룸 참가자 대조 — AGENT 실존 확인**: candidate + AGENT 관측 → **`ACTIVE` 복귀**(`disconnected_at` 초기화) / candidate만 관측 → `INTERRUPTED` 유지 | 신규 매핑. joined 이벤트 자체를 복귀의 증거로 삼지 않는다 — 지연·중복 전달, 고아 룸 재생성 입장(기능 2)이 가능하고, candidate 단독은 `ACTIVE`의 증거가 아니다(전 대조 지점 공통 규칙). 대조 실패 시 `500`으로 재전송 유도(전이는 멱등). **`started_at`은 최초값 보존**(재앵커 없음 — stale 회수와의 정합은 기능 1의 stale ACTIVE 대조가 담당) |
| `participant_joined` (candidate) | `AGENT_LOST` | no-op | 에이전트 없는 `ACTIVE`는 만들지 않는다 — 이후 `participant_joined(agent)`의 대조가 candidate를 관측해 `ACTIVE`로 수렴 |
| `participant_joined` (candidate) | 그 외 | no-op | 현행 유지 (구 토큰 재입장 흡수 포함) |
| `participant_joined` (kind=AGENT) | `AGENT_LOST` | **룸 참가자 대조 — AGENT 실존 확인 선행**: 룸에 AGENT 부재 → **no-op**(지연·중복 joined 흡수) / AGENT 실존 + candidate 관측 → `ACTIVE`(`disconnected_at` 초기화) / AGENT 실존 + candidate 부재 → `INTERRUPTED`(`disconnected_at` 보존, null이면 현재 시각) | 신규 매핑 — 재디스패치 복귀 경로. AGENT 실존을 먼저 확인하는 이유: 소실 전 joined의 중복·지연 전달이 에이전트 없는 룸을 `ACTIVE`로 되살리는 가짜 복구를 차단한다(대조는 실시간 관측이라 참가자 목록 1회로 둘 다 판정). `started_at`은 보존하되 null(`PENDING`발 `AGENT_LOST`)이면 현재 시각 기록. 대조 실패 시 이벤트를 `500`으로 끝내 재전송 유도(전이는 멱등) |
| `participant_left`·`participant_connection_aborted` (kind=AGENT) | `PENDING`·`ACTIVE`·**`INTERRUPTED`(추가)** | 판별 3-경로 — ③ `AGENT_LOST` 전이 시 **`disconnected_at` 보존** | 교차곱: `INTERRUPTED` 중 에이전트 소실. 판별·표식·행 규칙은 HBB1-294 그대로 |
| `participant_connection_aborted` (candidate) | * | no-op 유지 | 미입장(접속 실패) 사건 — 재시도는 프론트 소관이며, `INTERRUPTED` 창은 재입장 성공(`joined`)만이 닫는다 |
| `room_finished` | 비terminal 전체 (`INTERRUPTED` 포함) | 행 있음 → `ENDED`, 없음 → `ABORTED` | 현행 규칙이 그대로 적용 — **에이전트의 창 소진 룸 삭제가 이 경로로 `ABORTED` 수렴한다(`INTERRUPTED`의 주 수렴 경로)** |

**시나리오별 수렴 경로 (증분 — 전부 terminal 수렴 검증)**:

| 시나리오 | 이벤트 흐름 | 최종 상태 |
| --- | --- | --- |
| 이탈 → 재입장 | left(candidate)→`INTERRUPTED` → joined(candidate) 대조(candidate+AGENT)→`ACTIVE` → 정상 진행·종료 | `ENDED` |
| 이탈 → 창 소진 | `INTERRUPTED` → 에이전트 룸 삭제(`RECONNECT_TIMEOUT`) → room_finished 행 없음 | `ABORTED` |
| 이탈 → 창 소진 (면접 시간도 소진) | `INTERRUPTED` → 에이전트 flush 후 정상 종료 → room_finished 행 있음 | `ENDED` |
| 이탈 + 에이전트도 소실 | `INTERRUPTED` → left(agent) ③ → `AGENT_LOST`(`disconnected_at` 보존) → 재디스패치 → joined(agent) 대조 부재 → `INTERRUPTED`(잔여 창) → 재입장 또는 창 소진 | `ENDED` / `ABORTED` |
| 에이전트 소실 (candidate 재실) | left(agent) ③ → `AGENT_LOST` → 재디스패치 → joined(agent) 대조 candidate 관측 → `ACTIVE` → 복원 진행 | `ENDED` |
| 재디스패치 실패·워커 부재 | `AGENT_LOST` → joined(agent) 부재 → 유예 만료(`disconnected_at` 있으면 재연결 deadline 이후 — 기능 3 deadline 충돌 방지) | `ABORTED` |
| 가짜 `INTERRUPTED` (유령 left 역전) | 재입장 joined→`ACTIVE` → 구 연결 left(candidate) 후착 — reason 가드 no-op, 미실림 시 `INTERRUPTED` 오전이 → 전이 직후 즉시 대조(불발 시 유예 스위퍼 대조)가 candidate+AGENT 관측 → `ACTIVE` 복원 | `ENDED` |
| 이탈 중 정상 종료 + `room_finished` 유실 | `INTERRUPTED` → 면접 시간 소진, 에이전트 flush·룸 삭제 → webhook 전량 유실 → 유예 스위퍼의 **행 판별 선행**이 행을 관측 | `ENDED` |
| `AGENT_LOST` 중 candidate 이탈·재입장 반복 | left/joined(candidate)는 `disconnected_at` 기록/no-op만 — joined(agent) 대조가 최종 실상으로 분기 | 대조 결과에 수렴 |
| `INTERRUPTED` 중 `/end` | ACTIVE 동일 취급 — SendData → 에이전트 클로징·flush(candidate 부재 무관) → room_finished 행 있음 | `ENDED` (무응답 시 fallback) |
| 복귀 직후 재이탈 반복 | episode마다 독립 창(복귀 시 `disconnected_at` 초기화 → 재이탈 시 신규 기록) — 벽시계 누적은 stale ACTIVE 대조·상한이 상한 | `ENDED` / `ABORTED` |

**수렴 완결성 (불변식 확장)**: 본 스토리로 `INTERRUPTED`가 도달 가능해지므로 HBB1-294 불변식의 대상에 추가한다 — **`INTERRUPTED`는 webhook 없이도 유예 만료 스위퍼(DB 앵커 `disconnected_at`)로 유한 시간 내 terminal 수렴한다.** 주 수렴은 에이전트의 창 소진 룸 삭제(`room_finished`)이고 스위퍼는 에이전트 동반 소실 시의 안전망이다. 대조 skip에는 상한(유예 컷오프 간격(창+마진)의 4배 — stale `PENDING` 패턴 계승)이 있어 수렴은 LiveKit 가용성에 조건부가 아니다. `AGENT_LOST`의 수렴(유예 만료)은 유지되며, 재디스패치는 유예를 연장하지 않는다 — 상태를 바꾸는 것은 `participant_joined(agent)`의 도착 또는 재디스패치 경로의 관측 기반 복원(AGENT 사전 확인 — 기능 3)뿐이다. 단 `disconnected_at`이 있는 `AGENT_LOST`의 만료 시각은 재연결 deadline과의 늦은 쪽이다(기능 3 — deadline 충돌 방지, 여전히 유한이라 불변식은 성립).

**전환 순서 (배포 조건)**: 에이전트의 "즉시 종료 폐지"(AI 배포)가 Spring의 `INTERRUPTED` 매핑보다 **먼저 또는 동시**여야 한다. AI 선행 시 과도기는 candidate 이탈이 no-op으로 남지만 에이전트 창 소진 룸 삭제가 `ABORTED`로 수렴한다(재입장만 불가 — 현행과 동일한 UX). 역순이면 구 에이전트의 즉시 종료가 판별 ③→재디스패치를 촉발해 무의미한 잡 1회가 낭비된다(자기 제한적이나 불필요).

---

## 재연결 상태 전이

### 설명

매핑 확장 표(Overview)의 candidate 전이·`INTERRUPTED` 유예 정리와, `INTERRUPTED` 도달화에 따른 기존 경로 개정을 구현해야 한다.

- **이탈 전이**: `participant_left(candidate)` × `ACTIVE` → `INTERRUPTED` + `disconnected_at`(수신 시각 — 공통 Clock). reason=`DUPLICATE_IDENTITY`는 상태 무관 no-op. 어댑터는 벤더 무관 이벤트 표현에 **disconnect reason**을 추가해 전달한다(`global.livekit` 격리 유지).
- **전이 직후 즉시 대조**: `INTERRUPTED` 전이 커밋 후 [트랜잭션 밖] 룸 참가자 대조 1회 — **candidate + AGENT 동시 관측 시에만** 즉시 `ACTIVE` 복원(`disconnected_at` 초기화). candidate만 관측되면 유지한다 — 에이전트 소실 webhook(`left(agent)`)이 지연·유실 중일 수 있어 candidate 단독 관측은 `ACTIVE`의 증거가 아니다(유예 스위퍼 대조와 동일 규칙 — 에이전트 없는 룸을 `ACTIVE`로 되살리는 병리 차단). reason 가드 불발(미실림) 시의 가짜 `INTERRUPTED`를 스위퍼 주기(창+마진)가 아니라 왕복 1회 안에 보정한다 — 상태 조회로 재연결 UI를 띄우는 프론트의 오표시 창 제거가 목적. 대조 실패·부재 관측은 아무것도 하지 않는다(스위퍼가 담당). reason 가드가 동작하는 환경에서는 이 경로에 도달하지 않는다.
- **복귀 전이**: `participant_joined(candidate)` × `INTERRUPTED` → **룸 참가자 대조 후 처리** — candidate + AGENT 관측 시 `ACTIVE` + `disconnected_at` 초기화, candidate만 관측 시 유지(전 대조 지점 공통 규칙 — 에이전트 없는 `ACTIVE` 금지), 대조 실패 시 `500`으로 재전송 유도. `started_at`·`end_requested_at`은 불변(최초 보존 — fallback 창도 복귀로 연장되지 않는다).
- **유예 만료 스위퍼** (공통 스위퍼에 술어 추가): `status=INTERRUPTED AND end_requested_at IS NULL AND disconnected_at ≤ now − (reconnect-window + 마진 45s)` 세션을 처리한다. **`end_requested_at`이 있는 세션은 fallback이 전담한다** — `/end`가 보장한 정상 종료 창(180s)을 유예 스위퍼가 선점·박탈하지 않는 우선순위 분리(HBB1-294 stale/fallback 분리와 동일 패턴). 처리는 **판별 재사용이 선행**한다 — 창 소진의 정상 수렴(에이전트 flush·룸 삭제)이 `room_finished` 유실로 미반영됐을 수 있으므로 룸 부재 단독으로 `ABORTED`를 단정하지 않는다(terminal 확정 원칙 — `ENDED` ⇔ 행 존재):
  - transcript 행 있음 → `ENDED`(`ended_at`) + 룸 삭제(best-effort) — 이탈 중 면접 시간 소진·정상 종료 완료의 webhook 유실 보정.
  - 행 없음 + 종료 표식 있음 → `ABORTED`(`ended_at`) + 룸 삭제(cause 로그 — `RECONNECT_TIMEOUT` 포함 불분기).
  - 둘 다 없음 → **룸 참가자 대조**: candidate + AGENT 동시 관측 → `ACTIVE` 복원(`disconnected_at` 초기화 — 가짜 `INTERRUPTED`의 최종 보정) / candidate만 관측(AGENT 부재) → 이번 회차 skip(에이전트 소실 webhook이 지연·유실 중일 수 있어 판별 경로에 양보) / 부재·룸 미존재 → `ABORTED` + 룸 삭제(best-effort).
  - 대조 실패도 이번 회차 skip. **skip 상한은 사유 불문 공통이다** — candidate-only 관측이든 대조 실패든 `disconnected_at`이 유예 컷오프 간격(창+마진)의 4배(코드 상수 — 창에만 곱하면 짧은 창 설정에서 컷오프와 역전된다)를 넘기면 대조 없이 행·표식 판별만으로 terminal을 확정한다(행 → `ENDED`, 없음 → `ABORTED` + 룸 삭제 — 수렴의 LiveKit 가용성 비의존, stale `PENDING` 상한과 동일 패턴·근거).
- **`/end` 표 개정**: `INTERRUPTED`를 `AGENT_LOST`와 분리한다 — 기존 행의 근거("에이전트 부재가 증거 기반")가 사라졌다(에이전트가 살아 대기 중). **`INTERRUPTED`는 `ACTIVE`와 동일 취급**: `end_requested_at` 기록(최초 1회) → 커밋 후 SendData → 에이전트가 candidate 부재 상태로 클로징·flush·룸 삭제 → `ENDED`. 진행된 면접 산출물(행·리포트)이 보존된다 — 즉시 `ABORTED`는 내용 손실이다. `AGENT_LOST` 행은 현행 유지(즉시 `ABORTED` + 룸 삭제) — 재디스패치 경합은 user 잠금 직렬화로 `ABORTED` 선기록이 CAS를 차단하고, 이미 나간 dispatch는 자기 제한적으로 소멸한다(기능 3 실패 모델).
- **fallback 대상 확장**: fallback 스위퍼 술어를 `status IN (ACTIVE, INTERRUPTED) AND end_requested_at ≤ …`로 확장한다 — `INTERRUPTED` 중 `/end` 후 무응답도 같은 선기록·룸 삭제로 수렴한다.
- **판별 대상 상태 확장**: `participant_left`·`connection_aborted`(kind=AGENT)의 판별 3-경로 대상에 `INTERRUPTED`를 추가한다. ③ 전이 시 `disconnected_at`을 보존한다(재연결 deadline·기발급 재입장 토큰 만료의 앵커 단절 금지).
- **stale ACTIVE 회수 개정**: `started_at` 앵커가 "대기 시간 소거"라는 전제가 재연결로 약화된다 — 이탈·복귀 반복(에이전트가 창 동안 면접 시계를 멈추는 경우) 시 정상 세션의 벽시계 체류가 45m를 넘을 수 있는데, 현행 ③은 대조 없이 즉시 `ABORTED`라 살아있는 면접을 정리해 버린다. **stale ACTIVE ③에도 룸 참가자 대조를 추가한다** — candidate + AGENT 동시 관측 시 이번 회차 skip, 그 외는 현행대로 즉시 `ABORTED`. skip 상한은 stale 임계의 4배(기존 `PENDING` 상수 공유) — hang 에이전트의 무한 skip 병리(HBB1-294가 ACTIVE 대조를 두지 않았던 근거)는 이 상한이 그대로 방어한다. ①·②(행·표식 판별)는 대조 없이 현행 유지.

### 실행 조건

- HBB1-294 인프라 전제(공유 Redis·PostgreSQL, webhook URL 등록, `livekit.*`) 그대로.
- 에이전트의 재연결 유지 동작(AI 배포)이 선행 또는 동시여야 한다 — Overview 전환 순서.

### 검증 기준

- `ACTIVE` 세션의 `participant_left(candidate)`로 `INTERRUPTED` 전이·`disconnected_at` 기록 확인, reason=`DUPLICATE_IDENTITY`는 상태 무관 no-op 확인
- `INTERRUPTED`·`AGENT_LOST`·`PENDING`·terminal에서의 `participant_left(candidate)`가 표대로 처리되는지 확인 (`INTERRUPTED` no-op의 `disconnected_at` 불변 — 창 연장 금지 포함)
- `participant_joined(candidate)` × `INTERRUPTED`: candidate+AGENT 관측 → `ACTIVE` 복귀·`disconnected_at` 초기화·`started_at` 불변, candidate만 관측 → `INTERRUPTED` 유지, 대조 실패 → `500`(재전송 유도) 확인, × `AGENT_LOST` → no-op 확인
- `INTERRUPTED` 중 `participant_left(agent)` ③ → `AGENT_LOST` 전이 시 `disconnected_at` 보존 확인
- 이탈 전이 직후 즉시 대조: candidate+AGENT 동시 관측 시 `ACTIVE` 즉시 복원, candidate만·부재·대조 실패 시 `INTERRUPTED` 유지 확인 (candidate 단독 복원 금지)
- 유예 스위퍼의 판별 선행: 만료 `INTERRUPTED` + 행 있음 → `ENDED`(webhook 유실 보정 — `ABORTED` 오판 금지), 행 없음+표식 → `ABORTED`+cause 로그 확인
- 유예 스위퍼의 대조 분기(행·표식 없음): candidate+AGENT → `ACTIVE` 복원 / candidate만 → skip / 부재·룸 미존재 → `ABORTED`+룸 삭제 — 처리되는지, 미만료·타 상태는 불변인지 확인
- `end_requested_at`이 기록된 `INTERRUPTED` 세션은 유예 스위퍼가 건드리지 않고 fallback이 전담하는지 확인 (우선순위 분리)
- 대조 실패·candidate-only 공통으로 이번 회차 skip, 상한((창+마진)×4) 경과 시 대조 없이 행·표식 판별로 terminal 확정(행 → `ENDED`) 확인
- 유예 중 `room_finished` 선착(에이전트 창 소진 룸 삭제) 시 행 유무 규칙으로 먼저 수렴하고 스위퍼가 no-op인지 확인
- `/end` × `INTERRUPTED`: `end_requested_at` 기록·SendData 발신·`202` 확인(즉시 `ABORTED` 아님), fallback 스위퍼가 `INTERRUPTED`+`end_requested_at` 만료 세션을 선기록·룸 삭제로 처리하는지 확인
- stale ACTIVE ③: candidate+AGENT 관측 시 skip(상한 내), 상한 경과 시 강제 정리, 그 외 관측은 현행 즉시 `ABORTED` 확인
- `INTERRUPTED` 세션 보유 유저의 신규 생성이 409로 거부되고(IN_PROGRESS 계열 — 기존 가드), 유예 정리 후 생성 가능해지는지 확인
- 수동 E2E는 공통: 수동 검증 참조

### 성능 요구사항

- 없음 — 추가 LiveKit 왕복은 이탈 전이 직후 즉시 대조 1회·복귀 전이 대조 1회(이탈·재입장 이벤트는 세션당 수 건)와 스위퍼 대조(대상 세션당 회차별 최대 1회 — 행·표식 판별로 걸러진 세션만)이며, `livekit.api-timeout` 상한·트랜잭션 밖 원칙(HBB1-294)을 계승한다.

### 인터페이스 요구사항

- webhook 어댑터의 벤더 무관 이벤트 표현에 disconnect reason 필드 추가 (`global.livekit` → 세션 도메인).
- 룸 참가자 대조는 기존 어댑터(stale `PENDING` 대조) 재사용.

### 제약사항

- `disconnected_at`의 의미는 "현재 `INTERRUPTED` episode의 이탈 관측 시각"이다 — 복귀 시 초기화하며 이력을 누적하지 않는다.
- 프론트의 재연결 UI·재입장 호출 흐름은 프론트 스토리 소관.

### 기타 요구사항

- 전이 로그는 세션 id·이벤트·reason만 — 기존 방침 계승.

---

## 재입장 토큰 발급 API

### 설명

`POST /api/v1/sessions/{id}/rejoin` — 이탈한 candidate가 같은 세션에 재입장할 토큰을 발급한다.

- **identity 동일 보장**: 최초와 같은 `candidate-{sessionId}`(결정적 파생 — 난수·시각 성분 없음). 에이전트의 재개 판정(identity 일치)이 이 보장 위에 성립한다. 잔존 유령 연결은 LiveKit의 `DUPLICATE_IDENTITY` 강제 퇴장이 정리한다.
- **발급 조건**: 소유 세션이고, `status ∈ {INTERRUPTED, AGENT_LOST}` 이고, `disconnected_at IS NOT NULL` 이고, `now < disconnected_at + reconnect-window`. 위반 시 `409 SESSION_NOT_REJOINABLE`(S009 — 상태 부적합·창 만료·이탈 미관측 통합). **사유 구분은 로그로만 남긴다** — 메시지 자구는 계약이 아니고, S009가 발생하는 시점은 전부 "재시도 무의미"(재입장 대상이 아니게 됐거나 창이 끝남)라 프론트가 분기할 지점이 없다 — 일괄 종료 화면 처리(인터페이스 요구사항). `AGENT_LOST`를 포함하는 이유: 에이전트 소실·재디스패치 진행 중에도 candidate 재입장은 유효하다 — 입장해 두면 `participant_joined(agent)` 대조가 `ACTIVE`로 수렴시킨다.
- **TTL = 재연결 deadline 기준**: `disconnected_at + reconnect-window − now` (동적, 계약 — 값 정합 표). 에이전트도 자기 관측 시각 기준 같은 창을 적용하며 양측 수 초 편차는 감수한다.
- **무상태 발급**: 상태 전이·기록 없음 — 중복 발급은 무해하다(모든 토큰이 같은 identity·같은 deadline).
- **처리 순서**: 트랜잭션{user 행 잠금 → 세션 조회·소유 검증 → 발급 조건 검증} → 토큰 서명(로컬 연산 — LiveKit 왕복 아님) → 응답. 잠금이 유예 스위퍼의 `ABORTED`와 직렬화한다.
- **고아 룸 잔여 위험 (감수 — 명시)**: 발급 직후 스위퍼·에이전트가 세션을 닫는 잔여 경합에서 "join 실패로 표면화"를 전제하지 않는다 — LiveKit은 미존재 룸을 join 시 자동 생성할 수 있어(HBB1-294 "구 토큰의 빈 룸 재생성"과 동일 병리), 아직 유효한 토큰(양측 이탈 관측 시각 편차로 에이전트가 룸을 닫은 뒤에도 수 초 생존 가능)이 **candidate만 있는 고아 룸**을 만들 수 있다. 상태 정합에는 영향이 없다 — 세션이 이미 terminal이면 `joined(candidate)`가 no-op이고, **아직 `INTERRUPTED`면**(`room_finished` 미도착·유실) 복귀 전이의 룸 대조가 candidate 단독을 걸러 `INTERRUPTED`를 유지하므로(기능 1) 에이전트 없는 `ACTIVE`는 생기지 않는다. 수렴은 유예 스위퍼와 `room_finished`(candidate 퇴장 후 empty timeout)가 보장한다. 잔여는 candidate가 빈 룸에서 대기하는 UX이며, 완화는 프론트 계약(인터페이스 요구사항)이다.
- **응답**: 세션 생성 응답(`InterviewSessionCreateResponse`)과 **동일 필드명** — `livekitToken`·`livekitUrl`·`livekitRoom`. 프론트가 생성 응답을 보관하지 못한 경로(새 탭·앱 재시작)에서도 접속 정보가 응답만으로 완결되고, 생성의 접속 코드를 그대로 재사용한다.
- **최초 토큰 TTL 단축 (동반 변경)**: `livekit.token-ttl` 기본 1h → **10m**. 최초 생성 흐름의 발급→입장 실소요는 초 단위라 10m은 충분한 여유다. 효과: HBB1-18이 감수한 구토큰 잔여 위험(빈 룸 재생성·낡은 재입장)의 창이 1h→10m로 축소되고, stale `PENDING` 대조 상한의 안전 근거("토큰 TTL이 늦은 입장 차단" — HBB1-294)가 강화된다. 트레이드오프: 워커 장기 다운(>10m) 후 복귀 시 최초 토큰이 먼저 만료해 뒤늦은 매칭 세션에 입장 불가 — 해당 세션은 어차피 자동 교체·stale 회수의 수렴 대상이라 감수한다(유저는 재생성). dev/prod 배포 매니페스트의 `LIVEKIT_TOKEN_TTL` 권장값 갱신을 포함한다.

### 실행 조건

- 유저 인증(`Authorization: Bearer`). 토큰 서명은 기존 `livekit.*` 자격증명.

### 검증 기준

- `INTERRUPTED` 세션의 rejoin이 같은 identity(`candidate-{sessionId}`)·생성과 동일한 `livekitUrl`·`livekitRoom`과 함께 발급되고, 토큰 만료가 `disconnected_at + 창`과 일치(오차는 처리 시간 이내)하는지 확인
- `AGENT_LOST` + `disconnected_at` 있는 세션도 발급되는지, `disconnected_at` 없는 `AGENT_LOST`(candidate 재실)는 S009인지 확인
- `ACTIVE`·`PENDING`·terminal 세션의 rejoin이 S009인지, 창 만료(`disconnected_at + 창` 경과) 시 S009인지 확인 (`@ParameterizedTest`)
- 미존재 `404 S006`, 타 유저 `403 S007`, 미인증 `401` 확인
- 중복 발급이 상태를 바꾸지 않는지 확인 (무상태)
- 최초 생성 토큰의 TTL이 설정값(10m)을 따르는지 확인 (기존 발급 경로 회귀)

### 성능 요구사항

- 없음 — LiveKit 왕복 없는 로컬 서명 + 단건 조회.

### 인터페이스 요구사항

- 엔드포인트: `POST /api/v1/sessions/{id}/rejoin` (인증 필요) — `200 OK`, `{ success: true, data: { id, livekitToken, livekitUrl, livekitRoom } }` (생성 응답과 동일 구조·필드명).
- 프론트는 S009를 사유 불문 일괄 "세션 종료됨"으로 처리한다 — 사유별 분기 계약 없음(Swagger에 명시).
- 프론트 계약: 재입장 후 일정 시간 내 AGENT participant를 관측하지 못하면 퇴장·종료 화면으로 처리한다(무한 대기 금지 — 고아 룸 완화). 구체 대기값·UI는 프론트 스토리 소관이며, 본 계약은 "AGENT 미관측 무한 대기 금지"까지다.
- 에러 코드: 공통: 에러 코드 참조.

### 제약사항

- 재입장 토큰은 최초 토큰과 같은 권한 스코프(같은 룸·같은 identity)다 — 별도 grant 축소는 두지 않는다.
- 프론트 재연결 화면·자동 재시도 정책은 프론트 스토리 소관.

### 기타 요구사항

- 토큰 원문은 로그 금지(기존 방침). 발급 로그는 세션 id·잔여 TTL(초)만.

---

## 에이전트 재디스패치

### 설명

판별 ③으로 `AGENT_LOST` 전이가 확정된 세션에 대해, 유예 창 내 에이전트 복원을 시도해야 한다.

- **트리거**: 실시간 판별 ③(`participant_left`·`connection_aborted`(agent) 처리)의 `AGENT_LOST` 전이 **커밋 직후 1회**. 스위퍼 구동이 아니다 — 빠른 복원이 목적이고, 유예 스위퍼는 복원 실패의 수렴만 담당한다. stale 회수 경로의 ③(즉시 `ABORTED`)은 현행대로 재디스패치하지 않는다 — 면접 상한을 지난 시점의 복원은 무의미하다(HBB1-294 근거 유지).
- **최대 1회 (CAS — at-most-once)**: 신규 컬럼 `redispatched_at`(timestamptz nullable)에 대한 조건부 UPDATE — `SET redispatched_at = now WHERE id = ? AND status = 'AGENT_LOST' AND redispatched_at IS NULL` — 1건 갱신일 때만 진행한다(user 행 잠금 선행 — `/end`·스위퍼와 직렬화). **CAS는 사전 확인에서 AGENT 부재가 확인된 뒤에만 시도한다** — 마커의 의미는 "실제 dispatch 생성 시도 권한의 소진"이며, dispatch를 만들지 않는 관측 기반 복원은 마커를 소진하지 않는다(복원된 에이전트가 이후 실제로 소실되면 그 판별 ③은 온전한 재디스패치 기회를 가진다). 세션당 실제 재디스패치는 생애 최대 1회이며 재시도는 없다. **이 CAS의 보장은 at-most-once다** — 마커 커밋과 LiveKit 호출 사이 프로세스 다운 시 재디스패치가 미실행으로 남는 창은 감수한다: 재시도 가능한 마커(시도 상태 추적)를 두면 중복 실행 창이 생겨 단일성 계약의 본질(중복 금지)이 깨지고, 미실행 세션은 유예 만료 `ABORTED` → 유저 재생성이 이미 유한 복구 경로다. outbox 류 실행 보장 인프라는 단일 인스턴스·발생 빈도(crash가 정확히 그 사이에 있어야 함) 기준으로 두지 않는다 — 실측 시 재검토(기존 판정 기준 계승).
- **사전 확인 → CAS → 정리 → 생성 (단일성 계약 1차)**: `AGENT_LOST` 전이 커밋 직후 [트랜잭션 밖] 다음 순서로 진행한다.
  - ⓪ **AGENT 사전 확인**(참가자 조회 — **CAS보다 먼저**): AGENT 관측 시 CAS·삭제·생성 없이 **관측 기반 복원**으로 끝낸다 — 확보한 참가자 목록에 `joined(agent)` 매핑과 같은 분기를 적용한다: AGENT + candidate → `ACTIVE`, AGENT만 → `INTERRUPTED`(`disconnected_at` 보존, null이면 현재 시각). 반영은 별도 트랜잭션(user 잠금 + `status = AGENT_LOST` 조건부 UPDATE — 대조와 반영 사이의 상태 변화는 술어가 걸러낸다). **삭제 전의 관측이라 "종료 중인 구 잡" 모호성이 없다** — 살아 재연결한 구 에이전트면 이 복원으로 복구가 완성되고 기존 dispatch도 건드리지 않으며, **`redispatched_at`은 null로 남는다** — 복원은 재디스패치 기회를 소진하지 않는다(복원된 에이전트가 이후 실제로 소실되면 그 판별 ③이 실 디스패치를 시도할 수 있다). webhook 재전달에 복원을 맡기지 않는 이유: 최종 유실 시 실제 동작 중인 에이전트·룸을 유예 만료가 `ABORTED`로 오정리한다(webhook 비의존 수렴 원칙 — stale `PENDING`의 관측 기반 복원과 같은 패턴).
  - ① 부재였으면 **CAS로 재디스패치 권한을 획득한다**(위 "최대 1회" 항목 — 실패 시 여기서 끝) → ② `listAgentDispatch(룸)` → ③ 잔존 dispatch 전건 `deleteAgentDispatch`(공집합이면 생략 — 통상 경로: 잡 사망 시 dispatch도 소멸) → ④ **AGENT 부재 재확인**: 삭제 직후 AGENT가 관측되면 **복원하지 않고 이번 복구를 포기한다** — `DeleteDispatch`가 잡 종료 완료를 계약하지 않으므로 이 관측은 종료 중인 구 잡일 수 있고(사전 확인 부재~삭제 사이에 끼어든 재연결 포함), 복원 증거로 쓰면 곧 재소실 후 `redispatched_at` 소진으로 재복구 불가가 된다. 포기 세션은 유예 만료가 수렴시킨다(실제로는 살아있던 에이전트였을 극소 케이스의 조기 `ABORTED`는 감수 — 창이 삭제~재확인 사이로 좁다).
  - ⑤ **생성 직전 상태 재확인**: `status = AGENT_LOST`가 아니면 생성하지 않는다 — user 잠금의 직렬화는 CAS 트랜잭션까지다. CAS 커밋~create 사이에 `/end`가 `ABORTED` 전이·룸 삭제를 끝내면 이후의 `createDispatch`가 terminal 세션의 룸을 재생성하므로(룸 자동 생성 — 실측), 직전 재확인으로 이 창을 좁힌다(agent-dispatch.md 승계 재확인과 같은 패턴). 재확인~create 사이의 극소 잔여 창은 잔여물 절의 자기 제한 소멸로 감수한다.
  - ⑥ `createDispatch(룸, "kkori-interviewer", metadata, JRP_NEVER)`.
  - 어느 단계든 조회·삭제 실패 시 생성하지 않는다(동시 잡 방지가 복원보다 우선 — 세션은 유예 만료로 수렴). 부재 재확인 이후 구 잡이 재연결하는 잔여 창은 에이전트 owner 검사가 완화한다(계약 절 2계층). 어댑터는 기존 `SessionAgentDispatcher` 확장(`global.livekit` 격리·로깅 방침 그대로).
- **metadata 재조립**: 기존 `DispatchMetadataAssembler` 재사용 — 4필드 계약·자구 그대로. 조립 입력(세션 속성·이력서 `structured_data`)은 CAS 트랜잭션에서 확보한다. **최초와 달라질 수 있는 경로는 실재하지 않는다**: `INTERRUPTED`·`AGENT_LOST`는 non-terminal이라 `RESUME_IN_USE` 차단이 유지되어 이력서 수정·삭제가 불가하다 — 재조립 결과는 최초와 동일하며 스냅샷 저장을 두지 않는다.
- **복귀 전이**: 재디스패치된 에이전트의 `participant_joined(agent)` × `AGENT_LOST` → 룸 대조 분기(매핑 확장 표 — **AGENT 실존 확인 선행**: 부재면 no-op으로 지연·중복 joined를 흡수) — candidate 재실이면 `ACTIVE`, 부재면 `INTERRUPTED`(잔여 창). 신규/복원 판별은 에이전트가 Redis 상태 존재로 스스로 한다(계약 — Spring은 관여하지 않는다).
- **`JRP_NEVER` 유지 (확정)**: agent-dispatch.md의 재검토 항목을 본 스토리에서 확정한다 — 재실행 주체는 Spring 상태 머신 하나다. LiveKit 자동 재실행은 상태 머신에 모델링되지 않은 경로이자 활성 dispatch 단일성(중복 금지)을 깨는 두 번째 실행 주체라 계속 차단한다.
- **실패 모델**: list·delete·create 어느 단계 실패든 로그만 남기고 끝낸다(무재시도·에러 응답 없음 — 사용자 요청 경로가 아니다). 세션은 유예 만료 `ABORTED`로 수렴한다. 재디스패치 실패의 사용자 통지는 범위 밖(후속).
- **유예 값 상향 (60s → 90s)**: HBB1-294가 "재dispatch 스토리에서 재검토"로 걸어둔 값이다. 유예가 이제 순수 대기가 아니라 복원 창이다 — dispatch 왕복 + 워커 job 할당·프로세스 기동·룸 join(통상 수~수십 초)을 덮고 joined webhook 지연 여유를 둔 90s로 상향한다. 재디스패치는 유예를 연장하지 않으므로(앵커 `agent_lost_at` 불변) 스위퍼가 복원 진행 중 세션을 조기 정리하는 병리는 "90s 내 join(또는 관측 기반 복원) 미도착"뿐이고, 그 경우 정리가 맞다(joined 선착 시 조건부 UPDATE 경합은 어느 쪽이 이겨도 수렴 — `ACTIVE` 선전이면 스위퍼 술어 불일치 no-op, `ABORTED` 선기록이면 joined가 terminal no-op).
- **deadline 충돌 방지 (`disconnected_at` 있는 `AGENT_LOST`)**: 유예 만료 시각은 `agent_lost_at + 90s`와 `disconnected_at + reconnect-window + 마진 45s` 중 **늦은 쪽**이다. candidate 이탈 중 에이전트가 소실된 세션을 90s에 정리하면, 발급된 재입장 토큰의 deadline(이탈 + 3분) 약속을 상태 머신이 선제 파기하고, 계류 dispatch의 뒤늦은 join + candidate 재입장이 그 창 안에서 완성할 복구를 박탈한다. 두 앵커 모두 DB 컬럼·유한값이라 수렴 완결성은 유지된다(`disconnected_at` 없는 세션 — candidate 재실 — 은 90s 단독 그대로).
- **유예 만료 정리의 행 재판별**: 만료 세션의 `ABORTED` 확정 전에 transcript 행을 재확인한다 — 행 있으면 `ENDED`(기능 1 유예 스위퍼와 같은 terminal 확정 원칙 적용: 재디스패치 복원 에이전트가 면접을 완료했는데 `room_finished`·`joined`가 전부 유실된 병리에서 정상 완료를 `ABORTED`로 오판하지 않는다). EXISTS 1회 추가 비용뿐이다.
- **잔여물 (감수 — HBB1-289·294 계승)**: 유예 만료 `ABORTED` 후 계류 dispatch가 뒤늦게 할당되면 `createDispatch`의 룸 자동 생성으로 에이전트만 있는 룸이 잠시 생길 수 있다 — 에이전트는 Redis 상태로 복원하더라도 candidate가 올 수 없어(토큰 만료·창 소진) 자기 창 소진으로 룸을 삭제하고, `room_finished`는 terminal no-op이다. 기존과 동일한 자기 제한적 소멸이며 `listDispatch` 상시 보상은 두지 않는다(발생 실측 시 도입 — 기존 판정 기준 계승).

### 실행 조건

- 기존 디스패치 전제(worker 등록·`livekit.*`) 그대로. `listAgentDispatch`·`deleteAgentDispatch`는 livekit-server 0.14.0 `AgentDispatchServiceClient` 제공 범위다.

### 검증 기준

- 판별 ③ `AGENT_LOST` 전이 커밋 후 복구 파이프라인이 1회 진입하는지 — 사전 확인→(부재 시)CAS→list→(잔존 시)delete→부재 재확인→상태 재확인→create 순서로 진행되고, CAS 실패 시 이후 단계가 없는지, list 공집합이면 delete 없이 재확인으로 넘어가는지 확인
- **사전 확인**에서 AGENT 관측 시 CAS·삭제·create 없이 관측 기반 복원되는지 — AGENT+candidate → `ACTIVE`, AGENT만 → `INTERRUPTED`(`disconnected_at` 보존), 반영 시점에 상태가 이미 바뀌었으면 조건부 UPDATE no-op — 확인 (구 잡 재연결 복구, dispatch 불변)
- 사전 확인으로 복원된 세션의 `redispatched_at`이 null로 유지되고, 이후 실제 소실(판별 ③ 재발) 시 재디스패치가 가능한지 확인 (복원의 기회 비소진)
- **삭제 후 부재 재확인**에서 AGENT 관측 시 복원·create 모두 하지 않고 포기하며, 세션이 유예 만료로 수렴하는지 확인 (종료 중 구 잡 모호성 — 복원 증거 불사용)
- 동시 판별(중복 webhook)·재호출에서 CAS가 중복 실행을 차단하는지(최대 1회) 확인 (동시성 테스트)
- 재조립 metadata가 최초 디스패치와 자구까지 동일한지 확인 (계약 픽스처 재사용)
- 재디스패치 요청의 재시작 정책이 `JRP_NEVER`인지 확인
- 기존 dispatch 삭제 실패 시 create가 호출되지 않고 세션이 유예 만료로 수렴하는지 확인
- create 실패 시 로그만 남기고 유예 만료 `ABORTED`로 수렴하는지, `redispatched_at`은 유지되는지(재시도 없음) 확인
- `participant_joined(agent)` × `AGENT_LOST`: 룸에 AGENT 부재 → no-op(지연·중복 joined — 가짜 복구 차단), AGENT 실존+candidate 관측 → `ACTIVE`(`disconnected_at` 초기화·`started_at` 보존), AGENT 실존+candidate 부재 → `INTERRUPTED`(`disconnected_at` 보존), `started_at` null이면 현재 시각 기록 확인
- `/end` 선착(`ABORTED` 완료 후 트리거 진행) 시 CAS가 실패해 dispatch 변경 호출(list·delete·create)이 없는지 확인 (사전 확인 조회는 무해한 읽기 — 복원 시도가 있어도 조건부 UPDATE no-op)
- CAS 선착 후 create 전에 `/end`가 `ABORTED`를 끝낸 경우, 생성 직전 상태 재확인이 create를 차단하는지 확인 (결정적 재현 — 재확인 시점에 전이를 끼워 넣는 테스트, 극소 잔여 창은 잔여물 절 감수)
- stale 회수 ③ 경로에서는 재디스패치가 실행되지 않는지 확인
- 종료 표식이 있는 세션은 판별 ②로 빠져 재디스패치에 도달하지 않는지 확인 (계약 자연 충족의 회귀 고정)
- 유예 스위퍼가 90s 미만 `AGENT_LOST`를 건드리지 않고, `disconnected_at` 있는 세션은 90s가 지나도 재연결 deadline+마진 전에는 건드리지 않는지 확인 (deadline 충돌 방지)
- 만료 `AGENT_LOST` 정리 시 행 재판별 — 행 있음 → `ENDED`, 없음 → `ABORTED`+룸 삭제 확인 (terminal 확정 원칙)

### 성능 요구사항

- 없음 — 재디스패치 경로의 LiveKit 왕복은 세션당 최대 5회(사전 확인·list·delete·부재 재확인·create), 전부 트랜잭션·잠금 밖·`api-timeout` 상한.

### 인터페이스 요구사항

- `SessionAgentDispatcher` 어댑터 확장: dispatch 목록 조회·삭제. 실패 로그는 룸 이름·HTTP 코드 등 식별자만(metadata 전문 금지 — 기존 방침).

### 제약사항

- 재디스패치는 실시간 판별 ③에서만 — stale 경로·수동 트리거 없음.
- 재디스패치 실패의 사용자 통지(오류 안내 채널)·재시도 정책은 범위 밖.

### 기타 요구사항

- 재디스패치 로그: 세션 id·룸 이름·삭제한 기존 dispatch 수·결과. metadata는 UTF-8 바이트 수만(기존 방침).
- `AGENT_LOST` 유예 만료 정리 로그에 `redispatched_at` 유무를 포함한다 — 단 이 값은 **CAS 도달 여부만** 말한다(LiveKit 호출 전 기록이라 "CAS 후 프로세스 다운"·"list/delete/create 실패"·"create 성공 후 join 미도착"을 구분하지 못한다). 결과 구분은 재디스패치 단계별 결과 로그와 세션 id correlation으로 한다 — 진단 전용 결과 컬럼(`redispatch_result` 류)은 두지 않는다(상태 최소주의 — 로그로 충분).

---

## 공통: 스위퍼·설정

- **스위퍼**: 기존 단일 `@Scheduled` 컴포넌트에 `INTERRUPTED` 유예 스캔을 추가한다(앵커 `disconnected_at` — DB 기반, 재시작 무손실 원칙 유지). fallback 술어는 `INTERRUPTED`를 포함하도록 확장한다.
- **신규 컬럼** (`interview_session`): `redispatched_at`(timestamptz, nullable — 재디스패치 CAS 마커). `disconnected_at`은 기예약 컬럼을 사용한다. ERD(`docs/erd.md`) 갱신 포함. 마이그레이션 하드 제약은 HBB1-294와 동일 — dev/prod 배포는 Flyway 선행, **본 스토리 완료 기준은 local·CI + 로컬 실환경 수동 E2E**.
- **설정**:

  ```yaml
  session:
    reconnect-window: ${SESSION_RECONNECT_WINDOW:3m}   # 재연결 창 — 크로스 레포 계약값 (AI 레포 설정과 동일 주입)
    agent-lost-grace: ${SESSION_AGENT_LOST_GRACE:90s}  # 60s → 90s 상향 (재디스패치 왕복·join 소요 포함 — 기능 3)
  livekit:
    token-ttl: ${LIVEKIT_TOKEN_TTL:10m}                # 1h → 10m 단축 (기능 2)
  ```

  - `INTERRUPTED` 유예 = `reconnect-window` + 마진 45s(코드 상수 — 별도 설정 없음: 계약값에서 파생시켜 정합을 구조적으로 보장하고, 마진을 독립 설정으로 두면 정합 붕괴 경로만 늘어난다). 마진 45s 근거: 양측 이탈 관측 시각 편차(수 초) + 에이전트 창 소진 처리·룸 삭제 bounded retry 최악 34s(HBB1-294 실측 상수)에 여유를 둔 값. 대조 skip 상한 = 유예 컷오프 간격(창+마진)의 4배(코드 상수 — stale `PENDING` 상수와 동일 패턴, 역전 불가 기준).
  - `AGENT_LOST` 유예 만료 시각은 `disconnected_at` 있는 세션에 한해 `max(agent_lost_at + 90s, disconnected_at + 창 + 45s)` — 기능 3 deadline 충돌 방지.
  - dev/prod 매니페스트: `SESSION_RECONNECT_WINDOW` 추가, `SESSION_AGENT_LOST_GRACE`·`LIVEKIT_TOKEN_TTL` 권장값 갱신 — 배포 전 필수 체크리스트에 반영.

## 공통: 에러 코드

| 코드 | 이름 | HTTP | 상황 |
| --- | --- | --- | --- |
| S009 | SESSION_NOT_REJOINABLE | 409 | 재입장 불가 — 상태 부적합(`INTERRUPTED`·`AGENT_LOST` 아님)·이탈 미관측·재연결 창 만료. 사유는 로그로만 구분하며 프론트는 일괄 "세션 종료됨" 처리 (신설) |

- S001~S008 기존 정의 유지.

## HBB1-294 문서 갱신 범위 (`interview-session-completion.md` — 계약 변경 동시 반영)

| 절 | 변경 |
| --- | --- |
| 에이전트 동작 사실 | candidate 이탈 절을 재연결 유지 동작으로 교체(본 문서 계약 인용), 표식 cause에 `RECONNECT_TIMEOUT`·`RECOVERED_CLOSING` 추가(존재 판별 불변 명시) |
| 매핑 표 | `participant_left`(candidate) no-op 행 제거 → 본 문서 매핑 참조, 판별 대상 상태에 `INTERRUPTED` 추가, `connection_aborted`(candidate) 비고의 "재연결 스토리 소관" 문구 해소 |
| `/end` 표 | `AGENT_LOST`·`INTERRUPTED` 행 분리 — `INTERRUPTED`는 ACTIVE 동일 취급으로 개정, "본 스토리 도달 불가" 문구 제거 |
| fallback | 대상 상태에 `INTERRUPTED` 포함 |
| 시나리오 표 | candidate 이탈 행을 재연결 시나리오로 교체 |
| 수렴 완결성 | `INTERRUPTED`를 불변식 대상에 추가, "도달 불가·재연결 스토리가 도입" 문구 해소 |
| stale 회수 | ACTIVE ③에 대조·skip 상한 추가(전제 약화 반영), 토큰 TTL 인용(1h) 자구를 10m로 갱신 |
| `AGENT_LOST` 유예 만료 | 만료 시각을 `disconnected_at` 있는 세션에 한해 재연결 deadline과의 늦은 쪽으로 개정, 정리 전 행 재판별 추가(terminal 확정 원칙 — 행 있으면 `ENDED`) |
| 공통: 설정 | `agent-lost-grace` 90s 갱신(값 근거 포함), 유예 "재dispatch 스토리에서 재검토" 문구 해소 |
| 범위 제외 | 재연결·재디스패치 항목 제거(본 스토리로 이관 완료 표기) |

agent-dispatch.md는 `JRP_NEVER` 재검토 항목의 확정(유지 — 본 문서 기능 3 참조) 각주만 갱신한다.

## 공통: 수동 검증 (E2E)

HBB1-294와 동일 체계(로컬 Spring + 로컬 AI 워커 + 실제 LiveKit Cloud + 로컬 터널 webhook). 서명 요청 기반 전이·유예·재디스패치는 자동 테스트(JUnit)로 검증하고, 실환경은 다음을 관통 확인한다:

- candidate 이탈(탭 종료) → `INTERRUPTED` → rejoin 발급 → 재입장 → 면접 이어짐 → `ENDED` (대화 맥락 보존 확인)
- candidate 이탈 → 창(3분) 소진 → 에이전트 룸 삭제 → `ABORTED` → 이력서 차단 해제·재생성 가능
- 에이전트 프로세스 강제 종료 → `AGENT_LOST` → 재디스패치 → 복원 입장 → `ACTIVE` 복귀 → 면접 이어짐 (candidate 재실 경로)
- 에이전트 강제 종료 + candidate도 이탈 → 재디스패치 → `INTERRUPTED` → 재입장 → `ACTIVE` (교차곱 경로)
- `INTERRUPTED` 중 `/end` → 클로징·flush → `ENDED` + 리포트 생성 확인
- 창(3분) 만료 후 기발급 재입장 토큰으로 입장 시도 → LiveKit이 만료 토큰을 거부하는지 확인 (TTL=deadline 계약의 실효성 — 좀비 입장 차단)
- 세션 종료 직후(에이전트 룸 삭제·스위퍼 정리 직후) 아직 유효한 재입장 토큰으로 입장 시도 → 고아 룸(candidate 단독) 생성 여부 관측 → 세션 상태 불변(terminal)·empty timeout 소멸 확인 (고아 룸 경계 — 프론트 AGENT 미관측 처리의 전제 확인)
- webhook의 `participant_left(candidate)`에 disconnect reason이 실려 오는지 관측 — `DUPLICATE_IDENTITY` 가드의 실효성 확인(미실림이면 가드는 dead code로 남기지 않고 제거하되 즉시 대조·스위퍼 대조가 수렴 담당임을 재확인)

## AI 세션 교차 검토 항목

양쪽 PRD 초안의 다음 3개 결론이 일치해야 한다(불일치 시 계약 재합의):

1. **candidate 이탈 전제**: 즉시 종료 폐지·재연결 창 유지, 창 소진 시 flush 없는 룸 삭제(→`ABORTED`) / 시간 소진 시 정상 종료(→`ENDED`) — 본 문서 "에이전트 동작 사실 (개정)".
2. **값 3종 정합**: 창 3m 단일 계약값 / 재입장 토큰 TTL은 deadline 기준 동적 / Spring `INTERRUPTED` 유예는 창+마진(안전망 역할) — 본 문서 값 정합 표.
3. **metadata 불변**: 재디스패치는 동일 4필드 재조립, 구분 필드 없음, 복원 판별은 Redis 상태 존재 기반.
4. **단일성 2계층**: "정확히 1회" 자구는 중복 금지(at-most-once)의 뜻 / Spring 순서 보장(정리·부재 확인 후 생성)이 1차, 에이전트 owner 검사가 delete 후 구 잡 재연결 잔여 창의 완화 계층 — owner 검사가 이 잔여 창을 전제로 동작하는지.

AI 세션에 확인 요청 2건: (a) **재연결 창 동안 면접 시계가 멈추는지**(멈추면 Spring stale ACTIVE의 벽시계 상한 초과가 실재 — 본 문서는 대조+상한으로 이미 방어하나 문서 명제화용) (b) **재이탈 반복 횟수 상한 유무**(무상한이면 Spring 측 상한은 stale 대조 상한이 유일 — 감수 확인). identity 보장(회신 항목)은 **보장됨**으로 회신 완료 처리한다.

## 범위 제외

- AI 측 재연결 대기·복원·Redis 내구 저장 구현 (병행 스토리)
- 재디스패치 실패의 사용자 통지(오류 안내 채널) — 후속
- E1(탈퇴) 연계 — 탈퇴 시 세션 즉시 abort·파기
- 프론트 재연결 UI·자동 재시도·rejoin 호출 흐름 — 프론트 스토리
- 리포트 소비·egress 연계
