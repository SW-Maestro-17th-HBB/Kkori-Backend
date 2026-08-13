# 면접 녹음 및 음성 분석 요청 발행

> **User Story**: HBB1-20 — 나는 사용자로서 실시간 음성으로 면접관과 대화할 수 있다
>
> **하위 이슈**: HBB1-318 [개발] 세션 종료 후 녹음 저장

## Overview

본 기능은 면접 세션의 오디오를 **LiveKit Egress(RoomComposite, audio-only)** 로 녹음해 S3에 저장하고, 업로드 완료 시점에 리포트 워커의 음성 분석(2단계) 입력 이벤트를 발행하는 흐름을 정의한다. `interview-session-creation.md`가 확립한 생성 파이프라인의 커밋 후 단계를 확장하며, 웹훅 배관은 `interview-session-completion.md`의 기존 엔드포인트(`/api/v1/webhook/livekit`)를 계승한다.

발행 주체 분담은 워커 계약(2026-07-30 합의)을 따른다 — **대본 기반 리포트 생성 요청(`report.generation.requested`)은 에이전트가**, **음성 분석 요청(`report.audio.analysis.requested`)은 세션 도메인(본 스토리)이** 발행한다. 각자 자기가 1차로 아는 사실만 발행한다: 녹음 파일의 존재를 아는 것은 `egress_ended` 웹훅을 받는 세션 도메인뿐이다(에이전트는 업로드 완료 시점에 이미 종료되어 있다).

설계 검증: 2026-08-11 self-hosted SFU(EC2) 환경에서 전체 흐름(세션 생성 → 실면접 → 듀얼 채널 녹음 → S3 업로드 → 채널 분리 확인)을 E2E로 실측 완료. 재현 구성은 `Kkori-Livekit` 리포 참조.

### 기능 요구사항

| No. | Function | Description |
| --- | --- | --- |
| 1 | 세션 생성 시 녹음 시작 | 세션 생성 커밋 후(디스패치 성공 다음) `StartRoomCompositeEgress`를 호출해야 한다. `audio_only=true`, `audio_mixing=DUAL_CHANNEL_AGENT`, OGG, S3 출력. 응답의 `egressId`를 세션 행에 저장한다. **시작 실패는 세션을 실패시키지 않는다**(warn 로그 후 진행) — 녹음은 부가 기능이며, 음성 분석 누락은 워커 계약의 유예 완성 경로가 흡수한다. |
| 2 | egress_ended 웹훅 처리 | 기존 웹훅 엔드포인트에서 `egress_ended` 이벤트를 처리해야 한다. `egressInfo.status == EGRESS_COMPLETE`인 경우에만: `egressId`로 세션을 찾고, objectKey는 `fileResults[0].filename`, bucket은 EgressInfo에 echo되는 원요청(`roomComposite.fileOutputs[0].s3.bucket`)에서 추출해 세션 행에 기록한다(2026-08-11 확정 — `FileInfo`에는 bucket 필드가 없어 `location` URL 파싱 대신 요청 echo를 원천으로 쓴다). `EGRESS_FAILED`/`EGRESS_ABORTED`는 warn 로그만 남긴다. |
| 3 | 음성 분석 요청 발행 | 기능 2에서 기록 성공 시 `report.audio.analysis.requested` 스트림에 발행해야 한다. **멱등**: 세션에 objectKey가 이미 기록되어 있으면 기록·발행 모두 생략한다(웹훅 at-least-once 재전송 대비). |

### 발행 계약 (Kkori-AI worker 공유 — 임의 변경 금지)

`worker/src/contract/report.py`의 `AudioAnalysisRequested`와 크로스 레포 계약이다. 변경은 양 레포 합의·동시 반영으로만 한다.

- **스트림 키**: `report.audio.analysis.requested`
- **필드**: 전부 문자열로 인코딩한다 (Redis Stream 필드 특성, `ResumeParseRequestedMessage.toMap()`과 동일 방식).

| 필드 | 타입 | 값 |
| --- | --- | --- |
| `sessionId` | string | 세션 id(Long)의 문자열화 |
| `bucket` | string | 녹음 파일 S3 버킷명 |
| `objectKey` | string | 버킷 내 객체 키 (예: `recordings/room-<uuid>-<time>.ogg`) |

- 소비자 미구현 상태에서도 발행은 유효하다 — Stream은 소비자가 없어도 메시지를 보존한다(`ResumeAnalysisRequestPublisher` 주석과 동일 근거).
- **순서는 발행 → 기록으로 확정한다(2026-08-11)**. 발행 실패는 warn 로그 후 기록을 생략하고 웹훅 응답은 200을 유지한다 — 멱등 가드(objectKey)가 남지 않으므로 webhook 재전송이 오면 재발행 기회가 있고, 최종 누락은 워커 계약의 유예 완성 경로가 흡수한다. 기록 실패는 500으로 전파해 LiveKit 재전송을 유도한다 — 이때 발행이 중복될 수 있으나 at-least-once 소비 계약(워커의 sessionId 기준 멱등 처리)이 흡수한다. 이 순서는 테스트로 고정한다.

### Egress 요청 사양

2026-08-11 실측 검증된 요청 형태(JSON 표기, server-sdk-kotlin `EgressServiceClient`로 동일 구성):

```json
{
  "room_name": "<세션의 livekit_room>",
  "audio_only": true,
  "audio_mixing": "DUAL_CHANNEL_AGENT",
  "file_outputs": [{
    "filepath": "recordings/{room_name}-{time}.ogg",
    "s3": { "region": "<리전>", "bucket": "<버킷>" }
  }]
}
```

- **DUAL_CHANNEL_AGENT 채택 근거**: 리포트 음성 분석은 지원자 음성만 대상으로 한다(리포트 도메인 합의). 이 모드는 에이전트와 나머지 참가자를 좌/우 채널로 분리해 **세션당 파일 1개**를 유지한다 — 재연결(재입장)에도 파일이 쪼개지지 않는다. 실측: 왼쪽=에이전트, 오른쪽=지원자 (워커 추출 예: `ffmpeg -i in.ogg -af "pan=mono|c0=c1" candidate.wav`). 대안이던 ParticipantEgress는 지원자 이탈 시 egress가 종료되어 재연결마다 파일이 분열되고 본 계약(objectKey 1개)과 충돌해 배제했다.
- **objectKey는 예측하지 않는다** — `filepath`는 템플릿일 뿐이며, 실제 키는 웹훅 `fileResults`에서 읽는다(파일명 규칙과의 결합 제거).
- S3 자격증명: 요청에 넣지 않는다 — egress 인스턴스의 IAM Role(기본 자격증명 체인)을 사용한다(실측 검증됨).
- egress는 룸 라이프사이클에 묶인다: 세션 종료(룸 삭제) 시 자동 종료 후 업로드 완료 → `egress_ended` 웹훅. 별도 stop 호출이 불필요하다(실측 검증됨).

### 웹훅 배관

- 기존 `/api/v1/webhook/livekit`(서명 검증 포함)을 재사용한다. `egress_ended`는 **세션 상태 전이가 아니다** — `SessionWebhookSignal` enum을 확장하지 말고 어댑터에서 별도 핸들러로 분기한다(상태 머신 오염 방지).
- 이벤트 순서: `room_finished` 이후 수 초~수십 초 뒤 `egress_ended`가 도착한다(업로드 시간). 세션이 이미 terminal 상태여도 기능 2·3은 정상 동작해야 한다.
- self-hosted 환경의 웹훅 발송처는 SFU의 `livekit.yaml > webhook.urls`다(Cloud는 대시보드 설정). 로컬 개발 환경에서는 EC2 → localhost 웹훅이 도달하지 않음(NAT)을 전제한다.

### 데이터 모델

`interview_session`에 컬럼 추가 (모두 nullable — 녹음은 부가 기능):

| 컬럼 | 타입 | 용도 |
| --- | --- | --- |
| `egress_id` | varchar | 시작 응답의 egressId. 웹훅 → 세션 역매핑 키 |
| `recording_bucket` | varchar | 업로드 완료된 버킷명 |
| `recording_object_key` | varchar | 업로드 완료된 객체 키. **non-null이면 처리 완료(멱등 가드)** |

### 기타 요구사항 / 관찰 기록

- 발행부는 `ResumeAnalysisRequestPublisher`와 동일 패턴의 신설 `AudioAnalysisRequestPublisher`로 구현한다(`StringRedisTemplate.opsForStream()`).
- 실측 관찰(2026-08-11): 지원자가 마이크 트랙을 한 번도 발행하지 않은 룸(에이전트 단독 발화)은 녹음이 무음이 되는 엣지가 있다. 실서비스 흐름(지원자 마이크 필수)에서는 미발현이나, 스테이징에서 "녹음에 면접관 첫 인사가 온전히 담기는지" 1회 확인을 권장한다.

### 완료 조건 (검증 기준)

1. 세션 생성 시 `StartRoomCompositeEgress`가 위 사양대로 호출되고 `egress_id`가 세션 행에 저장된다.
2. egress 시작 실패 시에도 세션 생성 응답은 성공이며 warn 로그가 남는다.
3. `EGRESS_COMPLETE`인 `egress_ended` 웹훅 수신 시 `recording_bucket`/`recording_object_key`가 기록되고 `report.audio.analysis.requested`에 계약 필드가 발행된다.
4. 동일 `egress_ended` 웹훅을 2회 수신해도 발행은 1회다.
5. `EGRESS_FAILED` 웹훅은 기록·발행 없이 warn 로그만 남긴다.
6. 발행 메시지는 워커 계약 픽스처(`AudioAnalysisRequested.decode`)로 파싱 가능하다.
