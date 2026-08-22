# 内置上网智能体审视（功能与实现）

> 日期：2026-08-23 · 对象：`agent run` 全链路（CLI → command_run → StatefulAgentRunner →
> RobustBrowserAgent(CLI_TOOL_LOOP) → AgentToolCallLoop → cli.run → CliProcessManager →
> browser4-cli 子进程 → 同后端浏览器），即 v2 设计落地后的内置上网智能体。

## 1. 结论摘要（五个最重发现）

1. **引擎路由不一致**：`UserCommandExecutor` 的 `commandNormalizer?.normalize()` 无条件执行，
   任务文本含 URL 就被劫持到 v1 页面访问路径；裸 URL 直接走 `isConfiguredUrl` 页面访问。
   "默认 cli 引擎"只对无 URL 的自由任务生效。
2. **M0 守卫默认关闭**：`browser4.server.url` 只有 `AgentToolManager` 读取，无任何默认来源；
   正常部署下 `backendBaseUrl=null` → 无健康前置检查、无 `BROWSER4_CLI_SERVER` →
   后端端口不可达时 CLI 子进程会自行构建/下载并拉起服务器（"绝不自启服务器"的保证默认不成立）。
3. **长任务无兜底**：`doRunCliAgentLoop` 的 `AgentToolCallLoop` 未接 compressor；
   `CliJobRegistry` 未接进 `cli.run`；默认 120s 超时 → crawl/swarm 被杀或撞 500k 限制停机。
4. **工作区卫生**：agent 的 `coding.*` 以后端 cwd（仓库根）为工作目录，e2e 实际在仓库根
   写下 `get-first-para.js`/`inspect-para.js`/`p` 垃圾文件，无工作区边界。
5. **版本对齐未实现**：resolver 优先 PATH（本机 4.13.7），bundle 未打包 CLI，`doctor`
   兼容探测未接——4.13.7 ↔ 4.14 API 漂移风险。

## 2. 功能面

**能做什么（已实测）**：自主导航、输入、提交、等待、DOM/JS 提取、多步决策、按 SKILL.md
调用 CLI、`system.skillDoc` 按需读参考、`taskComplete`/文本回退完成报告、处理空元素等边角。
e2e 两次 status 200。

**缺什么**：URL 任务路由（P0）；长命令 job 化；视觉能力（纯文本模型）；认证/持久 profile
自动化；多任务 SKILL.md 摊销（每 run 重发 17.5k token）；每任务指标。

## 3. 实现面

**做得对的**：原生 function calling 消灭解析脆弱性；`taskComplete`+文本回退双通道；
`CliProcessManager` env 白名单/树杀/超时互斥单测齐全；并发信号量存在。

**实现的债**：
- 55/86 工具靠名字前缀过滤（`coding_`/`cli_`/`system_`）——脆弱；
- 文本完成回退启发式（连续 2 轮纯文本）偏 hack，且每轮重发全量历史（无压缩）；
- `CliToolExecutor` 不传 `sessionId` → 每会话并发上限从未生效（只有全局 8）；
- history 只留一个 `step=0` 最终状态，无每工具轨迹（可观测性较 v1 倒退）；
- 系统提示词浏览器向，但默认引擎也接管 coding 类任务（未实测）；
- 7 层往返：每次 `cli.run` ≈ 进程 spawn（~300ms avg）+ HTTP，20 步任务 ~6-12s 纯开销。

## 4. 决策审计

| 决策 | 结论 |
|---|---|
| CLI 子进程路线 / 原生 function calling / SKILL.md 驱动 | 保持（e2e 已验证） |
| 默认 cli 引擎 | 保持 |
| URL 任务路由 | **修改**：engine 非显式 OBSERVE_ACT 时跳过 normalizer，裸 URL 也走 agent |
| M0 守卫 | **修改**：REST 启动时自动注入 `browser4.server.url`，默认开启 |
| 长任务 | 修改：接 compressor + 长命令族走 CliJobRegistry |
| 工作区 | 修改：任务工作目录收敛到 AgentPaths 会话目录 |
| 文本回退启发式 | 简化：streak≥1 + 强化 prompt |
| 工具过滤 | 简化：显式白名单常量而非前缀 |
| 版本对齐 | 修改：resolver 比对 --version 与后端版本 |

## 5. 修复优先级

- **P0（本轮）**：① URL 任务路由一致（走 cli 引擎）；② M0 守卫默认开启。
- P1：compressor 接线 → 长命令 job 化 → 工作区隔离。
- P2：工具白名单常量、文本回退简化、版本对齐、指标。
