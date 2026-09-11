# 영구 삭제 (개인정보 파기)

> **User Story**: HBB1-13 — 나는 사용자로서 내 정보를 관리하기 위해 이력서·세션 로그·리포트를 영구 삭제 요청할 수 있다.
>
> **하위 이슈**: 미생성 (PRD 확정 후 기입)

## Overview

영구 삭제는 회원 탈퇴로 등록된 파기 대기 건을 유예 기간(3일) 경과 후 **Spring 배치가 실제로 파기**하는 기능 영역이다. 사용자의 "영구 삭제 요청"은 별도 API가 아니라 **회원 탈퇴(`DELETE /api/v1/user`)와 카카오 연결 해제 웹훅 그 자체**다 — 탈퇴가 즉시 접근을 차단하고 파기 대기(`deletion_log.PENDING_PURGE`)를 등록하며(1단계, HBB1-10 account.md), 본 스토리의 배치가 유예 후 파기와 `deletion_log` 종결 전환(`PURGED`/`FAILED`)을 수행한다(2단계). **파기의 조립·선점·종결은 계정 도메인이 소유**하고, 각 도메인(이력서·세션·리포트·인증)은 자기 데이터의 파기 접근(repositoryService)만 제공한다.

핵심 흐름: `탈퇴(soft delete·RT 폐기·동의 철회·파기 대기 등록·진행 중 세션 ABORTED) → 유예 3일 → 배치 스캔·PURGING 선점 → 이력서(S3·DB) → 세션(녹음 S3·대본 마스킹·soft delete) → 리포트(DB) → RT 삭제 → 카카오 unlink → users 식별정보 마스킹 → PURGED` (어느 단계든 실패 시 `FAILED` → 다음 회차 멱등 재시도)

### 파기 대상

| 대상 | 처리 | 근거 |
| --- | --- | --- |
| 이력서 — S3 원본, `resumes`, `resume_analysis_status`, `resume_chunks`(Worker 소유) | **물리 삭제** (S3 prefix `resumes/{userId}/` 전체 + 행 DELETE) | resume.md §5 "완전 삭제" |
| 세션 — 녹음 S3 객체 | **물리 삭제** (`recording_object_key` 기준, 삭제 후 컬럼 NULL) | 개인정보 원본 — 설계 초안 §9.2 |
| 세션 — `interview_session` | **soft delete** (`deleted_at` 기록, 행 유지) | id 연속성·참조 무결성 — 설계 초안 §9.2 |
| 세션 — `interview_transcript`(에이전트 소유) | **마스킹** (`content = []`, `deleted_at` 기록, 행 유지) | 발화 내용 제거·행 구조 유지 — 설계 초안 §9.2 |
| 리포트 — `reports`, `report_scores`, `report_feedbacks`, `report_generation_jobs`(Worker 소유) | **물리 삭제** | report.md §1 "완전 삭제" |
| `refresh_token` | **물리 삭제** (탈퇴 시 전량 폐기됨) | 잔여 행 제거 |
| `users` | **식별정보 마스킹** (`email`·`name` NULL, `provider_id = PURGED_{id}`), 행 유지 | account.md 기능 4 마스킹 규칙 |
| 카카오 연결 | **unlink** (어드민 키, `deletion_log.provider_id` 스냅샷) → 스냅샷 NULL | account.md 기능 3·4 |
| `user_consent` | **보존** — 파기 완료 후 1년, 이후 가명 `users` 행과 함께 삭제 | 동의 증빙 (기능 7) |
| `deletion_log` | **영구 보존** (식별정보 없음) | 파기 audit |
| 제외 — DB 백업 스냅샷, 애플리케이션 로그, `interview_metrics`(발화 내용 없는 파이프라인 지표), 에이전트 Redis 사본(TTL 24h) | 파기 대상 아님 | 제약사항 |

- Worker·에이전트 소유 테이블(`resume_chunks`·`interview_transcript`·`report_generation_jobs`)의 파기는 **Spring 배치가 JDBC로 직접 수행**한다 — "쓰기 권한 경계 = 소유권 경계" 원칙의 명시적 예외다. 파기 정책의 소유가 계정 도메인이고, 완료를 동기로 확인해야 `PURGED` 전환·재시도가 단순하기 때문이다(재생성 Job 갱신 `JdbcReportJobWriter` 선례 — 공통: 크로스 레포 계약).

### 파기 상태 (`deletion_log.status`)

| 상태 | 의미 | 전이 |
| --- | --- | --- |
| PENDING_PURGE | 탈퇴됨, 파기 대기 (account.md) | 유예 경과 시 배치가 `PURGING` 선점 / 유예 내 복구 시 `CANCELLED` |
| PURGING | 배치가 선점해 파기 진행 중 — 이 상태 계정의 로그인·가입은 `409 U002` | 완료 시 `PURGED` / 실패 시 `FAILED` / `updated_at`이 임계를 넘으면 stale로 재선점 |
| FAILED | 파기 실패, 재시도 대상 (`409 U002` 동일) | 다음 회차 `PURGING` 재선점 — 횟수 상한 없음 |
| PURGED | 파기 완료 (종결) — `purged_at` 기록, 보존 기간의 기산점 | 없음 |
| CANCELLED | 유예 내 복구로 파기 취소 (종결, account.md) | 없음 |

- 모든 전이는 조건부 UPDATE로 수행하고, `PURGING` 이후의 전이는 선점 시각(`updated_at`)을 펜싱 토큰으로 포함해 재선점된 건에 대한 낡은 전환을 무시한다(기능 2). 상태 전이의 상세·`purge_detail` 기록은 기능 2·3.

### 범위·경계

- **함께 다루는 인접 항목**: (1) 탈퇴 시점의 진행 중 세션 즉시 종료 — 세션 PRD 3종이 "E1 연계 후속 스토리"로 위임, (2) 개별 이력서 삭제(`DELETE /api/v1/resumes/{resumeId}`)의 물리 삭제 배치 — resume.md §5 "주기 미정", (3) Refresh Token 청소 배치 — 설계 초안 ADR-013 미구현, (4) 동의 이력 보존 정책 확정 — consent.md가 본 스토리에 위임.
- **인수하는 위임 사항**: account.md가 "영구 삭제 스토리"에 위임한 파기 실행·unlink·`PENDING_PURGE·FAILED → PURGING` 조건부 선점·재가입 유저의 unlink 생략·stale `PURGING` 회수·`FAILED` 재시도 정의·식별정보 파기 규칙 준수, 그리고 resume.md·report.md의 "회원 탈퇴 파기" 대상 정의.
- **변경하지 않는 것**: 탈퇴 요청·복구·유예 만료 재가입의 계약(account.md), 동의 항목·append-only 계약(consent.md). 사용자 요청 시점의 즉시 파기·유예 단축 API는 여전히 MVP 범위 외(account.md 제약).

### 기능 요구사항

| No. | Function | Description |
| --- | --- | --- |
| 1 | 탈퇴 시 진행 중 세션 즉시 종료 | 탈퇴 트랜잭션이 해당 유저의 non-terminal 세션을 `ABORTED`로 선기록하고, 커밋 후 LiveKit 룸을 정리해야 한다. |
| 2 | 파기 대상 선점·재시도 | 배치가 유예 경과 `PENDING_PURGE`·`FAILED`·stale `PURGING` 건을 조건부 UPDATE로 `PURGING` 선점하고, 실패 시 `FAILED`로 전환해 다음 회차에 재시도해야 한다. |
| 3 | 실데이터·식별정보 파기 | 이력서(S3·DB·청크)·세션(녹음 S3·대본 마스킹·세션 soft delete)·리포트(DB 전량)·RT를 멱등하게 파기하고 `users` 식별정보를 마스킹한 뒤 `PURGED`로 종결해야 한다. |
| 4 | 카카오 연결 해제 | `provider_id` 스냅샷으로 unlink를 호출하되 동일 회원번호의 활성 계정이 있으면 생략하고, 완료 후 스냅샷을 NULL 처리해야 한다. |
| 5 | 개별 이력서 물리 삭제 배치 | soft delete된 이력서의 S3 원본(공유 키 참조 확인)·청크·레코드를 배치가 물리 삭제해야 한다. |
| 6 | Refresh Token 청소 배치 | 만료된 RT는 즉시, 폐기된 RT는 보존 기간 경과 후 삭제해야 한다. |
| 7 | 동의·파기 이력 보존 정책 | `user_consent`는 파기 완료 후 1년 보존 뒤 가명 `users` 행과 함께 삭제하고, `deletion_log`는 파기 audit으로 보존해야 한다. |

---

## 탈퇴 시 진행 중 세션 즉시 종료

### 설명

회원 탈퇴 트랜잭션(`UserService.withdraw` — API·웹훅 공통, account.md 기능 3·5)이 조건부 soft delete의 **영향 행 수가 1인 경우에만**(직렬화 계약 — 밀린 0행 요청은 세션도 건드리지 않음) 아래를 추가로 수행해야 한다.

1. 해당 유저의 non-terminal 세션(`PENDING`·`ACTIVE`·`INTERRUPTED`·`AGENT_LOST`) 전부를 **조건부 UPDATE**로 `ABORTED` 전이한다(`ended_at` = 탈퇴 트랜잭션 시각 — 네 기록과 동일한 시각 공유). 룸 이름은 벌크 UPDATE **전에** 수집한다(벌크 UPDATE는 영속성 컨텍스트를 비운다 — 세션 생성의 자동 교체와 동일 주의).
2. **커밋 후** 수집한 룸을 best-effort로 삭제한다(`deleteRoomQuietly` — 실패는 로그만). 순서는 **선기록 후 삭제**로 고정한다 — 세션 종료 PRD의 fallback 계약과 같은 이유로, 선기록이 없으면 룸 삭제가 만드는 `room_finished`가 정상 종료로 오판된다. 선기록 후 도착하는 `room_finished`·`participant_left`는 terminal 공통 가드로 no-op이다.

- 근거: JWT 필터가 탈퇴 유저의 접근을 차단해도 세션 행이 non-terminal이면 룸·에이전트가 유지된다(면접 도중 서비스 외부에서 카카오 연결을 끊는 경우가 대표적). 유예까지 방치하면 최대 3일간 좀비 세션이 리소스를 점유하고, 유예 내 복구된 유저가 `SESSION_ALREADY_IN_PROGRESS`(409)·`RESUME_IN_USE`(409)에 막힌다. 세션 생성 PRD가 "생성 선점 → 세션 생성 후 탈퇴"로 수렴하는 경합의 잔존 세션 정리를 E1 연계에 위임한 항목의 이행이다.
- 잠금·직렬화: withdraw는 이미 user 행 잠금을 보유하고, 세션 전이 경로(webhook 핸들러·스위퍼)도 user 잠금을 선행하므로 전이가 직렬화된다. 전이는 **활성 재확인 없는** 전이다(탈퇴 유저의 세션도 전이시키는 세션 종료 PRD 계약과 일치).
- 웹훅 경로의 시간 예산: 룸 삭제(LiveKit 왕복, `api-timeout` 3초)가 웹훅의 **3초 응답 계약을 지연시키면 안 된다** — 웹훅 경로에서는 응답 스레드 밖(비동기 실행)에서 삭제한다. API 경로(`DELETE /api/v1/user`)는 세션 생성과 같이 커밋 후 동기 삭제를 허용한다. 어느 경로든 삭제 실패는 무해하다 — 세션은 이미 `ABORTED`이고, 잔존 룸은 LiveKit empty timeout·에이전트의 재연결 창 소진 처리로 소멸한다.
- 에이전트·녹음 측 효과: 룸 삭제로 에이전트 잡이 종료되고 egress가 종료되어 `egress_ended`가 도착할 수 있다 — 세션 도메인이 녹음 키를 기록하고 음성 분석 요청을 발행하지만 Worker에 해당 리포트가 없어 무해하며, 녹음 객체는 파기 배치(기능 3)가 세션 행의 키로 삭제한다. 에이전트가 삭제 직전 flush를 완료해 대본 행·리포트가 만들어지는 잔여 경합도 파기 배치가 흡수한다.
- 세션 도메인이 "유저의 진행 중 세션 일괄 종료(전이 + 커밋 후 룸 정리)"를 제공하고 계정 도메인이 이를 호출하는 구조를 권장한다 — 룸 관리 추상(`SessionRoomManager`)은 세션 도메인 소유다.

### 실행 조건

- account.md 기능 3(회원 탈퇴)·5(웹훅)의 탈퇴 트랜잭션이 구현되어 있어야 한다.
- 세션 종료 PRD(interview-session-completion.md)의 조건부 전이·terminal no-op 가드가 구현되어 있어야 한다.

### 검증 기준

- `PENDING`·`ACTIVE`·`INTERRUPTED`·`AGENT_LOST` 각 상태의 세션을 가진 유저가 탈퇴하면 세션이 `ABORTED`·`ended_at = deleted_at`으로 전이되고 커밋 후 룸 삭제가 호출되는지 확인 (`@ParameterizedTest`)
- `ENDED`·`ABORTED`(terminal) 세션은 변경되지 않는지 확인
- 이미 탈퇴된 유저의 재요청(조건부 UPDATE 0행)은 세션을 건드리지 않고 룸 삭제도 호출하지 않는지 확인
- 룸 삭제 실패(어댑터 예외)가 탈퇴 응답·커밋 결과에 영향을 주지 않는지 확인
- 웹훅 경로에서 룸 삭제가 지연되어도(어댑터 지연 stub) 200 응답이 3초 예산 내에 반환되는지 확인
- 탈퇴 후 도착하는 `room_finished`·`participant_left`가 no-op인지 확인
- 세션 생성과 탈퇴가 동시에 실행되어 생성이 선점한 경우, 이어지는 탈퇴가 그 세션을 `ABORTED`로 정리하는지 확인 (동시성 통합 테스트 — 세션 생성 PRD 검증 항목의 "잔존 세션은 E1 연계가 정리"의 이행)
- 탈퇴 유저의 세션이 `ABORTED`된 뒤 유예 내 복구한 유저가 새 세션을 생성할 수 있는지 확인

### 성능 요구사항

- `DELETE /api/v1/user`의 로컬 100ms 기준(account.md)은 룸 삭제(LiveKit 왕복)를 제외하고 유지한다.
- 웹훅은 룸 삭제와 무관하게 3초 이내 200을 반환해야 한다(account.md 기능 5).

### 인터페이스 요구사항

- 별도 엔드포인트 없음 — `DELETE /api/v1/user`·`GET·POST /api/v1/webhook/kakao/unlink`의 내부 처리 확장. 응답 계약 변경 없음.

### 제약사항

- 종료 신호(`SendData interview:end`)를 보내 에이전트의 정상 종료(클로징·flush)를 기다리지 않는다 — 탈퇴한 유저의 면접 대본·리포트는 어차피 파기 대상이므로 즉시 `ABORTED`·룸 삭제로 정리한다.

### 기타 요구사항

- 세션 PRD 3종(creation·completion·reconnection)의 "범위 제외 — E1 연계"와 ERD의 `interview_session.deleted_at` 비고를 본 문서 참조로 갱신한다(공통: 문서 동시 개정).

---

## 파기 대상 선점·재시도

### 설명

파기 배치는 **Spring 앱 내 `@Scheduled(fixedDelay)`** 로 실행한다(세션 수렴 스위퍼 `SessionSweeper` 선례 — 별도 프로세스 없음). 대상 시각이 전부 DB 컬럼에 있어 서버 재시작에도 감시가 유실되지 않으며, prod의 다중 인스턴스(ECS 2대) 동시 실행은 아래 조건부 선점이 무해화한다(분산 잠금 불필요). `fixedDelay`라 회차가 겹치지 않는다.

한 회차는 다음 순서다: 회차 시각 `now` 취득 → 후보 스캔 → 건별 {선점 → 파기(기능 3·4) → 종결} → 보존 만료 정리(기능 7). **건별 처리는 격리**한다 — 한 유저의 실패가 같은 회차의 다른 유저 처리를 막지 않는다(`forEachIsolated` 관용구).

**후보 조건** (스캔 쿼리 — 세 집합의 합):

| 상태 | 조건 | 의미 |
| --- | --- | --- |
| `PENDING_PURGE` | `requested_at + 유예 기간 <= now` | 유예 경과. 경계 정각은 경과로 판정한다 — account.md의 복구 가능 판정 `now < requested_at + 유예`의 정확한 여집합이라 어느 시각에도 복구 가능·파기 대상이 동시에 성립하지 않는다 |
| `FAILED` | 없음 | 이전 회차 파기 실패 — 유예는 이미 경과했다 |
| `PURGING` | `updated_at <= now - purging-timeout` | stale — 선점한 인스턴스가 중단(재배포·장애)되어 방치된 건 |

**선점**: 후보마다 **자체 짧은 트랜잭션**에서 조건부 UPDATE로 `status = PURGING, updated_at = now, purge_detail.attempts + 1`을 수행한다. WHERE 절에 위 후보 조건을 **중복 포함**해 스캔~선점 사이의 상태 변화(복구의 `CANCELLED` 전환, 타 인스턴스의 선점)를 흡수한다. **영향 행 수가 1인 인스턴스만 파기를 진행**하고 0이면 건너뛴다.

- 선점은 잠금을 잡지 않는다 — `deletion_log` 행만 갱신하므로 복구 경로(잠금 순서 user → deletion_log)와 교착이 없다: 복구가 로그 행 잠금을 쥐고 있으면 선점 UPDATE는 커밋까지 대기한 뒤 `CANCELLED`를 보고 0행이 되고, 선점이 먼저 커밋되면 복구가 잠금 후 스칼라 재확인에서 `PURGING`을 읽어 `409 PURGE_IN_PROGRESS`가 된다. account.md 기능 4가 "영구 삭제 스토리에 위임"한 **복구와 파기의 상호 배타**의 배치 측 이행이다.
- **펜싱 토큰**: 선점이 기록한 `updated_at`(선점 시각)을 그 건에 대해 **선점 이후 `deletion_log`에 하는 모든 쓰기** — 상태 전이(`PURGED`·`FAILED`), `purge_detail` 갱신, `provider_id` 스냅샷 NULL 처리(기능 4) — 의 술어에 포함한다 — `WHERE id = :id AND status = 'PURGING' AND updated_at = :claimedAt`. stale 회수로 다른 인스턴스가 재선점한 건은 `updated_at`이 달라져 뒤늦게 깨어난 원래 인스턴스의 쓰기가 전부 0행으로 무시된다(재선점 인스턴스의 결과만 유효). 종결·실패 전환에만 걸고 중간 기록을 빼먹으면 원래 인스턴스의 낡은 `purge_detail`이 재선점 인스턴스의 기록을 덮으므로 범위를 "모든 쓰기"로 둔다. 0행이면 WARN 로그. 단 `updated_at`을 갱신하는 쓰기(상태 전이)는 이후 같은 인스턴스가 비교할 값도 함께 바뀌므로, 한 건의 처리 안에서 `updated_at`을 바꾸는 쓰기는 종결·실패 전환 한 번뿐이어야 한다(중간 기록은 `updated_at`을 건드리지 않음).
- `purging-timeout` 기본 30분: 한 유저의 파기(S3 객체 수십 개·DB 수백 행·unlink 1회)는 수 초~수십 초라, 정상 진행 중인 건을 다른 인스턴스가 재선점하는 이중 실행을 막기에 충분한 여유다. 재선점되더라도 모든 단계가 멱등이라 중복 실행은 낭비일 뿐 무해하다.
- `CANCELLED`·`PURGED`는 스캔 대상이 아니다(종결 상태 — 중복 파기·복구 계정 파기 방지).

**실패 처리**: 파기·unlink·종결의 어느 단계든 예외가 나면 그 건을 `PURGING → FAILED`(펜싱 조건부, `updated_at = now`, `purge_detail.lastError`에 예외 클래스·요약 메시지)로 전환하고 다음 회차에 재시도한다.

- 재시도 횟수 상한을 두지 않는다 — `FAILED` 계정은 재로그인·재가입이 `409 PURGE_IN_PROGRESS`로 차단되므로(account.md) 방치할 수 없고, 원인이 해소되면(S3·카카오 장애 복구, 운영 조치) 자동으로 완료되어야 한다.
- 로그: 실패마다 WARN, `attempts`가 **3 이상**이면 ERROR(운영 개입 신호 — 일시 장애가 아닌 구조적 실패로 간주). 로그·`purge_detail`에 `provider_id` 원문·파일명 등 개인정보를 남기지 않는다(공통: 로그·개인정보). 유저 내부 id·`deletion_log.id`는 기록한다.
- FAILED → CANCELLED 전이는 존재하지 않는다(account.md).

**보존 만료 정리**: 같은 회차의 마지막 단계로 기능 7의 만료 스캔을 실행한다.

### 실행 조건

- account.md의 `deletion_log` 스키마(`updated_at`·`purge_detail` 포함)가 존재해야 한다 — 스키마 변경 없음. 부분 UNIQUE 인덱스 `ux_deletion_log_active_user`와 `(status, requested_at)` 인덱스는 여전히 Flyway 도입 시점으로 보류한다(활성 레코드가 항상 소량이라 풀스캔 수용 — ERD "마이그레이션 도구 도입 시 반영할 항목" 유지).
- 탈퇴 유예 기간·파기 배치 설정값이 로드되어 있어야 한다.

### 검증 기준

- 유예 경과 `PENDING_PURGE` 건이 한 회차에 선점→파기→`PURGED`로 종결되고 `purged_at`·`updated_at`·`attempts = 1`이 기록되는지 확인
- 유예 미경과 `PENDING_PURGE`는 선점되지 않고, 경계 정각(`requested_at + 유예`)에는 선점되는지 확인 (Clock 고정)
- `FAILED` 건이 다음 회차에 재시도되어 `PURGED`·`attempts = 2`가 되는지 확인
- `updated_at`이 `purging-timeout` 이상 지난 `PURGING`은 회수·재선점되고, 최근 `PURGING`은 건드리지 않는지 확인
- 동일 건을 두 스레드가 동시에 선점하면 한 건만 진행되는지 확인 (동시성 통합 테스트)
- 복구 제출(`/auth/signup`)과 선점이 동시에 실행돼도 정확히 하나만 성립하는지 확인 — 선점 선행이면 복구가 `409`이고 파기가 진행되며, 복구 선행이면 선점이 0행이고 데이터가 보존된다 (동시성 통합 테스트 — account.md 검증 항목의 배치 측 실증)
- 파기 단계 실패 시 `FAILED`·`lastError` 기록·WARN 로그, 3회째 실패에 ERROR 로그가 남는지 확인
- 재선점된 건에 대한 원래 인스턴스의 쓰기(종결·실패 전환, `purge_detail` 갱신, 스냅샷 NULL 처리)가 전부 펜싱으로 0행 처리되고 재선점 인스턴스의 기록만 남는지 확인
- 한 유저의 파기 실패가 같은 회차의 다른 유저 파기를 막지 않는지 확인
- `CANCELLED`·`PURGED` 건이 스캔·선점되지 않는지 확인
- 배치 회차가 겹치지 않고(fixedDelay), 설정 주기 변경이 반영되는지 확인 (설정 오버라이드)

### 성능 요구사항

- 한 회차 처리량 상한을 두지 않는다(MVP 규모 — 하루 탈퇴 건수가 수십 이하). 회차 소요가 주기를 넘어도 `fixedDelay`라 겹치지 않으며, 규모 증가 시 회차당 상한·페이지 처리를 재검토한다.
- 파기 배치는 API 응답 시간에 영향을 주지 않아야 한다 — 파기 트랜잭션은 건별·단계별로 짧게 유지하고(장시간 잠금 금지), S3·카카오 왕복은 트랜잭션 밖에서 한다.

### 인터페이스 요구사항

- 설정(공통: 배치 실행·설정 참조): 파기 배치 주기(`account.purge-interval`, 기본 10분), stale 회수 임계(`account.purging-timeout`, 기본 30분)
- `purge_detail` jsonb의 구조는 기능 3에서 정의한다.

### 제약사항

- 운영자용 수동 파기 트리거 API·즉시 파기 API는 두지 않는다 — 운영 개입은 DB 상태 조정(예: `FAILED` 원인 해소 후 다음 회차 대기)으로 한정한다.

### 기타 요구사항

- `FAILED`가 반복되는 건의 운영 절차: ERROR 로그의 `deletion_log.id`·유저 내부 id로 `purge_detail.lastError`를 확인해 원인(S3 권한·카카오 어드민 키·DB 제약 등)을 해소한다. 해소되면 다음 회차가 자동 완료한다.

---

## 실데이터·식별정보 파기

### 설명

선점한 건에 대해 아래 단계를 **이 순서대로** 수행한다. 각 단계는 **독립 트랜잭션**으로 실행하고, DB 쓰기 전에 **user 행 잠금(무필터, `lockUser`)을 선행**한다 — 유저 상태·세션·이력서를 쓰는 다른 경로(세션 전이·이력서 삭제)와 직렬화 지점을 공유하는 계약(account.md 기능 2, 잠금 순서 user → deletion_log → RT)이다. 외부 저장소(S3) 삭제는 **DB 포인터 삭제보다 먼저** 트랜잭션 밖에서 수행한다 — 포인터를 먼저 지우면 실패한 S3 삭제를 재시도할 재료가 사라진다(이력서는 prefix 규칙이 흡수하지만 녹음 키는 유도할 수 없다). **모든 단계는 멱등**해야 한다 — 재시도·재선점·중복 실행 시 이미 파기된 대상은 건너뛰고 예외를 내지 않는다. 또한 재시도는 **이전 시도에서 `DONE`·`SKIPPED*`로 기록된 단계를 실행하지 않고 그 기록(건수)을 보존**한다 — `FAILED`·미기록 단계만 실행한다(멱등 재실행은 기록을 0건으로 덮어 audit을 훼손한다).

**1. 이력서** — 대상은 해당 유저의 **모든** `resumes` 행(soft delete된 행 포함 — `@SQLRestriction`을 우회하는 조회 필요).

1. S3: 각 행의 (`original_file_bucket`, `original_file_key`) 객체를 삭제한 뒤, 설정 버킷(`app.s3.bucket`)의 prefix `resumes/{userId}/` 잔여 객체를 목록 조회로 전부 삭제한다 — "S3 저장 후 DB 저장 전 서버 사망"으로 남은 고아 객체까지 흡수한다(resume.md §1 스토리지 층). 공유 키 참조 확인은 불필요하다 — 키가 사용자별(`resumes/{userId}/{fileHash}.pdf`)이고 그 유저의 이력서를 전량 파기하기 때문이다.
2. DB(한 트랜잭션): `resume_chunks` DELETE(resume_id 기준 — Worker 소유 테이블, JDBC 직접) → `resume_analysis_status` DELETE → `resumes` DELETE. **물리 삭제(hard delete)** 다 — resume.md §5 기타 요구사항("완전 삭제")의 이행. `interview_session.resume_id`·`reports.resume_id`는 FK 없는 참조라 남아도 무해하며, 세션은 soft delete·리포트는 삭제된다.

**2. 세션** — 대상은 해당 유저의 **모든** `interview_session` 행.

1. 방어적 정리: non-terminal 세션이 남아 있으면(기능 1이 처리했어야 하며 스위퍼가 수렴시켰어야 하는 모순) `ABORTED` 전이 후 룸을 삭제한다(선기록 후 삭제).
2. 녹음: `recording_object_key`가 non-null인 세션의 (`recording_bucket`, `recording_object_key`) 객체를 삭제하고, 성공 시 두 컬럼을 NULL 처리한다(재시도 시 재삭제 불필요·"녹음 없음" 상태로 수렴). 웹훅 유실로 키가 기록되지 않은 객체는 S3 수명주기(`recordings/` 7일)가 흡수한다(report.md 녹음 수명 규칙 — 공통: 인프라 전제).
3. 대본: `interview_transcript`의 해당 세션 행을 **마스킹**한다 — `content = '[]'::jsonb`(NOT NULL·발화 배열 형식 유지), `deleted_at = now`, `WHERE session_id IN (…) AND deleted_at IS NULL`. 행은 유지한다(에이전트 소유 테이블, JDBC 직접 — 설계 초안 §9.2 "DB 레코드는 soft delete + 마스킹, S3 실파일은 hard delete" 확정의 이행).
4. 세션: `interview_session.deleted_at = now`(행 유지 — id 연속성·참조 무결성). `livekit_room`·`resume_id` 등 나머지 컬럼은 개인정보가 아니므로 유지한다.
5. 제외: `interview_metrics`(에이전트 소유 파이프라인 지연·토큰 수 지표 — 발화 내용 없음)와 에이전트의 Redis 대본 사본(TTL 24h로 자동 소멸)은 파기 대상이 아니다.

**3. 리포트** — 대상은 해당 유저의 **모든** `reports` 행(soft delete 포함 — `@SQLRestriction` 우회). 한 트랜잭션에서 `report_feedbacks` → `report_scores` → `report_generation_jobs`(Worker 소유, JDBC 직접) → `reports` 순으로 **물리 삭제**한다(report.md §1 기타 요구사항 "완전 삭제"의 이행). 리포트가 삭제되면 리포트 타임라인이 마스킹된 대본을 읽을 경로가 없으므로 Spring의 대본 읽기 경로(`JdbcTranscriptReader`)는 변경하지 않는다.

**4. Refresh Token** — 해당 유저의 `refresh_token` 행을 전부 DELETE한다. 탈퇴 시 전량 폐기되어 재사용 감지 재료로서의 가치가 없고(폐기 후 3일 경과 — 기능 6의 보존 기간 2일보다 길다), `user_id`로 연결되는 잔여 행을 남기지 않는다.

**5. 카카오 연결 해제** — 기능 4.

**6. 종결**(한 트랜잭션, user 행 잠금 → deletion_log 갱신 순):

1. `users` 식별정보 마스킹 — `email`·`name` NULL, `provider_id = PURGED_{users.id}`(account.md 기능 4의 마스킹 규칙 — 원본 복원 불가·id 기반 유일·재실행 멱등. 유예 초과 재가입 처리로 이미 마스킹된 계정도 같은 값이라 멱등). NULL이 아닌 마스킹인 이유는 account.md와 같다(NOT NULL·"모든 유저는 provider_id를 가진다" 불변식).
2. `deletion_log`: `PURGING → PURGED`(펜싱 조건부), `purged_at = now`, `updated_at = now`, `provider_id` NULL 확인, `purge_detail` 최종 기록.
3. `user_consent`는 건드리지 않는다(기능 7의 보존 정책).

- `users` 행 부재(도달 불가한 모순 — soft delete만 존재)는 ERROR 로그 후 식별정보 단계를 `SKIPPED`로 기록하고 종결한다(나머지 파기는 `user_id` 기준이라 영향 없음).
- 시각: 각 단계의 기록 시각(세션·대본 `deleted_at`, RT 삭제 등)은 **그 단계 트랜잭션에서 잠금 획득 후 취득**한 시각이고, `purged_at`은 종결 트랜잭션 시각이다(공통: 시각 처리).
- 유예 초과 재가입 유저의 옛 계정(식별정보는 이미 마스킹, `PENDING_PURGE` 유지 — account.md 기능 4)도 같은 절차를 탄다 — 파기 대상 식별이 전부 옛 `users.id` 기준(S3 prefix·`user_id` 컬럼)이라 새 계정의 데이터에 영향이 없다.
- Worker·에이전트의 뒤늦은 쓰기(파기 후 청크 INSERT·리포트 피드백 INSERT 등)는 유예 3일이 파이프라인 소요(수 분)를 압도하므로 발생하지 않는다고 본다. 발생하면 고아 행이 남지만 다음 기회(개별 이력서 물리 삭제·운영 점검)에서 정리한다 — 수용 잔여 위험.

**`purge_detail` 계약** — jsonb의 구조 정의 원천은 백엔드의 계약 record(`PurgeDetail`, 이력서 `StructuredData` 선례). 개인정보(회원번호·이메일·이름·파일명·객체 키)를 담지 않으며 **건수와 단계 상태만** 기록한다.

```json
{
  "attempts": 2,
  "lastAttemptAt": "2026-01-18T09:10:00Z",
  "steps": {
    "resumes":   { "status": "DONE", "rows": 3, "chunks": 41, "s3Objects": 3 },
    "sessions":  { "status": "DONE", "rows": 5, "transcripts": 4, "recordings": 4, "abortedLeftovers": 0 },
    "reports":   { "status": "DONE", "rows": 4 },
    "refreshTokens": { "status": "DONE", "rows": 2 },
    "unlink":    { "status": "DONE" },
    "identifiers": { "status": "DONE" }
  },
  "lastError": "S3Exception: Access Denied (attempt 1)"
}
```

- `steps.*.status`: `DONE` · `SKIPPED`(대상 없음·생략 조건 충족 — unlink는 `SKIPPED_ACTIVE_ACCOUNT`·`SKIPPED_ALREADY_UNLINKED`로 세분) · `FAILED`. 단계 실패 시 그 단계까지의 기록을 남기고 `lastError`를 갱신한다. 재시도 성공 시 `lastError`는 유지한다(마지막 실패 이력 — 종결 후에도 진단 재료).

### 실행 조건

- 이력서(HBB1-14 계열)·세션(HBB1-18·294·308·318)·리포트(HBB1-19 계열) 도메인의 테이블이 존재해야 한다. Worker·에이전트 소유 테이블(`resume_chunks`·`interview_transcript`·`report_generation_jobs`)은 dev/prod에서 Worker·에이전트 배포가 생성하며, 로컬·테스트는 기존 계약 픽스처 DDL로 생성한다. 파기 쿼리는 **테이블 부재 시 실패**한다(FAILED → 재시도) — 배포 순서의 전제를 조용히 넘기지 않는다.
- S3 IAM 권한: 데이터 버킷에 `s3:DeleteObject`와 prefix 목록 조회(`s3:ListBucket`)가 허용되어야 한다(공통: 인프라 전제).

### 검증 기준

- 활성·soft delete 이력서를 각각 가진 유저 파기 시 S3 원본(MinIO Testcontainers 실물 확인)·`resume_chunks`·`resume_analysis_status`·`resumes`가 전부 제거되는지 확인
- 행 없이 S3에만 남은 고아 객체(`resumes/{userId}/` 하위)도 삭제되는지 확인 — 목록이 한 페이지(1,000개)를 넘어도 끝까지 조회해 전부 삭제하는지 확인(페이지네이션)
- 다른 유저의 이력서·S3 객체·청크는 영향이 없는지 확인
- 녹음 키가 기록된 세션의 S3 객체가 삭제되고 `recording_bucket`·`recording_object_key`가 NULL이 되는지, 키가 없는 세션은 건너뛰는지 확인
- `interview_transcript.content`가 `[]`·`deleted_at` 기록으로 마스킹되고 행이 유지되는지, 이미 마스킹된 행은 재갱신되지 않는지 확인
- `interview_session.deleted_at`이 기록되고 행이 유지되는지 확인
- 파기 시점에 non-terminal로 남은 세션이 `ABORTED` 전이 후 룸 삭제까지 수행되는지 확인 (모순 방어)
- 리포트 4테이블(`reports`·`report_scores`·`report_feedbacks`·`report_generation_jobs`)이 soft delete된 리포트를 포함해 전부 삭제되는지 확인
- 해당 유저의 `refresh_token` 행이 전부 삭제되는지 확인
- `users.email`·`name`이 NULL, `provider_id`가 `PURGED_{id}`로 마스킹되고 `deletion_log`가 `PURGED`·`purged_at`·`provider_id NULL`·`purge_detail` 기록으로 종결되는지 확인
- `user_consent` 행이 변경·삭제되지 않는지 확인
- 각 단계를 파기 완료 상태에서 재실행해도 예외·변화가 없는지 확인 (멱등)
- 중간 단계(예: 리포트)에서 실패한 건을 재시도하면 이미 끝난 단계는 실행하지 않고(건수 기록 보존) 나머지를 완료해 `PURGED`가 되는지 확인
- S3 삭제 실패(권한·엔드포인트 오류 주입) 시 DB 행이 남고 `FAILED`로 전환되며, 복구 후 재시도가 성공하는지 확인
- 유예 초과 재가입 유저의 옛 계정 파기가 새 계정의 이력서·세션·리포트·S3 객체에 영향을 주지 않는지 확인
- 파기 완료(`PURGED`) 계정의 카카오 회원번호로 로그인하면 신규 가입으로 흐르는지 확인 (`provider_id` 마스킹 — account.md 계약)
- `purge_detail`·로그에 `provider_id` 원문·이메일·이름·파일명·객체 키가 나타나지 않는지 확인
- 각 단계 트랜잭션이 user 행 잠금을 선행하는지 확인 (pg_locks 관측 또는 잠금 보유 트랜잭션과의 경합 테스트 — 세션 전이와 파기 단계가 직렬화되는지)

### 성능 요구사항

- 한 유저 파기 소요는 S3 객체 수에 비례한다. 상한을 두지 않되(MVP 규모 — 유저당 이력서 수 개·세션 수십), 단계별 트랜잭션은 잠금 보유 시간을 DB 쓰기로 한정한다(S3·카카오 왕복은 잠금 밖).

### 인터페이스 요구사항

- 각 도메인의 파기 접근은 해당 도메인의 repositoryService가 제공한다(예: `ResumeRepositoryService.purgeByUserId`, `SessionRepositoryService.purge…`, `ReportRepositoryService.purgeByUserId`, `AuthRepositoryService.deleteAllByUserId`). Worker·에이전트 소유 테이블의 JDBC 접근 클래스는 그 데이터를 소비하는 도메인의 `repositoryservice` 패키지에 둔다(`JdbcReportJobWriter` 선례 — 예: `resume/repositoryservice/JdbcResumeChunkPurger`, `session/repositoryservice/JdbcTranscriptPurger`).
- 파기 배치의 조립(선점·단계 순서·종결)은 계정 도메인(`user`)이 소유한다 — 의존 방향 `user.service → 타 도메인 repositoryService`(CLAUDE.md 규칙 준수, repositoryService 간 의존 없음).
- 이력서·리포트의 soft delete 우회 조회는 `@SQLRestriction`이 적용되지 않는 네이티브 쿼리로 한다.

### 제약사항

- 파기 대상은 위 목록으로 한정한다. DB 백업 스냅샷 내 데이터는 백업 보존 정책(인프라) 소관, 애플리케이션 로그의 잔여 정보는 로그 보존 정책 소관이며, 로그에는 애초에 회원번호 원문을 남기지 않는다(account.md 기능 5 규칙을 배치에도 적용).
- 파기는 되돌릴 수 없다. `PURGED`는 종결 상태이며 복구 경로가 없다(account.md 상태 전이).

### 기타 요구사항

- Kkori-AI 측 반영 요청: Worker·에이전트는 (1) `resume_chunks`가 resume 단위로 사라질 수 있음, (2) `interview_transcript`가 `deleted_at` 기록·`content = []`로 마스킹될 수 있음(읽기 경로는 `deleted_at IS NULL` 조건 권장), (3) `report_generation_jobs`가 리포트와 함께 삭제될 수 있음을 전제해야 한다(공통: 크로스 레포 계약).

---

## 카카오 연결 해제

### 설명

파기 배치는 데이터 파기(기능 3의 1~4단계) 후, 종결 전에 카카오 연결 해제(unlink)를 수행해야 한다. 재료는 탈퇴 시 확보한 `deletion_log.provider_id` 스냅샷이다(`users.provider_id`는 유예 초과 처리로 먼저 마스킹될 수 있다 — account.md).

| 조건 | 처리 |
| --- | --- |
| 스냅샷이 NULL | 호출 없이 `SKIPPED_ALREADY_UNLINKED` — 이전 시도에서 이미 완료됨(재시도 멱등) |
| 스냅샷과 같은 `provider_id`의 **활성** 계정(`deleted_at IS NULL`)이 존재 | 호출 없이 `SKIPPED_ACTIVE_ACCOUNT` — 유예 초과 후 재가입한 유저의 새 카카오 연결을 끊지 않기 위함(account.md 기능 4 기타 요구사항의 이행). `PURGING` 중에는 로그인·가입이 409로 차단되므로 이 판정 후 새 계정이 생길 수 없다 |
| 그 외 | 어드민 키로 unlink 호출 |

- 호출: `POST https://kapi.kakao.com/v1/user/unlink`, 헤더 `Authorization: KakaoAK {kakao.admin-key}`, 폼 파라미터 `target_id_type=user_id`·`target_id={스냅샷}`. 성공 응답은 `{ "id": <회원번호> }`.
- 응답 해석: HTTP 200 → 완료. **HTTP 400 + 에러 코드 `-101`**(카카오 문서: "해당 앱에 카카오계정 연결이 완료되지 않은 사용자") → 이미 연결이 끊긴 사용자(서비스 외부 해제 등)로 보고 **완료로 간주**(멱등). 그 외(네트워크 오류·타임아웃·5xx·`-401` 키 오류 등) → 단계 `FAILED`(건 전체 `FAILED` → 재시도). `-101` 해석은 실연동에서 확인한다(실행 조건).
- 완료(`DONE`·`SKIPPED_*`) 후 같은 트랜잭션 흐름에서 `deletion_log.provider_id`를 NULL 처리한다(펜싱 조건부 — 기능 2) — 스냅샷도 개인 식별정보이므로 용도가 끝나면 즉시 제거한다(account.md 기능 3).
- 웹훅과의 관계: 어드민 키 unlink에는 연결 해제 웹훅이 발송되지 않는다(account.md 기능 5 — 카카오 문서 기준). 발송되더라도 웹훅 처리는 활성 유저 부재로 no-op이라 무해하다.
- 실행 순서를 데이터 파기 뒤에 두는 이유: 카카오 장애가 데이터 파기 진행을 막지 않게 한다 — 실패 시 재시도는 데이터 단계를 멱등하게 통과해 unlink만 다시 시도한다.
- 로컬·테스트는 실제 카카오를 호출할 수 없다(`kakao.admin-key` 더미). unlink 호출은 인터페이스(포트)로 분리하고 테스트는 더블로 응답을 주입한다. 실연동 검증은 dev 배포 후 수동 확인한다(공통: 수동 검증).

### 실행 조건

- 카카오 어드민 키·앱 ID가 설정되어 있어야 한다(HBB1-246에서 도입한 `kakao.admin-key`·`kakao.app-id` 재사용).
- **[실연동 확인 필요]** 이미 연결이 끊긴 회원번호에 대한 unlink 응답이 `-101`인지 dev 환경에서 1회 확인한다 — 카카오 REST API 문서는 unlink의 개별 에러 코드를 열거하지 않는다.

### 검증 기준

- 스냅샷이 있는 건의 파기에서 unlink가 스냅샷 값으로 1회 호출되고, 성공 시 `deletion_log.provider_id`가 NULL·`steps.unlink = DONE`인지 확인
- `-101` 응답이 완료로 처리되어 스냅샷이 NULL 되는지 확인
- 스냅샷과 같은 `provider_id`의 활성 계정이 있으면 unlink가 호출되지 않고 `SKIPPED_ACTIVE_ACCOUNT`·스냅샷 NULL이 되는지 확인 (유예 초과 재가입 시나리오)
- 스냅샷이 NULL인 건(재시도)은 호출 없이 `SKIPPED_ALREADY_UNLINKED`로 통과하는지 확인
- 네트워크 오류·5xx·`-401` 응답 시 건이 `FAILED`·스냅샷 유지가 되고, 다음 회차에 재호출되는지 확인 (`@ParameterizedTest`)
- unlink 호출이 트랜잭션·잠금 밖에서 수행되는지 확인 (호출 중 잠금 미보유)
- 로그에 회원번호 원문이 나타나지 않는지(HMAC 가명값만) 확인

### 성능 요구사항

- 카카오 왕복은 공통 HTTP 클라이언트 타임아웃(연결 3초·읽기 5초)을 따른다. 초과는 실패로 처리해 재시도한다.

### 인터페이스 요구사항

- 외부: `POST https://kapi.kakao.com/v1/user/unlink` (어드민 키 방식) — 설정 `kakao.unlink-uri`를 공통 `application.yaml`에 둔다(외부 고정 엔드포인트 — `token-uri`·`user-info-uri`와 동일 취급, 환경변수 아님).
- 환경 변수: 기존 `KAKAO_ADMIN_KEY` 재사용(신규 없음).

### 제약사항

- unlink 실패 상태(`FAILED`)가 지속되면 그 카카오 계정은 재가입이 계속 차단된다 — account.md가 수용한 잔여 리스크이며 운영 개입(기능 2 기타 요구사항)으로 해소한다.

### 기타 요구사항

- 없음

---

## 개별 이력서 물리 삭제 배치

### 설명

`DELETE /api/v1/resumes/{resumeId}`로 soft delete된 이력서(resume.md §5 — MVP는 soft delete만 수행, 물리 삭제는 배치 소관·주기 미정)를 배치가 물리 삭제해야 한다. 파기 배치와 같은 방식의 앱 내 `@Scheduled`(이력서 도메인 소유, 별도 주기)로 실행하며 건별로 격리한다.

**대상 조건**: `resumes.deleted_at IS NOT NULL` 이고 **soft delete 후 지연 시간(`resume.physical-delete-delay`, 기본 10분)이 경과**했으며, 다음 중 하나 — (a) 분석 상태가 terminal(`EMBEDDED`·`FAILED`)이거나, (b) soft delete 후 **지연 시간의 6배**(코드 상수 — 스위퍼의 상한 배수 선례, 튜닝 노브가 아닌 안전 백스톱)가 경과했다.

- **업로드 경로와의 직렬화(공유 키 보호)**: 지연만으로는 배치 실행 중의 재업로드를 보호하지 못한다 — 배치의 "활성 참조 확인 → S3 삭제" 사이에 같은 사용자·같은 파일의 재업로드가 "객체 존재 확인(있음 → 저장 생략) → 행 INSERT"를 끼워 넣으면 새 행이 사라진 객체를 가리킨다. 그래서 양쪽을 user 행 잠금으로 직렬화한다: 배치는 **잠금 안에서** 참조 확인 → S3 삭제 → 행 삭제를 한 트랜잭션으로 수행하고(이 단계에 한해 S3 삭제를 잠금 안에서 한다 — 단일 호출이라 짧다), 업로드는 잠금 밖의 저장 뒤 **잠금 안에서 객체 존재를 재확인해 없으면 다시 저장**한 뒤 행을 INSERT한다(resume.md §1). 배치가 먼저면 업로드가 재저장하고, 업로드가 먼저면 배치가 활성 참조를 보고 객체를 남긴다.
- 지연 시간의 근거: 업로드가 잠금 밖에서 수행하는 첫 저장과 잠금 획득 사이의 창, 그리고 삭제 직후의 파이프라인 잔여 처리를 흡수한다. 위 직렬화가 정합을 보장하므로 지연은 재저장(낭비)을 줄이는 완충일 뿐이다.
- 분석 terminal 조건의 근거: 삭제 API는 분석 진행 중 이력서도 삭제를 허용한다. 진행 중에 청크·행을 지우면 Worker가 그 뒤에 청크(이력서 본문 — 개인정보)를 INSERT해 고아로 남는다. 상한(6배)은 Worker가 상태를 영영 전이하지 않는 병리(Worker 중단)에서도 삭제가 수렴하게 하는 백스톱이며, 그 경우의 고아 청크는 수용 잔여 위험이다.

**처리**(건별, user 행 잠금 무필터 선행 — 업로드의 잠금 내 재확인·이력서 수정·삭제·세션 생성 경로와 직렬화. 아래 1·2를 잠금 트랜잭션 하나에서 수행하며, S3 삭제 실패는 트랜잭션째 되돌아가 행이 남는다):

1. S3: 같은 `user_id`·`file_hash`의 **활성 이력서**(`deleted_at IS NULL`)가 존재하면 객체를 **삭제하지 않는다**(해시 기반 키를 여러 레코드가 공유 — resume.md §5 검증 기준). 없으면 (`original_file_bucket`, `original_file_key`) 객체를 삭제한다. 같은 키를 공유하는 다른 soft delete 행은 각자 삭제 대상이며 S3 삭제는 멱등이다.
2. DB(한 트랜잭션): `resume_chunks` DELETE → `resume_analysis_status` DELETE → `resumes` DELETE.

- 탈퇴 파기(기능 3)와 같은 이력서를 두고 겹칠 수 있다 — 둘 다 멱등이라 무해하다. 탈퇴 유저의 soft delete 이력서는 어느 배치가 먼저 처리해도 결과가 같다.
- 실패한 건은 행이 남아 다음 회차에 재시도된다(상태 컬럼 없음 — 대상 조건이 그대로 성립). 실패 로그는 WARN(이력서 id·유저 내부 id).

### 실행 조건

- 이력서 삭제 API(soft delete)가 구현되어 있어야 한다(HBB1-14 계열 — 완료).
- S3 IAM 권한: `s3:DeleteObject`.

### 검증 기준

- soft delete 후 지연 시간이 경과하고 분석이 terminal인 이력서가 S3 객체·청크·분석 상태·행까지 물리 삭제되는지 확인
- 지연 시간 미경과 이력서는 삭제되지 않는지 확인 (Clock 고정)
- 분석 진행 중(`PARSING` 등)인 이력서는 상한 미경과 시 보류되고, 상한 경과 시 삭제되는지 확인
- 같은 파일을 재업로드해 활성 이력서가 같은 키를 공유하면 S3 객체는 남고 soft delete 행·청크만 삭제되는지 확인
- 배치 실행 중(활성 참조 확인 직후) 같은 파일의 재업로드가 끼어들어도 새 이력서의 객체가 남는지 확인 — 업로드의 잠금 내 존재 재확인·재저장 (경합 재현 테스트)
- 같은 키를 공유하는 soft delete 행 두 건이 모두 처리되고 S3 객체는 한 번만 삭제(두 번째는 no-op)되는지 확인
- S3 삭제 실패 시 행이 남고 다음 회차에 재시도되는지 확인
- `FAILED` 상태 이력서도 삭제되는지 확인 (resume.md §5)
- 다른 유저·활성 이력서는 영향이 없는지 확인
- 물리 삭제된 이력서의 파일을 다시 업로드하면 새 이력서가 정상 생성·분석되는지 확인

### 성능 요구사항

- 회차 처리량 상한 없음(MVP 규모). 건별 트랜잭션은 짧게 유지하고 S3 왕복은 잠금 밖에서 한다.

### 인터페이스 요구사항

- 설정: 물리 삭제 배치 주기(`resume.physical-delete-interval`, 기본 10분), 지연 시간(`resume.physical-delete-delay`, 기본 10분). 상한 배수(6)는 코드 상수.
- 별도 API 없음.

### 제약사항

- 삭제 API의 응답 계약·soft delete 즉시 비노출 동작(resume.md §5)은 변경하지 않는다.
- 유저가 삭제한 이력서의 복구는 지원하지 않는다(soft delete 창은 배치 지연을 위한 것이지 복구 창구가 아니다).

### 기타 요구사항

- resume.md §5의 "물리 삭제 배치의 주기: 미정"과 "S3 참조 확인은 배치 담당" 서술을 본 문서 참조로 갱신한다(공통: 문서 동시 개정).

---

## Refresh Token 청소 배치

### 설명

`refresh_token` 테이블의 누적을 막기 위해 배치가 아래 두 종류를 삭제해야 한다(설계 초안 ADR-013 — 미구현 항목의 이행). 인증 도메인 소유의 앱 내 `@Scheduled`(별도 주기)로 실행한다.

| 대상 | 조건 | 근거 |
| --- | --- | --- |
| 만료된 RT | `expired_at <= now` | 만료 후에는 재발급·Grace Period 어느 경로에서도 유효하게 취급되지 않는다(`RT_EXPIRED`) — 즉시 삭제 |
| 폐기된 RT | `revoked_at IS NOT NULL AND revoked_at <= now - 보존 기간` (기본 **2일**) | 재사용 감지 창 확보 — 폐기 직후는 Grace Period(60초)·재사용 탈취 감지(`RT_REUSE_DETECTED` → 전량 폐기)의 재료다. 2일이 지나면 감지 가치보다 누적 비용이 크다 |

- 재사용 감지에 대한 영향: 보존 기간이 지난 폐기 토큰을 재사용하면 `RT_REUSE_DETECTED`(전량 폐기) 대신 `RT_NOT_FOUND`(401)가 된다 — 어차피 재로그인을 유도하는 결과라 수용한다(ADR-013 트레이드오프). 회전(RTR)의 `replaced_by` 참조는 해시 문자열이라 선행 토큰이 삭제되어도 후속 토큰의 유효성에 영향이 없다.
- 두 조건은 단순 벌크 DELETE로 수행하며 잠금이 필요 없다 — 조건이 시각 기준이라 다중 인스턴스 동시 실행도 중복 삭제 시도가 0행으로 수렴한다. 재발급 경로의 `FOR UPDATE` 잠금과의 경합은 DELETE가 잠금 해제까지 대기하는 것으로 무해하다(폐기 직후 토큰은 보존 기간 안이라 삭제 대상이 아니다).
- 탈퇴 유저의 RT는 파기 배치(기능 3)가 파기 시점에 전량 삭제하므로 이 배치는 일반 청소용이다.

### 실행 조건

- 없음(기존 `refresh_token` 스키마 그대로).

### 검증 기준

- `expired_at`이 지난 RT가 회차에 삭제되고, 만료 전 RT는 남는지 확인 (Clock 고정)
- 폐기 후 보존 기간이 지난 RT는 삭제되고, 보존 기간 내 폐기 RT는 남는지 확인 (경계값)
- 유효 RT(미만료·미폐기)는 삭제되지 않는지 확인
- 삭제된 폐기 토큰의 재사용이 `RT_NOT_FOUND`로 거부되는지 확인
- 회전 체인의 선행 토큰이 삭제되어도 후속 토큰으로 재발급이 정상 동작하는지 확인
- 설정 주기·보존 기간 변경이 반영되는지 확인 (설정 오버라이드)

### 성능 요구사항

- 벌크 DELETE 1~2회로 끝난다. 회차당 상한 없음(MVP 규모).

### 인터페이스 요구사항

- 설정: 청소 주기(`jwt.refresh-token-cleanup-interval`, 기본 1시간), 폐기 토큰 보존 기간(`jwt.revoked-refresh-token-retention`, 기본 2일).

### 제약사항

- 회원 탈퇴의 RT 전량 폐기(즉시)·재사용 감지 계약(HBB1-11)은 변경하지 않는다.

### 기타 요구사항

- 없음

---

## 동의·파기 이력 보존 정책

### 설명

consent.md가 "영구 삭제 스토리에서 법무·개인정보 담당 확인을 거쳐 확정"하도록 위임한 동의 이력 보존 정책을 아래로 확정한다. 확정 전까지의 기본 상태("영구 보존을 전제하지 않는다")를 해소하며, 기간 값은 설정으로 두어 검토 결과에 따라 코드 변경 없이 조정할 수 있게 한다.

| 대상 | 정책 | 근거 |
| --- | --- | --- |
| `user_consent` | 파기 완료 시각(`deletion_log.purged_at`)부터 **1년** 보존 후 삭제 | 동의받았다는 사실의 증빙(분쟁 대응)과 개인정보보호법 제21조의 파기 원칙(목적 달성 후 파기) 사이의 결정. 파기 후 1년이면 탈퇴 관련 분쟁 제기 창을 충분히 덮는다 |
| `users` 가명 행 | `user_consent`와 함께 삭제 | 식별정보는 파기 시점에 이미 제거됐고(`email`·`name` NULL, `provider_id` 마스킹) `id`만 가명 키로 남는다 — 동의 이력이 사라지면 가명 키를 남길 이유가 없다 |
| `deletion_log` | **영구 보존** | 파기 사실의 audit. 종결 시점에 `provider_id` 스냅샷이 NULL이라 개인 식별정보가 없고, `users` 행 삭제 후 `user_id`는 어떤 것과도 연결되지 않는다 |

- 보존 기간 중 `user_consent`·`users` 가명 행은 **분리 보관·별도 접근 통제 없이** 기존 테이블에 둔다 — 식별정보 없는 가명 데이터라 별도 보관의 실익이 없다(consent.md가 열거한 검토 항목 "분리 보관"의 결론).
- 보존 기간 중 `user_id`의 익명화·치환은 하지 않는다 — `users` 행이 이미 가명 상태이고, 기간 만료 시 행 자체를 삭제하므로 별도 치환이 불필요하다.
- **만료 정리**: 파기 배치 회차의 마지막 단계에서 `deletion_log.status = PURGED AND purged_at <= now - 보존 기간`이고 `users` 행이 아직 존재하는 건을 스캔해, 건별 트랜잭션(user 행 잠금 선행)으로 그 유저의 `user_consent` 전체와 `users` 행을 삭제한다. `deletion_log`(해당 유저의 `CANCELLED` 이력 포함)는 남긴다. `users` 행 부재가 "정리 완료"의 표식이라 별도 상태 컬럼이 필요 없고 재실행은 대상 없음으로 멱등이다.
- 활성 유저·`CANCELLED`(복구) 건의 동의 이력은 영향이 없다 — append-only 계약(consent.md)은 서비스 경로의 계약이며, 보존 만료 삭제는 그 계약이 명시적으로 별개 축으로 둔 "보존 정책의 소관"이다.
- 외부 계약: 개인정보 처리방침에 "탈퇴·파기 후 동의 기록 1년 보존"을 고지한다(프론트·운영 소유). 법무·개인정보 담당 검토로 기간이 달라지면 설정값과 처리방침을 함께 갱신한다.

### 실행 조건

- 파기 배치(기능 2·3)가 `purged_at`을 기록해야 한다.
- 보존 기간 설정값이 로드되어 있어야 한다.

### 검증 기준

- `purged_at`이 보존 기간 이상 지난 `PURGED` 건의 `user_consent` 전체와 `users` 행이 삭제되고 `deletion_log`는 남는지 확인 (Clock 고정, 경계값)
- 보존 기간 미경과 `PURGED` 건은 `user_consent`·`users`가 유지되는지 확인
- 활성 유저·`CANCELLED` 건의 `user_consent`는 영향이 없는지 확인
- 이미 정리된 건(`users` 행 부재)이 재스캔되어도 변화·예외가 없는지 확인 (멱등)
- 파기 배치(기능 3)가 `user_consent`를 삭제하지 않는지 확인 (기능 3 검증 기준과 중복 — 보존 정책의 관점)
- 보존 기간 설정 변경이 반영되는지 확인 (설정 오버라이드)

### 성능 요구사항

- 없음 (건별 벌크 DELETE)

### 인터페이스 요구사항

- 설정: 동의 이력 보존 기간(`account.consent-retention`, 기본 `365d` — `Duration`은 연 단위를 지원하지 않으므로 일수로 표기)

### 제약사항

- 보존 기간은 설정으로만 조정한다(코드 상수 금지). 기간 단축은 이미 만료된 건을 다음 회차에 즉시 정리하므로 운영 고지 후 적용한다.
- 동의 이력의 열람·내보내기(데이터 이동권 대응)는 범위 외.

### 기타 요구사항

- consent.md의 "보존 정책(미확정 — 영구 삭제 스토리에서 확정)" 항목을 본 문서 참조로 개정한다(공통: 문서 동시 개정).

---

## 공통: 배치 실행·설정

- 세 배치(파기·이력서 물리 삭제·RT 청소)는 모두 앱 내 `@Scheduled(fixedDelayString = …)`로 실행한다(`SchedulingConfig`의 `@EnableScheduling` 기존 활성). 회차 시각은 주입된 `Clock`에서 취득한다.
- **스케줄러 스레드 풀**: Spring Boot의 기본 스케줄러 풀 크기는 1이라 세션 스위퍼(10초 주기)·SSE keepalive(20초 주기)·본 스토리의 배치 3종이 한 스레드를 순서대로 나눠 쓴다. 파기 회차가 S3·카카오 왕복으로 수십 초를 점유하면 그동안 스위퍼가 밀려 세션 수렴이 지연된다. `spring.task.scheduling.pool.size`를 주기 작업 수 이상으로 올리거나 파기 배치에 전용 실행기를 지정한다 — 환경별 운영값이 아니므로 공통 `application.yaml`에 둔다. 검증: 파기 회차가 진행 중인 동안 스위퍼 회차가 지연되지 않는지 확인.
- 다중 인스턴스 안전성: 파기 배치는 조건부 선점(기능 2), 이력서 물리 삭제·RT 청소는 멱등 벌크 처리로 보장한다 — 분산 잠금(ShedLock 등)을 도입하지 않는다.
- 설정값은 `@ConfigurationProperties` record + compact constructor fail-fast 패턴(`AccountPolicyProperties`·`JwtProperties` 선례)으로 관리하며, 0 이하 기간은 기동 실패다. CLAUDE.md 규칙에 따라 환경변수 주입값은 프로파일 파일에 둔다 — local은 `${ENV:기본값}`, dev/prod는 기본값 없는 `${ENV}`(배포 매니페스트에 아래 환경변수 추가 필요).

| 설정 키 | 환경변수 | 기본값(local) | 용도 |
| --- | --- | --- | --- |
| `account.purge-interval` | `PURGE_INTERVAL` | `10m` | 파기 배치 주기 (기능 2) |
| `account.purging-timeout` | `PURGING_TIMEOUT` | `30m` | stale `PURGING` 회수 임계 (기능 2) |
| `account.consent-retention` | `CONSENT_RETENTION` | `365d` | 동의 이력 보존 기간 (기능 7) |
| `resume.physical-delete-interval` | `RESUME_PHYSICAL_DELETE_INTERVAL` | `10m` | 이력서 물리 삭제 배치 주기 (기능 5) |
| `resume.physical-delete-delay` | `RESUME_PHYSICAL_DELETE_DELAY` | `10m` | soft delete 후 물리 삭제 지연 (기능 5) |
| `jwt.refresh-token-cleanup-interval` | `RT_CLEANUP_INTERVAL` | `1h` | RT 청소 배치 주기 (기능 6) |
| `jwt.revoked-refresh-token-retention` | `RT_REVOKED_RETENTION` | `2d` | 폐기 RT 보존 기간 (기능 6) |
| `kakao.unlink-uri` | (공통 파일, 환경변수 아님) | `https://kapi.kakao.com/v1/user/unlink` | unlink 엔드포인트 (기능 4) |

- 기존 설정 재사용: `account.withdrawal-grace-period`(유예 판정), `kakao.admin-key`(unlink), `app.s3.bucket`(이력서 prefix 삭제), `log-masking.hmac-key`(로그 가명화).
- 테스트에서는 배치 빈을 등록하지 않고(`app.batch.enabled=false` — 테스트 컨텍스트 전용 스위치, 기본 true) 스케줄 메서드를 직접 호출해 회차를 검증한다(스위퍼 테스트 관례). 통합 테스트가 유예 초과 시나리오용으로 시딩하는 과거 시각의 탈퇴 건을 백그라운드 회차가 실제로 파기하지 않게 하기 위함이다.

---

## 공통: 시각 처리

- account.md의 공통 규칙(UTC `Instant`, 주입된 `Clock`, 마이크로초 절삭, 트랜잭션당 시각 1회 취득·잠금 후 취득)을 그대로 따른다.
- 배치는 회차 시작 시각 `now` 하나로 **후보 판정**(유예 경과·stale·보존 만료)을 하고, 각 건·단계 트랜잭션의 **기록 시각**은 그 트랜잭션에서 잠금 획득 후 따로 취득한다 — 회차가 길어져도 기록 시각이 실제 커밋 순서에 역행하지 않는다.
- 유예 경과 판정식은 `requested_at + 유예 기간 <= now`(경계 정각 포함)로, account.md의 복구 가능 판정 `now < deleted_at + 유예 기간`과 정확히 상보다(`requested_at = deleted_at` — 탈퇴 트랜잭션의 시각 정합 요구).

---

## 공통: 로그·개인정보

- 배치 로그에 카카오 회원번호 원문을 남기지 않는다 — 필요 시 `LogMasker`의 HMAC 가명값만 기록한다(account.md 기능 5 규칙의 확장). 유저 내부 id(`users.id`)·`deletion_log.id`·이력서/세션/리포트 id는 기록한다.
- `purge_detail`에는 건수·단계 상태·예외 요약만 기록하고 식별정보·파일명·객체 키를 담지 않는다(기능 3 계약).
- 예외 기록(`lastError`·로그)은 **예외 클래스명과 큐레이션된 정보만** 담는다 — 자체 예외의 고정 형식 메시지(예: unlink의 HTTP 상태·에러 코드), AWS 예외의 오류 코드(`NoSuchBucket` 등), 원인 예외의 클래스명. 임의 예외의 메시지·응답 본문·스택 트레이스는 기록하지 않는다(DB 제약 위반 메시지에 컬럼 값이, HTTP 예외 메시지에 응답 본문이 실릴 수 있다). unlink 클라이언트는 원본 HTTP 예외를 원인(cause)으로 보존하지 않는다.

---

## 공통: 크로스 레포 계약 (Kkori-AI 공유)

Spring 배치가 Worker·에이전트 소유 테이블에 쓰는 유일한 경로다. 소유권 원칙("쓰기 권한 경계 = 소유권 경계")의 명시적 예외로 아래를 Kkori-AI PRD에 동일하게 기록한다. 스키마 변경 권한은 여전히 소유자에게 있으며, 아래 컬럼이 바뀌면 양 레포 합의·동시 반영으로만 한다.

| 테이블 | 소유 | Spring의 쓰기 | 소유자가 전제할 것 |
| --- | --- | --- | --- |
| `resume_chunks` | Worker | `DELETE WHERE resume_id IN (…)` (탈퇴 파기·이력서 물리 삭제) | resume 단위로 청크가 사라질 수 있다. 검색(에이전트 top-k)은 존재하는 청크만 반환하면 된다 |
| `interview_transcript` | 에이전트 | `UPDATE SET content = '[]', deleted_at = now WHERE session_id IN (…) AND deleted_at IS NULL` (탈퇴 파기) | 행이 마스킹될 수 있다. 읽기 경로는 `deleted_at IS NULL` 조건을 권장. `ON CONFLICT DO NOTHING` flush 멱등성은 행 유지로 보존된다 |
| `report_generation_jobs` | Worker | `DELETE WHERE report_id IN (…)` (탈퇴 파기) | 리포트와 함께 Job 행이 사라질 수 있다 |

- `interview_metrics`(에이전트)는 파기 대상이 아니다 — 발화 내용이 없는 파이프라인 지표. 세션 id로 연결되지만 세션 행은 유지되므로 참조 무결성 문제도 없다.

---

## 공통: 인프라 전제

- **S3 수명주기 규칙(`recordings/` prefix 7일 자동 삭제)**: report.md가 확정한 녹음 수명 규칙이자, 웹훅 유실로 `recording_object_key`가 기록되지 않은 녹음 객체를 지우는 유일한 경로다. **현재 Kkori-Infra `envs/prod/backend/s3.tf`에 수명주기 규칙이 없다** — 인프라 이슈로 등록해 본 스토리 배포 전에 적용한다(본 스토리 범위 외, 파기 완결성의 전제).
- IAM(태스크 롤): 데이터 버킷에 `s3:DeleteObject`·`s3:ListBucket`(prefix 목록 조회) 허용.
- 카카오 어드민 키(`KAKAO_ADMIN_KEY`)는 dev/prod에 이미 주입되어 있다(HBB1-246). unlink 실연동은 dev에서 검증한다.
- 배포 매니페스트(환경변수 목록)에 공통: 배치 실행·설정의 신규 환경변수 7종을 추가한다.

---

## 공통: 수동 검증 (E2E — dev)

- 테스트 계정으로 이력서 업로드·면접 1회·리포트 생성 후 탈퇴 → 유예 단축 설정(`WITHDRAWAL_GRACE_PERIOD`)으로 파기 배치 실행 → S3 콘솔에서 `resumes/{userId}/`·녹음 객체 부재, DB에서 이력서·리포트 행 부재·세션/대본 마스킹·`users` 마스킹·`deletion_log PURGED` 확인 → 카카오 개발자 콘솔의 연결된 사용자 목록에서 해당 계정 부재 확인 → 같은 카카오 계정으로 재로그인 시 신규 가입 흐름 확인.
- 이미 연결이 끊긴 회원번호(카카오계정 관리 페이지에서 수동 해제)에 대한 unlink 응답 코드가 `-101`인지 확인하고, 다르면 기능 4의 완료 판정 규칙을 개정한다.

---

## 공통: 에러 코드

- 신규 에러 코드 없음 — 본 스토리는 API를 추가하지 않는다. `U002 PURGE_IN_PROGRESS`(account.md)는 배치가 `PURGING`·`FAILED`를 만드는 주체가 됨에 따라 실제로 발생 가능해진다(기존 계약 그대로).

---

## 공통: 문서 동시 개정

본 PRD와 같은 PR에서 아래를 갱신한다(PRD가 CodeRabbit 리뷰 컨텍스트이므로 위임 문구가 실제 정의와 어긋나지 않게).

- `account.md`: Overview·기능 3·4·5의 "영구 삭제 스토리 범위/위임" 문구에 본 문서 참조 추가. 상태 전이의 `PURGING → PURGED/FAILED`·stale 회수 정의 위치 명시.
- `consent.md`: 기능 1 "보존 정책(미확정)"을 본 문서 기능 7 참조로 개정.
- `resume.md` §5: 물리 삭제 배치 주기·조건·S3 참조 확인을 본 문서 기능 5 참조로 개정, 기타 요구사항의 "개인정보 파기"에 기능 3 참조.
- `report.md` §1 기타 요구사항 "회원 탈퇴 파기"·§4 기타 요구사항 "면접 도메인 삭제 정책 확정 시 정합 재확인"을 본 문서 참조로 개정.
- 세션 PRD 3종의 "범위 제외 — E1 연계"를 본 문서 기능 1·3 참조로 개정.
- `docs/erd.md`: `interview_session.deleted_at`·`interview_transcript.deleted_at` 비고를 "파기 배치 기록"으로 확정, `resumes`·`reports` 계열의 "탈퇴 파기 시 물리 삭제" 비고 추가, `deletion_log.purge_detail` 계약 언급.
