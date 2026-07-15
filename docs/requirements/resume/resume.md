# 이력서 (Resume)

## Overview

이력서 도메인은 사용자가 PDF 이력서를 업로드하면 이를 비동기로 분석하여, 면접 질문 생성과 리포트 평가에 활용 가능한 구조화 데이터·검색 색인으로 만드는 기능 영역이다. Spring 서버는 업로드 검증·저장·상태 관리·조회 API를 담당하고, 실제 분석(텍스트 추출 → LLM 구조화 → 청킹 → 임베딩 → pgvector 색인)은 Redis Stream으로 분리된 Python AI Worker가 수행한다. 분석 진행 상태는 Redis Stream 상태 이벤트를 Spring이 소비하여 SSE로 사용자에게 실시간 전달한다.

핵심 데이터 흐름: `PDF 업로드 → S3 원본 저장 → 분석 요청 발행(Redis Stream) → Worker 분석 → 구조화 데이터·청크·임베딩 저장(PostgreSQL/pgvector) → 질문 생성 시 AI Worker가 청크를 직접 검색하여 활용`

RAG 검색과 질문 생성은 임베딩 모델을 보유한 Python AI Worker가 직접 수행하며(별도 REST API 없음), 이력서 도메인의 책임은 그 검색이 의존하는 청크 데이터의 무결성 보장까지다 — EMBEDDED 상태의 의미(§Overview 상태 표), 사용 중 변경 차단(§4), 삭제 시 청크 정리(§5).

### 분석 상태

| 상태 | 의미 |
| --- | --- |
| UPLOADED | 업로드 완료, 분석 대기 |
| PARSING | 분석 시작 (Worker가 요청을 수신하여 처리 시작) |
| TEXT_EXTRACTING | PDF 텍스트 추출 중 |
| STRUCTURING | LLM 구조화 진행 중 |
| PARSED | 텍스트 추출 및 구조화 완료 |
| EMBEDDING | 청킹·임베딩·색인 진행 중 |
| EMBEDDED | 면접에 활용 가능한 최종 상태 |
| FAILED | 분석 실패 (시퀀스 다이어그램에는 없으나 실패 처리를 위해 추가) |

### 기능 요구사항

| No. | Function | Description |
| --- | --- | --- |
| 1 | 이력서 업로드 및 비동기 분석 | PDF 파일을 검증·저장하고 비동기 분석 파이프라인(추출→구조화→색인)을 시작한다. |
| 2 | 이력서 조회 | 본인 이력서의 목록·상세 정보를 조회하고, 원본 PDF의 임시 다운로드 URL을 발급한다. |
| 3 | 분석 상태 추적 | 분석 진행 상태를 REST 조회 및 SSE 실시간 이벤트로 제공한다. |
| 4 | 파싱 결과 확인·수정·재분석 | 구조화된 이력서 내용을 사용자가 확인·수정하고, 수정본 기준 재분석을 요청한다. |
| 5 | 이력서 삭제 | 이력서와 파생 데이터(원본·구조화 데이터·청크·임베딩)를 삭제한다. |

---

## 1. 이력서 업로드 및 비동기 분석

### 설명

사용자가 `POST /api/v1/resumes`로 PDF를 업로드하면(multipart, `file` 필수 / `title` 선택 — 없으면 원본 파일명 사용), 서버는 파일을 검증한 뒤 S3에 원본을 저장하고 이력서·분석 상태(UPLOADED) 레코드를 생성한 후, Redis Stream에 분석 요청 이벤트를 발행하고 즉시 응답한다.

- 검증 순서: 파일 존재 → 확장자·MIME(`application/pdf`) → 크기(10MB) → PDF 열기(유효성·페이지 수 확인). Spring이 업로드 시점에 PDF를 열어 손상 여부와 페이지 수를 동기 검증하므로, 업로드 응답의 `pageCount`는 항상 채워진다. 깊은 파싱(텍스트 추출)은 Worker 담당.
- **중복 업로드 처리 (2층 구조)**:
  - **스토리지 층** — 파일 바이너리의 SHA-256을 `file_hash`로 저장하고, S3 objectKey를 사용자별 해시 기반(`resumes/{userId}/{fileHash}.pdf`)으로 생성한다. 같은 사용자의 같은 바이너리는 S3에 1부만 존재하며(소유권 경계가 키에 드러나 삭제 시 참조 확인도 사용자 내로 한정), "S3 저장 후 DB 저장 전 서버 사망"으로 남은 고아 객체도 재업로드 시 자연스럽게 재사용된다.
  - **사용자 흐름 층** — 같은 `file_hash`의 활성 이력서가 이미 있으면 **새로 만들지 않고, 아무 상태도 바꾸지 않고**, 기존 이력서 정보에 `duplicated: true`를 붙여 200으로 반환한다(분석 상태 무관 동일 규칙 — 업로드 API는 중복 시 부수효과가 없다). 프론트는 상태에 따라 안내한다: 진행 중이면 SSE 재연결, EMBEDDED면 기존 이력서로 이동, FAILED면 재분석 버튼(§4) 제공.
  - 중복 판단 범위는 **(userId + file_hash)** — 타 사용자의 같은 파일은 중복 판정 대상이 아니며(해시만으로 조회하면 타인의 resumeId가 노출되는 정보 누출), 동시 업로드 레이스는 부분 유니크 인덱스 `(user_id, file_hash) WHERE deleted_at IS NULL`이 방어한다.
- **FAILED 복구는 §4 재분석 API로만** 한다 — 같은 파일을 재업로드해도 위 규칙대로 `duplicated` 정보만 반환되며, 분석 재시작은 사용자의 명시적 재분석 요청으로만 일어난다.
- Worker 파이프라인: S3에서 PDF 다운로드 → PyMuPDF 텍스트 추출 → LLM으로 구조화(`structuredData`: profile/skills/projects/experiences) → 의미 단위 청킹 + 청크별 metadata 생성 → 임베딩 생성 → `resume_chunks` 저장(content, metadata, embedding) → 상태 EMBEDDED. 각 단계 진입 시 상태를 갱신하고 상태 이벤트를 발행한다.
- 추출 원문(raw text)은 **저장하지 않는다**. 재분석 등으로 원문이 필요하면 S3 원본에서 다시 파싱한다.
- 분석 실패(FAILED) 시 복구는 §4 재분석(전체 재분석 모드)으로 한다. 같은 파일을 재업로드해도 새 이력서가 생기지 않는다(위 중복 규칙).
- 구조화 완료 후 별도의 품질 확인 이벤트는 보내지 않는다. 분석 완료 후 프론트가 항상 사용자에게 파싱 결과 확인을 유도한다(§4).

### 실행 조건

- 사용자가 인증된 상태여야 한다.
- S3, Redis Stream이 가용해야 한다. S3 저장 실패 시 500(FILE_UPLOAD_FAILED), 분석 요청 발행 실패 시 500(RESUME_ANALYSIS_REQUEST_FAILED)으로 응답한다.

### 검증 기준

- PDF가 아닌 파일 업로드 시 400(INVALID_FILE_TYPE)으로 거부되는지 확인
- 10MB 초과 파일 업로드 시 413(FILE_TOO_LARGE)으로 거부되는지 확인 (Spring 멀티파트 한도 초과 예외도 GlobalExceptionHandler에서 동일 형식으로 변환)
- 손상되었거나 읽을 수 없는 PDF 업로드 시 400(INVALID_PDF)으로 거부되는지 확인
- 10페이지 초과 PDF 업로드 시 400(PAGE_LIMIT_EXCEEDED)으로 거부되는지 확인
- 파일 없이 요청 시 400(FILE_REQUIRED)으로 거부되는지 확인
- 정상 업로드 시 201 응답에 resumeId, pageCount, analysisStatus=UPLOADED가 포함되는지 확인
- 업로드 성공 후 분석 요청 이벤트가 Redis Stream에 발행되는지 확인
- title 미지정 시 원본 파일명이 title로 사용되는지 확인
- 동일 파일 재업로드 시(파일명이 달라도) 새 레코드·새 분석 없이 기존 이력서 정보 + `duplicated: true`가 200으로 반환되는지 확인
- 중복 응답이 기존 이력서의 상태를 어떤 경우에도 변경하지 않는지 확인
- 동시에 같은 파일이 업로드되어도 활성 이력서는 해시당 1개만 생성되는지 확인 (부분 유니크 인덱스 `WHERE deleted_at IS NULL` + 충돌 시 기존 레코드 반환)
- S3에 객체만 있고 활성 레코드가 없는 상태(고아)에서 같은 파일 업로드 시, 재저장 없이 기존 객체를 가리키는 새 레코드가 생성되는지 확인
- 분석 파이프라인 완료 후 상태가 EMBEDDED가 되고 resume_chunks가 생성되는지 확인
- raw text가 DB에 저장되지 않는지 확인

### 성능 요구사항

- 없음 (업로드 응답 시간·분석 소요 시간 SLA는 추후 정의)

### 인터페이스 요구사항

- `POST /api/v1/resumes` — multipart/form-data (`file`: PDF, `title`: string 선택)
- 응답은 공통 엔벨로프 `ApiResponse<T>`를 따른다 (이하 모든 REST API 동일)
- Redis Stream: 분석 요청 스트림(발행), 상태 이벤트 스트림(소비). **스트림별 메시지 스키마의 정의 원천은 계약 record** — `ResumeParseRequestedMessage`(요청: resumeId, userId, bucket, objectKey, **mode**[FULL|REINDEX] — 신규 업로드도 FULL로 발행하며, 5개 필드는 모드와 무관하게 전부 필수. REINDEX에서 bucket/objectKey는 무시됨), `ResumeStatusChangedMessage`(상태: resumeId, **userId**, status, message — userId는 SSE 사용자별 라우팅 근거로, Worker가 요청 메시지의 userId를 에코). Python Worker는 이 두 파일을 계약 문서로 참조
- 분석 요청 스트림은 Consumer Group 기반 **at-least-once** — 동일 메시지가 중복 전달될 수 있으므로 Worker 처리는 **resumeId 기준 멱등**이어야 한다(이행 방법은 Worker 소관). **모든 분석 요청은 반드시 EMBEDDED 또는 FAILED로 끝난다** — Worker는 ACK 없이 오래 방치된 메시지를 회수(XAUTOCLAIM)해 DB 상태 기준 체크포인트에서 재개하고, 재전달 횟수가 임계를 넘은 메시지는 재처리 없이 FAILED로 끝낸다(상세는 Worker PRD)

### 제약사항

- 파일 형식: PDF만 지원 (MIME `application/pdf`)
- 최대 크기: 10MB, 최대 페이지 수: 10페이지
- 회원당 이력서 보관 개수 제한: **미정**

### 기타 요구사항

- **Worker 장애·재처리 정책 (2026-07-15 확정)**: 회수한 메시지는 DB 상태로 재개 지점을 정한다 — EMBEDDED=스킵 후 ACK / EMBEDDING=기존 청크 정리 후 재임베딩 / PARSED=임베딩부터 / 그 이전=처음부터(원문 미저장이므로). 포기 규칙: 처리 시작 전 delivery count가 임계를 넘으면 재처리 없이 FAILED 기록(error_message "재전달 임계 초과" + 당시 count) 후 XACK. idle time·임계값·내부 재시도 수치는 Worker PRD 소관.
- **별도 DLQ 스트림은 두지 않는다** — 메시지는 모든 필드가 DB에서 재유도 가능한 포인터라 격리 보존할 고유 정보가 없고, 재처리는 재주입이 아니라 §4 재분석(DB에서 새 메시지 생성)으로 한다. 격리 건은 FAILED 레코드로 일반 실패와 구분·조회 가능.
- 잔여 한계: Worker가 장기간 완전 정지하면 회수도 멈춰 상태가 진행 중에 머문다 — API가 아닌 운영(모니터링·알림)의 영역.
- **계약 변경 권한은 백엔드** — 스트림·상태·structuredData 계약이 양 repo에서 어긋나면 이 문서와 계약 record가 우선한다. Worker PRD는 크로스 레포 참조가 불가하므로(리뷰·CI가 상대 repo를 못 봄) 계약 전문의 자기완결 사본을 유지하고, 표류는 Worker 측 골든 샘플 픽스처 테스트로 방어한다.
- **임베딩 벡터 스키마 (2026-07-15 확정)**: `resume_chunks.embedding vector(1024)` — Cohere Embed Multilingual v3 기준. 모델 교체는 컬럼·인덱스 재생성 + 전량 재임베딩을 수반하므로 차원·모델명을 스키마 사실로 기록. 모델 선정·운용(구조화 LLM Claude Haiku 4.5, 색인/질의 입력 타입 비대칭 등)은 Worker PRD 소관 — 면접 도메인의 질의 벡터 생성도 Python 쪽이므로 백엔드가 이행할 규칙은 없음.
- **Outbox 패턴은 MVP에서 도입하지 않기로 결정** (2026-07-14). 발행은 DB 트랜잭션 안에서 수행 — 발행 실패 시 롤백되어 사용자에게 실패가 보이는(시끄러운 실패) 쪽을 선택. 남는 구멍(발행 성공 후 커밋 실패 → 유령 이벤트)은 확률이 낮고, 사용자 재시도를 dedup이 흡수 + Worker의 "레코드 없으면 스킵" 계약으로 무해화됨. **도입 재검토 신호**: "성공했는데 분석이 시작 안 됨" 문의 발생, 요청 유실 제로 SLA 요구, 발행 지점이 여러 도메인으로 확대. 그 전 중간 단계로 "오래된 UPLOADED 재발행 배치"도 선택지.

---

## 2. 이력서 조회

### 설명

사용자는 본인이 업로드한 이력서를 조회할 수 있다.

- 목록(`GET /api/v1/resumes`): 페이지네이션(기본 page=0, size=20), 분석 상태 필터(`status`) 지원. 면접 시작 전 이력서 선택 화면, 대시보드에서 사용. 각 항목에 분석 상태(analysisStatus)를 포함해 FAILED 여부까지 표시한다.
- 상세(`GET /api/v1/resumes/{resumeId}`): 파일 메타데이터와 현재 분석 상태를 반환한다.
- 원본 다운로드(`GET /api/v1/resumes/{resumeId}/download-url`): S3 Presigned URL을 발급한다(만료 300초). 클라이언트는 S3에 직접 접근하지 않고 항상 이 URL을 통한다.

### 실행 조건

- 사용자가 인증된 상태여야 하며, 본인 이력서만 조회할 수 있다.

### 검증 기준

- 다른 사용자의 이력서 접근 시 403(RESUME_FORBIDDEN)이 반환되는지 확인
- 존재하지 않는 resumeId 조회 시 404(RESUME_NOT_FOUND)가 반환되는지 확인
- 목록이 page/size 파라미터대로 페이지네이션되는지 확인
- status 필터 지정 시 해당 상태의 이력서만 반환되고, 잘못된 상태값은 400(INVALID_STATUS)인지 확인
- FAILED 상태 이력서도 목록·상세에서 정상 조회되는지 확인
- 발급된 Presigned URL로 원본 PDF가 다운로드되고, 만료 시간 이후에는 접근이 거부되는지 확인

### 성능 요구사항

- 없음

### 인터페이스 요구사항

- `GET /api/v1/resumes?status=&page=&size=` / `GET /api/v1/resumes/{resumeId}` / `GET /api/v1/resumes/{resumeId}/download-url`
- S3 접근: 서버는 IAM Role 기반 SDK, 클라이언트는 Presigned URL만 사용

### 제약사항

- Presigned URL 만료: 300초

### 기타 요구사항

- 없음

---

## 3. 분석 상태 추적

### 설명

분석 진행 상태를 두 채널로 제공한다.

- REST 조회(`GET /api/v1/resumes/{resumeId}/status`): 현재 상태, 실패 정보(errorMessage), 시각 정보를 반환한다. SSE 유실·재연결 시 상태 동기화용.
- SSE 구독(`GET /sse/v1/resumes`): **사용자 단위 단일 연결**로 인증된 사용자 **본인 이력서의** 상태 변경만 push한다(userId 키 라우팅 — 타 사용자 이벤트 미수신). 이력서 단위 연결은 사용하지 않는다(브라우저 동시 연결 한도·연결 수명 관리 문제). SSE는 일반 REST(`/api/**`)와 분리된 `/sse/**` 네임스페이스를 사용한다.
- **클라이언트 주의**: 브라우저 표준 `EventSource`는 Authorization 헤더를 지원하지 않으므로, 프론트는 fetch 기반 SSE 클라이언트(예: `@microsoft/fetch-event-source`)로 Bearer 토큰을 실어 연결한다.

SSE 이벤트는 3종이며, data 스키마는 단일 형식으로 통일한다:

| event | 시점 |
| --- | --- |
| RESUME_ANALYSIS_STATUS_CHANGED | 중간 상태 변경(UPLOADED/PARSING/TEXT_EXTRACTING/STRUCTURING/PARSED/EMBEDDING) |
| RESUME_ANALYSIS_COMPLETED | 최종 성공(EMBEDDED) |
| RESUME_ANALYSIS_FAILED | 실패(FAILED) |

```json
{ "resumeId": 12, "status": "TEXT_EXTRACTING", "message": null }
```

- `message`는 status만으로 유도할 수 없는 정보 전달용이다(예: FAILED의 실패 사유). 정상 단계의 표시 문구·진행률 UI는 **프론트가 status 기반으로 매핑**하며, 서버는 별도 progress 필드를 내려주지 않는다.

프론트 규약: 화면 진입·SSE 재연결 시 REST(목록 또는 /status)로 현재 상태를 동기화하고, SSE는 그 이후의 변경분만 반영한다.

### 실행 조건

- 사용자가 인증된 상태여야 한다.
- Spring이 Redis Stream 상태 이벤트를 소비 중이어야 SSE push가 동작한다.

### 검증 기준

- 상태 변경 시 해당 사용자의 SSE 연결로 이벤트가 push되는지 확인
- 3종 이벤트의 data가 모두 동일 스키마({resumeId, status, message})인지 확인
- FAILED 이벤트의 message에 실패 사유가 포함되는지 확인
- 다른 사용자의 이력서 이벤트가 수신되지 않는지 확인
- SSE 연결이 끊긴 상태에서 상태가 변해도, 재연결 후 REST 조회로 최신 상태를 얻을 수 있는지 확인
- FAILED 시 /status 응답에 errorMessage가 포함되는지 확인

### 성능 요구사항

- 없음 (상태 변경 → SSE push 지연 SLA는 추후 정의)

### 인터페이스 요구사항

- `GET /api/v1/resumes/{resumeId}/status` / `GET /sse/v1/resumes` (Content-Type: text/event-stream)
- SSE keepalive: 프록시·ALB의 유휴 연결 종료를 막기 위해 15~30초 간격으로 SSE 주석 라인(`: ping`)을 전송한다

### 제약사항

- SSE로 놓친 이벤트는 재전송하지 않는다 — 복구는 REST 동기화로만 한다.

### 기타 요구사항

- 상태별 표시 문구·진행률 표현은 프론트 소관 (status 기반 매핑)

---

## 4. 파싱 결과 확인·수정·재분석

### 설명

사용자는 AI가 구조화한 이력서 내용을 확인하고 수정할 수 있다. 분석 완료 후 프론트는 항상 사용자에게 파싱 결과 확인을 유도한다(별도 품질 확인 이벤트 없음).

- 조회(`GET /api/v1/resumes/{resumeId}/parsed`): structuredData(profile/skills/projects/experiences)를 반환한다. **rawText는 응답에 포함하지 않는다.**
- 수정(`PATCH /api/v1/resumes/{resumeId}/parsed`): 사용자가 수정한 structuredData를 저장한다. 수정만으로는 재분석되지 않는다.
- 재분석(`POST /api/v1/resumes/{resumeId}/reanalyze`): **엔드포인트는 하나, 현재 상태가 모드를 결정한다** (사용자가 모드를 고르지 않음). Worker에 보내는 분석 요청 이벤트에 `mode` 필드로 전달한다.
  - **EMBEDDED → 재색인 모드(REINDEX)**: 수정된 structuredData를 진실로 삼아 청킹·임베딩·색인만 재수행. 구조화(텍스트 추출~STRUCTURING)를 재수행하면 LLM이 사용자 수정을 덮어쓰므로 건너뛴다. 상태는 EMBEDDING → EMBEDDED로 진행.
  - **FAILED → 전체 재분석 모드(FULL)**: S3 원본 PDF를 진실로 삼아 텍스트 추출부터 전부 재수행. FAILED의 유일한 복구 수단이다(§1). 상태는 UPLOADED부터 재시작. FAILED는 Worker가 재시도를 소진했거나 재전달 임계를 초과한 끝 상태이며, 서버는 자동 재시도하지 않는다 — 복구 주체는 항상 사용자.

파싱 결과의 조회·수정은 **분석이 완전히 완료된 상태(EMBEDDED)에서만 가능하다.** 진행 중(UPLOADED~EMBEDDING)에는 완료를 기다려야 하고, FAILED 이력서는 조회·수정 대상이 아니다(파싱 산출물이 없으므로).

### 실행 조건

- 사용자가 인증된 상태여야 하며, 본인 이력서만 접근할 수 있다.
- 조회·수정은 분석 상태가 EMBEDDED여야 한다. 진행 중이면 409(RESUME_ANALYSIS_IN_PROGRESS), FAILED면 409(RESUME_ANALYSIS_FAILED)로 거부한다.
- 재분석은 EMBEDDED 또는 FAILED에서 가능하다. 진행 중이면 409(RESUME_ANALYSIS_IN_PROGRESS)로 거부한다.
- 진행 중인 면접에서 사용 중인 이력서는 수정·재분석할 수 없다(409 RESUME_IN_USE) — 면접이 검색하는 청크가 도중에 갈리는 것을 방지 (§5 삭제와 동일한 보호).

### 검증 기준

- 분석 진행 중(EMBEDDED 이전) 조회·수정·재분석 요청 시 409(RESUME_ANALYSIS_IN_PROGRESS)가 반환되는지 확인
- FAILED 이력서에 조회·수정 요청 시 409(RESUME_ANALYSIS_FAILED)가 반환되는지 확인
- FAILED 이력서에 재분석 요청 시 mode=FULL로 분석 요청 이벤트가 발행되고 상태가 재시작되는지 확인
- EMBEDDED 이력서에 재분석 요청 시 mode=REINDEX로 발행되고 수정된 structuredData가 보존되는지 확인
- 구조가 잘못된 structuredData(역직렬화 불가, 배열 내 null 요소) 수정 요청 시 400(INVALID_INPUT_VALUE + fieldErrors)이 반환되는지 확인 — 전용 코드(INVALID_STRUCTURED_DATA)는 폐기하고 표준 검증 응답으로 통일
- 빈 배열·필드 누락(null)은 유효한 수정으로 허용되는지 확인 — 내용의 올바름은 시스템이 판정하지 않으며(부실하면 손해는 질문 품질로 사용자에게), 구조와 안전만 검증한다
- structuredData 크기 상한 초과 시 400으로 거부되는지 확인
- 수정 후 재분석을 요청해야만 청크·임베딩이 갱신되는지 확인 (수정만으로는 기존 색인 유지)
- 진행 중인 면접에서 사용 중인 이력서에 수정·재분석 요청 시 409(RESUME_IN_USE)가 반환되는지 확인
- 조회 응답에 rawText 필드가 없는지 확인

### 성능 요구사항

- 없음

### 인터페이스 요구사항

- `GET·PATCH /api/v1/resumes/{resumeId}/parsed` / `POST /api/v1/resumes/{resumeId}/reanalyze`(바디 없음)
- structuredData 스키마: profile(name, email), skills[](category, items[]), projects[](name, role, description, techStacks[]), experiences[](title, description)
- **스키마의 정의 원천은 계약 record `StructuredData`** (jsonb ↔ record 매핑, 스트림 계약 record와 동일 철학) — Worker(쓰기: LLM 구조화 결과 저장 / 읽기: REINDEX 입력)와 공유하는 계약 문서. 읽기는 관대(unknown 필드 무시), 쓰기는 엄격(구조 검증)

### 제약사항

- 재분석 요청은 바디 없음 — reason 필드는 소비처가 없어 제거 (모드는 상태가 결정하므로 사유도 상태에서 유도 가능)
- structuredData 크기 상한: 100KB — JSON 바디는 멀티파트와 달리 기본 크기 제한이 없어 대용량 주입(메모리·jsonb·청킹 입력) 방어 필요
- `retry_count`는 Worker가 기록하고 서버는 읽기 전용 — 재분석 시에도 서버는 초기화하지 않는다(리셋 시점·의미는 Worker PRD 소관)

### 기타 요구사항

- 없음

---

## 5. 이력서 삭제

### 설명

사용자가 이력서를 삭제하면(`DELETE /api/v1/resumes/{resumeId}`) 이력서 원본 파일(S3), 구조화 데이터, 청크, 임베딩이 삭제 대상으로 표시된다. MVP에서는 soft delete 후 배치로 물리 삭제한다.

### 실행 조건

- 사용자가 인증된 상태여야 하며, 본인 이력서만 삭제할 수 있다.
- 진행 중인 면접에서 사용 중인 이력서는 삭제할 수 없다.

### 검증 기준

- 삭제된 이력서가 목록·상세 조회에서 더 이상 노출되지 않는지 확인
- 진행 중인 면접에서 사용 중인 이력서 삭제 시 409(RESUME_IN_USE)가 반환되는지 확인
- 물리 삭제 시 S3 원본, structuredData, resume_chunks(임베딩 포함)가 모두 제거되는지 확인
- **같은 objectKey를 참조하는 다른 활성 이력서가 있으면 S3 객체는 삭제하지 않는지 확인** (해시 기반 키는 여러 레코드가 한 객체를 공유하므로 참조 확인 필수)
- FAILED 상태 이력서도 삭제 가능한지 확인

### 성능 요구사항

- 없음

### 인터페이스 요구사항

- `DELETE /api/v1/resumes/{resumeId}`

### 제약사항

- soft delete 후 물리 삭제 배치의 주기: **미정**

### 기타 요구사항

- **개인정보 파기**: 회원 탈퇴 후 3일이 경과하면 해당 회원의 모든 이력서 데이터(S3 원본, 이력서·분석 상태 레코드, structuredData, 청크·임베딩)를 완전 삭제한다.
