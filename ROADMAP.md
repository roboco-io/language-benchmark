# Roadmap

우선 목표(2026-10-17 사용자 확정): **바이브 코딩 백엔드에 적합한 언어 선택**. 모델·하네스를 고정하고 구현 언어를 바꿔 만드는 과정 지표와 결과물 지표를 비교한다. 체크는 수행 이력이며 언어 우위의 확정을 뜻하지 않는다.

## 현재 우선순위 (2026-10-17)

- [x] 리포 생성, 운영 골격 이식, 빈 대시보드 배포 ([설계](docs/superpowers/specs/2026-10-17-language-benchmark-design.md))
- [x] 다언어 Ralph 하네스 이식: 자매 리포 `ralph-model-benchmark` 스킬에 언어 축, `start.sh`·Docker 이중 게이트 추가
- [x] 런타임 측정 하네스: Docker 리소스 제한, 시드 스크립트, k6 부하·장시간 부하, 빌드·이미지·콜드 스타트, LOC·의존성, 취약점 스캔
- [x] 후속 과제(코멘트 수정 API) Hurl 정본 작성·해시 고정
- [x] Phase 0: 툴체인 버전 기록, Hurl 바이너리 확정, 언어별 빌드 시간으로 run 상한 확정, TS 기준선 재현 확인
- [x] EXP-001: 5 언어 × 2 모델(Sonnet 5.5, Opus 5.5) × 3회 = 30 run + 후속 과제 (L-01) — 2026-10-18 완료, [보고](experiments/001-five-language-realworld/report.md): 부분 검증
- [x] EXP-002: Java·PHP·C# × 2 모델(Sonnet 5.5, Opus 5.5) × 3회 = 18 run + 후속 과제 (L-02) — 2026-10-19 완료, [보고](experiments/002-java-php-csharp/report.md): 검증(탐색)
- [ ] 후속 실험 후보: 합격률 천장을 없앨 더 어려운 과제, 반복 수 확대, 가중치 민감도 분석
- [ ] 하네스 수정: 후속 과제 앱 복사에서 빌드 산출물 제외, Fargate 런타임 측정을 스킬 절차로 정식화
