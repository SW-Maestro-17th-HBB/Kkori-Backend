# 음성 세션 연결 토대

> **User Story**: HBB1-256 — 나는 팀에서 사용할 음성 기능의 토대를 마련하기 위해 LiveKit 룸에서 오디오가 오가는 연결을 구축할 수 있다.

## Overview

본 기능은 클라이언트가 LiveKit 룸에 접속해 오디오를 주고받을 수 있도록, 서버가 룸 입장용 접속 토큰(AccessToken)을 발급하는 흐름을 정의한다. 실제 오디오 스트림은 클라이언트와 LiveKit 서버(SFU) 사이에서 WebRTC로 오가며, Spring API Server는 미디어를 직접 다루지 않고 **입장 자격을 서명한 토큰을 발급하는 컨트롤 플레인** 역할만 담당한다.

LiveKit 서버는 전 환경 **LiveKit Cloud**를 사용한다. 서버는 LiveKit API Key/Secret으로 JWT(AccessToken)를 서명해 인증된 유저에게 발급하며, **API Secret은 어떤 경우에도 클라이언트로 노출하지 않는다**(서명은 서버에서만 수행하고 클라이언트는 서명된 JWT만 수신한다). 룸 이름(roomName)은 클라이언트가 지정하지 못하며 **서버가 요청마다 새로 발급**한다(실제 LiveKit 룸의 생성은 서버가 아니라 첫 참가자 접속 시 LiveKit이 자동 수행한다).

### 이 스토리의 위치 — 면접 세션의 최소 토대

본 스토리는 팀의 음성 기능(AI 면접)이 올라설 **연결 토대**다. 최종적으로 이 엔드포인트는 AI 면접 세션(`interview_session` 상태 머신, webhook 기반 상태 전이, Agent 디스패치, transcript 등 — 별도 면접 도메인 설계에서 정의)으로 확장된다. 그 진화 경로에 정합하도록, 본 스토리는 **세션의 최소 버전**으로 설계한다:

- 엔드포인트를 `POST /api/v1/sessions`로 두어 면접 세션이 이 경로에 상태·라이프사이클을 얹을 수 있게 한다.
- 룸을 요청마다 새로 생성해(세션마다 새 룸이라는 면접 모델과 일치), 유저 단위 공유 룸 같은 나중에 뒤집힐 잠정 규칙을 피한다.

단, 본 스토리에서는 **세션 상태를 저장하지 않는다** — 세션 레코드(테이블)·상태 머신(`PENDING`/`ACTIVE`/…)·webhook 수신·Agent 디스패치·transcript는 모두 **범위 밖**이며 면접 도메인 스토리에서 도입한다. 즉 본 스토리의 "세션"은 아직 "룸에 입장할 토큰을 발급받는 한 번의 접속 단위"라는 얇은 의미이고, 상태를 갖지 않는다. 참가자 신원(identity)은 현재 유저 식별자(`userId`)를 쓰되, 면접 세션이 도입되면 세션 파생 신원(예: `candidate-{sessionId}`)으로 대체될 수 있다.

본 스토리의 범위는 "오디오가 오가는 연결"의 확립까지다 — 토큰 발급과, 그 토큰으로 룸에 접속해 오디오가 양방향으로 오가는지의 검증까지를 포함한다. 음성-텍스트 변환(STT/TTS)·AI 파이프라인은 SFU가 담당하는 일이 아니며(SFU는 오디오 중계만) 본 스토리 범위 밖이다.

### 기능 요구사항

| No. | Function | Description |
| --- | --- | --- |
| 1 | 음성 세션 접속 토큰 발급 | 인증된 유저에게, 서버가 새로 발급한 roomName의 룸에 입장할 수 있는 LiveKit AccessToken(JWT)과 LiveKit 서버 URL을 발급해야 한다. |

---

## 음성 세션 접속 토큰 발급

### 설명

인증된 유저가 `POST /api/v1/sessions`를 호출하면, 서버는 새 roomName과 그 룸 입장용 LiveKit AccessToken(JWT), 그리고 클라이언트가 접속할 LiveKit 서버 URL을 반환해야 한다. 클라이언트는 이 토큰과 URL로 LiveKit 클라이언트 SDK를 통해 룸에 접속하고 오디오를 송수신한다.

- **룸 이름(roomName)**: 클라이언트가 지정하지 않으며 **서버가 요청마다 새 roomName을 생성**해야 한다(예: `room-{UUID}`). 매 요청이 새 룸을 가리키므로, 세션마다 독립 룸이 필요한 면접 모델과 정합한다. **본 API는 RoomService로 룸을 미리 프로비저닝하지 않는다** — 서버가 만드는 것은 roomName과 입장 토큰뿐이고, 실제 LiveKit 룸은 첫 참가자가 그 토큰으로 접속하는 시점에 LiveKit이 자동 생성한다. 룸 이름 생성 규칙의 상세(세션 레코드 연동, 다인 룸, 초대 등)는 면접 도메인 스토리에서 확장한다.
- **참가자 신원(identity)**: 인증된 유저의 `userId`를 토큰의 `identity`로 설정해야 한다. 아무나 임의 신원으로 입장하지 못하도록, identity는 클라이언트 입력이 아닌 서버가 인증 컨텍스트(`@AuthenticationPrincipal`)에서 확정한 값을 사용한다. LiveKit은 룸 내 identity의 유일성을 요구하므로(동일 identity 재입장 시 기존 참가자 연결이 끊김), 면접 세션 도입 시 세션 파생 신원(예: `candidate-{sessionId}`)으로 대체될 수 있다.
- **권한(grant) — 발행을 오디오로 제한**: 토큰에는 대상 룸 입장 권한(`RoomJoin=true`, `RoomName`)과 함께 다음 미디어 권한을 **명시적으로** 부여해야 한다. 기본 publish 권한에 기대면 카메라·화면공유·데이터 채널까지 열릴 수 있으므로 명시가 필수다. **발행(publish)은 마이크로 제한하고, 구독(subscribe)은 소스별 제한이 불가하므로 전체 허용한다** — LiveKit `canSubscribe`는 boolean이라 특정 소스만 구독 허용하는 개념이 없다.
  - `canPublish=true` (트랙 발행 허용)
  - `canSubscribe=true` (트랙 구독 허용 — 소스 제한 없음)
  - `canPublishSources=["microphone"]` (발행 소스를 마이크로 제한 — 카메라·화면공유 발행 차단)
  - `canPublishData=false` (데이터 채널 발행 차단)
- **만료(TTL)**: 발급된 AccessToken에는 만료 시간을 설정해야 한다(기본 1시간, 설정 프로퍼티 `livekit.token-ttl`). 만료된 토큰으로는 룸 최초 접속이 거부된다. 토큰은 서버에 저장하지 않는다(무상태, 서명 기반 검증은 LiveKit 서버가 수행). TTL 값은 면접 도메인에서 재연결 정책(짧은 입장 윈도우)에 맞춰 조정될 수 있다.
- **응답**: 토큰과 서버 URL, roomName을 응답 body(JSON)의 `data.livekitToken`·`data.livekitUrl`·`data.livekitRoom`으로 반환한다(성공 상태 `201 Created`). 필드명은 후속 면접 세션 엔드포인트(`interview.md` §5.3)와 동일하게 맞춰, 이 엔드포인트가 면접 세션으로 확장될 때 응답 계약이 그대로 이어지게 한다.
- **시크릿 보호**: LiveKit API Secret은 서버 설정으로만 보관하며, 응답·로그·클라이언트 어디에도 노출하지 않아야 한다.

### 실행 조건

- LiveKit Cloud 프로젝트가 생성되어 있고, 해당 프로젝트의 API Key·API Secret·서버 URL(wss)이 서버에 설정되어 있어야 한다.
- 유저가 인증되어 있어야 한다(`Authorization: Bearer {accessToken}`). 토큰 발급 엔드포인트는 인증 필요 경로다.
- 클라이언트가 LiveKit 서버 URL에 WebRTC로 접속 가능한 네트워크 환경이어야 한다.

### 검증 기준

- 인증된 유저의 요청에 대해 `201 Created`와 함께 `data.livekitToken`(서명된 JWT)·`data.livekitUrl`·`data.livekitRoom`이 반환되는지 확인
- 연속된 두 요청이 서로 다른 룸 이름(`livekitRoom`)을 반환하는지(요청마다 새 룸) 확인
- 반환된 토큰의 `identity`가 요청 유저의 `userId`와 일치하는지 확인 (다른 유저의 신원이 아님)
- 반환된 토큰이 서버가 발급한 roomName에 대한 입장 권한을 담고 있는지, 그리고 실제로 그 토큰으로 LiveKit 룸에 접속되는지 확인
- 발급된 토큰이 마이크 발행만 허용하는지 확인 — `canPublishSources`가 마이크로 제한되고 카메라·화면공유·데이터 채널 publish가 허용되지 않는지(토큰 grant 검사 또는 실제 접속 후 비디오/데이터 publish 시도가 거부되는지). 구독(subscribe)은 소스 제한이 없으므로 전체 허용됨을 전제로 한다.
- **양방향 오디오 송수신(수동 검증)**: 프로덕션 API(`POST /api/v1/sessions`)로 발급한 토큰(identity=`userId`)으로 한 클라이언트가 반환된 `livekitRoom`에 접속하고, **같은 roomName에 대해 별도 identity(예: `audio-test-peer`)로 발급한 검증용 토큰**으로 두 번째 클라이언트를 접속시켜, 한쪽의 오디오가 다른 쪽에 도달하는지 수동으로 확인한다. 검증용 peer 토큰은 **프로덕션 API로 발급되지 않으며**, LiveKit CLI(`lk token create`) 또는 별도 테스트 스크립트로 발급한다. (같은 API를 두 번 호출하면 서로 다른 룸이 나오고, 같은 토큰을 두 클라이언트가 공유하면 identity 중복으로 먼저 접속한 참가자가 `DUPLICATE_IDENTITY`로 끊기므로, 두 번째 참가자는 반드시 다른 identity의 토큰이어야 한다.)
- 인증 없이(또는 무효한 AT로) 토큰 발급을 요청하면 `401 UNAUTHORIZED`가 반환되는지 확인
- 응답 body·서버 로그 어디에도 LiveKit API Secret이 노출되지 않는지 확인
- 만료(TTL) 검증 — 두 갈래로 나눈다. 애플리케이션 `Clock`을 이동해도 Cloud 서버 시각은 바뀌지 않으므로 고정 Clock만으로는 Cloud의 실제 만료를 재현할 수 없다:
  - **단위 테스트**: 발급된 JWT를 디코딩해 `exp` claim이 설정 TTL(`livekit.token-ttl`)과 일치하는지 확인 (외부 통신 없이 서명 페이로드만 검사). 단 LiveKit JVM SDK 0.14.0의 `AccessToken#toJwt()`는 **`iat` claim을 넣지 않고 `exp`만** `System.currentTimeMillis() + ttl`로 계산하므로, `exp - iat` 방식은 쓸 수 없다. 대신 **발급 직전·직후 시각을 기록해 `exp`가 `[발급직전 + TTL, 발급직후 + TTL]` 범위 안에 드는지**를, JWT `exp`가 초 단위 정밀도인 점을 고려한 허용 오차와 함께 검사한다(또는 `exp - 현재시각 ≈ TTL`을 허용 오차 내에서 확인).
  - **Cloud 연동 테스트**: TTL을 수 초로 설정해 발급하고, 실제로 그 시간이 경과한 뒤 해당 토큰의 최초 룸 접속이 LiveKit Cloud에서 거부되는지 확인
- LiveKit 설정값(URL/Key/Secret)이 누락된 채 애플리케이션이 기동되면 부팅 시점에 실패(fail-fast)하는지 확인

### 성능 요구사항

- 토큰 발급 API는 로컬 환경 기준 100ms 이내로 응답해야 한다(토큰 서명은 로컬 연산으로 외부 통신이 없다).

### 인터페이스 요구사항

- 엔드포인트: `POST /api/v1/sessions` (인증 필요 — `Authorization: Bearer {accessToken}`), 성공 시 `201 Created`
- 접근 정책: **인증된 유저면 허용**한다. 면접 초안(`interview.md` §5.1)의 "ACTIVE 유저만 / PENDING 403" 같은 상태 기반 인가는 유저 상태 개념을 전제하는데, 본 스토리에는 그 상태가 없고 인증 필터가 이미 탈퇴 유저를 차단한다. 세밀한 상태 기반 인가는 면접 도메인이 상태를 도입하는 시점에 이 경로에 얹는다.
- 외부 시스템: LiveKit Cloud (룸 접속·오디오 중계). 토큰 발급 자체는 서버 로컬 서명이라 발급 시점에 LiveKit으로의 왕복은 없다.
- 설정 계약: `livekit.*` 프로퍼티로 주입하되, **local·dev·prod 모두 URL/Key/Secret은 환경변수로 받고 저장소에 실제 값을 커밋하지 않는다.** LiveKit Cloud 자격증명은 개발용이어도 Secret이며, `application-local.yaml`은 Git 추적 대상이라 여기에 실제 Key/Secret을 두면 커밋된다. local 개발자는 `.env`(Git 비추적) 또는 IDE 실행 환경변수로 주입한다. dev/prod는 기본값 없는 placeholder라 미주입 시 부팅 실패로 즉시 발견된다(fail-fast). TTL만 기본값을 둔다.

  ```yaml
  livekit:
    url: ${LIVEKIT_URL}
    api-key: ${LIVEKIT_API_KEY}
    api-secret: ${LIVEKIT_API_SECRET}
    token-ttl: ${LIVEKIT_TOKEN_TTL:1h}
  ```

  > 주의: 현재 저장소 `.gitignore`에 `.env` 항목이 없다. `.env`를 도입한다면 `.gitignore`에 추가하는 것을 이 스토리 또는 선행 작업으로 포함해야 실수 커밋을 막을 수 있다.

  TTL을 프로퍼티로 분리해야 "향후 조정"과 "만료 토큰 테스트"가 현실적으로 가능하다(검증 방법은 검증 기준의 TTL 항목 참조).
- **테스트 설정 계약 (CI 부팅 실패 방지 — 필수)**: `livekit.*`를 부팅 시 필수값으로 등록하면, 이 저장소의 LiveKit과 무관한 기존 `@SpringBootTest`들(auth·resume·user·pgvector 등)이 `LIVEKIT_*` 미주입 상태에서 Spring Context 로딩에 실패해 `./gradlew build`(CI 동일 명령) 전체가 깨진다. 현재 저장소엔 test 전용 프로파일이 없고 이 테스트들은 기본 프로파일로 부팅한다. 따라서:
  - 일반 단위·통합 테스트에는 Gradle `test` 태스크의 환경변수 또는 test 전용 프로파일(예: `application-test.yaml`)로 **안전한 더미 URL/Key/Secret**을 주입해 Context가 정상 부팅되게 한다.
  - 이 더미 값은 **토큰 구조·서명·TTL 검증에만** 쓰고, 실제 LiveKit Cloud 접속은 시도하지 않는다(더미 값으로는 접속 불가한 게 정상).
  - 실제 Cloud 연결·오디오·만료 확인 테스트는 **별도 태그**(예: JUnit `@Tag("livekit-cloud")`)로 분리해 기본 `./gradlew build`에서는 제외하고, 실행 시에만 실제 `LIVEKIT_*`를 주입한다.
- 모든 응답은 공통 envelope `ApiResponse<T>`로 감싼다. 성공 `{ "success": true, "data": {...} }`, 실패 `{ "success": false, "data": null, "error": { "code", "message", "fieldErrors" } }`. HTTP 상태코드는 바디에 넣지 않는다(HTTP 상태줄이 유일 원천).
- 에러는 `ErrorCode` enum(세션 도메인 접두사 `S` + 3자리)으로 정의한다: 설정 누락·서명 실패 등 서버 측 오류는 `SESSION_TOKEN_ISSUE_FAILED`(500, `S001`). 인증 실패는 공통 `UNAUTHORIZED`(401, C005)를 사용한다.

`POST /api/v1/sessions` 요청:

```
(body 없음 — 룸 이름·신원은 서버가 결정. 면접 도메인 도입 시 resumeId·interviewType 등이 추가된다)
```

`POST /api/v1/sessions` 응답 예시 (`201 Created`):

```json
{
  "success": true,
  "data": {
    "livekitToken": "eyJhbG...",
    "livekitUrl": "wss://<project>.livekit.cloud",
    "livekitRoom": "room-3f2a9c1e"
  }
}
```

### 제약사항

- LiveKit 서버는 본 스토리에서 전 환경 **LiveKit Cloud**를 사용한다. 자체 호스팅(self-host SFU)은 데이터 거버넌스·비용 등을 이유로 배포·운영 단계에서 전환할 수 있으며, 그 결정은 면접 도메인 및 배포 스토리에서 확정한다. **토큰 발급 코드 경로는 Cloud든 self-host든 동일하며 URL/Key/Secret 설정값만 달라지므로**, 본 스토리를 Cloud로 시작해도 후속 전환 비용은 설정 교체로 국한된다.
- 본 스토리는 **오디오만** 대상으로 한다. 비디오·화면공유는 범위 밖이다.
- 룸 이름은 서버가 생성하며 클라이언트 입력을 받지 않는다.
- **세션 상태를 저장하지 않는다.** 세션 레코드·상태 머신·룸 생성/조회/삭제(RoomServiceClient)·참가자 입퇴장 webhook 수신·Agent 디스패치·transcript는 모두 면접 도메인 스토리로 미룬다. 본 스토리의 `POST /api/v1/sessions`는 상태 없는 토큰 발급만 수행한다.
- STT/TTS·AI 에이전트(LiveKit Agents) 등 음성 파이프라인은 본 스토리 범위 밖이다. SFU는 오디오 중계만 담당한다.

### 기타 요구사항

- LiveKit SDK는 `io.livekit:livekit-server:0.14.0`(Maven Central, JVM 라이브러리)를 버전 고정해 사용하며 Java에서 그대로 호출한다.
- SDK의 `AccessToken#setTtl(long millis)`는 **밀리초 단위**다. 설정의 `livekit.token-ttl`(`Duration`)을 밀리초로 변환해 전달해야 한다(단위 혼동 시 만료가 1000배 어긋남).
- LiveKit SDK 밀착 코드(토큰 서명, 설정 프로퍼티)는 벤더 어댑터로 `global.livekit`에 격리하고, 도메인 서비스는 "입장권 발급" 추상에만 의존한다 — 벤더 교체 시 도메인 코드를 건드리지 않도록 한다(카카오 연동이 `global.oauth`에 격리된 것과 동일한 구조).
- 도메인 패키지는 면접 세션의 전신으로서 `session`을 사용한다(경로 `/api/v1/sessions`와 일관). 면접 도메인 확장 시 이 패키지가 세션 상태·라이프사이클을 흡수한다.
- API Secret 등 민감 설정은 로그 마스킹 대상에 포함되어야 한다.
```
