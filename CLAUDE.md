# CLAUDE.md

## 프로젝트 개요

Kkori — AI 면접 준비 서비스의 백엔드 (SW마에스트로 팀 HBB). Spring Boot 3.5.x / Java 21 / Gradle, 베이스 패키지 `com.aisw.kkori`.

## 명령어

```bash
docker compose up -d         # 로컬 PostgreSQL(5432) + Redis(6379) + MinIO(9000, 콘솔 9001) 기동 (개발 전 1회)
./gradlew bootRun            # 앱 실행 (8080)
./gradlew build              # 컴파일 + 전체 테스트 + 패키징 (CI와 동일 명령)
./gradlew test --tests "com.aisw.kkori.SomeTests"           # 테스트 클래스 단위 실행
./gradlew test --tests "com.aisw.kkori.SomeTests.method"    # 테스트 메서드 단위 실행
```

- 테스트는 Testcontainers가 Postgres/Redis 컨테이너를 자동 기동하므로 Docker만 실행 중이면 됨 (`TestcontainersConfiguration` 참조, `@SpringBootTest`에 `@Import` 필요)

## 작업 규칙

- 코드 변경 후 반드시 `./gradlew build`로 컴파일 + 테스트 통과를 확인할 것 (CI와 동일 명령)
- 커밋 메시지 타입은 `feat`, `fix`, `chore`, `docs`, `refactor`, `test`를 사용 (예: `feat: 이력서 업로드 API 추가`)

## 기술적 결정사항

- **Spring Boot 3.5.x** (4.x 아님) — 스타터 이름이 3.x 체계 (`spring-boot-starter-web` 등)
- **환경 설정은 프로파일 분리 + 비밀값만 placeholder** — `application.yaml`(공통) + `application-{local,dev,prod}.yaml`. 기본 프로파일은 local(`spring.profiles.default`)이라 `bootRun`이 바로 동작. local 파일엔 로컬 컨테이너용 더미 값을 하드코딩(비밀 아님), dev/prod 파일은 비밀값을 기본값 없는 `${DB_URL}` 형태로 주입받음(미주입 시 기동 실패로 즉시 발견). dev/prod 파일도 커밋해 리뷰 대상으로 유지. `spring-boot-docker-compose` 자동 주입은 설정 경로가 갈라지는 문제로 계속 사용하지 않음
- **S3는 Spring Cloud AWS(starter-s3)** — 로컬은 docker compose의 MinIO(endpoint `localhost:9000`, path-style), dev/prod는 endpoint·credentials를 설정하지 않아 SDK 기본 동작(실제 S3 + IAM Role). 코드 경로는 전 환경 동일
- **SecurityConfig는 개발 초기 임시 permitAll** — 인증 도메인 개발 시 실제 인가 규칙으로 교체 예정. OAuth2 클라이언트가 클래스패스에 있어 Spring Security 기본 유저(generated password)는 생성되지 않음
- **공통 응답은 고정 엔벨로프** — 모든 API는 `ApiResponse<T>`로 감싼다. 성공 `{ success: true, data: ... }`(무내용이면 `data: null`), 실패 `{ success: false, data: null, error: { code, message, fieldErrors } }`. **HTTP 상태코드는 바디에 넣지 않음**(중복은 안티패턴 — HTTP 상태줄이 유일 원천, 세밀한 구분은 비즈니스 `code`가 담당). 에러는 `ErrorCode` enum(도메인 접두사 + 3자리, 예: 공통 `C001`) + `BusinessException`으로 던지면 `GlobalExceptionHandler`가 변환

## 패키지 구조

- **도메인 기준(package-by-feature)** — `com.aisw.kkori` 아래 `global/`(공통)과 도메인 패키지(예: `resume`, `interview`, `user`)를 두고, 각 도메인 안에 계층별 하위 패키지를 둔다. 계층형(controller/service가 최상위)이 아님
- 각 도메인의 하위 패키지: `controller`(@RestController) / `service` / `repository`(JPA) / `api`(**Swagger 문서화 인터페이스** — `@Tag`/`@Operation` 담긴 interface, controller가 `implements`) / `dto`(요청·응답) / `domain`(@Entity, `global.entity.BaseEntity` 상속)
- 문서화 애너테이션은 컨트롤러에 직접 달지 말고 `api` 인터페이스로 분리해 컨트롤러를 얇게 유지
- 공통 계층 `global/`: `response`(ApiResponse/ErrorResponse), `exception`(ErrorCode/BusinessException/GlobalExceptionHandler), `entity`(BaseEntity), `config`(JpaConfig/SecurityConfig/SwaggerConfig)

## 브랜치 / PR 규칙

- **기본 브랜치는 `develop`** (통합 지점), `main`은 배포 전용
- 작업은 `feature/HBB1-<지라번호>-<영문 요약>` 브랜치 → develop PR
- 브랜치 접두사는 축약형이 아닌 전체 단어 사용 (`feat/` ❌ → `feature/` ✅)
- **PR은 항상 draft로 생성**, 준비되면 ready 전환
- PR 제목은 `<타입>: [HBB1-<지라번호>] <요약>` 형식 (예: `feat: [HBB1-14] 이력서 PDF 업로드 API 구현`) — 지라 키가 제목에 있으면 GitHub for Atlassian이 티켓에 자동 연결
- PR 본문은 템플릿(관련 이슈 / PRD 경로 / 완료 조건) 준수 — 완료 조건은 PRD에서 발췌한 검증 가능한 문장으로 작성하고, 체크는 검증된 후에만
- CodeRabbit이 develop 대상 PR을 자동 리뷰 (draft는 제외 — ready 전환 시점에 리뷰 시작, 이후 커밋은 증분 리뷰). 재리뷰가 필요하면 `@coderabbitai review` 코멘트
- CI(GitHub Actions)는 main/develop 대상 push·PR에서 `./gradlew build` 실행

## 문서 참조 맵

필요한 정보에 따라 아래 문서를 참조할 것:

| 필요한 정보 | 참조 문서 |
|---|---|
| 도메인 기능 요구사항, 정책, 검증 기준 | `docs/requirements/<도메인>/` 디렉토리 (도메인당 디렉토리, 문서 여러 개 가능, SRS 템플릿 형식) |
| 전체 아키텍처, 기술 스택 구성 | `docs/architecture.md` |
| DB 스키마, 엔티티 관계 | `docs/erd.md` (Mermaid) |

- `docs/drafts/` — 확정 전 개인 초안 (gitignore, 커밋 금지, 참조 대상 아님)
- 이슈/PR에서 PRD 참조 시 섹션 번호까지 명시 (예: `docs/requirements/resume/resume.md §2.1`)
- PRD는 CodeRabbit도 리뷰 컨텍스트로 참조하므로 요구사항 변경 시 반드시 문서를 먼저 갱신할 것
