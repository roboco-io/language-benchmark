# 가설 카탈로그

상태: `미실험` → `진행중` → `검증` / `부분 검증` / `기각` / `보류` / `기준 미달`

## L축 — 구현 언어

이 리포의 L 축은 에이전트가 백엔드를 구현하는 **프로그래밍 언어**를 뜻한다. 자매 리포 coding-agent-benchmark의 L 축(자연어 EN/KO)과는 다른 축이다.

| 코드 | 가설 | 상태 | 실험 |
|------|------|------|------|
| L-01 | 같은 RealWorld 백엔드 과제에서 바이브 코딩 적합성(만드는 과정 지표와 결과물 지표의 종합 점수)은 구현 언어(TypeScript, Python, Go, Rust, C++) 사이에 차이가 있으며, 그 순위는 Sonnet 5.5와 Opus 5.5에서 같다 | 부분 검증 | [EXP-001](../experiments/001-five-language-realworld/report.md) |
| L-02 | 같은 RealWorld 백엔드 과제에서 바이브 코딩 적합성(만드는 과정 지표와 결과물 지표의 종합 점수)은 Java, PHP, C# 사이에 차이가 있으며, 세 언어 안의 순위는 Sonnet 5.5와 Opus 5.5에서 같다. EXP-001 다섯 언어와의 비교는 실행 시점이 다른 탐색적 비교로 보고한다 | 검증(탐색) | [EXP-002](../experiments/002-java-php-csharp/report.md) |
| L-03 | 같은 RealWorld 백엔드 과제에서 바이브 코딩 적합성(만드는 과정 지표와 결과물 지표의 종합 점수)은 Kotlin과 Scala 사이에 차이가 있으며, 두 언어의 순위는 Sonnet 5.5와 Opus 5.5에서 같다. EXP-001·EXP-002 언어와의 비교는 실행 시점이 다른 탐색적 비교로 보고한다 | 진행중 | [EXP-003](../experiments/003-kotlin-scala/README.md) |
