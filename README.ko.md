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
<!-- 이 블록은 scripts/update_readme_results.py가 experiments/*/report.md에서 자동 생성한다. 직접 수정 금지. -->

| 실험 | 가설 | 판정 | 클라이언트 |
|------|------|------|------|
| [EXP-001](experiments/001-five-language-realworld/report.md) 5개 언어 RealWorld 백엔드 Ralph 루프 | L-01: 같은 RealWorld 백엔드 과제에서 바이브 코딩 적합성(만드는 과정 지표와 결과물 지표의 종합 점수)은 구현 언어(TypeScript, Python, Go, Rust, C++) 사이에 차이가 있으며, 그 순위는 Sonnet 5.5와 Opus 5.5에서 같다. | **부분 검증** | Claude Code 2.1.302 |

**EXP-001 — 5개 언어 RealWorld 백엔드 Ralph 루프** (부분 검증)  
언어 간 종합 점수 차이는 관측됐지만(Go는 두 모델 모두 상위 2위, Python은 두 모델 모두 최하위) 1위가 모델마다 달라(Sonnet 5.5 Rust, Opus 5.5 Go) 순위가 같다는 부분은 지지되지 않았고, 30 run 모두 합격해 합격률로는 언어를 구별하지 못했다. → [보고서](experiments/001-five-language-realworld/report.md)

<!-- RESULTS:END -->

### 종합 인사이트

EXP-001(2026-10-18, 5 언어 × Sonnet 5.5·Opus 5.5 × 3회)에서 RealWorld 백엔드는 다섯 언어 모두 두 모델에서 30/30 합격했고 29건이 첫 iteration에 끝났다. 따라서 이 과제 수준에서는 언어가 완주 가능성을 가르지 않았고, 차이는 구현 비용·시간과 결과물의 런타임 특성에서 나타났다.

24개 지표 균등 가중 종합 점수에서 Go는 두 모델 모두 상위 2위 안(Sonnet 2위, Opus 1위)이었고 Python은 두 모델 모두 최하위였다. 1위는 모델마다 달랐다(Sonnet Rust, Opus Go). C++은 구현 시간과 비용이 가장 컸지만 메모리 사용이 가장 작았고, Python·TypeScript는 구현은 빠르고 싸지만 처리량·이미지 크기·취약점 수에서 뒤졌다. Opus 5.5는 Sonnet 5.5보다 추정 비용이 약 2배, 구현 시간이 약 1.8배였고 본 과제 합격률 이득은 없었다.

한계: 과제 하나, 셀당 3회이며 종합 점수는 가중치 선택에 민감하다. 런타임 지표는 AWS Fargate Spot(Graviton4, 2 vCPU)에서 측정했다. 자세한 근거는 [EXP-001 보고](experiments/001-five-language-realworld/report.md)에 있다.
