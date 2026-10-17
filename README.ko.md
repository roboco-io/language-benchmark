# Language Benchmark

🌐 [English](README.md) · [한국어](README.ko.md) · [日本語](README.ja.md) · [中文](README.zh-CN.md)

이 프로젝트는 **코딩 에이전트(Claude Code)가 같은 RealWorld 백엔드를 TypeScript, Python, Go, Rust, C++로 구현한 결과를 비교해, 바이브 코딩 백엔드에 어떤 언어가 적합한지 판단한다.** 언어 런타임 자체의 순수 성능 벤치마크가 아니다. 측정 대상은 에이전트가 만든 코드이므로 결과에는 언어 특성과 에이전트의 구현 선택이 함께 들어 있다.

**📊 라이브 대시보드**: https://roboco.io/language-benchmark/

## 방법

- **고정 조건**: 모델·하네스는 Claude Code + Sonnet 5.5, Claude Code + Opus 5.5 두 조합이다. 둘 다 별도 구독 요금 없이 실행한다. 과제는 [RealWorld 백엔드](tasks/realworld-backend/)이고 DB는 모든 언어에서 SQLite다.
- **비교 축**: 구현 언어 5종과 언어별 스택(TypeScript Fastify+Prisma, Python FastAPI+SQLAlchemy, Go Gin+GORM, Rust Axum+SQLx, C++ Drogon).
- **만드는 과정 지표**: 구현 속도, 실패 포함 비용(API 단가 환산, 청구액 아님), 반복 횟수, 사람 개입 횟수, 구현 안정성(같은 조건 3회 합격률).
- **결과물 지표**: 런타임 속도(RPS·지연), 메모리(유휴·최대 RSS), 런타임 안정성(에러율·장시간 메모리 증가), 코드량·의존성, 빌드 시간·이미지 크기·콜드 스타트, 의존성 취약점, 유지보수성(기능 추가 후속 과제).
- **합격 조건**: `start.sh`로 띄운 서버와 `Dockerfile`로 빌드한 컨테이너가 모두 Hurl 154요청을 통과해야 한다.
- 규칙과 근거: [설계 문서](docs/superpowers/specs/2026-10-17-language-benchmark-design.md), [실험 품질 규칙](docs/experiment-quality-rules.md), [지표 스키마](analysis/metrics/SCHEMA.md), [로드맵](ROADMAP.md).

자매 리포 [coding-agent-benchmark](https://github.com/roboco-io/coding-agent-benchmark)는 언어를 TypeScript로 고정하고 모델·하네스 조합을 비교한다. 이 리포는 그 운영 방식을 그대로 쓰고 비교 축만 언어로 바꿨다.

## 실험 결과

<!-- RESULTS:BEGIN -->
아직 완료된 실험이 없다. 첫 실험 EXP-001(5개 언어 × 2개 모델 × 3회)은 [로드맵](ROADMAP.md)을 참조한다.
<!-- RESULTS:END -->

### 종합 인사이트

아직 결과가 없다.
