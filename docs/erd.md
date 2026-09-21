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
  INTERVIEW_SESSION ||--o| REPORTS : evaluated_as
  USERS ||--o{ REPORTS : owns
  RESUMES ||--o{ REPORTS : based_on
  REPORTS ||--o| REPORT_SCORES : scored_as
  REPORTS ||--o{ REPORT_FEEDBACKS : feedback_per_answer
  REPORTS ||--o| REPORT_GENERATION_JOBS : tracked_by
  INTERVIEW_SESSION ||--o{ INTERVIEW_METRICS : measured_as

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
    jsonb purge_detail "nullable, 파기 단계별 건수·상태 기록 — PurgeDetail 계약(deletion.md 기능 3), 개인정보 미포함"
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
    timestamptz deleted_at "nullable, 물리 삭제 배치 대상 표식 — 탈퇴 파기 시 행 DELETE(deletion.md 기능 3), 개별 삭제 배치는 후속 스토리"
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
    timestamptz deleted_at "nullable, 이력서와 함께 행 DELETE(deletion.md 기능 3)"
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
    timestamptz disconnected_at "nullable, 현재 INTERRUPTED episode의 이탈 관측 시각(재연결 deadline 앵커, 복귀 시 초기화)"
    timestamptz end_requested_at "nullable, 최초 /end 시각(fallback 앵커)"
    timestamptz agent_lost_at "nullable, AGENT_LOST 전환(유예 앵커)"
    timestamptz redispatched_at "nullable, 재디스패치 CAS 마커(at-most-once — CAS 도달 여부만)"
    string egress_id "nullable, 녹음 egress id(egress_ended webhook 역매핑 키)"
    string recording_bucket "nullable, 업로드 완료된 녹음 S3 버킷"
    string recording_object_key "nullable, 업로드 완료된 객체 키(non-null = 기록·발행 완료 멱등 가드)"
    timestamptz created_at
    timestamptz updated_at
    timestamptz deleted_at "nullable, 탈퇴 파기 배치가 기록 — 행 유지, 녹음 컬럼은 S3 삭제 후 NULL(deletion.md 기능 3)"
  }

  INTERVIEW_TRANSCRIPT {
    bigint id PK
    bigint session_id UK "NOT NULL, FK 없음(무FK 방침)"
    jsonb content "발화 객체 배열, 탈퇴 파기 시 빈 배열로 마스킹"
    timestamptz deleted_at "nullable, 탈퇴 파기 배치가 마스킹과 함께 기록 — 행 유지(deletion.md 기능 3)"
  }

  REPORTS {
    bigint id PK
    bigint user_id "NOT NULL"
    bigint interview_session_id UK "NOT NULL, uk_reports_interview_session_id, 세션당 리포트 1개"
    bigint resume_id "nullable, 이력서 삭제 후에도 리포트 유지"
    string status "NOT NULL, PENDING|PROCESSING|COMPLETED|FAILED"
    int overall_score "nullable, 코드 집계(평가된 축 평균)"
    int delivery_score "nullable, 2단계 음성 분석, 오디오 없으면 null"
    text summary "nullable, 총평"
    string resume_file_name_snapshot "NOT NULL, 생성 시점 이력서 파일명"
    jsonb weakness_tag_summary "nullable, 태그별 빈도"
    text failed_reason "nullable"
    timestamptz text_analyzed_at "nullable, 1단계 텍스트 평가 완료"
    timestamptz audio_analyzed_at "nullable, 2단계 음성 분석 완료"
    timestamptz completed_at "nullable"
    timestamptz created_at
    timestamptz updated_at
    timestamptz deleted_at "nullable, @SQLRestriction 필터"
  }

  REPORT_SCORES {
    bigint id PK
    bigint report_id UK "NOT NULL, uk_report_scores_report_id, 리포트당 1행(텍스트 3축 영역 점수)"
    int logic_score "NOT NULL"
    int specificity_score "NOT NULL"
    int technical_accuracy_score "NOT NULL"
    timestamptz created_at
    timestamptz updated_at
    timestamptz deleted_at "nullable"
  }

  REPORT_FEEDBACKS {
    bigint id PK
    bigint report_id "NOT NULL, (report_id, question_number) UNIQUE"
    int question_number "NOT NULL"
    int logic_score "NOT NULL"
    int specificity_score "NOT NULL"
    int technical_accuracy_score "NOT NULL"
    text feedback "NOT NULL"
    jsonb weakness_tags "nullable, 답변당 태그 배열"
    jsonb improvement_tasks "nullable"
    jsonb resume_context "nullable, 평가에 인용한 이력서 근거"
    timestamptz created_at
    timestamptz updated_at
    timestamptz deleted_at "nullable"
  }

  REPORT_GENERATION_JOBS {
    bigint id PK
    bigint report_id UK "NOT NULL, 리포트당 잡 1개"
    int retry_count "NOT NULL, default 0"
    text error_message "nullable"
    timestamptz requested_at "NOT NULL"
    timestamptz created_at "NOT NULL, default now()"
    timestamptz updated_at "NOT NULL, default now()"
  }

  INTERVIEW_METRICS {
    bigint id PK
    bigint session_id "NOT NULL, interview_metrics_session_id_idx, FK 없음"
    string batch_id "NOT NULL, 잡 ID(재시도 간 불변)"
    int ordinal "NOT NULL, (batch_id, ordinal) UNIQUE로 재시도 중복 차단"
    timestamptz ts "NOT NULL"
    string kind "NOT NULL, STT, LLM, TTS, VAD, EOU 등 이벤트 종류"
    jsonb payload "NOT NULL, 이벤트 원본"
  }
```

## 테이블 소유·비고

| 테이블 | 소유 | 비고 |
| --- | --- | --- |
| `users` · `refresh_token` · `user_consent` · `deletion_log` | Spring (E1) | `user_consent`는 append-only 이력. `deletion_log`는 auditing 미적용(명시 시각 관리) |
| `resumes` · `resume_analysis_status` | Spring (이력서) | 분석 상태는 Python Worker가 전이 기록(UPLOADED 이후). 탈퇴 파기 시 물리 삭제(deletion.md 기능 3) |
| `resume_chunks` | Python Worker | 테이블 생성·쓰기 모두 Worker 소관. pgvector 확장은 백엔드 리포(로컬 이미지·Testcontainers)가 제공. **예외**: 탈퇴 파기·개별 삭제 시 Spring이 `resume_id` 기준 DELETE(deletion.md 크로스 레포 계약) |
| `interview_session` | Spring (세션) | HBB1-18 신설, HBB1-294가 종료 전이(webhook·/end·스위퍼)와 `end_requested_at`·`agent_lost_at` 추가, HBB1-308이 재연결(`disconnected_at` 사용 개시)·재디스패치(`redispatched_at`) 추가. 인덱스 `(user_id, status)` |
| `interview_transcript` | Kkori-AI (에이전트) | 테이블 DDL·마이그레이션·쓰기 모두 에이전트 소관(Kkori-AI interview-end.md §4). Spring은 판별용 EXISTS 읽기만(HBB1-294 — interview-session-completion.md). dev/prod는 에이전트 배포가 테이블 존재의 선행 조건. **예외**: 탈퇴 파기 시 Spring이 `content = []`·`deleted_at` 마스킹 UPDATE(deletion.md 크로스 레포 계약) |
| `reports`, `report_scores`, `report_feedbacks` | 정의 Spring (리포트 엔티티), 행은 Python Worker | Worker가 INSERT, UPDATE로 생성 수명주기 전체를 맡고 Spring은 조회만 한다(save, delete 없음). DDL은 로컬 ddl-auto update로 생성, dev/prod는 validate. **예외**: 탈퇴 파기 시 Spring이 JDBC로 물리 삭제(deletion.md 기능 3) |
| `report_generation_jobs` | Python Worker | 테이블 생성, 쓰기 모두 Worker 소관(worker/src/report/repository.py). 리포트당 1행, 재시도 횟수와 오류 기록. Spring 미접근. **예외**: 탈퇴 파기 시 Spring이 JDBC로 DELETE(deletion.md 기능 3) |
| `interview_metrics` | Kkori-AI (에이전트) | agent/migrations/002. STT, LLM, TTS 등 파이프라인 메트릭을 이벤트당 1행 jsonb로 적재. Spring 미접근 |

## 마이그레이션 도구 도입 시 반영할 항목 (Flyway — 배포 스토리)

JPA 애너테이션으로 표현할 수 없어 보류 중인 DB 불변식·인덱스. baseline DDL 작성 시 포함할 것:

- `deletion_log`: 부분 UNIQUE 인덱스 `(user_id) WHERE status IN ('PENDING_PURGE','PURGING','FAILED')` — 유저당 활성 삭제 요청 1건 (account.md)
- `interview_session`: 부분 UNIQUE 인덱스 `(user_id) WHERE status NOT IN ('ENDED','ABORTED')` — 유저당 진행 중 세션 1개 (interview-session-creation.md)
- `interview_session`: `resume_id` 조회 인덱스 — `RESUME_IN_USE` 판정(`existsByResumeIdAndStatusIn`)이 현재 `(user_id, status)` 인덱스의 지원을 받지 못함. MVP 규모에서는 수용, DDL 작성 시 `(resume_id, status)` 검토
