# Language Benchmark

🌐 [English](README.md) · [한국어](README.ko.md) · [日本語](README.ja.md) · [中文](README.zh-CN.md)

This project **compares how a coding agent (Claude Code) implements the same RealWorld backend in TypeScript, Python, Go, Rust and C++, to judge which language suits vibe-coded backends.** It is not a raw language-runtime benchmark. What we measure is code the agent wrote, so results mix language traits with the agent's implementation choices.

**📊 Live dashboard**: https://roboco.io/language-benchmark/

## Methodology

- **Fixed**: two model–harness combinations, Claude Code + Sonnet 5.5 and Claude Code + Opus 5.5, both run on an existing subscription. The task is the [RealWorld backend](tasks/realworld-backend/); the database is SQLite in every language.
- **Compared**: five implementation languages with one stack each (TypeScript Fastify+Prisma, Python FastAPI+SQLAlchemy, Go Gin+GORM, Rust Axum+SQLx, C++ Drogon).
- **Build-process metrics**: implementation time, cost including failures (API-price equivalent, not billed spend), iterations, human interventions, implementation stability (pass rate over 3 runs).
- **Product metrics**: runtime speed (RPS, latency), memory (idle and peak RSS), runtime stability (error rate, memory growth under soak), code size and dependencies, build time, image size and cold start, dependency vulnerabilities, maintainability (a follow-up feature task).
- **Pass condition**: both the server started by `start.sh` and the container built from `Dockerfile` must pass all 154 Hurl requests.
- Rules and evidence: [design](docs/superpowers/specs/2026-10-17-language-benchmark-design.md), [quality rules](docs/experiment-quality-rules.md) (Korean), [metric schema](analysis/metrics/SCHEMA.md), [roadmap](ROADMAP.md).

The sister repo [coding-agent-benchmark](https://github.com/roboco-io/coding-agent-benchmark) fixes the language to TypeScript and compares model–harness combinations. This repo reuses its operating model and swaps the comparison axis to language.

## Results

<!-- RESULTS:BEGIN -->
No experiments have finished yet. See the [roadmap](ROADMAP.md) for the first one, EXP-001 (5 languages × 2 models × 3 runs).
<!-- RESULTS:END -->

### Overall insights

No results yet.
