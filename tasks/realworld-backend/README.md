# 공통 과제: RealWorld App 백엔드 (언어별 구현)

코딩 에이전트가 빈 저장소에서 같은 백엔드 API를 언어별로 구현하는 기본 과제다. 과제 정의와 채점 정본은 자매 리포 [coding-agent-benchmark의 같은 과제](https://github.com/roboco-io/coding-agent-benchmark/tree/main/tasks/realworld-backend)와 같고, 기술 스택만 언어별로 다르다.

## 과제 정의

[RealWorld](https://github.com/gothinkster/realworld) 스펙("Medium 클론")의 백엔드 API를 구현한다.

- **스펙**: [RealWorld API 스펙](https://realworld-docs.netlify.app/specifications/backend/endpoints/) — 인증(JWT), 유저/프로필, 아티클 CRUD, 코멘트, 즐겨찾기, 태그, 피드
- **시작 상태**: 빈 저장소(스캐폴딩 없음)

## 언어별 기술 스택 (2026-10-17 사용자 확정)

선정 기준은 자매 리포의 TypeScript 스택 선정 기준과 같다. 해당 언어에서 가장 널리 쓰이고 LLM 학습 데이터가 풍부한 조합을 고르고, DB는 모든 언어에서 SQLite로 통일한다. SQLite는 DB 서버 없이 파일로 바로 실행되므로 환경 설정이라는 교란 변수를 줄인다. SQLite 결과가 PostgreSQL 등 운영 DB의 특성을 대표하지는 않는다.

| 언어 | 웹 프레임워크 | DB 접근 | 비고 |
|---|---|---|---|
| TypeScript | Fastify | Prisma | 자매 리포 과제와 동일 |
| Python | FastAPI | SQLAlchemy | uvicorn |
| Go | Gin | GORM | 순수 Go SQLite 드라이버(modernc) |
| Rust | Axum | SQLx | tokio |
| C++ | Drogon | Drogon ORM | CMake, C++20 |

위 표는 EXP-001의 다섯 언어다. 언어별 스택의 정본은 레지스트리(`.claude/skills/langbench/languages/<key>.json`의 `stack`·`prompt`)이며, 새 언어는 [langbench-add-language](../../.claude/skills/langbench-add-language/SKILL.md) 스킬로 추가한다.

## 기동 규약

자매 리포 하네스는 `npm run dev`로 서버를 띄웠으므로 TypeScript 전용이었다. 이 리포는 언어와 무관하게 다음 두 진입점을 요구한다.

1. **`start.sh`** (저장소 루트): 의존성 설치와 빌드를 포함해 서버를 띄운다. 포트는 환경변수 `PORT`로 받는다. Ralph loop 채점에 쓴다.
2. **`Dockerfile`** (저장소 루트): 운영용(release) 빌드 이미지를 만든다. 컨테이너도 `PORT` 환경변수로 포트를 받는다. 런타임 측정에 쓴다.

Dockerfile을 에이전트가 직접 쓰게 하는 이유는 이미지 크기와 빌드 시간도 바이브 코딩 결과물의 일부로 측정하기 위해서다. 그 대가로 에이전트가 debug 빌드를 고르면 런타임 수치가 언어 특성보다 에이전트 선택을 반영하게 되므로, 하네스는 run별 빌드 모드와 워커 설정을 기록한다.

## 워커·SQLite 규칙 (모든 언어 공통)

- 서버는 **컨테이너에 할당된 CPU 코어 수만큼** 워커 또는 스레드를 쓴다. Node는 cluster(코어 수만큼 프로세스), Python은 uvicorn workers(코어 수), Go·Rust·C++은 런타임·스레드 풀을 코어 수로 맞춘다.
- 여러 프로세스·스레드가 한 SQLite 파일에 쓰는 충돌은 모든 언어에 **WAL 모드**와 **`busy_timeout` 5초**를 똑같이 적용해 다룬다.
- 이 규칙은 실험별 정본 프롬프트에 들어가고, 프롬프트는 md5로 고정한다.

## 완료 기준 (Definition of Done)

- [ ] `start.sh`로 띄운 서버가 실험별로 고정한 Hurl 정본 13파일·154요청을 전부 통과한다(파일 해시·실행 경로·버전 고정, 종료 코드 0).
- [ ] `Dockerfile`로 빌드한 컨테이너가 같은 Hurl 정본을 전부 통과한다.
- [ ] 채점 정본은 실험 대상이 수정할 수 없는 위치에서 실행하며, 빈 테스트나 잘못된 채점기 바이너리로 통과하지 않는다.

두 채점이 모두 통과해야 run을 합격으로 본다(2026-10-17 사용자 결정). 따라서 합격 run은 모두 런타임 측정이 가능하다.

## 후속 과제 — 유지보수성

합격한 run의 저장소에서 같은 모델로 새 세션을 열어 **코멘트 수정 API**(`PUT /api/articles/:slug/comments/:id`, 작성자만 수정 가능)를 추가하게 한다. 이 기능은 RealWorld 스펙에 없으므로 이 리포에서 Hurl 파일 1개를 새로 작성해 해시를 고정한다. 실행은 본 과제와 같은 Ralph 하네스로 하고 상한은 최대 5 iteration·20분이다. 새 Hurl과 기존 154요청(호스트·Docker 양쪽)이 모두 통과하면 성공이고, 기존 요청이 깨지는 회귀는 실패다.

## 통제 조건

- 모델(`claude-sonnet-5-5`, `claude-opus-5-5`)·하네스(Claude Code)·추론 설정은 실험별 manifest에 기록한다.
- 언어별 프롬프트 변형은 스택 지정 문단 외에는 바이트 단위로 같아야 하고, 각 변형의 md5를 실험 README에 기록한다.
- 런타임 측정은 모든 언어에 같은 Docker 리소스 제한, 같은 시드 데이터, 같은 k6 시나리오를 적용한다.
