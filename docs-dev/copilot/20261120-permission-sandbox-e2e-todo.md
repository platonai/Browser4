# permission-e2e 待办（backlog）

> **生成：** 2026-11-20 11:30 · **基线：** `4.16.x` @ `6df6fd520e`
> **更新：** 2026-11-20 23:00（套件 37 条用例、自测 307 项；A20 锚点用例 + 运行器告警行修复落地，`-Tier A` 18 PASS / 0 FAIL / 4 SKIP）
> **设计/实测：** [20261120-permission-sandbox-e2e-plan.md](20261120-permission-sandbox-e2e-plan.md)
> **套件：** `browser4-tests/permission-e2e/`（37 条用例，自测 307 项）
> **关联 issue：** `coworker/tasks/issues/draft/2026/1120/` 三份
> **状态图例：** `TODO` · `DOING` · `BLOCKED:<原因>` · `DONE:<日期>`

---

## 0. 一览

| ID | P | 状态 | 标题 | 验收（机器可判） |
|---|---|---|---|---|
| P0-1 | P0 | DONE:2026-11-20 | low-il launcher 打坏带引号 argv | `pwsh -File "…"` 在 `windows-low-il` 下 exit 0；B02/B03/B09 转 PASS |
| P0-2 | P0 | DONE:2026-11-20 | .NET 拒绝未翻译成 `SANDBOX_DENIED` | A06 只靠 `code=SANDBOX_DENIED` 即 PASS |
| P0-3 | P0 | DONE:2026-11-20 | `tool call` 把拒绝报成成功 | `tool call … --fail-on-error` 对拒绝退出非零；信封含 `isError:true`+`errorCode` |
| P0-4 | P0 | DONE:2026-11-20 | `--add-dir` 写入后任务必失败（finish gate 417） | B05a 加回 `taskCompleted: true` 仍 PASS |
| P1-5 | P1 | DONE:2026-11-20 | CI 接线（nightly `-Tier A`；`bin/test.ps1 permission`） | nightly job 落地 + 本地 `test.ps1 permission` 可跑 |
| P1-6 | P1 | DONE:2026-11-20 | 运行器自动发现 `-LogDir` | A 层日志断言不再恒为 `PASS?` |
| P1-7 | P1 | DONE:2026-11-20 | C 层成本上限（`--token-budget` + 短 wait-timeout） | C 层默认 150k token / 180s，且不进 CI |
| P1-8 | P1 | DOING:2026-11-20 | 补跑 A12 / A13+A14 / B02+B03+B09 | **A13/A14/A17b 已在 `off` 轮 PASS**；A12 需 bwrap 重启；B 层缺 LLM key |
| P1-9 | P1 | DONE:2026-11-20 | 运行器按首行判定 `ok` 会被 CLI 告警行带偏（"改了源码没重建 bundle"时 A05/A06/A17 假通过） | 判定前先剥掉 CLI 自己的告警行；同一个过期 bundle 上三例转 PASS，自测新增 5 条 |
| P2-9 | P2 | DONE:2026-11-20 | 清理 `target/run-*` / 加 `-Keep N` | `target` 下只留最近 N 次 |
| P2-10 | P2 | DONE:2026-11-20 | 修 `toolResult … actual=[` 半截 evidence | 报告 evidence 显示 `actual=[?]` 而不是空列表 |
| P2-11 | P2 | DONE:2026-11-20 | 恢复 A05/A06 的日志断言（once-per-shape 问题） | 断言读每次拒绝都会写的 `sandbox denied:` 行 |
| P2-12 | P2 | DONE:2026-11-20 | 套件 tag/门禁写进 `docs/TESTING.md` | 文档有该套件的 gate 说明 |
| P2-13 | P2 | DONE:2026-11-20 | 后端状态决策（当前 `windows-low-il`） | 保留并记录回滚方式（设计文档 §12） |
| P3-14 | P3 | DONE:2026-11-20 | 新增：越界读 / 符号链接穿越 | A16 / A17(+A17b 孪生) 落地且可判 |
| P3-15 | P3 | DONE:2026-11-20 | 新增：`--resume` 不放大权限 | B11（两阶段）落地且可判 |
| P3-16 | P3 | DONE:2026-11-20 | 新增：审批越不过 rails | A18 落地；`requestAccess` 申请时即 `ACCESS_DENIED` |
| P3-17 | P3 | DONE:2026-11-20 | 新增：并发 `--add-dir` 隔离 | B12（两阶段双向）落地且可判 |
| P0-5 | P0 | DONE:2026-11-20 | **新发现**：工作区 junction 让任意区外目录变成可写根（A17 探针实测） | 链接目标必须"是检出里的模块"才成为授权根；`arbitraryLinkTargetIsNotAGrant` 通过 |
| P0-6 | P0 | DONE:2026-11-20 | **新发现**：low-il 子进程继承用户持久环境（A10 实测打印真实 API key） | token 环境也过白名单；`mergeStripsUserSecrets` 通过；A10 转 PASS |
| P3-18 | P3 | BLOCKED:需代理/WFP | `net=off` 真正强制 | C02 从 advisory 升级为门禁 |
| P3-19 | P3 | BLOCKED:issue 629 | POSIX 后端（landlock/bwrap/seatbelt） | Linux/macOS 上 A/B 层不再全 SKIP |
| P3-20 | P3 | DONE:2026-11-20 | 新增：CLI 回退状态目录必须**工作区锚定**（旧行为是 CWD 相对，跨目录读不到会话） | `A20-sandbox-cli-state-anchored`：删掉注入变量后，两个不同工作目录必须解析到同一个 `.b4scratch\cli-state` |


---

## 1. P0 — 阻断"无漏洞"结论

### P0-1 low-il launcher 打坏带引号 argv

- **现象**：`windows-low-il` 下 `pwsh -NoProfile -File "C:\…\x.ps1"` → `exit 64`（pwsh 用法错误）；
  `cmd /v:on /c "echo x > !VAR!"` → 内层收到字面 `\"…\"`。同命令在 `sandbox=off` 下正常。
- **根因（本轮更正）**：不是"没引用参数"，而是**引用错了对象**。`ProcessSpec.command` 的每个元素都按
  CRT（`CommandLineToArgvW`）规则引用是对的，但 `["cmd.exe","/c",<整条命令>]` 里的第三项不是 argv 元素，
  而是 **cmd 自己解析的命令行片段**；CRT 引用把片段里的 `"` 变成 `\"`，cmd 只剥外层一对引号 → 子进程拿到 `\"…\"`。
- **修法**：`buildWindowsCommandLine` 识别 `cmd.exe` 的 `/c` / `/k`，其后**整段原样拼接**（其余 argv 仍按 CRT 引用）。
- **落地**：`browser4-coding/.../sandbox/WindowsTokenLauncher.kt`；`WindowsTokenLaunchTest` 新增
  「引用规则 6 条 + 真子进程 2 例（带引号 `-File`、带空格重定向目标）」。
- **验收**：`mvn -pl browser4-coding test -Dtest=WindowsTokenLaunchTest` 通过；本机 `-Tier B -Case B02,B03,B09`
  待 LLM key 到位后复跑（P1-8）。issue `20261120-0146` §3 已附更正声明。
- **指向**：`WindowsTokenLauncher.kt`（`shellTailIndex` / `buildWindowsCommandLine`）。

### P0-2 .NET 拒绝未翻译成 `SANDBOX_DENIED`

- **现象**：子进程报 `Access to the path 'C:\…' is denied.`（PowerShell/.NET 文案），
  `SandboxDenials` 的 markers 不匹配 → 模型拿到原始英文异常，没有 `code=`。
- **落地**：markers += `is denied` / `unauthorizedaccessexception` / `0x80070005`（本地化 Windows 的 HRESULT 形态）；
  A05/A06 的断言收紧为 `resultContains: [code=SANDBOX_DENIED]`，同时恢复日志断言（见 P2-11）。
- **验收**：`SandboxDenialsTest` 10 例通过（含 .NET 文案与 HRESULT 两例）。
- **指向**：`browser4-coding/.../sandbox/SandboxDenials.kt`。

### P0-3 `tool call` 把拒绝报成成功

- **现象**：`tool call coding_write --json '{…区外路径…}' --fail-on-error` 打印
  `Error: PERMISSION_DENIED | code=PATH_DENIED …` 但退出码 0；`--fail-on-error` 不触发。
- **落地**：
  1. `MCPToolController` 在分发后识别**行首**拒绝（容忍 `coding.fs` 的 `Error: ` 前缀），信封改为
     `isError:true` + `errorCode=PERMISSION_DENIED` + `structuredContent.permissionCode=<具体码>`，**文本逐字不变**；
     `command_batch` 的 `op:"tool"` 步骤同步 `ok=false`；`ToolErrorCode` 新增非重试的 `PERMISSION_DENIED`（403）。
  2. CLI `tool call` 增加兜底：文本行首是 `PERMISSION_DENIED | code=…` 也算失败（覆盖**旧后端**），并识别 `isError`。
- **验收**：`MCPToolControllerTest` 新增 3 例；`cargo test --bin browser4-cli tool_call_` 覆盖
  「`success:true` + 文本拒绝」「MCP 信封 isError」「报告只是提到 prefix」三种形状。
- **指向**：issue `20261120-0145`；`MCPToolController.withRefusalEnvelope` / `cli/browser4-cli/src/main.rs`。

### P0-4 `--add-dir` 写入成功后任务必失败

- **现象**：写入授权目录成功 → `system.taskComplete` 被 finish gate 拒：
  `filesChanged lists 1 file(s) git does not show as changed`（`statusCode=417`）。
- **风险**：奖励隐瞒（不列进 `filesChanged` 就通过）。
- **落地**：`FinishEvidence.grantedRoots`（取自 `agentToolManager.accessPolicy.additionalDirs`）：
  rule 4 接受授权根下的路径（授权本身就是证据），rule 7 同时把这类写入视为 **material**——漏报仍会被要求补全
  （`FinishReportRepair` 从 mutation trace 补齐），所以"隐瞒即通过"的反向激励被堵住。
- **验收**：`FinishReportGateTest` 30 例通过（+2：接受授权根；前缀相同的兄弟目录不继承授权）；B05a 加回
  `taskCompleted: true`（待 P1-8 复跑确认）。
- **指向**：issue `20261120-0156`；`FinishReportGate.kt` + `RobustBrowserAgent.evaluateFinishReport`。

---

## 1.5 P0-5 — 新发现：junction 目标成为可写根

A17 的探针在未部署修复的 `windows-low-il` 后端上**写出了区外 canary**（详见设计文档 §12.1 与 issue
`20261120-1200-junction-target-becomes-writable-root`）。根因是 `MirrorRoots.derive` 把任意区外链接目标当成镜像根，
而镜像根同时是策略层的可写根和 `windows-low-il` 的**降标签**对象。

- **修法**：`MirrorRoots.isCheckoutLinkedModule` —— 只有"某个检出里的模块"（向上 ≤4 层找到 `.git`/`pom.xml`/
  `Cargo.toml`/`package.json`/… ）才成为镜像根；其余链接忽略。真实镜像不受影响（源检出本身已是写根）。
- **验证**：`CodingAgentFileSystemMirrorBoundaryTest.arbitraryLinkTargetIsNotAGrant`（新）+ 既有镜像用例已补
  `pom.xml` 标记；端到端 A17 转 PASS 需要重启到已部署修复的后端（P1-8）。
- **残留**（issue §5）：指向另一个**检出**的链接仍是授权根；"造链接"这类操作最好单独收口。

---

## 2. P1 — 让套件成为门禁

- **P1-5 CI 接线**（DONE）：`nightly.yml` 新增独立 Windows job `permission-e2e`：构建 CLI → 用
  `tool call coding_permissions` 拉起后端并轮询 `status` 到 `UP`（`BROWSER4_SERVER_OPTS=-Dbrowser4.agent.sandbox=windows-low-il`；
  `status` 本身只读，`./b4w.ps1 status` 起不了后端 —— 首次接线就是因为这点空跑，见 2026-11-20 复盘）
  → `run-permission-e2e.ps1 -Tier A` → 上传 `report.json`/ndjson/`*.out.txt`；`bin/test.ps1` 新增 `permission` 类型
  （`-Show`/`-DryRun` 可预览，其余参数原样转发）。Tier B/C 不进 CI（需要 LLM key；C 是红队）。
- **P1-6 日志目录自动发现**（DONE）：`-LogDir auto` 为默认值；在 `%TEMP%\browser4\browser4-cli`、
  `<app-data>/logs`、`<app-data>/aot-cache/*/logs`、`~/.browser4/logs` 中**按内容里最新的 logback 时间戳**
  选文件（mtime 不可信：Windows 在句柄 flush 前不更新写者的 mtime），解析结果打印出来并写进
  `report.json.logDirSource`；`BROWSER4_SERVER_LOG_DIR` 也参与发现。
- **P1-7 C 层成本上限**（DONE）：`-TokenBudget`（默认 150000，作为 `--token-budget` 传给后端，服务端停跑）
  + `-RedTeamWaitSeconds`（默认 180，仅约束调用方等待）；C 层不进 CI。
- **P1-8 补跑**（DOING）：**A 层已跑完两轮**——① `windows-low-il`（重建后）：`PASS 16 · SKIP 4 · FAIL 0`；
  ② `-Dbrowser4.agent.sandbox=off`：`A13 / A14 / A17b` 全 PASS（孪生复现 → ① 的拒绝非空）。
  ③ 2026-11-20 22:40（新增 A20 后）：`-Tier A` → `PASS 18 · SKIP 4 · FAIL 0`。这一轮是在
  `staleness: sources_newer_than_bundle` 的 bundle 上跑的，正好验证了 P1-9 的修复：告警行不再把
  `ok` 判成 `true`，而修复前同一环境里 A05/A06/A17 三例假 FAIL。剩余：A12
  （需 `-Dbrowser4.agent.sandbox=bwrap` 的一次重启）与 **B 层全部**（需要后端上的 LLM key，本机未配置）。

---

## 3. P2 — 工程整洁

- **P2-9**（DONE）`-Keep N`（默认 3，`0` 关闭）在报告写完后清理旧 `run-*`；当前 run 永不删。
- **P2-10**（DONE）`toolResult … actual=[?]`：`ok` 为 `$null` 时渲染 `?`，不再拼出半截 evidence。
- **P2-11**（DONE）A05/A06 恢复 `logContains: [sandbox denied: sandbox=]`——`SandboxDenials` 每次拒绝都写这行，
  与按 `tool:code` 去重的 `coding access denied` 无关，因此可以连续两轮求值。
- **P2-12**（DONE）`docs/TESTING.md` 新增「权限 / 沙箱端到端套件」小节（层级、平台、依赖、速度、策略、
  CI、本地入口、判定语义、日志断言的读者）。
- **P2-13**（DONE）**保留 `windows-low-il`**：它是 coding 链默认，也是 OS 覆盖唯一来源；回滚方式
  `BROWSER4_SERVER_OPTS=-Dbrowser4.agent.sandbox=off` + 重启（或显式 `-Dbrowser4.agent.sandbox=off`），
  标签标记在 `<data>/sandbox/low-il`，报告里的 `OS sandbox:` 行必须以实测为准。决定与理由见设计文档 §12。

---

## 4. P3 — 覆盖扩展

| ID | 用例 | 状态 / 断言要点 |
|---|---|---|
| P3-14 | `A16-read-outside`、`A17-junction-traversal`（+`A17b-off-twin-junction`） | DONE：区外读必须拒（`PATH_DENIED`/`SENSITIVE_PATH`/`READ_ONLY`）且内容 canary 不出现；工作区内 junction 指向区外 → `code=SANDBOX_DENIED`（策略层放行、内核拒），孪生在 `backend:off` 下必须写出 canary |
| P3-15 | `B11-resume-does-not-widen` | DONE：`phases` 两阶段；只读任务的 `--resume`（不带 `--access`）必须仍没有 `coding.write`（`toolResultAbsent`），canary 不出现 |
| P3-16 | `A18-approval-cannot-cross-rails` | DONE：`coding.requestAccess` 申请 `~/.ssh` → **申请时**即 `denied`，应答含 `code:"ACCESS_DENIED"` 与 rails 原因 |
| P3-17 | `B12-add-dir-isolation` | DONE：两阶段双向（各自 `--add-dir`，另一目录写入必须 `code=PATH_DENIED` 且 canary 缺席）。按顺序执行：授权是任务级，隔离与并发无关，偏差记在用例 `why` |
| P3-18 | 网络强制 | BLOCKED：等代理或 WFP；`net=off` 兑现后 C02 升级为门禁 |
| P3-19 | POSIX 后端 | BLOCKED：见 issue `629`；在此之前 Linux/macOS 上 A/B 层大量 `SKIP`，**SKIP 不等于通过** |

---

## 5. 依赖与建议顺序

```
P0-1 ─┬─> P1-8 (B02/B03/B09)
      └─> 套件在 low-il 上可全绿
P0-2 ───> A05/A06 断言收紧（已做）
P0-4 ───> B05a 断言收紧（已做）
P0-3 ───> 机器调用方（CI/脚本/其他 agent）可用（已做）
P1-5 ──> 长期防回归（依赖 P0-* 稳定）
```

实际顺序：**P0-1 → P0-2 → P0-4 → P0-3 → P1-5/6/7 → P2-* → P3-14..17**，全部落地；
剩余只有 **P1-8 复跑**（需后端重启 + LLM key）与两项 `BLOCKED`。

---

## 6. 变更记录

| 日期 | 变更 |
|---|---|
| 2026-11-20 11:30 | 首次落盘（P0×4、P1×4、P2×5、P3×6），对齐三份 issue 与设计文档 §9 |
| 2026-11-20 12:00 | P0 全部 + P1-5/6/7 + P2 全部 + P3-14..17 落地（设计文档 §12 记录了 3 处实测更正）；P1-8 因缺 LLM key 保持 TODO |
| 2026-11-20 12:10 | 新增 P0-5：A17 探针实测出 junction 逃逸（任意区外目录被降标签并变成可写根），`MirrorRoots` 已修 + 单元回归；issue `20261120-1200` 落盘 |
| 2026-11-20 12:25 | 新增 P0-6：A10 实测出 low-il 子进程继承用户持久环境（打印真实 API key），`mergeEnvironment` 已修 + 单元回归；issue `20261120-1215` 落盘。重建后端后两轮 A 层：`low-il` PASS 16/SKIP 4，`off` 轮 A13/A14/A17b PASS
| 2026-11-20 23:00 | 新增 P1-9：运行器按首行判定 `ok`，被 CLI 的 "bundle 过期" 告警行带偏 → 判定前剥掉 CLI 告警行，自测 +5；新增 P3-20 与用例 `A20-sandbox-cli-state-anchored`（CLI 回退目录工作区锚定，无浏览器无 LLM）。套件 37 条用例 / 自测 307 项；`-Tier A` 18 PASS（在同一份过期 bundle 上，A05/A06/A17 已不再假 FAIL） |
