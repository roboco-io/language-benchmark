# Language Benchmark

🌐 [English](README.md) · [한국어](README.ko.md) · [日本語](README.ja.md) · [中文](README.zh-CN.md)

本项目**比较编码智能体(Claude Code)用 TypeScript、Python、Go、Rust、C++ 实现同一个 RealWorld 后端的结果,以判断哪种语言最适合氛围编程(vibe coding)后端。** 这不是语言运行时本身的纯性能基准测试。测量对象是智能体编写的代码,因此结果中同时包含语言特性和智能体的实现选择。

**📊 实时仪表盘**: https://roboco.io/language-benchmark/

## 方法

- **固定条件**: 模型与工具组合为 Claude Code + Sonnet 5.5 和 Claude Code + Opus 5.5,两者都在现有订阅内运行,无额外订阅费用。任务为 [RealWorld 后端](tasks/realworld-backend/),所有语言都使用 SQLite 数据库。
- **比较维度**: 5 种实现语言及各自的技术栈(TypeScript Fastify+Prisma、Python FastAPI+SQLAlchemy、Go Gin+GORM、Rust Axum+SQLx、C++ Drogon)。
- **构建过程指标**: 实现时间、含失败的成本(按 API 单价换算,非实际账单)、迭代次数、人工介入次数、实现稳定性(同条件 3 次的通过率)。
- **产出物指标**: 运行速度(RPS、延迟)、内存(空闲与峰值 RSS)、运行稳定性(错误率、长时间负载下的内存增长)、代码量与依赖数、构建时间、镜像大小与冷启动、依赖漏洞、可维护性(新增功能的后续任务)。
- **通过条件**: 由 `start.sh` 启动的服务器和由 `Dockerfile` 构建的容器都必须通过全部 154 个 Hurl 请求。
- 规则与依据: [设计文档](docs/superpowers/specs/2026-10-17-language-benchmark-design.md)、[实验质量规则](docs/experiment-quality-rules.md)(韩语)、[指标定义](analysis/metrics/SCHEMA.md)、[路线图](ROADMAP.md)。

姊妹仓库 [coding-agent-benchmark](https://github.com/roboco-io/coding-agent-benchmark) 将语言固定为 TypeScript,比较模型与工具组合。本仓库沿用其运作方式,只把比较维度换成语言。

## 实验结果

<!-- RESULTS:BEGIN -->
<!-- 此区块由 scripts/update_readme_results.py 根据 experiments/*/report.md 自动生成（翻译来自 scripts/readme_i18n.json）。请勿手动编辑。 -->

| 实验 | 假设 | 判定 | 客户端 |
|------|------|------|------|
| [EXP-001](experiments/001-five-language-realworld/report.md) 五种语言 RealWorld 后端 Ralph 循环 | L-01: 在同一个 RealWorld 后端任务中，氛围编程适用性（构建过程指标与产出物指标的综合得分）在实现语言（TypeScript、Python、Go、Rust、C++）之间存在差异，且该排名在 Sonnet 5.5 与 Opus 5.5 中相同。 | **部分验证** | Claude Code 2.1.302 |

**EXP-001 — 五种语言 RealWorld 后端 Ralph 循环** (部分验证)  
观察到语言间综合得分差异（Go 在两个模型中均进入前两名，Python 在两个模型中均为最后），但第一名因模型而异（Sonnet 5.5 为 Rust，Opus 5.5 为 Go），因此“排名相同”部分未获支持；30 个 run 全部通过，合格率无法区分语言。 → [报告](experiments/001-five-language-realworld/report.md)

<!-- RESULTS:END -->

### 综合洞察

在 EXP-001（2026-10-18，5 种语言 × Sonnet 5.5、Opus 5.5 × 3 次）中，RealWorld 后端任务在两个模型下五种语言全部通过（30/30），其中 29 个 run 在第一次 iteration 完成。在这一任务难度下，语言并未决定能否完成，差异体现在实现成本、时间以及产出物的运行时特性上。

在 24 项指标等权的综合得分中，Go 在两个模型下都进入前两名（Sonnet 第 2，Opus 第 1），Python 在两个模型下都是最后一名。第一名因模型而异（Sonnet 为 Rust，Opus 为 Go）。C++ 实现时间和成本最高，但内存占用最小；Python、TypeScript 实现快且便宜，但在吞吐量、镜像大小和漏洞数上落后。Opus 5.5 的估算成本约为 Sonnet 5.5 的 2 倍，实现时间约 1.8 倍，在本任务上没有合格率收益。

局限：只有一个任务，每格 3 次，综合得分对权重选择敏感。运行时指标在 AWS Fargate Spot（Graviton4，2 vCPU）上测得。依据见 [EXP-001 报告](experiments/001-five-language-realworld/report.md)。
