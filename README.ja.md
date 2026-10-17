# Language Benchmark

🌐 [English](README.md) · [한국어](README.ko.md) · [日本語](README.ja.md) · [中文](README.zh-CN.md)

このプロジェクトは、**コーディングエージェント(Claude Code)が同じ RealWorld バックエンドを TypeScript、Python、Go、Rust、C++ で実装した結果を比較し、バイブコーディングのバックエンドにどの言語が適しているかを判断する。** 言語ランタイム自体の純粋な性能ベンチマークではない。測定対象はエージェントが書いたコードであり、結果には言語の特性とエージェントの実装上の選択が混ざっている。

**📊 ライブダッシュボード**: https://roboco.io/language-benchmark/

## 方法

- **固定条件**: モデル・ハーネスは Claude Code + Sonnet 5.5 と Claude Code + Opus 5.5 の2組み合わせ。どちらも追加のサブスクリプション料金なしで実行する。課題は [RealWorld バックエンド](tasks/realworld-backend/)、DB は全言語で SQLite。
- **比較軸**: 実装言語5種と言語別スタック(TypeScript Fastify+Prisma、Python FastAPI+SQLAlchemy、Go Gin+GORM、Rust Axum+SQLx、C++ Drogon)。
- **作る過程の指標**: 実装時間、失敗を含むコスト(API 単価換算、請求額ではない)、反復回数、人の介入回数、実装の安定性(同条件3回の合格率)。
- **成果物の指標**: ランタイム速度(RPS・レイテンシ)、メモリ(アイドル・最大 RSS)、ランタイムの安定性(エラー率・長時間負荷でのメモリ増加)、コード量・依存数、ビルド時間・イメージサイズ・コールドスタート、依存の脆弱性、保守性(機能追加の後続課題)。
- **合格条件**: `start.sh` で起動したサーバーと `Dockerfile` でビルドしたコンテナの両方が Hurl 154 リクエストをすべて通過すること。
- ルールと根拠: [設計文書](docs/superpowers/specs/2026-10-17-language-benchmark-design.md)、[実験品質ルール](docs/experiment-quality-rules.md)(韓国語)、[指標スキーマ](analysis/metrics/SCHEMA.md)、[ロードマップ](ROADMAP.md)。

姉妹リポジトリ [coding-agent-benchmark](https://github.com/roboco-io/coding-agent-benchmark) は言語を TypeScript に固定してモデル・ハーネスの組み合わせを比較している。このリポジトリはその運用方法をそのまま使い、比較軸だけを言語に変えた。

## 実験結果

<!-- RESULTS:BEGIN -->
完了した実験はまだない。最初の実験 EXP-001(5言語 × 2モデル × 3回)は[ロードマップ](ROADMAP.md)を参照。
<!-- RESULTS:END -->

### 総合インサイト

まだ結果はない。
