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
尚无已完成的实验。第一个实验 EXP-001(5 种语言 × 2 个模型 × 3 次)见[路线图](ROADMAP.md)。
<!-- RESULTS:END -->

### 综合洞察

暂无结果。
