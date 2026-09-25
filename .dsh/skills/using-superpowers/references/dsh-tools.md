# DSH 工具映射（DeepSeek Harness）

superpowers 技能正文按 Claude Code 的工具名书写。在 DSH 中按下表替换即可，语义一一对应。

| superpowers 里的名字（Claude Code） | DSH 实际工具 | 备注 |
| --- | --- | --- |
| `Skill` / `Skill` tool | `skill` | 用 `skill` 工具加载技能，加载后正文即指令 |
| `Read` | `read` | 读取文本文件（带行号） |
| `Write` | `write` | 创建或整文件覆盖 |
| `Edit` | `edit` | 精确字符串替换 |
| `Glob` | `glob` | 按路径模式找文件 |
| `Grep` | `grep` | 按内容正则搜索 |
| `Bash` | `pwsh` | **Windows 环境**：DSH 挂载的是 PowerShell，不是 bash |
| `TodoWrite` | `todo_write` | 传完整清单，每次调用整体替换 |
| `Task` / 子代理 | `subagent`（独立上下文）/ `subagent_fork`（继承本对话） | 默认后台运行 |
| 并行执行 | `workflow` | 只在用户明确要求大规模并行编排时使用；少量并发用多个 `subagent` |
| 长时间目标 | `create_goal` / `update_goal` | 跨轮次的单一完成目标 |
| `WebFetch` / `WebSearch` | `web_fetch` / `web_search` | — |
| 计划模式 | `exit_plan_mode` | — |
| 交付物理文件 | `present` | 用户要收到的产出，写完必须调用一次 |

## DSH 环境事实

- **平台**：Windows。`hooks/`、`run-hook.cmd`、`.claude-plugin/`、`.codex-plugin/`、`.cursor-plugin/` 这些是 Claude Code / Codex / Cursor 的宿主安装件，**在 DSH 下不生效，也不需要** —— DSH 用 `dsh-skill-filesystem` 直接扫描技能目录。
- **技能发现根**（按优先级）：
  1. `<项目根>/.dsh/skills`（本项目使用，rank 100）
  2. `<项目根>/.agents/skills`（rank 200）
  3. `~/.dsh/skills`（用户级，rank 400）
  4. `~/.agents/skills`（rank 500）
- 只识别**顶层** `<name>/SKILL.md` 或 `<name>.md`，**不递归嵌套**。新增/改名/删除/编辑 frontmatter 会热加载，无需重启。
- 项目根 = 最近的含 `.git` 的祖先目录；没有则用当前工作目录。
- 本机 `D:\summerDiary` **尚未 `git init`**，所以项目根暂时 = 工作目录本身。

## 编写新技能时

frontmatter 必填 `name`（kebab-case）与 `description`；可选 `whenToUse`、`metadata`、`disable-model-invocation`、`user-invocable`。
`scripts/`、`references/`、`assets/` 等 bundle 资源可以照常使用，DSH 会在正文里给出资源基底指引。
