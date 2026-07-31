# ERD

전체 DB 스키마와 엔티티 관계. 코드(`@Entity`)가 스키마의 원천이며, 이 문서는 그 요약이다 — 컬럼 상세가 어긋나면 엔티티 코드가 우선한다.

## 공통 규칙

- **FK 제약 없음**: 도메인 간 참조(`user_id`, `resume_id` 등)는 FK 제약 없이 id만 보관하고 애플리케이션이 무결성을 관리한다(도메인 간 결합 최소화). 아래 다이어그램의 관계선은 논리적 참조다.
- **BaseEntity 상속 테이블**(`users`, `resumes`, `resume_analysis_status`, `interview_session`): `created_at`(NOT NULL)·`updated_at`(NOT NULL)·`deleted_at`(nullable, soft delete) 공통 보유. 시각은 전부 `timestamptz`(UTC Instant, 마이크로초 절삭).
- **soft delete 필터**: `resumes`는 `@SQLRestriction("deleted_at IS NULL")`로 자동 필터. `users`·`interview_session`은 필터 없이 경로별 수동 재확인(잠금 후 활성 재확인 패턴).

```mermaid
erDiagram
  USERS ||--o{ REFRESH_TOKEN : owns
  USERS ||--o{ USER_CONSENT : records
  USERS ||--o{ DELETION_LOG : requests
  USERS ||--o{ RESUMES : owns
  RESUMES ||--|| RESUME_ANALYSIS_STATUS : tracks
  RESUMES ||--o{ RESUME_CHUNKS : indexed_as
  USERS ||--o{ INTERVIEW_SESSION : owns
  RESUMES ||--o{ INTERVIEW_SESSION : based_on
  INTERVIEW_SESSION ||--o| INTERVIEW_TRANSCRIPT : flushed_as

  USERS {
    bigint id PK
    string email "nullable"
    string name "nullable, varchar(100)"
    string provider_id UK "NOT NULL, 카카오 회원번호, 파기 시 PURGED_{id} 마스킹"
    timestamptz created_at
    timestamptz updated_at
    timestamptz deleted_at "nullable, 탈퇴 시각"
  }

  REFRESH_TOKEN {
    bigint id PK
    bigint user_id "NOT NULL, ix_refresh_token_user_id"
    string token_hash UK "NOT NULL, varchar(64)"
    string jti "NOT NULL, varchar(36)"
    timestamptz expired_at "NOT NULL"
    timestamptz revoked_at "nullable"
    string replaced_by "nullable, RTR 체인"
    timestamptz created_at
  }

  USER_CONSENT {
    bigint id PK
    bigint user_id "NOT NULL, ix_user_consent_user_id"
    string consent_type "NOT NULL, append-only"
    string action "NOT NULL, AGREED|WITHDRAWN"
    int version "NOT NULL, 동의서 버전"
    timestamptz created_at
  }

  DELETION_LOG {
    bigint id PK
    bigint user_id "NOT NULL"
    string provider_id "nullable, 탈퇴 시 스냅샷"
    timestamptz requested_at "NOT NULL"
    timestamptz purged_at "nullable"
    string status "NOT NULL, PENDING_PURGE|PURGING|PURGED|FAILED|CANCELLED"
    jsonb purge_detail "nullable"
    timestamptz updated_at "NOT NULL, 벌크 전이 시 명시 갱신"
  }

  RESUMES {
    bigint id PK
    bigint user_id "NOT NULL"
    string title "NOT NULL"
    string file_hash "NOT NULL, varchar(64), (user_id+hash) 활성 중복 방지"
    string original_file_bucket "NOT NULL"
    string original_file_key "NOT NULL, resumes/{userId}/{fileHash}.pdf"
    string original_file_name "NOT NULL"
    bigint file_size "NOT NULL"
    string mime_type "NOT NULL"
    int page_count "NOT NULL"
    jsonb structured_data "nullable, StructuredData 계약"
    timestamptz created_at
    timestamptz updated_at
    timestamptz deleted_at "nullable"
  }

  RESUME_ANALYSIS_STATUS {
    bigint id PK
    bigint resume_id FK "UNIQUE, 세션당 1행 아님 — 이력서당 1행"
    string parse_status "NOT NULL, UPLOADED~EMBEDDED|FAILED"
    string parser_version "nullable, Worker 기록"
    text error_message "nullable"
    int retry_count "NOT NULL, Worker 기록"
    timestamptz started_at "nullable"
    timestamptz completed_at "nullable"
    timestamptz failed_at "nullable"
    timestamptz created_at
    timestamptz updated_at
    timestamptz deleted_at "nullable"
  }

  RESUME_CHUNKS {
    bigint id PK
    bigint resume_id "NOT NULL"
    text content
    jsonb metadata
    vector_1024 embedding "pgvector, Cohere Embed Multilingual v3"
  }

  INTERVIEW_SESSION {
    bigint id PK
    bigint user_id "NOT NULL"
    bigint resume_id "nullable, THIRTY_MIN 필수는 앱 검증(FIVE_MIN 선택)"
    string interview_type "NOT NULL, THIRTY_MIN|FIVE_MIN"
    string position "NOT NULL, BACKEND|FRONTEND"
    string status "NOT NULL, PENDING|ACTIVE|INTERRUPTED|AGENT_LOST|ENDED|ABORTED"
    string livekit_room UK "NOT NULL, 세션-룸 매핑(webhook 역추적 키)"
    timestamptz started_at "nullable, ACTIVE 전환"
    timestamptz ended_at "nullable, terminal 전환"
    timestamptz disconnected_at "nullable, INTERRUPTED 전환(후속 스토리)"
    timestamptz end_requested_at "nullable, 최초 /end 시각(fallback 앵커)"
    timestamptz agent_lost_at "nullable, AGENT_LOST 전환(유예 앵커)"
    timestamptz created_at
    timestamptz updated_at
    timestamptz deleted_at "nullable, E1 파기 연계(후속 스토리)"
  }

  INTERVIEW_TRANSCRIPT {
    bigint id PK
    bigint session_id UK "NOT NULL, FK 없음(무FK 방침)"
    jsonb content "발화 객체 배열"
    timestamptz deleted_at "nullable"
  }
```

## 테이블 소유·비고

| 테이블 | 소유 | 비고 |
| --- | --- | --- |
| `users` · `refresh_token` · `user_consent` · `deletion_log` | Spring (E1) | `user_consent`는 append-only 이력. `deletion_log`는 auditing 미적용(명시 시각 관리) |
| `resumes` · `resume_analysis_status` | Spring (이력서) | 분석 상태는 Python Worker가 전이 기록(UPLOADED 이후) |
| `resume_chunks` | Python Worker | 테이블 생성·쓰기 모두 Worker 소관. pgvector 확장은 백엔드 리포(로컬 이미지·Testcontainers)가 제공 |
| `interview_session` | Spring (세션) | HBB1-18 신설, HBB1-294가 종료 전이(webhook·/end·스위퍼)와 `end_requested_at`·`agent_lost_at` 추가. INTERRUPTED 전이만 후속 스토리. 인덱스 `(user_id, status)` |
| `interview_transcript` | Kkori-AI (에이전트) | 테이블 DDL·마이그레이션·쓰기 모두 에이전트 소관(Kkori-AI interview-end.md §4). Spring은 판별용 EXISTS 읽기만(HBB1-294 — interview-session-completion.md). dev/prod는 에이전트 배포가 테이블 존재의 선행 조건 |

## 마이그레이션 도구 도입 시 반영할 항목 (Flyway — 배포 스토리)

JPA 애너테이션으로 표현할 수 없어 보류 중인 DB 불변식·인덱스. baseline DDL 작성 시 포함할 것:

- `deletion_log`: 부분 UNIQUE 인덱스 `(user_id) WHERE status IN ('PENDING_PURGE','PURGING','FAILED')` — 유저당 활성 삭제 요청 1건 (account.md)
- `interview_session`: 부분 UNIQUE 인덱스 `(user_id) WHERE status NOT IN ('ENDED','ABORTED')` — 유저당 진행 중 세션 1개 (interview-session-creation.md)
- `interview_session`: `resume_id` 조회 인덱스 — `RESUME_IN_USE` 판정(`existsByResumeIdAndStatusIn`)이 현재 `(user_id, status)` 인덱스의 지원을 받지 못함. MVP 규모에서는 수용, DDL 작성 시 `(resume_id, status)` 검토
