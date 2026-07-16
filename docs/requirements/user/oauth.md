# 소셜 계정 가입 · 로그인

> **User Story**: HBB1-11 — 나는 사용자로서 서비스를 이용하기 위해 소셜 계정으로 가입·로그인할 수 있다.
>
> **하위 이슈**: HBB1-57 [개발] 소셜 로그인 개발 · HBB1-54 [개발] 토큰 발급 개발 · HBB1-55 [개발] 토큰 재발급 개발 · HBB1-56 [테스트] 인증 기능 테스트

## Overview

본 기능은 사용자가 카카오 소셜 계정으로 서비스에 가입하거나 로그인하고, 이후 JWT 기반 인증으로 서비스를 이용하는 흐름을 정의한다. 카카오 인가 코드 처리와 유저 판정은 Spring API Server가 담당하며, 프론트(React)가 카카오 콜백으로 받은 인가 코드를 백엔드에 전달하는 프론트 콜백 방식을 사용한다. 유저 정보와 Refresh Token은 PostgreSQL에 저장된다.

인증 흐름은 소셜 로그인(카카오 code 교환 → 신규/기존/복구 판정 → 신규·복구 유저는 동의 후 계정 생성·복구) → 토큰 발급(JWT Access/Refresh Token) → 토큰 재발급(RTR + Grace Period)의 세 기능으로 구성된다. 계정은 필수 동의 완료 시점에 생성되며, "동의 전 계정" 상태는 존재하지 않는다. 탈퇴 계정의 복구도 동일하게 재동의 완료 시점에 성립한다.

본 문서는 인증 흐름의 계약(요청·판정·응답)까지만 정의한다. 동의 항목의 정의와 저장 정책은 수집 동의 스토리(HBB1-12), 탈퇴·복구·파기의 내부 처리는 계정 관리 스토리(HBB1-10) 및 영구 삭제 스토리의 PRD에서 다룬다.

### 기능 요구사항

| No. | Function | Description |
| --- | --- | --- |
| 1 | 소셜 로그인 | 카카오 인가 코드로 유저를 신규/기존/복구 대상으로 판정하고, 신규·복구 유저는 signup token 발급 후 필수 동의를 거쳐 계정을 생성·복구해야 한다. |
| 2 | 토큰 발급 | 로그인·가입이 완료된 유저에게 Access Token(30분)과 Refresh Token(14일)을 발급하고, RT는 해시로 DB에 저장해야 한다. |
| 3 | 토큰 재발급 | Refresh Token으로 새 토큰 쌍을 발급하되 RTR을 적용하고, Grace Period(60초)로 정상 재시도와 탈취를 구분해야 한다. |

---

## 소셜 로그인

### 설명

사용자가 카카오 로그인 페이지에서 인증을 완료하면 프론트가 인가 코드(code)를 받아 `POST /api/v1/auth/kakao`로 전달한다. 서버는 code를 카카오 access token으로 교환하고 사용자 정보(회원번호·이메일)를 조회한 뒤, `provider_id` 조회 결과와 `deleted_at`으로 유저를 판정해야 한다.

- **기존 유저** (`provider_id` 존재 + `deleted_at IS NULL`): 즉시 JWT를 발급하여 로그인을 완료해야 한다.
- **신규 유저** (조회 결과 없음): 계정을 만들지 않고 **signup token**(10분 유효, 서버 미저장)만 발급해야 한다. signup token은 JWT로, claim은 `provider_id`·`email`(null 가능)·`nickname`(null 가능, 카카오 프로필 닉네임 — 가입 시 `users.name`의 출처)·`token_type=signup`·만료로 구성하며 AT와 별도의 서명 키를 사용한다. 검증 시 `token_type`을 확인해 AT로의 오용을 차단해야 한다.
- **복구 대상** (`provider_id` 존재 + `deleted_at` 기록, 탈퇴 유예 기간 내): 즉시 복구하지 않고 **복구용 signup token**을 발급하며, `isNewUser: false`·`isRestored: true`로 응답해 프론트가 복구 안내와 함께 동의 화면으로 라우팅할 수 있게 해야 한다. 복구용 토큰은 신규 가입용과 동일한 claim 구성에 **`deletion_log_id`(해당 탈퇴 요청 식별자) claim을 추가**해 특정 탈퇴 건에 바인딩한다(복구 후 재탈퇴한 계정을 옛 토큰으로 되돌리는 재사용 차단 — 상세는 HBB1-10). 탈퇴로 동의가 철회된 상태이므로 재동의 제출(`/auth/signup`) 시점에 복구가 성립한다. **유예가 만료된 탈퇴 계정도** 계정 변경 없이 신규 유저 응답(`isNewUser: true`)과 함께 같은 방식으로 바인딩된 토큰을 받는다 — 식별정보 파기와 신규 생성은 제출 시점의 재판정이 수행한다. 복구·만료 처리의 내부(`deleted_at` 해제, 삭제 로그·동의 기록 갱신, 상태·유예 재판정)는 계정 관리 스토리(HBB1-10)의 요구사항을 따른다.
- 신규·복구 유저는 프론트가 동의 화면으로 라우팅하고, 동의 완료 시 `POST /api/v1/auth/signup`으로 signup token과 동의 내역을 전달한다. 서버는 서명 검증 후 `consents`의 형태(중복·미지원 type 등 — 아래 형태 규칙), 제출된 동의의 동의서 버전이 서버 현재 버전과 일치하는지(불일치 시 `409 CONSENT_VERSION_MISMATCH` — 버전 확인 계약은 HBB1-12), 필수 동의 항목이 전부 동의됐는지 순서대로 확인하고, **토큰의 `deletion_log_id` 유무**로 분기한다: 없으면(신규 가입용) `users` 생성을, 있으면(탈퇴 건 바인딩) 해당 건을 **잠금 하에 재판정**해 유예 내면 계정 복구를, 유예 만료면 식별정보 파기 후 신규 생성을 — 동의 기록 저장과 한 트랜잭션으로 처리한 뒤 JWT를 발급해야 한다(상세는 HBB1-10). 재판정에서 파기가 진행·재시도 중이면 `409 PURGE_IN_PROGRESS`로 차단하고, 용도가 어긋난 토큰(신규 가입용 토큰인데 `provider_id`가 탈퇴 상태 계정, 바인딩 토큰인데 대상 탈퇴 건이 이미 처리됨)은 `401 INVALID_SIGNUP_TOKEN`으로 거부해 재로그인을 유도한다.
- 중복 가입은 `provider_id`의 UNIQUE 제약으로 차단해야 한다(활성 유저의 `provider_id`로 signup 시 409 ALREADY_REGISTERED). 유예 내 탈퇴 상태의 `provider_id`는 중복이 아니라 복구로 처리한다.
- 카카오가 이메일을 제공하지 않는 유저(이메일 제공 미동의)도 가입을 허용해야 한다. `users.email`은 nullable로 저장하며, signup token의 email claim도 null일 수 있다.
- 기존/복구 유저의 판정과 토큰 발급은 **user 행 잠금 하에** 수행해야 한다(잠금 순서 user → RT). 잠금 없이 조회 후 RT를 발급하면 탈퇴의 RT 전량 폐기와 경합해 탈퇴 후 활성 RT가 남을 수 있고, 복구 경로의 `deleted_at` 변경이 탈퇴를 되덮을 수 있다(HBB1-10에서 확정한 직렬화 계약).

### 실행 조건

- 카카오 개발자 콘솔에 앱이 등록되어 있고 client secret이 서버에 설정되어 있어야 한다.
- 프론트의 카카오 콜백 redirect URI가 카카오 콘솔에 등록되어 있어야 한다.
- Spring API Server가 카카오 인증 서버 및 PostgreSQL에 접근 가능해야 한다.

### 검증 기준

- 기존 유저의 code 전달 시 `isNewUser: false`와 함께 accessToken·refreshToken이 반환되는지 확인
- 신규 유저의 code 전달 시 `isNewUser: true`와 signupToken만 반환되고, `users` 테이블에 계정이 생성되지 않았는지 확인
- 필수 동의 항목이 하나라도 누락된 signup 요청이 `400 MISSING_REQUIRED_CONSENT`로 거부되는지 확인
- 서버 현재 버전과 다른 동의서 `version`으로 제출된 signup 요청이 `409 CONSENT_VERSION_MISMATCH`로 거부되고 계정·동의 기록이 생성되지 않는지 확인
- `consents`에 동일 `type`이 중복되거나 알 수 없는 `type`이 포함된 요청이 `400`으로 거부되고(last-wins 금지) 계정·동의 기록이 생성되지 않는지 확인
- 만료되거나 위변조된 signup token으로 signup 요청 시 `401 INVALID_SIGNUP_TOKEN`이 반환되는지 확인
- 용도가 어긋난 signup token(신규 가입용으로 탈퇴 계정 복구 시도, 이미 사용된 복구용 토큰 재사용)이 `401 INVALID_SIGNUP_TOKEN`으로 거부되는지 확인
- 가입 완료 시 `users`와 동의 기록이 함께 생성되고, 트랜잭션 중단 시 둘 다 롤백되는지 확인
- 동일 `provider_id`로 중복 가입 시도 시 `409 ALREADY_REGISTERED`가 반환되는지 확인
- 만료·재사용된 카카오 code에 대해 `401 KAKAO_AUTH_FAILED`, code 누락·형식 오류에 대해 `400 INVALID_CODE`, 카카오 서버 통신 오류에 대해 `500 KAKAO_SERVER_ERROR`가 반환되는지 확인
- 이메일 제공에 동의하지 않은 카카오 계정도 가입이 완료되고 email이 NULL로 저장되는지 확인
- signup token을 `Authorization` 헤더의 AT로 사용하면 거부되는지(`token_type` 검증) 확인
- 탈퇴 유예 기간 내 유저가 로그인하면 `isNewUser: false`·`isRestored: true`와 signupToken만 반환되고 accessToken·refreshToken은 반환되지 않는지 확인
- 복구 대상의 signup 제출 시 계정이 복구되고 토큰이 반환되는지 확인 (복구 내부 처리의 상세 검증은 HBB1-10 범위)

### 성능 요구사항

- 각 API는 로컬 환경 기준 100ms 이내로 응답해야 한다. 단, `/auth/kakao`의 카카오 API 왕복 2회(외부 통신) 소요 시간은 측정에서 제외한다.

### 인터페이스 요구사항

- 엔드포인트: `POST /api/v1/auth/kakao` (인증 불필요), `POST /api/v1/auth/signup` (signup token으로 신원 확인)
- 외부 API: 카카오 토큰 교환 API, 카카오 사용자 정보 조회 API
- 환경 변수: 카카오 client id / client secret / redirect URI(카카오 토큰 교환 시 code 발급에 쓰인 URI 검증용), signup token 서명 키
- 모든 응답은 공통 envelope `ApiResponse<T>`로 감싼다. 성공 `{ "success": true, "data": {...} }`, 실패 `{ "success": false, "data": null, "error": { "code", "message", "fieldErrors" } }`. HTTP 상태코드는 바디에 넣지 않는다(HTTP 상태줄이 유일 원천).
- 에러는 `ErrorCode` enum(인증 도메인 접두사 `A` + 3자리)으로 정의한다: `INVALID_CODE`(400) · `KAKAO_AUTH_FAILED`(401) · `KAKAO_SERVER_ERROR`(500) (`/auth/kakao`), `MISSING_REQUIRED_CONSENT`(400) · `INVALID_SIGNUP_TOKEN`(401) · `ALREADY_REGISTERED`(409) (`/auth/signup`). `/auth/signup`은 동의 도메인 코드 `CONSENT_VERSION_MISMATCH`(409, U005 — HBB1-12에서 정의)도 함께 사용한다

`POST /api/v1/auth/kakao` 요청:

```json
{ "code": "카카오 인가 코드" }
```

요청은 `code` 하나만 받으며, redirect_uri는 서버 설정값을 사용한다.

`POST /api/v1/auth/kakao` 응답 예시 (기존 유저):

```json
{
  "success": true,
  "data": {
    "isNewUser": false,
    "isRestored": false,
    "accessToken": "eyJhbG...",
    "refreshToken": "eyJhbG..."
  }
}
```

`accessToken`·`refreshToken`은 기존 유저에게만, `signupToken`은 신규·복구 대상 유저에게 내려간다. 복구 대상은 `isRestored: true`로 구분한다.

`POST /api/v1/auth/kakao` 응답 예시 (신규 유저):

```json
{
  "success": true,
  "data": {
    "isNewUser": true,
    "signupToken": "eyJhbG..."
  }
}
```

`POST /api/v1/auth/kakao` 응답 예시 (복구 대상 유저):

```json
{
  "success": true,
  "data": {
    "isNewUser": false,
    "isRestored": true,
    "signupToken": "eyJhbG..."
  }
}
```

`POST /api/v1/auth/signup` 요청 예시:

```json
{
  "signupToken": "eyJhbG...",
  "consents": [
    { "type": "privacy", "agreed": true, "version": 1 },
    { "type": "audio_usage", "agreed": true, "version": 1 },
    { "type": "resume_usage", "agreed": true, "version": 1 },
    { "type": "marketing", "agreed": false }
  ]
}
```

`version`은 사용자가 동의 화면에서 확인한 동의서 버전으로, `agreed: true` 항목에 필수다. 항목·버전의 제공(`GET /api/v1/consents`)과 대조 계약은 수집 동의 스토리(HBB1-12)를 따른다.

`consents` 배열의 형태 규칙: 각 항목은 `type`·`agreed`가 필수이며, 알 수 없는 `type`, **동일 `type`의 중복**, 항목 필드 누락(null)은 `400 INVALID_INPUT_VALUE`(공통 C002)로 거부한다 — 동의 증적 입력에서 중복을 마지막 값으로 조용히 수렴시키는 처리(last-wins)는 금지한다. 검증 순서는 형태(400) → 동의서 버전 대조(409) → 필수 동의(400)다.

### 제약사항

- 소셜 프로바이더는 카카오 단일만 지원한다(비밀번호 로그인 없음). 멀티 프로바이더는 확장 범위.
- signup token 유효기간은 10분이며, 서버에 저장하지 않으므로 개별 무효화는 지원하지 않는다. 단 복구용 토큰은 `deletion_log_id` 바인딩에 의해 해당 탈퇴 건이 처리되는 순간 사실상 무효가 된다(재사용 시 401).
- signup 시 필수 동의 항목은 `privacy`·`audio_usage`·`resume_usage` 3종이다. 동의 항목의 정의·변경, 저장 정책(append-only), 동의서 버전 확인 계약(확인 버전 제출·불일치 409)은 수집 동의 스토리(HBB1-12)를 따른다.
- 카카오 인가 코드는 백엔드가 아닌 프론트 콜백으로만 수신한다.

### 기타 요구사항

- provider_id·email 등 신원 정보는 signup token 서명으로 위변조를 차단하며, 평문으로 프론트에 신뢰를 위임하지 않는다.

---

## 토큰 발급

### 설명

로그인·가입·복구가 완료된 유저에게 JWT 기반 토큰 쌍을 발급해야 한다.

- **Access Token(AT)**: 유효기간 30분. 무상태 검증(서명 기반)하며 서버에 저장하지 않는다. claim은 `sub`(userId)·`iat`·`exp`·`token_type` 최소 구성으로 하고, email·name 등 개인정보는 담지 않는다.
- **Refresh Token(RT)**: 유효기간 14일, JWT 형식. 발급 시 평문이 아닌 SHA-256 해시(`token_hash`)로 `refresh_token` 테이블에 저장해야 한다(유출 대비).
- 유저당 RT 개수는 제한하지 않는다(다중 기기 동시 로그인 허용). 폐기·만료 RT의 누적은 청소 배치가 관리한다.
- 토큰 쌍은 응답 body(JSON)의 `data.accessToken`·`data.refreshToken`으로 반환해야 한다. URL 쿼리파라미터·쿠키로 전달하지 않는다.
- 발급 이후 모든 인증 필요 API는 `Authorization: Bearer {accessToken}` 헤더로 인증하며, 인터셉터가 매 요청 AT 서명을 검증하고 DB에서 `users.deleted_at`을 조회해 탈퇴 유저를 차단(401)해야 한다.
- 로그아웃(`POST /api/v1/auth/logout`)은 멱등 연산으로 항상 200을 반환해야 한다. body의 RT가 AT 유저의 소유일 때만 `revoked_at`을 기록해 폐기하고, 타인 소유·미존재 RT는 무시한다(존재 여부 비노출). 이미 발급된 AT는 만료(최대 30분)까지 유효하며, AT 블랙리스트는 MVP 범위에서 제외한다.

### 실행 조건

- 소셜 로그인 판정(기존) 또는 가입·복구 완료가 선행되어야 한다.
- JWT 서명 키가 서버에 설정되어 있어야 한다.
- `refresh_token` 테이블과 `token_hash` UNIQUE 인덱스(`ux_refresh_token_token_hash`)가 존재해야 한다.

### 검증 기준

- 발급된 AT가 30분, RT가 14일 만료로 설정되는지 확인
- RT가 DB에 평문이 아닌 SHA-256 해시로 저장되는지 확인
- AT payload에 userId 외 email·name 등 개인정보가 포함되지 않는지 확인
- 발급된 AT로 인증 필요 API(예: `GET /api/v1/user`) 호출이 성공하는지 확인
- AT 없이 또는 무효한 AT로 인증 필요 API 호출 시 `401 UNAUTHORIZED`가 반환되는지 확인
- 탈퇴 처리된 유저의 잔여 AT로 요청 시 인터셉터가 `deleted_at` 검증으로 401을 반환하는지 확인
- 로그아웃 후 해당 RT로 재발급 시도가 거부되는지 확인
- 타인 소유 또는 DB에 없는 RT로 로그아웃해도 200이 반환되고, 해당 RT는 폐기되지 않는지 확인

### 성능 요구사항

- 각 API는 로컬 환경 기준 100ms 이내로 응답해야 한다. (인터셉터의 매 요청 DB 조회 1회는 MVP 규모에서 무시 가능한 수준으로 판단)

### 인터페이스 요구사항

- 토큰 반환: `/auth/kakao`(기존), `/auth/signup`(신규·복구), `/auth/reissue`(재발급) 응답의 `data` 필드
- 인증 헤더: `Authorization: Bearer {accessToken}`
- 로그아웃: `POST /api/v1/auth/logout` (Bearer AT 필요, body에 refreshToken 포함)
- 환경 변수: JWT 서명 키, AT/RT 만료 시간 설정

### 제약사항

- RT 저장소는 PostgreSQL로 한정한다(Redis 이관은 트래픽 측정 후 검토).
- AT 즉시 무효화(블랙리스트)는 지원하지 않는다. 탈퇴 유저 차단은 인터셉터의 `deleted_at` 검증으로 대체한다.
- RT 폐기 표현은 `revoked_at`(datetime) 단독으로 하며 boolean 컬럼을 병행하지 않는다.

### 기타 요구사항

- 만료된 RT는 청소 배치가 즉시 삭제하고, 폐기된 RT는 재사용 감지 창 확보를 위해 2일 경과 후 삭제한다.

---

## 토큰 재발급

### 설명

AT 만료 시 프론트가 RT를 실어 `POST /api/v1/auth/reissue`를 호출하면, 서버는 새 AT와 새 RT를 함께 발급해야 한다(Refresh Token Rotation).

- 서버는 전달받은 RT를 `token_hash`로 조회한다. RT가 없거나 만료된 경우 401을 반환해 재로그인을 유도해야 한다.
- **유효한 RT** (`revoked_at IS NULL`): 새 RT를 저장하고 기존 RT에 `revoked_at = now`, `replaced_by = 새 RT`를 기록한 뒤 새 AT·RT를 반환해야 한다.
- **폐기된 RT 재사용, 폐기 후 60초 이내 (Grace Period)**: 응답 유실 후 정상 재시도로 간주한다. 새로 발급하지 않고 `replaced_by`로 원래 발급했던 RT를 찾아 그대로 반환해야 한다("붕 뜬 토큰" 방지).
- **폐기된 RT 재사용, 60초 초과**: 탈취로 간주하고 해당 userId의 모든 RT를 무효화한 뒤 `401 RT_REUSE_DETECTED`를 반환해 전체 재로그인을 강제해야 한다(fail-secure).
- Grace Period는 회전(RTR)으로 폐기되어 `replaced_by`가 기록된 RT에만 적용해야 한다. 로그아웃·탈퇴로 폐기된 RT(`replaced_by IS NULL`)는 경과 시간과 무관하게 401을 반환한다.
- 재발급은 **user 행을 먼저 잠근 뒤 활성 여부를 재확인**해야 하며, 탈퇴 유저의 재발급은 `401 RT_NOT_FOUND`로 거부한다. "탈퇴 시 RT 전량 폐기로 자연 차단"에만 기대면 안 되는 이유: 재발급이 RT 행 잠금을 먼저 잡은 채 탈퇴의 전량 폐기(벌크 UPDATE)가 대기하는 잠금 순서 역전에서, 재발급이 새로 INSERT한 RT는 대기하던 폐기 쿼리의 대상에 포함되지 않아 탈퇴 후에도 활성 RT가 남을 수 있다. 유저 상태를 쓰는 경로는 **잠금 순서(user 행 → RT 행)를 공유**해 이 경합을 차단한다(HBB1-10에서 확정).

### 실행 조건

- 유저에게 발급된 RT가 `refresh_token` 테이블에 존재해야 한다.
- `refresh_token` 테이블에 `revoked_at`·`replaced_by`(대체 RT의 `token_hash` 참조 — FK 제약 없이 애플리케이션이 무결성을 처리하며, 참조 대상이 삭제된 경우 재발급 거부로 fail-safe) 컬럼이 존재해야 한다.

### 검증 기준

- 유효한 RT로 재발급 시 새 AT·RT가 반환되고, 기존 RT에 `revoked_at`과 `replaced_by`가 기록되는지 확인
- DB에 없는 RT로 요청 시 `401 RT_NOT_FOUND`, 만료된 RT로 요청 시 `401 RT_EXPIRED`가 반환되는지 확인
- 폐기 후 60초 이내에 동일 RT로 재요청하면 새 토큰이 아닌 원래 발급했던 RT가 반환되고, DB에 추가 RT가 생성되지 않는지 확인
- 폐기 후 60초 초과에 동일 RT로 재요청하면 `401 RT_REUSE_DETECTED`가 반환되고, 해당 유저의 모든 RT에 `revoked_at`이 기록되는지 확인
- 전체 무효화 이후 해당 유저의 다른 기기 RT로도 재발급이 불가능한지 확인
- 로그아웃·탈퇴로 폐기된 RT(`replaced_by IS NULL`)는 폐기 후 60초 이내라도 재발급이 거부되는지 확인
- 탈퇴된 유저에게 활성 RT가 남아 있어도(경합 잔여 상태) 재발급이 `401 RT_NOT_FOUND`로 거부되는지 확인
- 재발급과 탈퇴가 동시에 실행돼도 탈퇴 완료 후 해당 유저의 활성 RT가 남지 않는지 확인 (동시성 통합 테스트)

### 성능 요구사항

- 각 API는 로컬 환경 기준 100ms 이내로 응답해야 한다.

### 인터페이스 요구사항

- 엔드포인트: `POST /api/v1/auth/reissue` (인증 불필요, RT 자체로 인증)
- 입력: `{ "refreshToken": "eyJhbG..." }`
- 출력: `data.accessToken`(새 AT), `data.refreshToken`(새 RT 또는 Grace Period 시 원래 RT)

에러 응답 예시 (`RT_REUSE_DETECTED`, HTTP 401):

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "A0XX",
    "message": "다른 기기에서 토큰 재사용이 감지되었습니다. 다시 로그인해 주세요."
  }
}
```

### 제약사항

- Grace Period는 60초로 고정한다. 60초를 초과한 폐기 RT 재사용은 예외 없이 탈취로 처리한다.
- Grace Period 내 재시도에는 새 RT를 발급하지 않고 반드시 `replaced_by` 참조로 원래 RT를 반환한다.
- 재사용 감지 완화(전체 무효화 생략)는 지원하지 않는다. RTR의 보안 가치를 유지한다.

### 기타 요구사항

- 탈취범이 먼저 RT를 회전시킨 경우 정상 유저도 재로그인이 필요하나, 전체 무효화로 탈취범 토큰까지 차단되므로 fail-secure 동작으로 허용한다.
- 폐기된 RT는 재사용 감지 창(2일) 동안 보존 후 청소 배치가 삭제한다.
