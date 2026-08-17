# Superpowers (Qoder Plugin)

一套完整的智能体软件开发方法论插件，源自 [obra/superpowers](https://github.com/obra/superpowers)。

Superpowers 从会话一开始就改变智能体的工作方式：先通过 `brainstorming` 澄清真实需求并产出设计文档，再生成可执行的实施计划，然后以 TDD 方式分任务实现（子代理驱动），每步都经过代码审查与验证，最后干净地收尾开发分支。

## 来源 (Provenance)

- **上游仓库**: https://github.com/obra/superpowers
- **上游许可**: MIT License（见 [LICENSE](https://github.com/obra/superpowers/blob/main/LICENSE)）
- **转换方式**: 按 `create-plugin` 工作流从上游仓库 `skills/` 目录逐技能复制，SKILL.md 内容保持原样，未改写任何上游实现文件。
- **Logo**: 取自上游 `assets/superpowers-small.svg`。

## 包含的技能 (14)

| 技能 | 用途 |
|------|------|
| `using-superpowers` | 会话引导：在任何响应前先检查并调用相关技能（含各平台工具适配参考） |
| `brainstorming` | 创造性工作前的设计探索：通过问答澄清意图、探索方案、分段呈现设计并保存设计文档 |
| `writing-plans` | 将已批准的设计拆分为 2-5 分钟粒度的任务，每个任务含精确文件路径、完整代码与验证步骤 |
| `executing-plans` | 分批执行计划，在检查点暂停与用户确认 |
| `subagent-driven-development` | 每个任务派遣全新实现子代理，两阶段审查（规格符合 + 代码质量） |
| `dispatching-parallel-agents` | 对无共享状态、无顺序依赖的多个独立任务并行派遣子代理 |
| `test-driven-development` | 强制 RED-GREEN-REFACTOR 循环，先写失败测试再写实现代码 |
| `systematic-debugging` | 4 阶段根因分析（含 root-cause-tracing、defense-in-depth、condition-based-waiting 技术） |
| `verification-before-completion` | 在声称完成/修复前必须运行验证命令并以证据为准 |
| `requesting-code-review` | 任务完成或合并前派遣代码审查子代理，按严重程度报告问题 |
| `receiving-code-review` | 以技术严谨性回应审查反馈，拒绝表演性附和或盲目实施 |
| `using-git-worktrees` | 用独立工作区隔离特性开发（原生工具或 git worktree 兜底） |
| `finishing-a-development-branch` | 实现完成后验证测试并提供 merge/PR/保留/丢弃选项 |
| `writing-skills` | 按最佳实践创建/编辑/验证新技能（含测试方法论） |

## 支持文件

- `skills/brainstorming/` — spec-document-reviewer-prompt.md、visual-companion.md、scripts/（本地可视化伴随服务）
- `skills/requesting-code-review/code-reviewer.md` — 审查子代理提示词
- `skills/subagent-driven-development/` — implementer/task-reviewer/re-review 三个提示词 + scripts/（sdd-workspace、task-brief、review-package）
- `skills/systematic-debugging/` — root-cause-tracing.md、defense-in-depth.md、condition-based-waiting.md 及示例
- `skills/test-driven-development/writing-good-tests.md` — 测试编写参考
- `skills/using-superpowers/references/` — Codex/Pi/Antigravity 平台工具适配
- `skills/writing-plans/plan-document-reviewer-prompt.md` — 计划文档审查提示词
- `skills/writing-skills/` — 最佳实践、说服原则、graphviz 约定与渲染脚本等

## 省略内容及原因

- `hooks/`（Claude Code 专用 SessionStart hook，用于注入 bootstrap）：Qoder 会自动加载已安装插件的技能并按 description 自动触发，无需 SessionStart hook；该 hook 依赖 `${CLAUDE_PLUGIN_ROOT}` 等 Claude Code 运行时变量，无法在 Qoder 中运行。相关说明已保留在 `using-superpowers` 技能内。
- `.claude-plugin/`、`.codex-plugin/`、`.cursor-plugin/`、`.kimi-plugin/`、`.opencode/`、`.pi/`、`.gemini/` 等平台专属元数据：仅对各自平台运行时有效，对 Qoder 无意义。
- `CLAUDE.md` / `AGENTS.md`：上游贡献者指南（面向给 superpowers 提 PR 的场景），与日常使用技能无关。
- `docs/`、`tests/`、`scripts/`、`evals/`：上游开发/测试基础设施，非技能运行时依赖。
- `.agents/plugins/marketplace.json`：其他平台的市场配置。

## Qoder 使用说明

- 安装后技能自动生效：Qoder 会在会话中根据用户请求匹配技能描述并触发对应技能（如用户说"帮我设计一个 X"，将自动触发 `brainstorming`）。
- 技能中的 "Task tool / subagent" 对应 Qoder 的 `Agent` 工具（子代理），"Read"、"Bash" 等工具 Qoder 均已提供等价能力。
- 部分技能（如 `brainstorming` 的可视化伴随、`writing-skills` 的 graphviz 渲染）依赖 Node.js 或本地脚本，按需使用；不使用不影响核心流程。
- 上游遥测：`brainstorming` 的视觉伴随功能可能从上游网站加载 logo 图片。可通过设置环境变量 `SUPERPOWERS_DISABLE_TELEMETRY`（任意真值）禁用。

## 验证

- 已通过离线验证器 `validate_qoder_plugin.py` 校验（见转换记录）。
- 每个 `SKILL.md` 均保留上游 `name` + `description` frontmatter。
