# 评测任务：`code devtask`（Browser4 自开发计划器）

> 被测命令：`./b4w.ps1 cli code devtask "<task>"`
> 评测工件：`bin/eval-devtask.ps1`（可执行）+ 本文件（任务定义与基线）
> 基线：`4.14.2-SNAPSHOT` @ `61b7962f29`
> 判读：门禁 PASS/FAIL 决定退出码。K1–K8 已全部修复、登记已删除（§4）：现在任何门禁失败都直接判失败。脚本仍支持 KNOWN/XPASS 语义，供将来登记新的已知差距

---

## 1. 任务目标

`code devtask` 是 Browser4 自开发的入口：把一句自然语言任务变成一串可执行步骤
（AGENTS.md 开发流：定位代码 → 影响分析 → 编译 → 最小范围测试 → CDP 陷阱检查 → 仓库治理校验，
**不含 commit**）。它既是给人看的计划，也是给代理执行的脚本，还是监督方验收的依据。

本评测任务回答三个问题：

1. **计划对不对**——信号（模块 / 文件 / 测试类 / 新模块）是否绑定到"最深、最相关"的目标？
2. **计划能不能执行**——步骤里的路径、命令是否真实存在、词法合法、不含副作用？
3. **命令能不能当门禁用**——退出码是否承载失败？参数契约是否可靠？延迟是否可接受？

评测是**黑盒**的：只通过 `b4w.ps1`（开发 wrapper）→ `browser4-cli`（Rust）→ MCP `coding_devTask`
→ `DevTaskPlanner` 这条真实链路观察输出，不 import 后台类、不 mock。

### 不变量（I1–I6）

| # | 不变量 | 违反后果 |
|---|---|---|
| I1 | 计划模式只读：不写文件、不跑构建 | 代理会把"看一眼"变成改动/长构建 |
| I2 | 可解析：`Dev task plan (N steps):` + 编号 1..N + 每步 `[coding.<tool>]` + `coding.*` 命令 | 代理/监督脚本无法机械消费 |
| I3 | 绑定正确：编译/测试绑定**最深**受影响模块；新模块优先于同深度 DEPENDENTS 键 | 编错模块 = 假绿 |
| I4 | 不臆造：无代码信号时明说"需澄清范围" | 代理被误导到无关模块 |
| I5 | 幂等：同输入同输出 | 无法回归比对 |
| I6 | 失败可见：失败以非零退出码承载，不吞错 | CI/监督方看不见失败 |

---

## 2. 探针矩阵（评测输入）

每个探针是一句自然语言任务，覆盖一类信号解析路径。全部**只渲染计划**，从不传 `--execute`。

| 探针 | 任务文本（原文） | 想抓的信号 |
|---|---|---|
| `driver-bare` | `fix cursor positioning in Browser4WebDriver.kt and add a test class FillCursorTest in browser4-rest` | 裸文件名 + 浏览器驱动文件 + 测试类 |
| `existing-plugin` | `fix a test in browser4-plugins/browser4-seo and run SeoServiceTest` | 已存在插件的深层模块 + 命名测试类 |
| `new-plugin` | `创建新插件 browser4-plugins/browser4-evalprobe，实现 src/main/resources/evalprobe/count.js 与 EvalprobeServiceTest` | 未知 `browser4-plugins/<name>` → scaffold → 新模块绑定 |
| `cli-crate` | `add a new browser4-cli command with a batch test in cli/browser4-cli/src/commands.rs` | Rust CLI crate（cargo，不碰 Maven） |
| `no-signal` | `please improve the overall performance` | 无信号时必须明说，不得臆造 |
| `prose-test` | `write a test and run the module suite for browser4-rest` | 散文里的 "test" 不是测试类（不得产生 `-Dtest=`） |
| `zh-mixed` | `在 browser4-rest 的 MCPToolController 增加一个端点，并补 MCPToolControllerTest` | 中文文本不得破坏 ASCII 信号解析 |
| `path-rooted` | `touch browser4-coding/src/main/kotlin/ai/platon/pulsar/coding/DevTaskPlanner.kt` | 工作区相对路径原样读取 + 路径反推模块 |
| `dependents-hijack` | `在 browser4-agentic、browser4-core/browser4-protocol、browser4-core/browser4-skeleton、browser4-pdk 的 DEPENDENTS 补 browser4-plugins/browser4-evalprobe2，并补 Evalprobe2ConfigTest` | 同深度 DEPENDENTS 键不得劫持构建/测试绑定 |

---

## 3. 门禁目录

门禁 ID 与 `bin/eval-devtask.ps1` 中的 `Add-Gate -Id` 一一对应。当前没有已登记差距（K1–K8 均已修复，见 §4）。

### A. 计划形状（对每个探针输出都检查）

| 门禁 | 判据 |
|---|---|
| `G-EXIT[probe]` | 退出码 0，打印出 `Dev task plan (N steps):` |
| `G-SHAPE[probe]` | N == 实际步骤数；编号严格 1..N；每步有 `[coding.*]` 工具名与一行 `coding.*` 命令 |
| `G-NO-COMMIT[probe]` | 全计划不含 `git add/commit/push` |

### B. 信号绑定

| 门禁 | 判据 | 基线 |
|---|---|---|
| `G-BARE-CONTEXT` | 裸驱动文件名（`Browser4WebDriver.kt`）的定位/影响/陷阱步骤落在 `browser4-core/browser4-browser` | PASS |
| `G-TRAP-PATH` | 每个 `trapCheck` 的 `path=` 在磁盘上真实存在 | PASS |
| `G-TEST-BIND-DRIVER` | `FillCursorTest` 绑定 `-pl browser4-rest -Dtest=… -Dsurefire.failIfNoSpecifiedTests=false` | PASS |
| `G-DEEPEST-BUILD` | `mvnBuild` 绑定 `browser4-plugins/browser4-seo`（不是聚合器） | PASS |
| `G-TEST-SCOPE` | 测试命令 `-pl` 同名模块 + `-Dtest=SeoServiceTest` + `-am` 容忍 flag | PASS |
| `G-IMPACT-DEEPEST` | `impact` 绑定最深模块，而非 `browser4-plugins` | PASS |
| `G-NO-AGGREGATOR-SIGNAL` | `Inferred modules` 不把聚合器 `browser4-plugins` 计入信号 | PASS |
| `G-SCAFFOLD-FIRST` | 新插件任务第 1 步是 `scaffoldToDir`，`dir=` 指向新模块 | PASS |
| `G-NEW-MODULE-BUILD` | `mvnBuild` 绑定新模块，且不绑定 DEPENDENTS 键 | PASS |
| `G-NEW-MODULE-TEST` | 测试跑在新模块里 + `-Dtest=EvalprobeServiceTest` | PASS |
| `G-NEW-MODULE-PATH-PREFIX` | 新模块内的斜杠路径补模块前缀 | PASS |
| `G-CARGO-STEP` | CLI crate 任务调度 `cargo test --bin browser4-cli` | PASS |
| `G-CLI-NO-MVN` | CLI crate 任务不臆造 Maven 模块构建 | PASS |
| `G-CLI-READ-PATH` | 读取 `cli/browser4-cli/src/commands.rs` 原样路径 | PASS |
| `G-NOSIGNAL-TEXT` | `Signals:` 明说无信号 | PASS |
| `G-NOSIGNAL-THIN` | 无信号计划保持最薄（仅治理 validate） | PASS |
| `G-PROSE-NO-DTEST` | 散文 "test" 不产生 `-Dtest=` | PASS |
| `G-CJK-MODULE` | 中文文本仍解析出 `modules: browser4-rest` | PASS |
| `G-CJK-DTEST` | 中文文本仍按命名测试类定域 | PASS |
| `G-ROOTED-PATH` | 相对路径原样读取且由路径反推模块 | PASS |
| `G-DEPENDENTS-NO-HIJACK` | DEPENDENTS 键不劫持新模块的构建/测试绑定 | PASS |

### C. 副作用与确定性

| 门禁 | 判据 |
|---|---|
| `G-DETERMINISTIC` | 同一任务渲染两次，输出逐字节一致（`-Fast` 跳过） |
| `G-READONLY` | 整轮评测前后 `git status --porcelain` 不变（无 git 则 SKIP） |

### D. CLI 参数契约

| 门禁 | 判据 | 基线 |
|---|---|---|
| `G-ARG-MISSING` | 缺 `<task>` → 非零退出 + `Missing required argument` + 用法行 | PASS |
| `G-ARG-UNKNOWN` | 位置参数**之后**的未知选项 → 非零退出 + `unknown option` + 支持列表 | PASS |
| `G-ARG-UNKNOWN-BEFORE` | 位置参数**之前**的未知选项（如 `--verfiy`）同样被诊断为未知选项 | PASS |
| `G-HELP-OPTIONS` | `--help` 列出 `--verify/--run-tests/--execute` | PASS |
| `G-HELP-EXAMPLES` | help 广告的 `--help --examples` 真能给出示例 | PASS |

### E. `--verify` 执行语义（`-Fast` 跳过）

| 门禁 | 判据 | 基线 |
|---|---|---|
| `G-VERIFY-SECTION` | `--verify` 输出含 `--- Verification ---` 且有 `repo-consistency` 结论 | PASS |
| `G-VERIFY-COMPILE` | 编译子检查**真的跑起来**（不被命令白名单拦截） | PASS |
| `G-VERIFY-VERDICT` | 任一子检查失败 → CLI 退出码非 0 | PASS |

### F. 性能

评测开头先跑一对固定任务（同一句 `please improve the overall performance`，连跑两次）：
第一发记 `cold`，第二发记 `warm`。`warm` 是本机稳态，`cold - warm` 的差值暴露"每次调用都重扫工作区"的成本。

| 门禁 | 判据 | 基线 |
|---|---|---|
| `G-PLAN-LATENCY` | warm 计划成本 ≤ 同轮测得的传输地板（`code workspace`）+ 1s | PASS |
| `G-PLAN-LATENCY-CEILING` | 任何调用不超过 90s（防挂死） | PASS |

---

## 4. 历史差距登记（K1–K8，已全部修复）

本节原记录 8 项在 `61b7962f29` 上复现的差距。**当前基线上它们全部 `XPASS`**：门禁通过、
登记删除，只保留"差距 → 原现象 → 修复落点"的追溯表，防止同类退化再次发生。

| 差距 | 原现象 | 修复落点 | 门禁 |
|---|---|---|---|
| K1 | 裸文件名落不到所属模块，`read`/`impact`/`trapCheck` 指向不存在的路径 | `DevTaskPlanner.plan(task, knownModules, fileIndex)` 接受裸文件名索引；`CodingToolExecutor.buildBareFileIndex` 用 `CodingAgentFileSystem.findFiles` 解析；`DevTaskPlannerOneshotTest` 锁定 | `G-BARE-CONTEXT` / `G-TRAP-PATH` |
| K2 | `impact` 取聚合器而 `mvnBuild` 取叶子 | `DevTaskPlanner.selectTargetModule()` 统一"最深模块"规则，`impact`/`mvnBuild`/`verify` 共用 | `G-IMPACT-DEEPEST` |
| K3 | 新插件任务把 `browser4-plugins` 计入信号 | `DevTaskPlanner.dropAncestorSignals()`：祖先模块在更深的已/待建模块出现时被丢弃 | `G-NO-AGGREGATOR-SIGNAL` |
| K4 | Windows 上 `mvnw.cmd` 被命令白名单拦截 | `CodingAgentShell.normalizeCommandName()` 按 basename 去掉 `.cmd/.bat/.exe` 后判定；`MavenBuildSupport.mavenLauncher()` 返回绝对路径 | `G-VERIFY-COMPILE` |
| K5 | 子检查失败仍 `exit 0` | `VerificationReport` 结构化 verdict；CLI 的 `code verify`/`code mvn`/`code validate`/`code devtask` 依 verdict 返回非零退出码（`code_command_failure`） | `G-VERIFY-VERDICT` |
| K6 | 位置参数之前的未知选项吞掉任务文本 | `run()` 先执行 `validate_command_options`，再校验必填位置参数 | `G-ARG-UNKNOWN-BEFORE` |
| K7 | `--help --examples` 没有示例 | `coding.devTask` / `coding.verify` 的 `ToolSpec.examples`（`GET /mcp/tools/specs` 可读） | `G-HELP-EXAMPLES` |
| K8 | 每次计划调用重扫全工作区（冷 11–12s / 热 4.5s） | `ModuleGraph.cachedGraph()`：`walkFileTree` 剪枝 + pom mtime/size 签名缓存；`scanPomFiles` 跳过 `target/.git/node_modules/docs*` | `G-PLAN-LATENCY`（判据改为"传输地板 + 1s"） |

> 对应的实现阶段：P0.4/P0.7 → K4/K6，P0.1–P0.3 → K5，P1.6 → K8，P1.7 → K1–K3，P3.2 → K7。
> 计划与验收口径见 [`browser4-oneshot-dev-task-plan.md`](browser4-oneshot-dev-task-plan.md)。

---
## 5. 运行评测

```powershell
# 全量（9 探针 + 延迟对 + 确定性 + 只读 + CLI 契约 + --verify），约 75–80s
# 首次运行或后端需从源码重建时另加 1–3 分钟
.\bin\eval-devtask.ps1

# 快跑：跳过 --verify 与确定性重跑；复用已构建的 CLI 二进制
.\bin\eval-devtask.ps1 -Fast -NoBuild

# 单探针排查 + 机器可读报告
.\bin\eval-devtask.ps1 -Probe driver-bare -Json -JsonPath .\devtask-eval.json

# 已知差距是否仍未修复？确认 K* 全部转 XPASS 后再决定删登记
.\bin\eval-devtask.ps1 -Strict
```

**退出码**：`0` = 所有必需门禁 PASS（已知差距不计）；`1` = 有必需门禁 FAIL（或 `-Strict` 下有 KNOWN）；
`2` = 评测环境问题（找不到仓库、找不到 `b4w.ps1`、探针 ID 未知）。

**报告语义**：`PASS` / `FAIL` / `KNOWN`（已登记差距）/ `XPASS`（登记差距不再复现 → 该更新本文件）/
`SKIP`（本机跑不了，例如没有 git 时的只读门禁、`-Fast` 跳过的段）。

**安全约束**：评测脚本**从不**传 `--execute`；`G-READONLY` 用 `git status` 前后比对来证明这一点。
JSON 报告请写到仓库外（例如 `$env:TEMP`），否则会污染只读门禁。

---

## 6. 基线结果

### 6.1 全量（`-Strict`）

```powershell
PS> .\bin\eval-devtask.ps1 -Strict
  gates    : 65 pass, 0 fail, 0 known gap, 0 skipped
  latency  : cold 3.83s / warm 4.01s (delta -0.18s); probes median 3.91s (min 3.77s / max 4.6s)
  wall     : 111.2s
  verdict  : PASS                       # 退出码 0
```

K1–K8 修复后登记已删除（见 §4），`-Gap` 标记全部移除：任何一条门禁再退化都是 **FAIL**，
不会退化成 KNOWN。`-Fast`（跳过 `--verify` 与确定性重跑）约 65s，退出码同样是 0。

### 6.2 探针 → 计划形状（全部 PASS）

有信号的计划现在是 6–8 步，按 AGENTS.md 开发流排列：

```
scaffold(新模块) → read/listDir → impact → implement(契约) → mvnBuild → test → trapCheck → validate → verify
```

| 探针 | 关键断言 |
|---|---|
| `driver-bare` | 裸文件名解析到 `browser4-core/browser4-browser/src/.../Browser4WebDriver.kt`；`trapCheck` 路径存在；`FillCursorTest` 仍绑定 `-pl browser4-rest` |
| `existing-plugin` | `impact`/`mvnBuild`/`test` 都绑定 `browser4-plugins/browser4-seo`；`Inferred modules` 不含聚合器 |
| `new-plugin` | 第 1 步 `scaffoldToDir`；构建/测试绑定新模块；新模块内斜杠路径自动补前缀 |
| `cli-crate` | 只调度 `cargo test --bin browser4-cli`，不臆造 Maven 模块 |
| `no-signal` | 计划保持最薄（仅 `coding.validate`），`Signals:` 明说无信号 |
| `prose-test` | 散文 "test" 不产生 `-Dtest=` |
| `zh-mixed` | 中文文本仍解析模块与测试类 |
| `path-rooted` | 相对路径原样读取，模块由路径反推 |
| `dependents-hijack` | DEPENDENTS 键不劫持新模块的构建/测试 |

### 6.3 退化时最先变红的门禁

按"退化影响面 × 检测可靠度"排序，这几条是这套评测最有价值的部分：

1. `G-READONLY` —— 任何"看一眼顺手写文件/跑构建"的改动立刻红，且证据直接是 `git status` 差异。
2. `G-TEST-SCOPE` / `G-NEW-MODULE-TEST` —— 绑定错模块 = 假绿，这是历史上反复踩过的坑（v1.1–v1.4 的多份复盘）。
3. `G-SHAPE[*]` —— 计划格式是代理与监督方的接口，形状一破整条链就哑。
4. `G-VERIFY-VERDICT` —— 子检查失败但退出码为 0，是"门禁不可用"的根因；这条红线必须保持 FAIL 而非 KNOWN。
5. `G-DEPENDENTS-NO-HIJACK` —— 同深度 tie-break 回退时立刻红，属于最容易回归的规则。

---

## 7. 与既有测试的分工

| 层次 | 位置 | 覆盖 |
|---|---|---|
| 纯解析单测 | `browser4-coding/src/test/.../DevTaskPlannerTest.kt`（21 例） | planner 规则本身，无 IO |
| mock e2e | `cli/browser4-cli/tests/e2e/scenarios/mock_server.rs:8618` | CLI 子命令 → `coding_devTask` 的名字/参数映射 |
| **本评测** | `bin/eval-devtask.ps1` + 本文件 | 真实 wrapper + 真实后端 + live pom 图：计划形状、信号绑定、副作用、CLI 契约、`--verify` 语义、延迟 |

三者互补，不可互相替代：单测证明"规则对"，mock 证明"接线对"，本评测证明"**这条命令在真实工作区上可用**"。

---

## 8. 维护规则

1. 改 `DevTaskPlanner`、`code-devtask` 的 CLI 定义（`commands.rs`）、或 `CodingToolExecutor.devTask` 分支
   → 先跑 `-Strict`，再更新 §3 基线与 §6。
2. 修掉某个 K → 对应门禁会 `XPASS`：删除该 K 登记、把 §3 的"基线"改成 PASS、更新 §6。
3. 新增探针 → §2 加一行 + 脚本 `$script:AllProbes` 加一项 + §3 加对应门禁（缺一不可，否则评测与文档会漂移）。
4. 本文件是**自开发回路的质量闸**：`code devtask` 若退化，评测会先红，而不是等到代理执行出错才发现。
5. 已知差距的**修复计划**见 [`browser4-oneshot-dev-task-plan.md`](browser4-oneshot-dev-task-plan.md)：
   K1–K3 落在其 P1.7、K4 落在 P0.4、K5 落在 P0.2/P0.3、K6–K7 落在 P0/P3 的 CLI 收口项；
   修完后本文件的 §3 基线与 §6 需同步更新。

### G. 结构化裁决 · 实现契约 · 安全探针（P0/P1.1/K9/K10）

| 门禁 | 判据 | 备注 |
|---|---|---|
| `G-VERDICT-PASS` | `code verify` 在健康检查上退出 0 且输出 `VERDICT_JSON:` + `"passed":true` | 验证权威层的 CLI 契约 |
| `G-VERDICT-FAIL` | 失败 verdict（不存在的模块）退出码非 0 且 CLI 侧可见 `"passed":false` | eval K5 的端到端反面 |
| `G-IMPL-STEP` | 有信号的计划恰好一个 `coding.implement` 契约步骤（purpose 含 "not mechanically executable"，command 含 `acceptance=`），且计划含 `Acceptance: [...]` 行 | P1.1/P1.2 |
| `G-PROTECT` | `code write VERSION` 被拒且文件内容不变（探针自愈：若守卫失效会先恢复原内容再判红） | K9 保护绕过 |
| `G-INJECTION` | `ls && curl …` / `ls; curl …` / `ls \| curl …` 三种复合命令全部被拒 | K10 命令注入；`$(…)` 形态由 `CodingAgentShellTest` 覆盖（pwsh wrapper 会先消费 `$()`） |
