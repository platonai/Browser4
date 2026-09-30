# RobustBrowserAgent#run v2 设计评审（全新视角）

> 日期：2026-08-22 · 对象：《robust-browser-agent-run-v2-design.md》v0.1
> 方法：先审"要不要做"（需求正确性）→ 再审"怎么做"（方案正确性）→ 最后审"细节"（实现质量）；
> 含 Kill Your Darlings、场景走查、故障注入、复杂度预算、成本建模、可测试性审计、对抗视角。

## 1. 结论摘要（最重的五个发现）

1. **问题陈述错位**：v1 的两个实测事故（64k 截断、JSON 解析崩溃）都不是架构问题——
   64k 是 PulsarRPA 配置默认值（已修），JSON 崩溃一行容错即可解。用"修 bug"论证换引擎说服力不足。
   CLI 路线真正的价值是**工具面标准化**：同一份 SKILL.md + browser4-cli 接口既能驱动后端内 agent，
   也能移植到外部 agent 环境——这个战略价值文档没写，写了才能正当化整套进程机制。
2. **输出缓冲自相矛盾**：§4.1"尾部保留 64KB + spill"与 §4.1.1"HeadTailBuffer 头尾 1MiB"并存，未裁决。
3. **"杀进程树" ≠ "取消操作"**：crawl/swarm 是 CLI submit→poll 同步等待、任务跑在后端。
   杀掉 CLI 轮询进程，后端任务仍继续。文档把进程级取消当操作级取消（P0）。
4. **12 道进程防线是借来的漂亮衣服**：browser4-cli 是短命 HTTP 客户端，僵尸组扫描、spill 文件、
   PID 复用永久边界、PTY 阶梯这些是为"长期交互 shell + trap 孙进程"设计的，复杂度预算超支。
5. **argv 解析是最高风险实现细节却被一笔带过**：X-SQL `WHERE x='y'` 类引号，"POSIX 感知 tokenizer"
   分分钟翻车。必须定案并拿 SKILL.md 全命令语料做 fixture 测试。

## 2. 假设清单

| # | 假设 | 状态 |
|---|---|---|
| 1 | deepseek-v4 原生 function calling（经 langchain4j）可用 | 待验证（M1） |
| 2 | coding/cli 工具经 `ToolSpecificationConverter` 生成的 schema 模型可用 | 待验证（M1） |
| 3 | CLI 子进程单次 ~百毫秒级 | 待验证（实测） |
| 4 | CLI 支持 env/flag 指定 server（BROWSER4_SERVER 注入） | **存疑**——需确认，否则临时 config 或逐命令传参 |
| 5 | CLI 不会 fork 持久 daemon 干扰进程树管理 | **存疑**——需审计 `daemon.rs` |
| 6 | SKILL.md 全文 50KB ≈ 15-20k token 且模型能消化 | 待验证（成本+效果） |
| 7 | 模型会遵循"先 snapshot 再行动"的卫生规则 | 待验证（e2e 观察） |
| 8 | 三平台打包 CLI 二进制可行（构建/体积/许可） | 待验证 |
| 9 | 自动安装有固定来源 + 校验和 | 待验证（文档未写分发源） |
| 10 | 长命令（crawl/swarm）后端任务可单独取消 | **存疑**——需确认后端取消端点 |
| 11 | ToolLoopCompressor 对 CLI 大输出结果有效 | 待验证 |
| 12 | v2"全程 coding 模式 + CLI 管浏览器"不绑定 driver | 基本成立，需验证 |

## 3. 问题清单

### P0（必须先解决）
- 输出缓冲矛盾（tail 64KB vs head+tail 1MiB）——先定一个。
- 进程级取消 ≠ 操作级取消——杀 CLI 轮询进程后后端任务残留。
- argv 策略未定案——X-SQL/选择器引号是真实翻车点。
- CLI 版本漂移：本机 4.13.7 与 4.14 后端不同源；resolver 必须版本对齐/兼容探测，文档未提。

### P1
- 12 道防线过度设计（复杂度预算）。
- CLI 可能 fork daemon——树杀边界必须排除/特殊处理持久进程。
- BROWSER4_SERVER 注入机制未验证。
- 自动安装的分发源、版本固定、校验和缺失。
- yield 会话化与 CLI 原生异步协议职责重叠——先查 crawl/swarm 是否本身支持 task-id/异步语义。

### P2
- 文档 bug："10 道"防线实际列了 12 项。
- Windows yield 下限 10s 理由写"ConPTY"，但本设计无 PTY，理由不成立。
- 并发队列无上限定义。
- 错误分类未映射到重试策略（retryable / non-retryable / fatal）。
- 无指标（CLI 调用数、延迟、截断率）。
- SKILL.md 每任务 15-20k token 基线成本未量化、未定会话级缓存策略。

## 4. 决策审计

| 决策 | 结论 | 理由 |
|---|---|---|
| CLI 子进程路线 | **保持**（用户指定），重写动机 | 价值 = SKILL.md/工具面标准化与可移植性；不是"修 bug" |
| 原生 function calling + taskComplete | **保持** | 架构正确，消灭解析脆弱性 |
| SKILL.md 全文入 system prompt | **保持**，补量化+缓存 | 一次性基线，多任务摊销 |
| coding+cli 工具集、不披露 tab/browser | **保持** | 符合需求；补"进程内工具仍存在，只是 v2 不披露" |
| 三层能力缝 | **简化** | 保留 service 层（resolve/spec），别为对称而建第四层 |
| 12 道进程防线 | **砍到 ~6** | 保留树杀+grace、Job Object/PDEATHSIG、env 白名单、输出有界、job 注册表+取消、teardown |
| yield 会话化 | **修改** | 优先 CLI 原生异步协议；job 模式只兜底 |
| argv tokenizer | **定案：放弃，走 shell 单 argv** | 安全无增益（模型已有 coding.shell）+ 引号正确性由 shell 保证；语料 fixture 改为对 SKILL.md 命令示例的冒烟验证 |
| 输出缓冲 | **修正矛盾** | 统一：原始捕获 head+tail 有界，返回模型按 token 截断 |
| 环境白名单 | **保持** | 强且便宜 |
| 后台任务杀进程树 | **修改** | 区分进程取消与后端任务取消 |
| 自动安装 | **保持**，补分发源/校验和/版本对齐 | |
| runEngine 开关 / v1 不动 | **保持** | |

## 5. 简化机会（修订版要砍的）

1. 进程防线 12 → 6（删：僵尸组扫描、spill 文件、PID 复用永久边界、PTY 阶梯、管道排水限时）。
2. 输出缓冲二选一。
3. yield 窗口降级为"已知长命令族直接进 job 模式 / 原生异步协议"，不为通用机制铺路。
4. 不为 browser4-cli 做 PTY/交互 stdin——它是无交互 HTTP 客户端，删相关设计。

## 6. 修订计划（对应设计文档改动）

1. 重写第 1、2 节动机：工具面标准化 + 稳健原生循环；v1 bug 列为附带收益；
   补一句"进程内工具面仍存在但 v2 不披露"。
2. §4.1 重写：统一输出缓冲；错误分类 → 重试策略映射；补指标；并发队列上限；
   两段式取消（进程级 kill + 后端操作级取消）。
3. argv 定案：M1 加"SKILL.md 全命令语料 fixture 测试"。
4. §4.2 补版本对齐 + 自动安装分发源/校验和。
5. §4.3 补 SKILL.md 会话级缓存 + 每任务成本量化。
6. §7/§8：新增 M0（审计 daemon、server 注入、任务级取消）；裁剪 M1 过度测试；
   修正文档 bug（"12 道"、Windows yield 理由）。

## 7. 验证计划更新

- **M0（新增，已完成 2026-08-22，结论写入设计文档 §4.1）**：`daemon.rs` 不 fork 持久 CLI daemon，
  但会自建/重启后端（外部启动的服务器永不重启）；server URL 注入 = env `BROWSER4_CLI_SERVER`；
  crawl / agent-command 有取消端点，swarm 无取消端点（记录为限制）。
- **M1**：原生 function calling 验证 + CliProcessManager 核心单测（裁剪后）+ argv fixture 测试 +
  版本兼容探测 + CLI 单次开销实测 + SKILL.md token 成本实测。
- **M2**：补三平台打包路径与自动安装源。
- **M4**：补多任务 SKILL.md 摊销验证、长命令取消"后端任务确实停止"断言。
